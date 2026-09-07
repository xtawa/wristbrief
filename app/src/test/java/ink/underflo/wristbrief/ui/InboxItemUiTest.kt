package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.data.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxItemUiTest {
    @Test
    fun articleItem_isSanitizedAndMapped() {
        val item = FeedItem(
            title = "  Android &amp; Wear  ",
            link = "https://example.com/article",
            description = "<p>Material <b>3</b> update&nbsp;arrived.</p>",
            published = "  2h ago  ",
            audioUrl = null
        ).toInboxItemUi(" Example Feed ")

        assertEquals("Android & Wear", item.title)
        assertEquals("Material 3 update arrived.", item.summary)
        assertEquals("Example Feed", item.source)
        assertEquals("2h ago", item.timeLabel)
        assertFalse(item.isPodcast)
        assertEquals("https://example.com/article", item.id)
    }

    @Test
    fun podcastItem_usesFallbackSummaryAndAudioAsStableId() {
        val item = FeedItem(
            title = "Episode 42",
            link = null,
            description = null,
            published = null,
            audioUrl = "https://cdn.example.com/42.mp3"
        ).toInboxItemUi("Decoder")

        assertTrue(item.isPodcast)
        assertEquals("Podcast episode", item.summary)
        assertEquals("https://cdn.example.com/42.mp3", item.id)
        assertEquals("", item.timeLabel)
    }

    @Test
    fun blankSourceAndDescription_haveReadableFallbacks() {
        val item = FeedItem(
            title = "Story",
            link = null,
            description = "   ",
            published = null,
            audioUrl = null
        ).toInboxItemUi("  ")

        assertEquals("Feed", item.source)
        assertEquals("Open to read more", item.summary)
        assertEquals("Story", item.id)
    }
}
