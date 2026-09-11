package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DailyBriefTest {

    @Test
    fun returnsNullWhenNoItemsProvided() {
        val input = DailyBriefInputBuilder.build(emptyList())
        assertNull(input)
    }

    @Test
    fun formatsUnreadItemsIntoBoundedDailyBriefInput() {
        val items = (1..10).map { i ->
            MobileFeedItem(
                id = "item-$i",
                feedId = "feed-$i",
                feedTitle = "Source $i",
                title = "Article $i",
                link = "https://example.com/article-$i",
                description = "<p>Clean excerpt for article $i</p>",
                published = "2026-09-11",
                audioUrl = null,
                cachedAtEpochMs = 1773200000000L,
            )
        }

        val input = DailyBriefInputBuilder.build(items)
        assertNotNull(input)
        assertTrue(input!!.itemCount in 1..6)
        assertTrue(input.title.contains("Daily Brief"))
        assertTrue(input.content.contains("Source: Source 1"))
        assertTrue(input.content.contains("Title: Article 1"))
        assertTrue(input.content.contains("Clean excerpt for article 1"))
        assertTrue(input.content.length <= 12_000)
    }

    @Test
    fun dateKeyIsIsoFormat() {
        val key = DailyBriefInputBuilder.todayKey(Date(1773200000000L))
        assertTrue(key.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}
