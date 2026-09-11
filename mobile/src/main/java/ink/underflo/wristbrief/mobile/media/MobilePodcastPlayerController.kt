package ink.underflo.wristbrief.mobile.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PodcastPlayerState(
    val currentEpisode: PodcastPlaybackRequest? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val errorMessage: String? = null,
    val isVisible: Boolean = false,
)

class MobilePodcastPlayerController(
    private val context: Context,
    private val progressStore: PodcastProgressStore = SharedPreferencesPodcastProgressStore(context),
) {
    private val _state = MutableStateFlow(PodcastPlayerState())
    val state: StateFlow<PodcastPlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRequest: PodcastPlaybackRequest? = null

    private val progressPollRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (_state.value.isPlaying) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                mainHandler.post(progressPollRunnable)
            } else {
                mainHandler.removeCallbacks(progressPollRunnable)
                updateProgress()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            val duration = mediaController?.duration?.takeIf { it > 0 } ?: 0L
            _state.update {
                it.copy(
                    isBuffering = isBuffering,
                    durationMs = duration,
                    errorMessage = if (playbackState == Player.STATE_READY) null else it.errorMessage,
                )
            }
            if (playbackState == Player.STATE_READY) {
                updateProgress()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(playbackSpeed = playbackParameters.speed) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Playback error",
                )
            }
        }
    }

    init {
        connectController()
    }

    private fun connectController() {
        if (mediaController != null || controllerFuture != null) return
        try {
            val sessionToken = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, MobilePodcastPlaybackService::class.java),
            )
            val future = MediaController.Builder(context.applicationContext, sessionToken).buildAsync()
            controllerFuture = future
            future.addListener({
                try {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    pendingRequest?.let { req ->
                        pendingRequest = null
                        play(req)
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = e.message) }
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            // Catch for testing environments without ServiceManager
        }
    }

    fun play(request: PodcastPlaybackRequest) {
        val controller = mediaController
        if (controller == null) {
            pendingRequest = request
            _state.update {
                it.copy(
                    currentEpisode = request,
                    isBuffering = true,
                    isVisible = true,
                    errorMessage = null,
                )
            }
            connectController()
            return
        }

        val saved = progressStore.get(request.id)
        val startPos = saved?.positionMs ?: 0L
        val initialSpeed = saved?.playbackSpeed ?: 1f

        val mediaItem = MediaItem.Builder()
            .setMediaId(request.id)
            .setUri(Uri.parse(request.audioUrl))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title)
                    .setArtist(request.feedTitle)
                    .build()
            )
            .build()

        controller.setMediaItem(mediaItem, startPos)
        controller.playbackParameters = PlaybackParameters(initialSpeed)
        controller.prepare()
        controller.play()

        _state.update {
            it.copy(
                currentEpisode = request,
                isPlaying = true,
                isBuffering = true,
                currentPositionMs = startPos,
                playbackSpeed = initialSpeed,
                isVisible = true,
                errorMessage = null,
            )
        }
    }

    fun pause() {
        mediaController?.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        mediaController?.play()
        _state.update { it.copy(isPlaying = true) }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        mediaController?.seekTo(target)
        _state.update { it.copy(currentPositionMs = target) }
    }

    fun seekBy(deltaMs: Long) {
        val controller = mediaController ?: return
        val current = controller.currentPosition
        val duration = controller.duration
        val target = (current + deltaMs).coerceAtLeast(0L)
        val finalTarget = if (duration > 0L) target.coerceAtMost(duration) else target
        controller.seekTo(finalTarget)
        _state.update { it.copy(currentPositionMs = finalTarget) }
    }

    fun cycleSpeed() {
        val next = nextPlaybackSpeed(_state.value.playbackSpeed)
        mediaController?.playbackParameters = PlaybackParameters(next)
        _state.update { it.copy(playbackSpeed = next) }
    }

    fun dismiss() {
        pause()
        _state.update { it.copy(isVisible = false) }
    }

    private fun updateProgress() {
        val controller = mediaController ?: return
        val current = controller.currentPosition.coerceAtLeast(0L)
        val duration = controller.duration.takeIf { it > 0 } ?: _state.value.durationMs
        _state.update {
            it.copy(
                currentPositionMs = current,
                durationMs = duration,
            )
        }
    }

    fun release() {
        mainHandler.removeCallbacks(progressPollRunnable)
        mediaController?.let {
            it.removeListener(playerListener)
            it.release()
        }
        mediaController = null
        controllerFuture = null
    }
}
