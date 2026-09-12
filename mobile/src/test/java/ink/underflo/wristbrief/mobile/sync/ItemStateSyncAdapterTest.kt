package ink.underflo.wristbrief.mobile.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutbox : CloudSyncOutbox {
    val enqueued = mutableListOf<Pair<SyncEntityType, Triple<String, String, Long>>>()
    var pending = emptyList<OutboxMutation>()
    val removed = mutableListOf<String>()
    var cleared = false

    override fun enqueue(entityType: SyncEntityType, entityId: String, payloadJson: String, updatedAtEpochMs: Long, isDeleted: Boolean) {
        enqueued += entityType to Triple(entityId, payloadJson, updatedAtEpochMs)
    }
    override fun getPending(limit: Int, nowEpochMs: Long): List<OutboxMutation> = pending
    override fun remove(ids: List<String>) {
        removed += ids
    }
    override fun count(): Int = pending.size
    override fun incrementRetry(ids: List<String>, nowEpochMs: Long) {}
    override fun clearAll() {
        cleared = true
    }
}

private class FakeCloudSyncApi : CloudSyncApi {
    var pushCalls = 0
    var pullCalls = 0
    var pushedToken: String? = null
    var pushedMutations: List<OutboxMutation> = emptyList()
    var pullResult = SyncPullResult(cursor = 42L, hasMore = false, subscriptions = emptyList(), itemStates = emptyList(), playbackProgress = emptyList())
    var pushFailure = false

    override suspend fun push(sessionToken: String, deviceId: String, mutations: List<OutboxMutation>): Result<SyncPushResult> {
        pushCalls += 1
        pushedToken = sessionToken
        pushedMutations = mutations
        return if (pushFailure) Result.failure(IllegalStateException("offline")) else Result.success(SyncPushResult(appliedCount = mutations.size, newCursor = 42L))
    }
    override suspend fun pull(sessionToken: String, deviceId: String, cursor: Long, limit: Int): Result<SyncPullResult> {
        pullCalls += 1
        return Result.success(pullResult)
    }
}

private class FakePreferences : CloudSyncPreferences {
    var cursor = 0L
    override fun getOrCreateDeviceId(): String = "dev_test"
    override fun getCursor(deviceId: String): Long = cursor
    override fun saveCursor(deviceId: String, cursor: Long) {
        this.cursor = cursor
    }
    override fun resetAll() {
        cursor = 0L
    }
}

private object NoopSyncTarget : CloudSyncTarget {
    override fun mergeSubscriptions(deltas: List<SyncSubscriptionDelta>) {}
    override fun mergeItemStates(deltas: List<SyncItemStateDelta>) {}
    override fun mergePlaybackProgress(deltas: List<SyncPlaybackProgressDelta>) {}
}

private fun coordinatorOf(api: CloudSyncApi, outbox: CloudSyncOutbox, preferences: CloudSyncPreferences) =
    CloudSyncCoordinator(api = api, outbox = outbox, target = NoopSyncTarget, preferences = preferences)

class ItemStateSyncAdapterTest {
    @Test
    fun itemStateMutationCarriesOnlyTheChangedField() {
        val outbox = FakeOutbox()
        outbox.enqueueItemState("item-1", isRead = true, nowEpochMs = 1000L)
        outbox.enqueueItemState("item-1", isSaved = false, nowEpochMs = 2000L)

        assertEquals(SyncEntityType.ITEM_STATE, outbox.enqueued[0].first)
        assertEquals("item-1", outbox.enqueued[0].second.first)
        assertEquals("""{"isRead":true}""", outbox.enqueued[0].second.second)
        assertEquals(1000L, outbox.enqueued[0].second.third)
        // Saved mutations never echo the read field (and vice versa), so the two
        // field clocks stay independent end-to-end.
        assertEquals("""{"isSaved":false}""", outbox.enqueued[1].second.second)
    }

    @Test
    fun subscriptionUpsertAndTombstonePayloads() {
        val outbox = FakeOutbox()
        outbox.enqueueSubscription("feed-1", "https://example.com/rss", nowEpochMs = 100L, title = "Example", category = "news")
        outbox.enqueueSubscription("feed-1", "https://example.com/rss", nowEpochMs = 200L, isDeleted = true)

        assertEquals(SyncEntityType.SUBSCRIPTION, outbox.enqueued[0].first)
        assertTrue(outbox.enqueued[0].second.second.contains("\"feedUrl\":\"https://example.com/rss\""))
        assertTrue(outbox.enqueued[0].second.second.contains("\"title\":\"Example\""))
        assertTrue(outbox.enqueued[1].second.second.contains("\"feedUrl\""))
    }

    @Test
    fun signedOutRuntimeNeverStartsACycle() {
        val api = FakeCloudSyncApi()
        val runtime = CloudSyncRuntime(
            coordinator = coordinatorOf(api, FakeOutbox(), FakePreferences()),
            sessionTokenProvider = { null },
        )
        assertNull(runtime.requestSync())
        assertEquals(0, api.pushCalls)
        assertEquals(0, api.pullCalls)
        runtime.dispose()
    }

    @Test
    fun runtimeDrainsOutboxPushesAndPulls() = runBlocking {
        val outbox = FakeOutbox()
        val mutation = OutboxMutation(
            id = "m1",
            entityType = SyncEntityType.ITEM_STATE,
            entityId = "item-1",
            payloadJson = """{"isRead":true}""",
            updatedAtEpochMs = 100,
            createdAtEpochMs = 100,
        )
        outbox.pending = listOf(mutation)
        val api = FakeCloudSyncApi()
        val preferences = FakePreferences()
        val runtime = CloudSyncRuntime(
            coordinator = coordinatorOf(api, outbox, preferences),
            sessionTokenProvider = { "wbs_${"A".repeat(43)}" },
        )

        runtime.requestSync()?.join()

        assertEquals(1, api.pushCalls)
        assertEquals("wbs_${"A".repeat(43)}", api.pushedToken)
        assertEquals(listOf("m1"), outbox.removed)
        assertTrue(api.pullCalls >= 1)
        assertEquals(42L, preferences.cursor)
        runtime.dispose()
    }

    @Test
    fun failedPushKeepsMutationsForRetryWithBackoff() = runBlocking {
        val outbox = FakeOutbox()
        outbox.pending = listOf(
            OutboxMutation(
                id = "m1",
                entityType = SyncEntityType.ITEM_STATE,
                entityId = "item-1",
                payloadJson = """{"isRead":true}""",
                updatedAtEpochMs = 100,
                createdAtEpochMs = 100,
            )
        )
        val api = FakeCloudSyncApi()
        api.pushFailure = true
        val runtime = CloudSyncRuntime(
            coordinator = coordinatorOf(api, outbox, FakePreferences()),
            sessionTokenProvider = { "wbs_${"B".repeat(43)}" },
        )

        runtime.requestSync()?.join()

        assertEquals(1, api.pushCalls)
        assertTrue(outbox.removed.isEmpty())
        runtime.dispose()
    }
}
