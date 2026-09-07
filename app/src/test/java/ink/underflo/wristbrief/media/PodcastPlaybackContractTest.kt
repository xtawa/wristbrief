package ink.underflo.wristbrief.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastPlaybackContractTest {
    @Test
    fun acceptsHttpsPlaybackRequest() {
        val request = PodcastPlaybackRequest(
            id = "episode-1",
            title = "Episode 1",
            audioUrl = "https://example.com/audio.mp3"
        )

        assertEquals("episode-1", request.id)
        assertEquals("https://example.com/audio.mp3", request.audioUrl)
    }

    @Test
    fun acceptsCaseInsensitiveHttpsScheme() {
        assertEquals(
            "HTTPS://example.com/audio.mp3",
            requireHttpsPodcastUrl("HTTPS://example.com/audio.mp3")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpPlaybackUrl() {
        PodcastPlaybackRequest(
            id = "episode-1",
            title = "Episode 1",
            audioUrl = "http://example.com/audio.mp3"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankEpisodeId() {
        PodcastPlaybackRequest(
            id = " ",
            title = "Episode 1",
            audioUrl = "https://example.com/audio.mp3"
        )
    }
}
