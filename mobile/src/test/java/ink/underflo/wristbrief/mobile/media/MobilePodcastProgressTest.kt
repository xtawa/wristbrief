package ink.underflo.wristbrief.mobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePodcastProgressTest {

    @Test
    fun codecRoundTripsEpisodePositionAndSpeed() {
        val items = listOf(
            PodcastEpisodeProgress("ep-1-🎧", 120_000L, 1.5f),
            PodcastEpisodeProgress("ep-2", 45_000L, 1.25f),
        )
        val encoded = encodePodcastProgress(items)
        val decoded = decodePodcastProgress(encoded)

        assertEquals(2, decoded.size)
        assertEquals(120_000L, decoded["ep-1-🎧"]?.positionMs)
        assertEquals(1.5f, decoded["ep-1-🎧"]?.playbackSpeed)
        assertEquals(45_000L, decoded["ep-2"]?.positionMs)
        assertEquals(1.25f, decoded["ep-2"]?.playbackSpeed)
    }

    @Test
    fun resumePositionRestartsCompletedEpisodes() {
        assertEquals(120_000L, normalizedResumePosition(120_000L, 600_000L))
        assertEquals(0L, normalizedResumePosition(585_000L, 600_000L))
        assertEquals(0L, normalizedResumePosition(600_000L, 600_000L))
    }

    @Test
    fun checkpointPolicyRespects15SecondsInterval() {
        assertFalse(shouldCheckpoint(30_000L, 44_999L))
        assertTrue(shouldCheckpoint(30_000L, 45_000L))
        assertTrue(shouldCheckpoint(45_000L, 20_000L))
    }

    @Test
    fun playbackSpeedCyclesSupportedValues() {
        assertEquals(1.25f, nextPlaybackSpeed(1f))
        assertEquals(1.5f, nextPlaybackSpeed(1.25f))
        assertEquals(1.75f, nextPlaybackSpeed(1.5f))
        assertEquals(2f, nextPlaybackSpeed(1.75f))
        assertEquals(1f, nextPlaybackSpeed(2f))
    }

    @Test
    fun formatPlaybackTimeFormatsHoursAndMinutes() {
        assertEquals("01:15", formatPlaybackTime(75_000L, 0L))
        assertEquals("01:15 / 05:00", formatPlaybackTime(75_000L, 300_000L))
        assertEquals("1:01:15 / 1:30:00", formatPlaybackTime(3675_000L, 5400_000L))
    }

    @Test
    fun playbackContractEnforcesHttps() {
        val valid = PodcastPlaybackRequest("id1", "Title", "https://example.com/audio.mp3")
        assertEquals("https://example.com/audio.mp3", valid.audioUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackContractRejectsHttp() {
        PodcastPlaybackRequest("id1", "Title", "http://example.com/audio.mp3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackContractRejectsBlankId() {
        PodcastPlaybackRequest("  ", "Title", "https://example.com/audio.mp3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackContractRejectsBlankTitle() {
        PodcastPlaybackRequest("id1", "   ", "https://example.com/audio.mp3")
    }

    @Test
    fun defaultGetLatestActiveReturnsOnlyInProgressEpisodes() {
        val completed = PodcastEpisodeProgress("ep-done", 300_000L, 1f, 300_000L, false, 1000L, completed = true)
        val notStarted = PodcastEpisodeProgress("ep-new", 0L, 1f, 300_000L, false, 2000L, completed = false)
        val inProgress = PodcastEpisodeProgress("ep-active", 65_000L, 1.25f, 300_000L, true, 3000L, completed = false)

        val store = object : PodcastProgressStore {
            private val list = listOf(completed, notStarted, inProgress)
            override fun get(episodeId: String) = list.find { it.episodeId == episodeId }
            override fun all() = list
            override fun save(progress: PodcastEpisodeProgress) {}
        }

        val active = store.getLatestActive()
        assertEquals("ep-active", active?.episodeId)
        assertEquals(65_000L, active?.positionMs)
        assertEquals(1.25f, active?.playbackSpeed)
    }

    @Test
    fun defaultGetLatestActiveReturnsNullWhenAllCompletedOrNotStarted() {
        val completed = PodcastEpisodeProgress("ep-done", 300_000L, 1f, 300_000L, false, 1000L, completed = true)
        val notStarted = PodcastEpisodeProgress("ep-new", 0L, 1f, 300_000L, false, 2000L, completed = false)

        val store = object : PodcastProgressStore {
            private val list = listOf(completed, notStarted)
            override fun get(episodeId: String) = list.find { it.episodeId == episodeId }
            override fun all() = list
            override fun save(progress: PodcastEpisodeProgress) {}
        }

        val active = store.getLatestActive()
        assertEquals(null, active)
    }
}
