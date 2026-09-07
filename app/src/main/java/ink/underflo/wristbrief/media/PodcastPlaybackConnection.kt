package ink.underflo.wristbrief.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing playback state; progress refreshes in memory without writing storage every tick. */
data class PodcastPlaybackState(
    val mediaId: String? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f
) {
    val progressLabel: String get() = formatPlaybackTime(positionMs, durationMs)
}

/** Activity-scoped controller connection. Playback itself remains owned by MediaSessionService. */
class PodcastPlaybackConnection(context: Context) {
    private val appContext = context.applicationContext
    private val progressStore: PodcastProgressStore = SharedPreferencesPodcastProgressStore(appContext)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingRequest: PodcastPlaybackRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PodcastPlaybackState())
    val state: StateFlow<PodcastPlaybackState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publishState(player)
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            controller?.let(::publishState)
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    fun connect() {
        if (controllerFuture != null || controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PodcastPlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    publishState(mediaController)
                    mainHandler.removeCallbacks(progressTicker)
                    mainHandler.post(progressTicker)
                    pendingRequest?.let(::play)
                    pendingRequest = null
                }
            }, appContext.mainExecutor)
        }
    }

    fun disconnect() {
        mainHandler.removeCallbacks(progressTicker)
        controller?.removeListener(playerListener)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
    }

    fun play(request: PodcastPlaybackRequest) {
        val mediaController = controller
        if (mediaController == null) {
            pendingRequest = request
            connect()
            return
        }

        val saved = progressStore.get(request.id)
        val mediaItem = MediaItem.Builder()
            .setMediaId(request.id)
            .setUri(requireHttpsPodcastUrl(request.audioUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
            .build()
        mediaController.setMediaItem(mediaItem)
        mediaController.prepare()
        mediaController.seekTo(normalizedResumePosition(saved?.positionMs ?: 0L, mediaController.duration))
        mediaController.setPlaybackSpeed(saved?.playbackSpeed ?: 1f)
        mediaController.play()
        publishState(mediaController)
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) {
        controller?.let { player ->
            val max = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, max))
            publishState(player)
        }
    }

    fun cyclePlaybackSpeed() {
        controller?.let { player ->
            player.setPlaybackSpeed(nextPlaybackSpeed(player.playbackParameters.speed))
            publishState(player)
        }
    }

    private fun publishState(player: Player) {
        _state.value = PodcastPlaybackState(
            mediaId = player.currentMediaItem?.mediaId,
            title = player.mediaMetadata.title?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            playbackSpeed = player.playbackParameters.speed
        )
    }
}
