package ink.underflo.wristbrief.mobile.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ink.underflo.wristbrief.mobile.db.SqlitePodcastProgressStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper

class MobilePodcastPlaybackService : MediaSessionService() {
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
        ensureNotificationChannel()
        val dbHelper = WristBriefDatabaseHelper(this)
        progressStore = SqlitePodcastProgressStore(dbHelper)
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
                        saveCurrentProgress(force = true)
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    saveCurrentProgress(force = true)
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player).build()
        mainHandler.postDelayed(checkpointRunnable, PROGRESS_CHECKPOINT_MS)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager != null && notificationManager.getNotificationChannel("podcast_playback") == null) {
                val channel = NotificationChannel(
                    "podcast_playback",
                    "Podcast Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Playback controls and status for podcast audio"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentProgress(force = true)
        super.onTaskRemoved(rootIntent)
    }

    private fun saveCurrentProgress(force: Boolean, completed: Boolean = false) {
        val player = mediaSession?.player ?: return
        val episodeId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val duration = player.duration.coerceAtLeast(0L)
        val currentPosition = if (completed) 0L else player.currentPosition.coerceAtLeast(0L)
        val previous = progressStore.get(episodeId)
        if (!force && previous != null && !shouldCheckpoint(previous.positionMs, currentPosition)) return

        val speed = player.playbackParameters.speed
            .takeIf { s -> s in SUPPORTED_PLAYBACK_SPEEDS } ?: 1f

        val current = PodcastEpisodeProgress(
            episodeId = episodeId,
            positionMs = currentPosition,
            playbackSpeed = speed,
            durationMs = duration,
            isPlaying = player.isPlaying,
            lastPlayedAtEpochMs = System.currentTimeMillis(),
            completed = completed,
        )
        progressStore.save(current)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(checkpointRunnable)
        saveCurrentProgress(force = true)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
