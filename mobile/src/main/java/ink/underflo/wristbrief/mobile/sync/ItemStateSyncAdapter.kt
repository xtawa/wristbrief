package ink.underflo.wristbrief.mobile.sync

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Enqueues an ITEM_STATE mutation for the cloud outbox. Read and saved are
 * independent fields with independent clocks (ItemStateSync.kt): each user
 * action enqueues only the field it changed, so one field can never clobber
 * the other server-side (the gateway arbitrates per-field by timestamp).
 */
fun CloudSyncOutbox.enqueueItemState(
    itemId: String,
    isRead: Boolean? = null,
    isSaved: Boolean? = null,
    nowEpochMs: Long,
) {
    require(isRead != null || isSaved != null) { "item state mutation must carry a field" }
    val payload = buildJsonObject {
        if (isRead != null) put("isRead", isRead)
        if (isSaved != null) put("isSaved", isSaved)
    }.toString()
    enqueue(
        entityType = SyncEntityType.ITEM_STATE,
        entityId = itemId,
        payloadJson = payload,
        updatedAtEpochMs = nowEpochMs,
        isDeleted = false,
    )
}

/** Enqueues a SUBSCRIPTION upsert (or tombstone) for the cloud outbox. */
fun CloudSyncOutbox.enqueueSubscription(
    subscriptionId: String,
    feedUrl: String,
    nowEpochMs: Long,
    isDeleted: Boolean = false,
    title: String? = null,
    category: String? = null,
    enabled: Boolean = true,
    sendToWatch: Boolean = true,
    watchKeywords: List<String> = emptyList(),
) {
    val payload = buildJsonObject {
        put("feedUrl", feedUrl)
        if (title != null) put("title", title)
        if (category != null) put("category", category)
        put("enabled", enabled)
        put("sendToWatch", sendToWatch)
        if (watchKeywords.isNotEmpty()) put("watchKeywords", buildJsonArray { watchKeywords.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }.toString()
    enqueue(
        entityType = SyncEntityType.SUBSCRIPTION,
        entityId = subscriptionId,
        payloadJson = payload,
        updatedAtEpochMs = nowEpochMs,
        isDeleted = isDeleted,
    )
}
