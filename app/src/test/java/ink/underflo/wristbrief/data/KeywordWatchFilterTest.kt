package ink.underflo.wristbrief.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordWatchFilterTest {
    @Test fun keywords_areNormalizedAndMatchedCaseInsensitivelyWithOrSemantics() {
        assertEquals(listOf("AI", "Wear OS"), normalizeWatchKeywords(listOf(" AI ", "ai", "Wear   OS")))
        assertTrue(watchKeywordsMatch("New Gemini model", "AI release notes", listOf("ai", "podcast")))
        assertTrue(watchKeywordsMatch("Wear OS update", null, listOf("wear os")))
        assertFalse(watchKeywordsMatch("Android update", "Battery improvements", listOf("wear os", "gemini")))
        assertTrue(watchKeywordsMatch("Anything", null, emptyList()))
    }

    @Test fun refreshAndOfflineInboxApplyPerFeedKeywordFilter() {
        val subscription = FeedSubscription(
            id = "feed",
            title = "Tech",
            url = "https://example.com/feed",
            watchKeywords = listOf("AI"),
        )
        val store = MemoryFeedStore(
            subscriptions = mutableListOf(subscription),
            cached = mutableListOf(
                cached("old-match", "AI from cache"),
                cached("old-miss", "Unrelated cache"),
            ),
        )
        val loader = FeedLoader {
            listOf(
                FeedItem("AI launch", null, "New model", null, null, guid = "match"),
                FeedItem("Sports", null, "Match report", null, null, guid = "miss"),
            )
        }
        val repository = FeedInboxRepository(loader, store, clock = { 10L })

        val refreshed = repository.refresh()
        assertEquals(listOf("AI launch"), refreshed.items.map { it.title })
        assertEquals(listOf("AI launch"), repository.cachedItems().map { it.title })

        store.cached += cached("offline-miss", "Still unrelated")
        assertEquals(listOf("AI launch"), repository.cachedItems().map { it.title })
    }

    private fun cached(id: String, title: String) = CachedFeedItem(
        id = id,
        feedId = "feed",
        feedTitle = "Tech",
        title = title,
        link = null,
        description = null,
        published = null,
        audioUrl = null,
        cachedAtEpochMs = 1L,
    )

    private class MemoryFeedStore(
        val subscriptions: MutableList<FeedSubscription>,
        val cached: MutableList<CachedFeedItem>,
    ) : FeedStore {
        private var read = emptySet<String>()
        private var saved = emptySet<String>()
        override fun subscriptions(): List<FeedSubscription> = subscriptions.toList()
        override fun saveSubscriptions(subscriptions: List<FeedSubscription>) { this.subscriptions.apply { clear(); addAll(subscriptions) } }
        override fun cachedItems(): List<CachedFeedItem> = cached.toList()
        override fun saveCachedItems(items: List<CachedFeedItem>) { cached.apply { clear(); addAll(items) } }
        override fun readItemIds(): Set<String> = read
        override fun saveReadItemIds(itemIds: Set<String>) { read = itemIds }
        override fun savedItemIds(): Set<String> = saved
        override fun saveSavedItemIds(itemIds: Set<String>) { saved = itemIds }
    }
}
