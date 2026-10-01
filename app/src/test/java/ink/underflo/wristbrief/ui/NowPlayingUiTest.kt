package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.media.PodcastPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingUiTest {
    @Test
    fun nothingLoadedIsAnExplicitEmptyStateWithoutInventedProgress() {
        val ui = PodcastPlaybackState().toNowPlayingUi()

        assertFalse(ui.hasMedia)
        assertNull(ui.title)
        assertNull(ui.progressLabel)
        assertFalse(ui.isPlaying)
    }

    @Test
    fun loadedEpisodeShowsMetadataTitleNeverTheMediaId() {
        val ui = PodcastPlaybackState(
            mediaId = "guid:https://example.com/ep-42",
            title = "  Episode 42  ",
            isPlaying = true,
            positionMs = 61_000L,
            durationMs = 600_000L,
            playbackSpeed = 1.5f,
        ).toNowPlayingUi()

        assertTrue(ui.hasMedia)
        assertEquals("Episode 42", ui.title)
        assertEquals("1:01 / 10:00", ui.progressLabel)
        assertTrue(ui.isPlaying)
        assertEquals(1.5f, ui.playbackSpeed)
    }

    @Test
    fun loadedEpisodeWithoutMetadataTitleFallsBackToGenericLabelNotId() {
        val ui = PodcastPlaybackState(mediaId = "guid:opaque", title = " ").toNowPlayingUi()

        assertTrue(ui.hasMedia)
        assertNull(ui.title)
    }
}
