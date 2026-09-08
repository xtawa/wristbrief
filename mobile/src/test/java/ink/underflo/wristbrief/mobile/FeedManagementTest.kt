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

    @Test fun manager_validatesBeforePersisting_andPublishesMutations() = runBlocking {
        val store = MemoryStore(); val publisher = RecordingPublisher()
        val manager = MobileFeedManager(store, object : FeedProbe { override suspend fun validate(url: String) = "Example Feed" }, publisher)
        val added = manager.add("https://example.com/feed.xml", "") as FeedMutationResult.Success
        assertEquals("Example Feed", added.feeds.single().title)
        assertEquals(1, publisher.snapshots.size)
        val disabled = manager.setEnabled(added.feeds.single().id, false) as FeedMutationResult.Success
        assertFalse(disabled.feeds.single().enabled)
        assertEquals(2, publisher.snapshots.size)
        assertTrue(manager.add("https://example.com/feed.xml", "Duplicate") is FeedMutationResult.Error)
    }

    private class MemoryStore : MobileFeedStore { var value = emptyList<MobileFeedSubscription>(); override fun load() = value; override fun save(feeds: List<MobileFeedSubscription>) { value = feeds } }
    private class RecordingPublisher : FeedSyncPublisher { val snapshots = mutableListOf<List<MobileFeedSubscription>>(); override fun publish(feeds: List<MobileFeedSubscription>) { snapshots += feeds } }
}
