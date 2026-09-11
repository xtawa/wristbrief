package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedManagementTest {
    @Test fun urlValidation_requiresHttps_andNormalizesFragmentAndDefaultPort() {
        assertNull(normalizeFeedUrl("http://example.com/feed")); assertNull(normalizeFeedUrl("https://user:pass@example.com/feed")); assertEquals("https://example.com/feed", normalizeFeedUrl("HTTPS://EXAMPLE.COM:443/feed#latest"))
    }

    @Test fun categoryNormalization_collapsesWhitespace_boundsLength_andAllowsUncategorized() {
        assertNull(normalizeFeedCategory("   "))
        assertEquals("Tech News", normalizeFeedCategory("  Tech   News  "))
        assertEquals(80, normalizeFeedCategory("x".repeat(120))!!.length)
    }

    @Test fun manager_validatesBeforePersisting_andPublishesIndependentWearPreference() = runBlocking {
        val store = MemoryStore(); val publisher = RecordingPublisher(); val manager = MobileFeedManager(store, object : FeedProbe { override suspend fun validate(url: String) = "Example Feed" }, publisher)
        val added = manager.add("https://example.com/feed.xml", "", " Tech ") as FeedMutationResult.Success
        assertEquals("Example Feed", added.feeds.single().title); assertEquals("Tech", added.feeds.single().category); assertTrue(added.feeds.single().sendToWatch)
        val hidden = manager.setSendToWatch(added.feeds.single().id, false) as FeedMutationResult.Success
        assertFalse(hidden.feeds.single().sendToWatch); assertTrue(hidden.feeds.single().enabled)
        val moved = manager.setCategory(added.feeds.single().id, " News  Daily ") as FeedMutationResult.Success
        assertEquals("News Daily", moved.feeds.single().category)
        val disabled = manager.setEnabled(added.feeds.single().id, false) as FeedMutationResult.Success
        assertFalse(disabled.feeds.single().enabled); assertFalse(disabled.feeds.single().sendToWatch); assertEquals(4, publisher.snapshots.size)
        assertTrue(manager.add("https://example.com/feed.xml", "Duplicate") is FeedMutationResult.Error)
    }

    @Test fun manager_supportsCustomSendToWatchOnAddAndUpdate() = runBlocking {
        val store = MemoryStore(); val publisher = RecordingPublisher(); val manager = MobileFeedManager(store, object : FeedProbe { override suspend fun validate(url: String) = "Feed 2" }, publisher)
        val added = manager.add("https://example.com/nowatch.xml", "No Watch", sendToWatch = false) as FeedMutationResult.Success
        assertFalse(added.feeds.single().sendToWatch)
        val updated = manager.update(added.feeds.single().id, "https://example.com/nowatch.xml", "Watch Now", sendToWatch = true) as FeedMutationResult.Success
        assertTrue(updated.feeds.single().sendToWatch)
    }

    @Test fun mobileFeedCodec_defaultsLegacyRowsToSendToWatch_andUncategorized_andRoundTripsCategory() {
        val legacy = """[{"id":"a","title":"A","url":"https://example.com/a","enabled":false}]"""
        val decodedLegacy = decodeMobileFeedSubscriptions(legacy).single(); assertFalse(decodedLegacy.enabled); assertTrue(decodedLegacy.sendToWatch); assertNull(decodedLegacy.category)
        val original = listOf(MobileFeedSubscription("b", "B", "https://example.com/b", enabled = true, sendToWatch = false, category = "Tech"))
        assertEquals(original, decodeMobileFeedSubscriptions(encodeMobileFeedSubscriptions(original)))
    }

    @Test fun wearPayload_filtersPhoneOnlyFeeds_withoutChangingEnabledState() {
        val payload = wearSyncPayloadFor(listOf(MobileFeedSubscription("watch", "Watch", "https://example.com/watch", enabled = false, sendToWatch = true, category = "Tech"), MobileFeedSubscription("phone", "Phone", "https://example.com/phone", enabled = true, sendToWatch = false, category = "News")))
        assertEquals(listOf(SyncFeed("watch", "Watch", "https://example.com/watch", false)), payload.subscriptions)
    }

    private class MemoryStore : MobileFeedStore { var value = emptyList<MobileFeedSubscription>(); override fun load() = value; override fun save(feeds: List<MobileFeedSubscription>) { value = feeds } }
    private class RecordingPublisher : FeedSyncPublisher { val snapshots = mutableListOf<List<MobileFeedSubscription>>(); override fun publish(feeds: List<MobileFeedSubscription>) { snapshots += feeds } }
}
