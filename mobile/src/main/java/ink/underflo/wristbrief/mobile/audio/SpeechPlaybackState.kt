package ink.underflo.wristbrief.mobile.audio

/** Where a speech preview is in the play/pause cycle. */
enum class SpeechPlaybackState { IDLE, PREPARING, READY, PLAYING, ENDED, ERROR }

data class SpeechPreviewState(
    val playback: SpeechPlaybackState = SpeechPlaybackState.IDLE,
    val mediaId: String? = null,
    val issue: SpeechPreviewIssue? = null,
) {
    val isPlayable: Boolean
        get() = mediaId != null && (playback == SpeechPlaybackState.READY ||
            playback == SpeechPlaybackState.PLAYING ||
            playback == SpeechPlaybackState.ENDED)

    val isBusy: Boolean get() = playback == SpeechPlaybackState.PREPARING
}

/** Incremental inputs the player reports to the state machine. */
sealed interface SpeechPlayerEvent {
    data class Opened(val mediaId: String) : SpeechPlayerEvent
    data class Prepared(val durationMs: Long) : SpeechPlayerEvent
    data object Started : SpeechPlayerEvent
    data object Paused : SpeechPlayerEvent
    data object Ended : SpeechPlayerEvent
    data class Failed(val mediaType: Int) : SpeechPlayerEvent
    data object Released : SpeechPlayerEvent
}

/**
 * Pure play/pause state machine. Kept free of Android types so the transitions that
 * decide what the play button shows are unit-testable.
 */
object SpeechPlaybackReducer {

    fun reduce(
        state: SpeechPreviewState,
        event: SpeechPlayerEvent,
        issueForMediaType: (Int) -> SpeechPreviewIssue,
    ): SpeechPreviewState = when (event) {
        is SpeechPlayerEvent.Opened -> state.copy(
            playback = SpeechPlaybackState.PREPARING,
            mediaId = event.mediaId,
            issue = null,
        )
        is SpeechPlayerEvent.Prepared -> state.copy(
            playback = if (state.playback == SpeechPlaybackState.PLAYING) {
                SpeechPlaybackState.PLAYING
            } else {
                SpeechPlaybackState.READY
            },
            issue = null,
        )
        SpeechPlayerEvent.Started -> state.copy(playback = SpeechPlaybackState.PLAYING, issue = null)
        SpeechPlayerEvent.Paused -> state.copy(
            playback = if (state.playback == SpeechPlaybackState.PREPARING) {
                SpeechPlaybackState.PREPARING
            } else {
                SpeechPlaybackState.READY
            },
        )
        SpeechPlayerEvent.Ended -> state.copy(playback = SpeechPlaybackState.ENDED)
        is SpeechPlayerEvent.Failed -> state.copy(
            playback = SpeechPlaybackState.ERROR,
            issue = issueForMediaType(event.mediaType),
        )
        SpeechPlayerEvent.Released -> SpeechPreviewState()
    }
}
