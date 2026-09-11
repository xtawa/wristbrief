package ink.underflo.wristbrief.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ink.underflo.wristbrief.tile.requestContinueListeningTileUpdate

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

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (
                        reason == Player.DISCONTINUITY_REASON_SEEK ||
                        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    ) {
                        // A prepared episode can be seeked before playback ever starts. Persist the
                        // explicit user position immediately instead of relying on a later
                        // checkpoint/service teardown, both of which may never happen cleanly.
                        saveCurrentProgress(force = true)
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    // Speed changes are user state too. Persist immediately so a controller can
                    // disconnect or the service can stop without losing the new preference.
                    saveCurrentProgress(force = true)
                }
            })
        }
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
        if (force) requestContinueListeningTileUpdate(this)
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
