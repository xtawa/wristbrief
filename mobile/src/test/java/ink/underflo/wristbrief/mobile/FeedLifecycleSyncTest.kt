package ink.underflo.wristbrief.mobile

import ink.underflo.wristbrief.mobile.sync.CloudSyncOutbox
import ink.underflo.wristbrief.mobile.sync.OutboxMutation
import ink.underflo.wristbrief.mobile.sync.SyncEntityType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryFeedStore : MobileFeedStore {
    var feeds = emptyList<MobileFeedSubscription>()
    override fun load(): List<MobileFeedSubscription> = feeds
    override fun save(feeds: List<MobileFeedSubscription>) {
        this.feeds = feeds
    }
}

private class RecordingProbe : FeedProbe {
    override suspend fun validate(url: String): String? = "Discovered Title"
}

private class RecordingPublisher : FeedSyncPublisher {
    var published = 0
    override fun publish(feeds: List<MobileFeedSubscription>) {
        published += 1
    }
}

private class RecordingOutbox : CloudSyncOutbox {
    val enqueued = mutableListOf<Pair<SyncEntityType, Triple<String, String, Long>>>()
    override fun enqueue(entityType: SyncEntityType, entityId: String, payloadJson: String, updatedAtEpochMs: Long, isDeleted: Boolean) {
        enqueued += entityType to Triple("$entityId${if (isDeleted) ":deleted" else ""}", payloadJson, updatedAtEpochMs)
    }
    override fun getPending(limit: Int, nowEpochMs: Long): List<OutboxMutation> = emptyList()
    override fun remove(ids: List<String>) {}
    override fun count(): Int = 0
    override fun incrementRetry(ids: List<String>, nowEpochMs: Long) {}
    override fun clearAll() {}
}

private class RecordingListener : MobileFeedListener {
    val added = mutableListOf<MobileFeedSubscription>()
    val updated = mutableListOf<MobileFeedSubscription>()
    val removed = mutableListOf<MobileFeedSubscription>()
    override fun onSubscriptionAdded(feed: MobileFeedSubscription) {
        added += feed
    }
    override fun onSubscriptionUpdated(feed: MobileFeedSubscription) {
        updated += feed
    }
    override fun onSubscriptionRemoved(feed: MobileFeedSubscription) {
        removed += feed
    }
}

class FeedLifecycleSyncTest {
    @Test
    fun addEnqueuesSubscriptionUpsertAndNotifiesAdded() = runBlocking {
        val store = InMemoryFeedStore()
        val outbox = RecordingOutbox()
        val listener = RecordingListener()
        val manager = MobileFeedManager(store, RecordingProbe(), RecordingPublisher(), cloudOutbox = outbox, listener = listener)

        val result = manager.add("https://example.com/feed.xml", "My Feed")

        assertTrue(result is FeedMutationResult.Success)
        assertEquals(1, store.feeds.size)
        assertEquals(1, outbox.enqueued.size)
        assertEquals(SyncEntityType.SUBSCRIPTION, outbox.enqueued[0].first)
        assertTrue(outbox.enqueued[0].second.second.contains("\"feedUrl\":\"https://example.com/feed.xml\""))
        assertTrue(outbox.enqueued[0].second.second.contains("\"deleted\":false").not())
        assertEquals(listOf("My Feed"), listener.added.map { it.title })
        assertTrue(listener.updated.isEmpty())
    }

    @Test
    fun removeEnqueuesCloudTombstoneAndNotifiesRemoved() = runBlocking {
        val store = InMemoryFeedStore()
        val outbox = RecordingOutbox()
        val listener = RecordingListener()
        val manager = MobileFeedManager(store, RecordingProbe(), RecordingPublisher(), cloudOutbox = outbox, listener = listener)
        manager.add("https://example.com/feed.xml", "My Feed")
        val feed = store.feeds.first()

        val result = manager.remove(feed.id)

        assertTrue(result is FeedMutationResult.Success)
        assertTrue(store.feeds.isEmpty())
        // The second enqueued mutation is the deletion tombstone.
        assertEquals(2, outbox.enqueued.size)
        assertEquals("${feed.id}:deleted", outbox.enqueued[1].second.first)
        assertTrue(outbox.enqueued[1].second.second.contains("\"feedUrl\":\"https://example.com/feed.xml\""))
        assertEquals(listOf(feed.id), listener.removed.map { it.id })
    }

    @Test
    fun attributeToggleNotifiesUpdatedWithoutRefreshSemantics() = runBlocking {
        val store = InMemoryFeedStore()
        val listener = RecordingListener()
        val manager = MobileFeedManager(store, RecordingProbe(), RecordingPublisher(), cloudOutbox = RecordingOutbox(), listener = listener)
        manager.add("https://example.com/feed.xml", "My Feed")
        val feed = store.feeds.first()

        manager.setEnabled(feed.id, false)

        assertEquals(0, listener.added.size - 1) // only the initial add
        assertEquals(listOf(false), listener.updated.map { it.enabled })
        assertTrue(listener.removed.isEmpty())
    }

    @Test
    fun duplicateUrlIsRejectedLocally() = runBlocking {
        val store = InMemoryFeedStore()
        val manager = MobileFeedManager(store, RecordingProbe(), RecordingPublisher())
        manager.add("https://example.com/feed.xml", "My Feed")

        val duplicate = manager.add("https://example.com/feed.xml", "Second")

        assertTrue(duplicate is FeedMutationResult.Error)
        assertEquals(1, store.feeds.size)
    }
}
