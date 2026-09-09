package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedManagementTest {
    @Test fun urlValidation_requiresHttps_andNormalizesFragmentAndDefaultPort() {
        assertNull(normalizeFeedUrl("http://example.com/feed"))
        assertNull(normalizeFeedUrl("https://user:pass@example.com/feed"))
        assertEquals("https://example.com/feed", normalizeFeedUrl("HTTPS://EXAMPLE.COM:443/feed#latest"))
    }

    @Test fun manager_validatesBeforePersisting_andPublishesIndependentWearPreference() = runBlocking {
        val store = MemoryStore(); val publisher = RecordingPublisher()
        val manager = MobileFeedManager(store, object : FeedProbe { override suspend fun validate(url: String) = "Example Feed" }, publisher)
        val added = manager.add("https://example.com/feed.xml", "") as FeedMutationResult.Success
        assertEquals("Example Feed", added.feeds.single().title)
        assertTrue(added.feeds.single().sendToWatch)
        assertEquals(1, publisher.snapshots.size)

        val hidden = manager.setSendToWatch(added.feeds.single().id, false) as FeedMutationResult.Success
        assertFalse(hidden.feeds.single().sendToWatch)
        assertTrue(hidden.feeds.single().enabled)

        val disabled = manager.setEnabled(added.feeds.single().id, false) as FeedMutationResult.Success
        assertFalse(disabled.feeds.single().enabled)
        assertFalse(disabled.feeds.single().sendToWatch)
        assertEquals(3, publisher.snapshots.size)
        assertTrue(manager.add("https://example.com/feed.xml", "Duplicate") is FeedMutationResult.Error)
    }

    @Test fun mobileFeedCodec_defaultsLegacyRowsToSendToWatch_andRoundTripsNewField() {
        val legacy = """[{"id":"a","title":"A","url":"https://example.com/a","enabled":false}]"""
        val decodedLegacy = decodeMobileFeedSubscriptions(legacy).single()
        assertFalse(decodedLegacy.enabled)
        assertTrue(decodedLegacy.sendToWatch)

        val original = listOf(MobileFeedSubscription("b", "B", "https://example.com/b", enabled = true, sendToWatch = false))
        assertEquals(original, decodeMobileFeedSubscriptions(encodeMobileFeedSubscriptions(original)))
    }

    @Test fun wearPayload_filtersPhoneOnlyFeeds_withoutChangingEnabledState() {
        val payload = wearSyncPayloadFor(
            listOf(
                MobileFeedSubscription("watch", "Watch", "https://example.com/watch", enabled = false, sendToWatch = true),
                MobileFeedSubscription("phone", "Phone", "https://example.com/phone", enabled = true, sendToWatch = false),
            ),
        )
        assertEquals(listOf(SyncFeed("watch", "Watch", "https://example.com/watch", false)), payload.subscriptions)
    }

    private class MemoryStore : MobileFeedStore { var value = emptyList<MobileFeedSubscription>(); override fun load() = value; override fun save(feeds: List<MobileFeedSubscription>) { value = feeds } }
    private class RecordingPublisher : FeedSyncPublisher { val snapshots = mutableListOf<List<MobileFeedSubscription>>(); override fun publish(feeds: List<MobileFeedSubscription>) { snapshots += feeds } }
}
