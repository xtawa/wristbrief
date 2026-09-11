package ink.underflo.wristbrief.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastPlaybackResumeTest {
    @Test
    fun recreatedServiceResumesPersistedEpisodePositionAndSpeedThroughUiConnection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        val mediaFile = createSilentWav(context, durationSeconds = 60)
        val episodeId = "ci-recreated-service-resume"
        val progressStore = SharedPreferencesPodcastProgressStore(context)
        progressStore.save(
            PodcastEpisodeProgress(
                episodeId = episodeId,
                positionMs = 17_000L,
                playbackSpeed = 1.75f,
            )
        )

        // Start from a genuinely stopped service so this exercises reconstruction rather than
        // merely reconnecting another controller to the same in-memory player.
        context.stopService(serviceIntent)
        Thread.sleep(300L)

        val connection = PodcastPlaybackConnection(
            context = context,
            progressStore = progressStore,
            mediaItemFactory = { request ->
                // Production construction still requires HTTPS. The internal test seam swaps only
                // the MediaItem source so CI can use deterministic local media without weakening
                // the shipped network policy.
                MediaItem.Builder()
                    .setMediaId(request.id)
                    .setUri(Uri.fromFile(mediaFile))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
                    .build()
            },
        )
        val request = PodcastPlaybackRequest(
            id = episodeId,
            title = "CI resume episode",
            audioUrl = "https://podcast.invalid/episode.mp3",
        )

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                connection.play(request)
            }

            val resumed = awaitPlaybackState(connection, timeoutMs = 10_000L) { state ->
                state.mediaId == episodeId &&
                    state.positionMs >= 17_000L &&
                    abs(state.playbackSpeed - 1.75f) < 0.001f
            }

            assertEquals(episodeId, resumed.mediaId)
            assertTrue(
                "Playback did not resume near the persisted position: ${resumed.positionMs}",
                resumed.positionMs in 17_000L..30_000L,
            )
            assertEquals(1.75f, resumed.playbackSpeed, 0.001f)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                connection.disconnect()
            }
            context.stopService(serviceIntent)
            mediaFile.delete()
        }
    }

    private fun awaitPlaybackState(
        connection: PodcastPlaybackConnection,
        timeoutMs: Long,
        predicate: (PodcastPlaybackState) -> Boolean,
    ): PodcastPlaybackState {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNanos) {
            val state = connection.state.value
            if (predicate(state)) return state
            Thread.sleep(50L)
        }
        return connection.state.value.also { state ->
            assertTrue("Timed out waiting for resumed playback state: $state", predicate(state))
        }
    }

    private fun createSilentWav(context: Context, durationSeconds: Int): File {
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
        return File(context.cacheDir, "ci-media-session-resume.wav").also { file ->
            file.outputStream().use { output ->
                output.write(header.array())
                output.write(ByteArray(dataSize) { 0x80.toByte() })
            }
        }
    }
}
