package ink.underflo.wristbrief.mobile.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SyncEntityType {
    SUBSCRIPTION,
    ITEM_STATE,
    PLAYBACK_PROGRESS;

    fun toWire(): String = when (this) {
        SUBSCRIPTION -> "subscription"
        ITEM_STATE -> "item_state"
        PLAYBACK_PROGRESS -> "playback_progress"
    }

    companion object {
        fun fromWire(value: String): SyncEntityType = when (value) {
            "subscription" -> SUBSCRIPTION
            "item_state" -> ITEM_STATE
            "playback_progress" -> PLAYBACK_PROGRESS
            else -> throw IllegalArgumentException("Unknown sync entity type: $value")
        }
    }
}

data class OutboxMutation(
    val id: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val payloadJson: String,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean = false,
    val retryCount: Int = 0,
    val createdAtEpochMs: Long,
    val nextAttemptEpochMs: Long = 0L,
) {
    fun toWireJson(): JsonObject = buildJsonObject {
        put("idempotencyKey", id)
        put("entity", entityType.toWire())
        put("entityId", entityId)
        put("updatedAt", updatedAtEpochMs)
        put("deleted", isDeleted)
        put("payload", Json.parseToJsonElement(payloadJson))
    }
}

data class SyncSubscriptionDelta(
    val subscriptionId: String,
    val feedUrl: String,
    val title: String?,
    val category: String?,
    val enabled: Boolean,
    val sendToWatch: Boolean,
    val revision: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long?,
)

data class SyncItemStateDelta(
    val itemId: String,
    val isRead: Boolean,
    val isSaved: Boolean,
    val readChangedAtEpochMs: Long?,
    val savedChangedAtEpochMs: Long?,
    val revision: Long,
    val updatedAtEpochMs: Long,
)

data class SyncPlaybackProgressDelta(
    val contentId: String,
    val episodeLocalId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val completed: Boolean,
    val progressGeneration: Long,
    val revision: Long,
    val updatedAtEpochMs: Long,
)

data class SyncPullResult(
    val cursor: Long,
    val hasMore: Boolean,
    val subscriptions: List<SyncSubscriptionDelta>,
    val itemStates: List<SyncItemStateDelta>,
    val playbackProgress: List<SyncPlaybackProgressDelta>,
)

data class SyncPushResult(
    val appliedCount: Int,
    val newCursor: Long = 0L,
)

sealed interface SyncCycleResult {
    data class Success(val cursor: Long, val mergedCount: Int) : SyncCycleResult
    data class PartialFailure(val reason: String) : SyncCycleResult
}
