package ink.underflo.wristbrief.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
    private val accountFence = PlaybackAccountFence.process

    /** Fence generation under which the current media item was loaded; main-thread confined. */
    private var loadedGeneration = accountFence.currentGeneration()

    /**
     * Called on sign-out/account switch after the fence was revoked (possibly off the main thread).
     * Stops and clears only an item that is still from the revoked account, so a new account's
     * playback started in the meantime is left alone.
     */
    private val stopStaleItem = Runnable {
        val player = mediaSession?.player ?: return@Runnable
        if (player.currentMediaItem != null && !accountFence.allowsPersistence(loadedGeneration)) {
            player.stop()
            player.clearMediaItems()
        }
    }

    private val accountStopHandler: () -> Unit = { mainHandler.post(stopStaleItem) }

    private val checkpointRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress(force = false)
            mainHandler.postDelayed(this, PROGRESS_CHECKPOINT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        progressStore = SharedPreferencesPodcastProgressStore(this)
        loadedGeneration = accountFence.currentGeneration()
        accountFence.registerStopHandler(accountStopHandler)
        val speechAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(speechAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // The item carries the account generation of the play request; untagged items
                    // (e.g. from a system media controller) belong to whichever account is current.
                    loadedGeneration = PlaybackAccountFence.generationOf(mediaItem)
                        ?: accountFence.currentGeneration()
                    // A request made before a sign-out but delivered after it must not play.
                    if (mediaItem != null && !accountFence.allowsPersistence(loadedGeneration)) {
                        mainHandler.post(stopStaleItem)
                    }
                }

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

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentProgress(force = true)
        super.onTaskRemoved(rootIntent)
    }

    private fun saveCurrentProgress(force: Boolean, completed: Boolean = false) {
        val player = mediaSession?.player ?: return
        // Cheap early exit; the authoritative check is runIfCurrent below.
        if (!accountFence.allowsPersistence(loadedGeneration)) return
        val episodeId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val currentPosition = if (completed) 0L else player.currentPosition.coerceAtLeast(0L)
        val previous = progressStore.get(episodeId)
        if (!force && previous != null && !shouldCheckpoint(previous.positionMs, currentPosition)) return

        val duration = player.duration.coerceAtLeast(0L)
        val current = PodcastEpisodeProgress(
            episodeId = episodeId,
            positionMs = currentPosition,
            playbackSpeed = player.playbackParameters.speed
                .takeIf { speed -> speed in SUPPORTED_PLAYBACK_SPEEDS }
                ?: 1f,
            durationMs = duration,
            isPlaying = player.isPlaying,
            lastPlayedAtEpochMs = System.currentTimeMillis(),
            completed = completed,
        )
        // Generation check, store write and phone publish happen as one step under the fence lock,
        // so a concurrent sign-out purge either sees this write (and deletes it) or refuses it.
        val persisted = accountFence.runIfCurrent(loadedGeneration) {
            progressStore.save(current)
            runCatching {
                // putDataItem is asynchronous, so the lock is not held across I/O completion.
                ink.underflo.wristbrief.sync.WearPlaybackSyncManager(this).publishLocalProgress(current)
            }
        }
        if (persisted && shouldRequestContinueListeningTileUpdate(previous, current, force)) {
            requestContinueListeningTileUpdate(this)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(checkpointRunnable)
        mainHandler.removeCallbacks(stopStaleItem)
        accountFence.unregisterStopHandler(accountStopHandler)
        saveCurrentProgress(force = true)
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
