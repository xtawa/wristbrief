package ink.underflo.wristbrief.mobile.sync

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncMergeTest {

    private class InMemoryCloudSyncOutbox : CloudSyncOutbox {
        private val mutations = mutableListOf<OutboxMutation>()

        override fun enqueue(
            entityType: SyncEntityType,
            entityId: String,
            payloadJson: String,
            updatedAtEpochMs: Long,
            isDeleted: Boolean,
        ) {
            mutations.add(
                OutboxMutation(
                    id = "out_${System.currentTimeMillis()}_${entityType.name}_$entityId",
                    entityType = entityType,
                    entityId = entityId,
                    payloadJson = payloadJson,
                    updatedAtEpochMs = updatedAtEpochMs,
                    isDeleted = isDeleted,
                    retryCount = 0,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        override fun getPending(limit: Int, nowEpochMs: Long): List<OutboxMutation> = mutations.take(limit)
        override fun remove(ids: List<String>) {
            mutations.removeAll { it.id in ids }
        }
        override fun count(): Int = mutations.size
    }

    @Test
    fun outboxEnqueuesAndSerializesMutations() {
        val outbox = InMemoryCloudSyncOutbox()

        outbox.enqueue(
            entityType = SyncEntityType.ITEM_STATE,
            entityId = "item_1",
            payloadJson = """{"isRead":true}""",
            updatedAtEpochMs = 1000L,
        )

        val pending = outbox.getPending()
        assertEquals(1, pending.size)
        assertEquals(SyncEntityType.ITEM_STATE, pending[0].entityType)
        assertEquals("item_1", pending[0].entityId)

        val wire = pending[0].toWireJson()
        assertEquals("item_state", wire["entity"]?.jsonPrimitive?.content)
        assertEquals("item_1", wire["entityId"]?.jsonPrimitive?.content)
        assertEquals(1000L, wire["updatedAt"]?.jsonPrimitive?.longOrNull)
        assertEquals(true, wire["payload"]?.jsonObject?.get("isRead")?.jsonPrimitive?.booleanOrNull)

        outbox.remove(listOf(pending[0].id))
        assertEquals(0, outbox.count())
    }

    @Test
    fun conflictResolverMaintainsIndependentReadAndSavedClocks() {
        // Initially: unread, unsaved at t=0
        var state = ResolvedItemState(isRead = false, isSaved = false, readAt = 0L, savedAt = 0L)

        // 1. Mark saved at t=1000
        state = CloudSyncConflictResolver.resolveItemState(
            currentIsRead = state.isRead,
            currentIsSaved = state.isSaved,
            currentReadAt = state.readAt,
            currentSavedAt = state.savedAt,
            delta = SyncItemStateDelta(
                itemId = "art_1",
                isRead = false,
                isSaved = true,
                readChangedAtEpochMs = null,
                savedChangedAtEpochMs = 1000L,
                revision = 1L,
                updatedAtEpochMs = 1000L,
            ),
        )
        assertFalse(state.isRead)
        assertTrue(state.isSaved)
        assertEquals(1000L, state.savedAt)

        // 2. Mark read at t=1500 (without overwriting saved)
        state = CloudSyncConflictResolver.resolveItemState(
            currentIsRead = state.isRead,
            currentIsSaved = state.isSaved,
            currentReadAt = state.readAt,
            currentSavedAt = state.savedAt,
            delta = SyncItemStateDelta(
                itemId = "art_1",
                isRead = true,
                isSaved = false, // Stale/missing saved flag in incoming delta
                readChangedAtEpochMs = 1500L,
                savedChangedAtEpochMs = null, // saved was not changed
                revision = 2L,
                updatedAtEpochMs = 1500L,
            ),
        )
        // Both read and saved must remain true!
        assertTrue(state.isRead)
        assertTrue(state.isSaved)
        assertEquals(1500L, state.readAt)
        assertEquals(1000L, state.savedAt)
    }

    @Test
    fun conflictResolverArbitratesPlaybackGenerationsAndCompletion() {
        // Episode completed at gen 1, t=2000
        val currentGen = 1L
        val currentCompleted = true
        val currentLastPlayedAt = 2000L

        // 1. Stale update at gen 1, t=1000, not completed -> Should be rejected
        val staleDelta = SyncPlaybackProgressDelta(
            contentId = "ep_1",
            episodeLocalId = "ep_1",
            positionMs = 100_000L,
            durationMs = 300_000L,
            playbackSpeed = 1.0f,
            completed = false,
            progressGeneration = 1L,
            revision = 1L,
            updatedAtEpochMs = 1000L,
        )
        assertFalse(
            CloudSyncConflictResolver.shouldApplyPlaybackProgress(
                currentGen,
                currentCompleted,
                currentLastPlayedAt,
                staleDelta,
            ),
        )

        // 2. Deliberate replay: gen 2 at t=3000, not completed -> Should be accepted
        val newGenDelta = SyncPlaybackProgressDelta(
            contentId = "ep_1",
            episodeLocalId = "ep_1",
            positionMs = 5000L,
            durationMs = 300_000L,
            playbackSpeed = 1.0f,
            completed = false,
            progressGeneration = 2L,
            revision = 2L,
            updatedAtEpochMs = 3000L,
        )
        assertTrue(
            CloudSyncConflictResolver.shouldApplyPlaybackProgress(
                currentGen,
                currentCompleted,
                currentLastPlayedAt,
                newGenDelta,
            ),
        )

        // 3. Lower generation update (gen 0) -> Should be rejected
        val oldGenDelta = SyncPlaybackProgressDelta(
            contentId = "ep_1",
            episodeLocalId = "ep_1",
            positionMs = 5000L,
            durationMs = 300_000L,
            playbackSpeed = 1.0f,
            completed = false,
            progressGeneration = 0L,
            revision = 1L,
            updatedAtEpochMs = 4000L,
        )
        assertFalse(
            CloudSyncConflictResolver.shouldApplyPlaybackProgress(
                currentGen,
                currentCompleted,
                currentLastPlayedAt,
                oldGenDelta,
            ),
        )
    }
}
