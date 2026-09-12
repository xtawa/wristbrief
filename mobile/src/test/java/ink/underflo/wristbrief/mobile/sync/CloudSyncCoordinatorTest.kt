package ink.underflo.wristbrief.mobile.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudSyncCoordinatorTest {

    private lateinit var outbox: FakeCloudSyncOutbox
    private lateinit var target: FakeCloudSyncTarget
    private lateinit var fakePrefs: FakeCloudSyncPreferences

    class FakeCloudSyncOutbox : CloudSyncOutbox {
        val mutations = mutableListOf<OutboxMutation>()

        override fun enqueue(
            entityType: SyncEntityType,
            entityId: String,
            payloadJson: String,
            updatedAtEpochMs: Long,
            isDeleted: Boolean,
        ) {
            mutations.add(
                OutboxMutation(
                    id = "out_${mutations.size + 1}",
                    entityType = entityType,
                    entityId = entityId,
                    payloadJson = payloadJson,
                    updatedAtEpochMs = updatedAtEpochMs,
                    isDeleted = isDeleted,
                    retryCount = 0,
                    createdAtEpochMs = System.currentTimeMillis(),
                    nextAttemptEpochMs = 0L,
                )
            )
        }

        override fun getPending(limit: Int, nowEpochMs: Long): List<OutboxMutation> {
            return mutations.filter { it.nextAttemptEpochMs <= nowEpochMs }.take(limit)
        }

        override fun remove(ids: List<String>) {
            mutations.removeAll { it.id in ids }
        }

        override fun incrementRetry(ids: List<String>, nowEpochMs: Long) {
            for (i in mutations.indices) {
                val m = mutations[i]
                if (m.id in ids) {
                    val newRetry = m.retryCount + 1
                    val delay = minOf(300_000L, 1000L * (1 shl minOf(newRetry, 10)))
                    mutations[i] = m.copy(retryCount = newRetry, nextAttemptEpochMs = nowEpochMs + delay)
                }
            }
        }

        override fun count(): Int = mutations.size

        override fun clearAll() {
            mutations.clear()
        }
    }

    class FakeCloudSyncTarget : CloudSyncTarget {
        val mergedSubscriptions = mutableListOf<SyncSubscriptionDelta>()
        val mergedItemStates = mutableListOf<SyncItemStateDelta>()
        val mergedPlayback = mutableListOf<SyncPlaybackProgressDelta>()

        override fun mergeSubscriptions(deltas: List<SyncSubscriptionDelta>) {
            mergedSubscriptions.addAll(deltas)
        }

        override fun mergeItemStates(deltas: List<SyncItemStateDelta>) {
            mergedItemStates.addAll(deltas)
        }

        override fun mergePlaybackProgress(deltas: List<SyncPlaybackProgressDelta>) {
            mergedPlayback.addAll(deltas)
        }
    }

    class FakeCloudSyncPreferences : CloudSyncPreferences {
        private var deviceId: String? = null
        private val cursors = mutableMapOf<String, Long>()

        override fun getOrCreateDeviceId(): String {
            if (deviceId == null) deviceId = "dev_fake_123"
            return deviceId!!
        }

        override fun getCursor(deviceId: String): Long = cursors[deviceId] ?: 0L

        override fun saveCursor(deviceId: String, cursor: Long) {
            cursors[deviceId] = cursor
        }

        override fun resetAll() {
            deviceId = null
            cursors.clear()
        }
    }

    class FakeCloudSyncApi : CloudSyncApi {
        var pushCalled = false
        var pullCalled = false
        var shouldPushFail = false
        var shouldPullFail = false

        val pushedMutations = mutableListOf<OutboxMutation>()
        var pullDeltasResponse = SyncPullResult(
            cursor = 100L,
            hasMore = false,
            subscriptions = emptyList(),
            itemStates = emptyList(),
            playbackProgress = emptyList(),
        )

        override suspend fun push(sessionToken: String, deviceId: String, mutations: List<OutboxMutation>): Result<SyncPushResult> {
            pushCalled = true
            if (shouldPushFail) return Result.failure(RuntimeException("Network error"))
            pushedMutations.addAll(mutations)
            return Result.success(SyncPushResult(appliedCount = mutations.size, newCursor = 50L))
        }

        override suspend fun pull(sessionToken: String, deviceId: String, cursor: Long, limit: Int): Result<SyncPullResult> {
            pullCalled = true
            if (shouldPullFail) return Result.failure(RuntimeException("Pull error"))
            return Result.success(pullDeltasResponse)
        }
    }

    @Before
    fun setUp() {
        outbox = FakeCloudSyncOutbox()
        target = FakeCloudSyncTarget()
        fakePrefs = FakeCloudSyncPreferences()
    }

    @Test
    fun syncOnceDrainsOutboxOnPushSuccess() = runBlocking {
        outbox.enqueue(
            entityType = SyncEntityType.ITEM_STATE,
            entityId = "item_1",
            payloadJson = "{\"isRead\":true}",
            updatedAtEpochMs = 1000L,
        )
        assertEquals(1, outbox.count())

        val api = FakeCloudSyncApi()
        var wearNotified = false
        val coordinator = CloudSyncCoordinator(
            api = api,
            outbox = outbox,
            target = target,
            preferences = fakePrefs,
            wearPublisher = { wearNotified = true },
        )

        val result = coordinator.syncOnce("token_valid")
        assertTrue(result is SyncCycleResult.Success)
        assertTrue(api.pushCalled)
        assertEquals(0, outbox.count())
    }

    @Test
    fun syncOnceAppliesExponentialBackoffOnPushFailure() = runBlocking {
        outbox.enqueue(
            entityType = SyncEntityType.SUBSCRIPTION,
            entityId = "feed_1",
            payloadJson = "{\"feedUrl\":\"https://example.com\"}",
            updatedAtEpochMs = 1000L,
        )

        val api = FakeCloudSyncApi().apply { shouldPushFail = true }
        val coordinator = CloudSyncCoordinator(
            api = api,
            outbox = outbox,
            target = target,
            preferences = fakePrefs,
        )

        val t0 = 1_000_000L
        coordinator.syncOnce("token_valid", nowEpochMs = t0)

        // Mutation must still be in outbox, but not eligible immediately at t0
        assertEquals(1, outbox.count())
        val pendingAtT0 = outbox.getPending(limit = 10, nowEpochMs = t0)
        assertEquals(0, pendingAtT0.size)

        // Eligible after backoff (e.g. at t0 + 10_000)
        val pendingLater = outbox.getPending(limit = 10, nowEpochMs = t0 + 10_000)
        assertEquals(1, pendingLater.size)
        assertEquals(1, pendingLater[0].retryCount)
    }

    @Test
    fun syncOncePullsDeltasAndNotifiesWear() = runBlocking {
        val api = FakeCloudSyncApi()
        api.pullDeltasResponse = SyncPullResult(
            cursor = 250L,
            hasMore = false,
            subscriptions = listOf(
                SyncSubscriptionDelta(
                    subscriptionId = "sub_remote_1",
                    feedUrl = "https://remote.com/rss",
                    title = "Remote Feed",
                    category = "Tech",
                    enabled = true,
                    sendToWatch = true,
                    revision = 1L,
                    updatedAtEpochMs = 250L,
                    deletedAtEpochMs = null,
                )
            ),
            itemStates = emptyList(),
            playbackProgress = emptyList(),
        )

        var wearNotified = false
        val coordinator = CloudSyncCoordinator(
            api = api,
            outbox = outbox,
            target = target,
            preferences = fakePrefs,
            wearPublisher = { wearNotified = true },
        )

        val result = coordinator.syncOnce("token_valid")
        assertTrue(result is SyncCycleResult.Success)
        assertEquals(250L, (result as SyncCycleResult.Success).cursor)
        assertEquals(1, result.mergedCount)
        assertTrue(wearNotified)
        assertEquals(250L, fakePrefs.getCursor("dev_fake_123"))
        assertEquals(1, target.mergedSubscriptions.size)
    }

    @Test
    fun clearUserDataWipesOutboxAndPreferences() {
        outbox.enqueue(
            entityType = SyncEntityType.ITEM_STATE,
            entityId = "item_wipe",
            payloadJson = "{}",
            updatedAtEpochMs = 1000L,
        )
        fakePrefs.saveCursor("dev_fake_123", 999L)

        val coordinator = CloudSyncCoordinator(
            api = FakeCloudSyncApi(),
            outbox = outbox,
            target = target,
            preferences = fakePrefs,
        )

        coordinator.clearUserData()
        assertEquals(0, outbox.count())
        assertEquals(0L, fakePrefs.getCursor("dev_fake_123"))
    }
}
