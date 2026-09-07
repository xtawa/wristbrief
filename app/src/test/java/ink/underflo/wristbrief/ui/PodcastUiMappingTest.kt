package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.data.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastUiMappingTest {
    @Test
    fun carriesAudioUrlFromFeedItemToDetail() {
        val audioUrl = "https://example.com/episode.mp3"
        val inbox = FeedItem(
            guid = "episode-guid",
            title = "Episode",
            link = "https://example.com/episode",
            description = "Notes",
            published = "2026-09-08",
            audioUrl = audioUrl
        ).toInboxItemUi("Podcast Feed")

        assertTrue(inbox.isPodcast)
        assertEquals(audioUrl, inbox.audioUrl)
        assertEquals(audioUrl, inbox.toArticleDetailUi(isOffline = false).audioUrl)
    }
}
