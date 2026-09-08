package ink.underflo.wristbrief.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Owns podcast playback independently of the Activity lifecycle. */
class PodcastPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var progressStore: PodcastProgressStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private val checkpointRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress(force = false)
            mainHandler.postDelayed(this, PROGRESS_CHECKPOINT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        progressStore = SharedPreferencesPodcastProgressStore(this)
        val speechAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        val player = ExoPlayer.Builder(this).build().apply {
            // Let Media3 request/abandon audio focus for speech playback and pause
            // automatically when a wired/Bluetooth route becomes noisy/disconnects.
            setAudioAttributes(speechAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) saveCurrentProgress(force = true)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        saveCurrentProgress(force = true, completed = true)
                    }
                }
            })
        }
        // MediaSession exposes standard play/pause/seek transport commands to
        // Bluetooth headsets and system media controls without a custom receiver.
        mediaSession = MediaSession.Builder(this, player).build()
        mainHandler.postDelayed(checkpointRunnable, PROGRESS_CHECKPOINT_MS)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentProgress(force = true)
        super.onTaskRemoved(rootIntent)
    }

    private fun saveCurrentProgress(force: Boolean, completed: Boolean = false) {
        val player = mediaSession?.player ?: return
        val episodeId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val currentPosition = if (completed) 0L else player.currentPosition.coerceAtLeast(0L)
        val previous = progressStore.get(episodeId)
        if (!force && previous != null && !shouldCheckpoint(previous.positionMs, currentPosition)) return

        progressStore.save(
            PodcastEpisodeProgress(
                episodeId = episodeId,
                positionMs = currentPosition,
                playbackSpeed = player.playbackParameters.speed
                    .takeIf { speed -> speed in SUPPORTED_PLAYBACK_SPEEDS }
                    ?: 1f
            )
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(checkpointRunnable)
        saveCurrentProgress(force = true)
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
