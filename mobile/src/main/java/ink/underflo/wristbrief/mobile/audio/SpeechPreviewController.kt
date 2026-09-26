package ink.underflo.wristbrief.mobile.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the short-text speech preview: generation, play, pause, stop and errors.
 *
 * The voice is provider-generated, so [SpeechPreviewUiState.mustDiscloseSyntheticVoice]
 * is always true for anything that can be played; the UI must show that disclosure
 * before and while listening.
 */
class SpeechPreviewController(
    private val api: SpeechPreviewApi,
    private val scope: CoroutineScope,
    private val player: SpeechPreviewPlayer,
    /** Called before a preview starts so podcast playback yields the audio focus. */
    private val onBeforePlay: () -> Unit = {},
) {
    private val _state = MutableStateFlow(SpeechPreviewUiState())
    val state: StateFlow<SpeechPreviewUiState> = _state.asStateFlow()

    private var audio: ByteArray? = null

    /**
     * True once a preview sample has been generated and handed to the player.
     *
     * This is deliberately NOT learned from a player callback: generation success is
     * already known here, and a player that reports nothing is still a player that can be
     * asked to play. Deriving "audio is available" from a callback is what previously left
     * the button showing "Play" while audio was actually loaded.
     */
    private var sampleReady = false

    /**
     * What the user asked the player to do.
     *
     * Owned by the control path (play/pause/stop), corrected — but never solely determined
     * — by the player's event stream. A player that reports nothing, or that reports a
     * Started/Paused before the listener is attached, must not be able to make the UI show
     * a control state opposite to the action the user just took.
     */
    private var playIntent = false
    private var generating = false

    /**
     * Incremented whenever an in-flight generation is superseded (cancelled or replaced),
     * so a late response cannot overwrite newer state.
     */
    private var generationToken = 0
    private var generationIssue: SpeechPreviewIssue? = null
    private var playback = SpeechPreviewState()

    private fun derive() {
        val activeIssue = playback.issue ?: generationIssue
        // Playability follows from generation success plus "no error", NOT from a player
        // callback: a loaded sample must be playable even before the player reports state.
        val playable = sampleReady && audio != null && playback.issue == null
        _state.value = SpeechPreviewUiState(
            isGenerating = generating,
            isPlaying = playable && playIntent,
            canPlay = playable,
            canStop = sampleReady || generating || playback.mediaId != null,
            issue = activeIssue,
            mustDiscloseSyntheticVoice = true,
        )
    }

    /** Generates the preview if needed, then plays it. */
    fun playOrLoad(request: SpeechPreviewRequest) {
        if (sampleReady && audio != null && playback.issue == null) {
            togglePlay()
            return
        }
        if (!request.isSendable) {
            generationIssue = SpeechPreviewIssue.from(
                result = SpeechPreviewResult.Failure(
                    code = SpeechPreviewErrors.INVALID_TEXT,
                    httpStatus = 400,
                    message = "Nothing to read",
                ),
                requestWasSendable = false,
            )
            derive()
            return
        }
        generating = true
        generationIssue = null
        val token = ++generationToken
        derive()
        scope.launch {
            val result = api.generate(request)
            // A superseded request must not publish its result over newer state.
            if (token != generationToken) return@launch
            generating = false
            when (result) {
                is SpeechPreviewResult.Success -> {
                    audio = result.audio
                    sampleReady = true
                    generationIssue = null
                    derive()
                    startPlayback(result.audio, result.mimeType)
                }
                is SpeechPreviewResult.Failure -> {
                    audio = null
                    sampleReady = false
                    generationIssue = SpeechPreviewIssue.from(result, requestWasSendable = true)
                    derive()
                }
            }
        }
    }

    /**
     * Starts an existing sample. Returns true when the caller should treat the action as
     * handled; false when the sample is not loaded yet, so the caller starts generation.
     */
    fun togglePlay() {
        if (!sampleReady || audio == null) return
        if (playIntent) {
            playIntent = false
            player.pause()
        } else {
            playIntent = true
            onBeforePlay()
            player.play()
        }
        // The player's events arrive during the call, so recompute before the caller reads.
        derive()
    }

    /**
     * Called when the user taps the play/pause control.
     *
     * While a request is in flight the card disables the control, so a tap here either
     * toggles a loaded sample or starts generation. Routing both through one entry point
     * means the tap can never be silently dropped.
     */
    fun onPlayPauseRequested(request: SpeechPreviewRequest) {
        if (sampleReady && audio != null) togglePlay() else playOrLoad(request)
    }

    /**
     * Retry deliberately re-requests generation instead of replaying a failure, so a
     * transient provider or transport error can actually clear.
     */
    fun retry(request: SpeechPreviewRequest) {
        stop()
        playOrLoad(request)
    }

    fun stop() {
        playIntent = false
        player.stop()
        audio = null
        sampleReady = false
        generationIssue = null
        generating = false
        playback = SpeechPreviewState()
        derive()
    }

    fun release() {
        playIntent = false
        player.release()
        audio = null
        sampleReady = false
        generationIssue = null
        generating = false
        playback = SpeechPreviewState()
        derive()
    }

    /**
     * Feeds a player callback into the pure state machine.
     *
     * The player is authoritative about *playback state*, but it only corrects
     * [playIntent] on an unambiguous signal: Started means playing, Paused/Ended mean not.
     * A `Released` (or a stale event) leaves the intent alone, so a transient lifecycle
     * event can never flip the button against what the user just did.
     */
    fun onPlayerEvent(event: SpeechPlayerEvent) {
        playback = SpeechPlaybackReducer.reduce(playback, event, ::playbackIssue)
        when (event) {
            SpeechPlayerEvent.Started -> playIntent = true
            SpeechPlayerEvent.Paused, SpeechPlayerEvent.Ended -> playIntent = false
            is SpeechPlayerEvent.Failed -> {
                playIntent = false
                audio = null
                sampleReady = false
            }
            else -> Unit
        }
        derive()
    }

    private fun startPlayback(bytes: ByteArray, mimeType: String) {
        playIntent = true
        onBeforePlay()
        val mediaId = "speech_${System.currentTimeMillis()}"
        player.play(audio = bytes, mimeType = mimeType, mediaId = mediaId)
        // The player reports Opened/Prepared/Started through [onPlayerEvent] while play()
        // runs, and the state published before this call is already stale. Re-deriving here
        // is what makes the button show "Pause" as soon as audio actually starts.
        derive()
    }

    private fun playbackIssue(errorType: Int): SpeechPreviewIssue = SpeechPreviewIssue(
        reason = SpeechPreviewIssueReason.GENERATION_FAILED,
        errorCode = "playback_error_$errorType",
        canRetry = true,
    )

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            api: SpeechPreviewApi,
            onBeforePlay: () -> Unit = {},
        ): SpeechPreviewController {
            val controller = SpeechPreviewController(
                api = api,
                scope = scope,
                player = Media3SpeechPreviewPlayer(context),
                onBeforePlay = onBeforePlay,
            )
            controller.player.eventListener = controller::onPlayerEvent
            return controller
        }
    }
}

data class SpeechPreviewUiState(
    val isGenerating: Boolean = false,
    val isPlaying: Boolean = false,
    val canPlay: Boolean = false,
    val canStop: Boolean = false,
    val issue: SpeechPreviewIssue? = null,
    val mustDiscloseSyntheticVoice: Boolean = true,
)
