package ink.underflo.wristbrief.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncOutboxTest {
    private lateinit var store: InMemorySyncOutboxStore

    @Before
    fun setup() {
        store = InMemorySyncOutboxStore()
    }

    @Test
    fun enqueueAndRetrievePending() {
        val entry = OutboxEntry(
            id = "test-1",
            entityType = OutboxEntityType.ITEM_STATE,
            entityKey = "item-1",
            payload = """{"read":true}""",
            createdAtEpochMs = 1000L,
            nextAttemptAtEpochMs = 1000L,
        )

        store.enqueue(entry)
        assertEquals(1, store.size())

        val pending = store.pending(nowEpochMs = 1000L)
        assertEquals(1, pending.size)
        assertEquals("test-1", pending.first().id)
    }

    @Test
    fun enqueueReplacesPendingForSameEntity() {
        val entry1 = OutboxEntry(
            id = "test-1",
            entityType = OutboxEntityType.ITEM_STATE,
            entityKey = "item-1",
            payload = """{"read":true}""",
            createdAtEpochMs = 1000L,
        )
        val entry2 = OutboxEntry(
            id = "test-2",
            entityType = OutboxEntityType.ITEM_STATE,
            entityKey = "item-1",
            payload = """{"read":false}""",
            createdAtEpochMs = 2000L,
        )

        store.enqueue(entry1)
        store.enqueue(entry2)

        assertEquals(1, store.size())
        val pending = store.pending(nowEpochMs = 3000L)
        assertEquals("test-2", pending.first().id)
        assertEquals("""{"read":false}""", pending.first().payload)
    }

    @Test
    fun recordAttemptIncrementsAndAppliesBackoff() {
        val entry = OutboxEntry(
            id = "test-1",
            entityType = OutboxEntityType.PLAYBACK,
            entityKey = "ep-1",
            payload = """{"pos":100}""",
            createdAtEpochMs = 1000L,
            nextAttemptAtEpochMs = 1000L,
        )
        store.enqueue(entry)

        // Record 1st failed attempt
        store.recordAttempt("test-1", maxAttempts = 3, backoffBaseMs = 1000L, nowEpochMs = 1500L)

        // At 1600ms, backoff is 1000 * 2^0 = 1000ms -> next attempt at 2500ms
        assertTrue(store.pending(nowEpochMs = 1600L).isEmpty())
        assertEquals(1, store.pending(nowEpochMs = 2600L).size)

        // Record 2nd and 3rd failed attempt
        store.recordAttempt("test-1", maxAttempts = 3, backoffBaseMs = 1000L, nowEpochMs = 2600L)
        store.recordAttempt("test-1", maxAttempts = 3, backoffBaseMs = 1000L, nowEpochMs = 5000L)

        // After maxAttempts (3), it is dropped from active outbox
        assertEquals(0, store.size())
    }

    @Test
    fun dequeueRemovesEntry() {
        val entry = OutboxEntry(
            id = "test-1",
            entityType = OutboxEntityType.SUBSCRIPTION,
            entityKey = "sub-1",
            payload = "{}",
        )
        store.enqueue(entry)
        assertEquals(1, store.size())

        store.dequeue("test-1")
        assertEquals(0, store.size())
    }

    @Test
    fun clearWipesAllPending() {
        store.enqueue(OutboxEntry(id = "1", entityType = OutboxEntityType.ITEM_STATE, entityKey = "k1", payload = ""))
        store.enqueue(OutboxEntry(id = "2", entityType = OutboxEntityType.ITEM_STATE, entityKey = "k2", payload = ""))
        assertEquals(2, store.size())

        store.clear()
        assertEquals(0, store.size())
    }
}
