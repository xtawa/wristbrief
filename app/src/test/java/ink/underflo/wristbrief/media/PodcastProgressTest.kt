package ink.underflo.wristbrief.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastProgressTest {
    @Test
    fun codecRoundTripsEpisodePositionAndSpeed() {
        val encoded = encodePodcastProgress(
            listOf(
                PodcastEpisodeProgress("episode-中文-🎧", 91_000L, 1.5f),
                PodcastEpisodeProgress("second", 5_000L, 1f)
            )
        )

        val decoded = decodePodcastProgress(encoded)
        assertEquals(91_000L, decoded.getValue("episode-中文-🎧").positionMs)
        assertEquals(1.5f, decoded.getValue("episode-中文-🎧").playbackSpeed)
        assertEquals(5_000L, decoded.getValue("second").positionMs)
    }

    @Test
    fun unknownOrCorruptStorageIsIgnored() {
        assertTrue(decodePodcastProgress("v2\nwhatever").isEmpty())
        assertTrue(decodePodcastProgress("v1\nnot-base64\tbad\tdata").isEmpty())
    }

    @Test
    fun resumePositionRestartsCompletedEpisodes() {
        assertEquals(120_000L, normalizedResumePosition(120_000L, 600_000L))
        assertEquals(0L, normalizedResumePosition(580_000L, 600_000L))
        assertEquals(0L, normalizedResumePosition(600_000L, 600_000L))
    }

    @Test
    fun checkpointPolicyAvoidsFrequentWrites() {
        assertFalse(shouldCheckpoint(30_000L, 44_999L))
        assertTrue(shouldCheckpoint(30_000L, 45_000L))
        assertTrue(shouldCheckpoint(45_000L, 20_000L))
    }

    @Test
    fun playbackSpeedCyclesThroughSupportedValues() {
        assertEquals(1.25f, nextPlaybackSpeed(1f))
        assertEquals(1.5f, nextPlaybackSpeed(1.25f))
        assertEquals(1f, nextPlaybackSpeed(2f))
        assertEquals(1.25f, nextPlaybackSpeed(1.1f))
    }

    @Test
    fun progressLabelIsCompactForWear() {
        assertEquals("1:05 / 10:00", formatPlaybackTime(65_000L, 600_000L))
        assertEquals("1:01:01 / 2:00:00", formatPlaybackTime(3_661_000L, 7_200_000L))
        assertEquals("0:12", formatPlaybackTime(12_000L, 0L))
    }
}
