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

    @Test
    fun inputHashIsDeterministicAndDetectsChanges() {
        val hash1 = DailyBriefInputBuilder.computeInputHash("Sample content")
        val hash2 = DailyBriefInputBuilder.computeInputHash("Sample content")
        val hash3 = DailyBriefInputBuilder.computeInputHash("Different content")
        assertEquals(hash1, hash2)
        assertTrue(hash1.isNotBlank())
        assertTrue(hash1 != hash3)
    }

    @Test
    fun inMemoryDailyBriefStoreRetainsUpTo7DaysAndPrunesOlder() {
        val store = InMemoryDailyBriefStore()
        for (i in 1..10) {
            store.save(
                DailyBriefRecord(
                    dateKey = "2026-09-%02d".format(i),
                    title = "Brief $i",
                    tiny = "Tiny $i",
                    long = "Long $i",
                    bullets = listOf("B$i"),
                    topics = listOf("T$i"),
                    generatedAtEpochMs = 1773200000000L + (i * 86_400_000L),
                    sourceCount = i,
                    inputHash = "hash-$i",
                )
            )
        }

        // Only 7 latest entries should remain (days 4..10)
        val history = store.history(limit = 10)
        assertEquals(7, history.size)
        assertEquals("2026-09-10", history.first().dateKey)
        assertEquals("2026-09-04", history.last().dateKey)

        // Day 1, 2, 3 should have been pruned
        assertNull(store.get("2026-09-01"))
        assertNull(store.get("2026-09-02"))
        assertNull(store.get("2026-09-03"))
        assertNotNull(store.get("2026-09-04"))
        assertNotNull(store.get("2026-09-10"))

        assertEquals("2026-09-10", store.getLatest()?.dateKey)
    }
}
