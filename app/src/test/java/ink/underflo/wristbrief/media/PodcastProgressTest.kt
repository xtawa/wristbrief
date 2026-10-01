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
    fun resumeUsesTheEpisodesOwnPersistedDurationNotThePreviousItem() {
        // 40 min into a 60 min episode stays resumable even if the previously loaded item was
        // only 10 min long (the old code passed that stale controller duration).
        val longEpisode = PodcastEpisodeProgress(
            episodeId = "long",
            positionMs = 2_400_000L,
            durationMs = 3_600_000L,
        )
        assertEquals(2_400_000L, resumePositionFor(longEpisode))

        // Unknown duration keeps the saved position rather than inventing completion.
        assertEquals(17_000L, resumePositionFor(PodcastEpisodeProgress("unknown", 17_000L)))

        // Near the end of its own duration, or explicitly completed, restarts from zero.
        assertEquals(0L, resumePositionFor(longEpisode.copy(positionMs = 3_590_000L)))
        assertEquals(0L, resumePositionFor(longEpisode.copy(completed = true)))
        assertEquals(0L, resumePositionFor(null))
    }

    @Test
    fun checkpointPolicyAvoidsFrequentWrites() {
        assertFalse(shouldCheckpoint(30_000L, 44_999L))
        assertTrue(shouldCheckpoint(30_000L, 45_000L))
        assertTrue(shouldCheckpoint(45_000L, 20_000L))
    }

    @Test
    fun continueListeningTileRefreshOnlyFollowsChangedForcedProgress() {
        val previous = PodcastEpisodeProgress("episode", 30_000L, 1.25f)
        val same = previous.copy()
        val moved = previous.copy(positionMs = 45_000L)
        val speedChanged = previous.copy(playbackSpeed = 1.5f)

        assertFalse(shouldRequestContinueListeningTileUpdate(previous, same, force = true))
        assertFalse(shouldRequestContinueListeningTileUpdate(previous, moved, force = false))
        assertTrue(shouldRequestContinueListeningTileUpdate(previous, moved, force = true))
        assertTrue(shouldRequestContinueListeningTileUpdate(previous, speedChanged, force = true))
        assertTrue(shouldRequestContinueListeningTileUpdate(null, previous, force = true))
    }

    @Test
    fun playbackSpeedCyclesThroughSupportedValues() {
        assertEquals(1.25f, nextPlaybackSpeed(1f))
        assertEquals(1.5f, nextPlaybackSpeed(1.25f))
        assertEquals(1f, nextPlaybackSpeed(2f))
        assertEquals(1f, nextPlaybackSpeed(1.1f))
    }

    @Test
    fun progressLabelIsCompactForWear() {
        assertEquals("1:05 / 10:00", formatPlaybackTime(65_000L, 600_000L))
        assertEquals("1:01:01 / 2:00:00", formatPlaybackTime(3_661_000L, 7_200_000L))
        assertEquals("0:12", formatPlaybackTime(12_000L, 0L))
    }
}
