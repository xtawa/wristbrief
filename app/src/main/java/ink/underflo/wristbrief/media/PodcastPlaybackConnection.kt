package ink.underflo.wristbrief.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Activity-scoped controller connection. Playback itself remains owned by MediaSessionService. */
class PodcastPlaybackConnection(context: Context) {
    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingRequest: PodcastPlaybackRequest? = null

    fun connect() {
        if (controllerFuture != null || controller != null) return
        val token = SessionToken(
            appContext,
            ComponentName(appContext, PodcastPlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(appContext, token).buildAsync().also { future ->
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { mediaController ->
                            controller = mediaController
                            pendingRequest?.let(::play)
                            pendingRequest = null
                        }
                },
                appContext.mainExecutor
            )
        }
    }

    fun disconnect() {
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

        val mediaItem = MediaItem.Builder()
            .setMediaId(request.id)
            .setUri(requireHttpsPodcastUrl(request.audioUrl))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title)
                    .build()
            )
            .build()
        mediaController.setMediaItem(mediaItem)
        mediaController.prepare()
        mediaController.play()
    }
}
