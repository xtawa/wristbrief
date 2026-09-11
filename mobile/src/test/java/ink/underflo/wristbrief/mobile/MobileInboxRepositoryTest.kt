package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileInboxRepositoryTest {

    private class InMemoryInboxStore : MobileInboxStore {
        var savedItems: List<MobileFeedItem> = emptyList()
        override fun load(): List<MobileFeedItem> = savedItems
        override fun save(items: List<MobileFeedItem>) { savedItems = items }
    }

    private class InMemoryFeedStore(var feeds: List<MobileFeedSubscription> = emptyList()) : MobileFeedStore {
        override fun load(): List<MobileFeedSubscription> = feeds
        override fun save(feeds: List<MobileFeedSubscription>) { this.feeds = feeds }
    }

    private class FakeFeedProbe : FeedProbe {
        override suspend fun validate(url: String): String? = "Test Feed"
    }

    private class FakeFeedSyncPublisher : FeedSyncPublisher {
        override fun publish(feeds: List<MobileFeedSubscription>) {}
    }

    private class InMemoryItemStateAdapter : ItemStateReaderAndWriter {
        val readStates = mutableMapOf<String, Boolean>()
        val savedStates = mutableMapOf<String, Boolean>()
        override fun isRead(itemId: String): Boolean = readStates[itemId] == true
        override fun isSaved(itemId: String): Boolean = savedStates[itemId] == true
        override fun setRead(itemId: String, isRead: Boolean) { readStates[itemId] = isRead }
        override fun setSaved(itemId: String, isSaved: Boolean) { savedStates[itemId] = isSaved }
    }

    private class FakeItemFetcher(
        private val responses: Map<String, List<ParsedFeedItem>> = emptyMap(),
    ) : FeedItemFetcher {
        override suspend fun fetch(url: String): List<ParsedFeedItem> {
            return responses[url] ?: throw IllegalStateException("Network failed for $url")
        }
    }

    @Test
    fun emptySubscriptionsProducesEmptyInbox() = runBlocking {
        val feedManager = MobileFeedManager(InMemoryFeedStore(), FakeFeedProbe(), FakeFeedSyncPublisher())
        val repo = MobileInboxRepository(
            feedManager = feedManager,
            store = InMemoryInboxStore(),
            stateAdapter = InMemoryItemStateAdapter(),
            fetcher = FakeItemFetcher(),
        )

        val result = repo.refresh()
        assertEquals(0, result.totalCount)
        assertTrue(repo.items().isEmpty())
        assertFalse(result.isOfflineFallback)
    }

    @Test
    fun refreshFetchesAndCachesItems() = runBlocking {
        val feed = MobileFeedSubscription(
            id = "tech-feed-id",
            title = "Tech News",
            url = "https://example.com/tech.xml",
            enabled = true,
        )
        val feedManager = MobileFeedManager(InMemoryFeedStore(listOf(feed)), FakeFeedProbe(), FakeFeedSyncPublisher())
        val parsed = listOf(
            ParsedFeedItem(
                title = "Android 17 Preview",
                link = "https://example.com/android17",
                description = "All the new features.",
                published = "2026-09-10",
                audioUrl = null,
                guid = "guid-android-17",
            ),
            ParsedFeedItem(
                title = "Podcast #1",
                link = "https://example.com/ep1",
                description = "Audio episode.",
                published = "2026-09-09",
                audioUrl = "https://example.com/audio/ep1.mp3",
                guid = null,
            ),
        )
        val fetcher = FakeItemFetcher(mapOf("https://example.com/tech.xml" to parsed))
        val store = InMemoryInboxStore()
        val stateAdapter = InMemoryItemStateAdapter()

        val repo = MobileInboxRepository(
            feedManager = feedManager,
            store = store,
            stateAdapter = stateAdapter,
            fetcher = fetcher,
            clock = { 1000L },
        )

        val result = repo.refresh()
        assertEquals(2, result.totalCount)
        assertEquals(2, repo.items().size)
        assertEquals(2, repo.unreadItems().size)

        val firstItem = repo.items()[0]
        assertEquals("guid:guid-android-17", firstItem.id)
        assertEquals("Android 17 Preview", firstItem.title)

        // Test read status
        repo.setRead(firstItem.id, true)
        assertTrue(repo.isRead(firstItem.id))
        assertEquals(1, repo.unreadItems().size)

        // Test saved status
        repo.setSaved(firstItem.id, true)
        assertTrue(repo.isSaved(firstItem.id))
        assertEquals(1, repo.savedItems().size)
    }

    @Test
    fun failedFeedRetainsCachedItemsAsOfflineFallback() = runBlocking {
        val feed = MobileFeedSubscription(
            id = "failing-feed",
            title = "Flaky Feed",
            url = "https://example.com/flaky.xml",
            enabled = true,
        )
        val feedManager = MobileFeedManager(InMemoryFeedStore(listOf(feed)), FakeFeedProbe(), FakeFeedSyncPublisher())
        val store = InMemoryInboxStore().apply {
            savedItems = listOf(
                MobileFeedItem(
                    id = "cached-1",
                    feedId = "failing-feed",
                    feedTitle = "Flaky Feed",
                    title = "Previous Article",
                    link = "https://example.com/prev",
                    description = "Cached content",
                    published = null,
                    audioUrl = null,
                    cachedAtEpochMs = 500L,
                )
            )
        }

        val repo = MobileInboxRepository(
            feedManager = feedManager,
            store = store,
            stateAdapter = InMemoryItemStateAdapter(),
            fetcher = FakeItemFetcher(), // Throws network error
        )

        val result = repo.refresh()
        assertTrue(result.isOfflineFallback)
        assertEquals(1, result.failedFeedTitles.size)
        assertEquals("Flaky Feed", result.failedFeedTitles[0])
        assertEquals(1, repo.items().size)
        assertEquals("Previous Article", repo.items()[0].title)
    }
}
