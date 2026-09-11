package ink.underflo.wristbrief.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastPlaybackServiceTest {
    @Test
    fun mediaControllerConnectsToPlaybackServiceWithoutPreparingMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = connectController(context)

        try {
            // buildAsync() completing successfully proves the controller connected to the target
            // MediaSessionService without needing to prepare media.
            assertNotNull(controller)
        } finally {
            releaseController(controller)
        }
    }

    @Test
    fun serviceRetainsPreparedEpisodePositionAndSpeedAcrossControllerReconnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        val mediaFile = createSilentWav(context)
        context.startService(serviceIntent)

        var firstController: MediaController? = null
        var secondController: MediaController? = null
        try {
            val configuredController = connectController(context)
            firstController = configuredController
            val ready = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        ready.countDown()
                    }
                }
            }

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                configuredController.addListener(listener)
                configuredController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("ci-episode")
                        .setUri(Uri.fromFile(mediaFile))
                        .build()
                )
                configuredController.prepare()
            }
            assertTrue("Local CI media did not reach STATE_READY", ready.await(10, TimeUnit.SECONDS))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                configuredController.removeListener(listener)
                configuredController.seekTo(12_000L)
                configuredController.playbackParameters = PlaybackParameters(1.5f)
                assertEquals("ci-episode", configuredController.currentMediaItem?.mediaId)
                assertEquals(12_000L, configuredController.currentPosition)
                assertEquals(1.5f, configuredController.playbackParameters.speed, 0.001f)
            }
            releaseController(configuredController)
            firstController = null

            val reconnectedController = connectController(context)
            secondController = reconnectedController
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("ci-episode", reconnectedController.currentMediaItem?.mediaId)
                assertEquals(12_000L, reconnectedController.currentPosition)
                assertEquals(1.5f, reconnectedController.playbackParameters.speed, 0.001f)
            }
        } finally {
            firstController?.let(::releaseController)
            secondController?.let(::releaseController)
            context.stopService(serviceIntent)
            mediaFile.delete()
        }
    }

    @Test
    fun activePlaybackRemainsServiceOwnedAcrossControllerReconnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        val mediaFile = createSilentWav(context, durationSeconds = 60)
        context.startService(serviceIntent)

        var firstController: MediaController? = null
        var secondController: MediaController? = null
        try {
            val playingController = connectController(context)
            firstController = playingController
            val ready = CountDownLatch(1)
            val playing = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        ready.countDown()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        playing.countDown()
                    }
                }
            }

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playingController.addListener(listener)
                playingController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("ci-active-episode")
                        .setUri(Uri.fromFile(mediaFile))
                        .build()
                )
                playingController.prepare()
            }
            assertTrue("Local CI media did not reach STATE_READY", ready.await(10, TimeUnit.SECONDS))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playingController.seekTo(5_000L)
                playingController.play()
            }
            assertTrue("Local CI media did not enter active playback", playing.await(10, TimeUnit.SECONDS))

            val positionBeforeReconnect = longArrayOf(0L)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playingController.removeListener(listener)
                assertTrue(playingController.playWhenReady)
                assertTrue(playingController.isPlaying)
                positionBeforeReconnect[0] = playingController.currentPosition
            }
            releaseController(playingController)
            firstController = null

            // No controller owns playback during this interval. The MediaSessionService should
            // remain the owner and keep the player active until a new controller connects.
            Thread.sleep(500L)

            val reconnectedController = connectController(context)
            secondController = reconnectedController
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("ci-active-episode", reconnectedController.currentMediaItem?.mediaId)
                assertEquals(Player.STATE_READY, reconnectedController.playbackState)
                assertTrue(reconnectedController.playWhenReady)
                assertTrue(reconnectedController.isPlaying)
                assertTrue(
                    "Playback position regressed across controller reconnect",
                    reconnectedController.currentPosition >= positionBeforeReconnect[0]
                )
                reconnectedController.pause()
            }
        } finally {
            firstController?.let(::releaseController)
            secondController?.let(::releaseController)
            context.stopService(serviceIntent)
            mediaFile.delete()
        }
    }

    @Test
    fun orderlyServiceStopPersistsPreparedEpisodePositionAndSpeed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        val mediaFile = createSilentWav(context, durationSeconds = 60)
        val episodeId = "ci-service-stop-episode"
        val progressStore = SharedPreferencesPodcastProgressStore(context)
        progressStore.save(PodcastEpisodeProgress(episodeId, positionMs = 0L, playbackSpeed = 1f))
        context.startService(serviceIntent)

        var controller: MediaController? = null
        try {
            val configuredController = connectController(context)
            controller = configuredController
            val ready = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        ready.countDown()
                    }
                }
            }

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                configuredController.addListener(listener)
                configuredController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(episodeId)
                        .setUri(Uri.fromFile(mediaFile))
                        .build()
                )
                configuredController.prepare()
            }
            assertTrue("Local CI media did not reach STATE_READY", ready.await(10, TimeUnit.SECONDS))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                configuredController.removeListener(listener)
                configuredController.seekTo(17_000L)
                configuredController.playbackParameters = PlaybackParameters(1.75f)
                assertEquals(17_000L, configuredController.currentPosition)
                assertEquals(1.75f, configuredController.playbackParameters.speed, 0.001f)
            }

            val persistedBeforeStop = awaitStoredProgress(
                store = progressStore,
                episodeId = episodeId,
                expectedPositionMs = 17_000L,
                expectedPlaybackSpeed = 1.75f,
                timeoutMs = 5_000L,
            )
            assertNotNull("Seek/speed were not persisted before service stop", persistedBeforeStop)
            assertEquals(17_000L, persistedBeforeStop!!.positionMs)
            assertEquals(1.75f, persistedBeforeStop.playbackSpeed, 0.001f)

            releaseController(configuredController)
            controller = null

            assertTrue("Started playback service could not be stopped", context.stopService(serviceIntent))
            val saved = awaitStoredProgress(
                store = progressStore,
                episodeId = episodeId,
                expectedPositionMs = 17_000L,
                expectedPlaybackSpeed = 1.75f,
                timeoutMs = 5_000L,
            )
            assertNotNull("Service stop did not preserve podcast progress", saved)
            assertEquals(17_000L, saved!!.positionMs)
            assertEquals(1.75f, saved.playbackSpeed, 0.001f)
        } finally {
            controller?.let(::releaseController)
            context.stopService(serviceIntent)
            mediaFile.delete()
        }
    }

    private fun connectController(context: Context): MediaController {
        val token = SessionToken(
            context,
            ComponentName(context, PodcastPlaybackService::class.java),
        )
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(10, TimeUnit.SECONDS)
    }

    private fun releaseController(controller: MediaController) {
        // MediaController methods, including release(), must run on the controller's
        // application thread. The controller is built with the app main looper here.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.release()
        }
    }

    private fun awaitStoredProgress(
        store: PodcastProgressStore,
        episodeId: String,
        expectedPositionMs: Long,
        expectedPlaybackSpeed: Float? = null,
        timeoutMs: Long,
    ): PodcastEpisodeProgress? {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNanos) {
            store.get(episodeId)?.let { progress ->
                val speedMatches = expectedPlaybackSpeed == null ||
                    abs(progress.playbackSpeed - expectedPlaybackSpeed) < 0.001f
                if (progress.positionMs == expectedPositionMs && speedMatches) return progress
            }
            Thread.sleep(50L)
        }
        return store.get(episodeId)
    }

    private fun createSilentWav(context: Context, durationSeconds: Int = 15): File {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 8
        val dataSize = sampleRate * durationSeconds * channels * bitsPerSample / 8
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        return File(context.cacheDir, "ci-media-session-silence.wav").also { file ->
            file.outputStream().use { output ->
                output.write(header.array())
                output.write(ByteArray(dataSize) { 0x80.toByte() })
            }
        }
    }
}
