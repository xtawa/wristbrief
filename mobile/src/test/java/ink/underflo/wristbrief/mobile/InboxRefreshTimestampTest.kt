package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The refresh timestamp behind Settings' "Last refresh" line and the auto-refresh policy. */
class InboxRefreshTimestampTest {
    private class MapStorage : PreferencesStorage {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()
        override fun getString(key: String, defValue: String?): String? = strings[key] ?: defValue
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getBoolean(key: String, defValue: Boolean): Boolean = booleans[key] ?: defValue
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    }

    private class InMemoryInboxStore : MobileInboxStore {
        var savedItems: List<MobileFeedItem> = emptyList()
        override fun load(): List<MobileFeedItem> = savedItems
        override fun save(items: List<MobileFeedItem>) { savedItems = items }
    }

    private class InMemoryFeedStore(var feeds: List<MobileFeedSubscription>) : MobileFeedStore {
        override fun load(): List<MobileFeedSubscription> = feeds
        override fun save(feeds: List<MobileFeedSubscription>) { this.feeds = feeds }
    }

    private object NoopProbe : FeedProbe { override suspend fun validate(url: String): String? = null }
    private object NoopPublisher : FeedSyncPublisher { override fun publish(feeds: List<MobileFeedSubscription>) {} }
    private object NoopState : ItemStateReaderAndWriter {
        override fun isRead(itemId: String) = false
        override fun isSaved(itemId: String) = false
        override fun setRead(itemId: String, isRead: Boolean) {}
        override fun setSaved(itemId: String, isSaved: Boolean) {}
    }

    @Test
    fun `preferences report null until a refresh has been recorded`() {
        val preferences = AppPreferences(MapStorage())
        assertNull(preferences.getLastInboxRefreshEpochMs())
        preferences.setLastInboxRefreshEpochMs(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, preferences.getLastInboxRefreshEpochMs())
    }

    @Test
    fun `repository records the refresh time once a refresh contacted feeds`() = runBlocking {
        val preferences = AppPreferences(MapStorage())
        val feed = MobileFeedSubscription(id = "f", title = "Feed", url = "https://example.com/feed.xml", enabled = true)
        val repo = MobileInboxRepository(
            feedManager = MobileFeedManager(InMemoryFeedStore(listOf(feed)), NoopProbe, NoopPublisher),
            store = InMemoryInboxStore(),
            stateAdapter = NoopState,
            fetcher = object : FeedItemFetcher {
                override suspend fun fetch(url: String): List<ParsedFeedItem> = emptyList()
            },
            clock = { 42L },
            onRefreshCompleted = { preferences.setLastInboxRefreshEpochMs(it) },
        )
        repo.refresh()
        assertEquals(42L, preferences.getLastInboxRefreshEpochMs())
    }

    @Test
    fun `no subscriptions means no refresh is recorded`() = runBlocking {
        val preferences = AppPreferences(MapStorage())
        val repo = MobileInboxRepository(
            feedManager = MobileFeedManager(InMemoryFeedStore(emptyList()), NoopProbe, NoopPublisher),
            store = InMemoryInboxStore(),
            stateAdapter = NoopState,
            fetcher = object : FeedItemFetcher {
                override suspend fun fetch(url: String): List<ParsedFeedItem> = emptyList()
            },
            clock = { 42L },
            onRefreshCompleted = { preferences.setLastInboxRefreshEpochMs(it) },
        )
        repo.refresh()
        assertNull(preferences.getLastInboxRefreshEpochMs())
    }
}
