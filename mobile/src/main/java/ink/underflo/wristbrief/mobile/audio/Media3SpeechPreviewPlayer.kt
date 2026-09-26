package ink.underflo.wristbrief.mobile.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** Seam so the preview controller can be unit-tested without an Android player. */
interface SpeechPreviewPlayer {
    fun play(audio: ByteArray, mimeType: String, mediaId: String)
    fun play()
    fun pause()
    fun stop()
    fun release()

    /** Player callbacks feed the pure state machine. */
    var eventListener: ((SpeechPlayerEvent) -> Unit)?
}

/**
 * Plays a generated speech preview through the same Media3 stack the app already uses
 * for podcasts. Audio focus, becoming-noisy handling and pause-on-focus-loss come from
 * Media3's audio attributes, so no second playback framework is introduced.
 */
class Media3SpeechPreviewPlayer(
    context: Context,
    override var eventListener: ((SpeechPlayerEvent) -> Unit)? = null,
) : SpeechPreviewPlayer {

    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var previewFile: File? = null

    private fun emit(event: SpeechPlayerEvent) {
        eventListener?.invoke(event)
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(appContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> emit(SpeechPlayerEvent.Prepared(duration.coerceAtLeast(0L)))
                        Player.STATE_ENDED -> emit(SpeechPlayerEvent.Ended)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    emit(if (isPlaying) SpeechPlayerEvent.Started else SpeechPlayerEvent.Paused)
                }

                override fun onPlayerError(error: PlaybackException) {
                    emit(SpeechPlayerEvent.Failed(error.errorCode))
                }
            })
        }
        player = created
        return created
    }

    override fun play(audio: ByteArray, mimeType: String, mediaId: String) {
        val exo = ensurePlayer()
        deletePreviewFile()
        val file = File(appContext.cacheDir, "$PREVIEW_FILE_PREFIX$mediaId.mp3")
        file.writeBytes(audio)
        previewFile = file

        emit(SpeechPlayerEvent.Opened(mediaId))
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(android.net.Uri.fromFile(file))
            .setMimeType(mimeType.takeIf { it.isNotBlank() } ?: MimeTypes.AUDIO_MPEG)
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun play() {
        player?.playWhenReady = true
    }

    override fun pause() {
        player?.playWhenReady = false
    }

    override fun stop() {
        player?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
        deletePreviewFile()
        emit(SpeechPlayerEvent.Released)
    }

    override fun release() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        deletePreviewFile()
        emit(SpeechPlayerEvent.Released)
    }

    private fun deletePreviewFile() {
        previewFile?.delete()
        previewFile = null
    }

    companion object {
        const val PREVIEW_FILE_PREFIX = "speech_preview_"

        /** Removes previews left behind by a previous process. */
        fun clearStalePreviews(context: Context) {
            runCatching {
                context.applicationContext.cacheDir
                    .listFiles { file -> file.name.startsWith(PREVIEW_FILE_PREFIX) }
                    ?.forEach { it.delete() }
            }
        }
    }
}
