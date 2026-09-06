package ink.underflo.wristbrief.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class PodcastPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context.applicationContext).build()

    fun play(url: String) {
        require(url.startsWith("https://")) { "Only HTTPS podcast media is allowed" }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun pause() = player.pause()

    fun release() = player.release()
}
