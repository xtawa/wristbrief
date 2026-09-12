package ink.underflo.wristbrief.mobile.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper

interface CloudSyncTarget {
    fun mergeSubscriptions(deltas: List<SyncSubscriptionDelta>)
    fun mergeItemStates(deltas: List<SyncItemStateDelta>)
    fun mergePlaybackProgress(deltas: List<SyncPlaybackProgressDelta>)
}

class CloudSyncMerge(
    private val dbHelper: WristBriefDatabaseHelper,
) : CloudSyncTarget {
    override fun mergeSubscriptions(deltas: List<SyncSubscriptionDelta>) {
        if (deltas.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (delta in deltas) {
                if (delta.deletedAtEpochMs != null) {
                    // Soft delete / disable locally
                    val values = ContentValues().apply {
                        put("enabled", 0)
                    }
                    db.update("feed_sources", values, "feed_id = ?", arrayOf(delta.subscriptionId))
                } else {
                    val values = ContentValues().apply {
                        put("feed_id", delta.subscriptionId)
                        put("url", delta.feedUrl)
                        if (delta.title != null) put("title", delta.title)
                        if (delta.category != null) put("category", delta.category)
                        put("enabled", if (delta.enabled) 1 else 0)
                        put("send_to_watch", if (delta.sendToWatch) 1 else 0)
                    }
                    db.insertWithOnConflict("feed_sources", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun mergeItemStates(deltas: List<SyncItemStateDelta>) {
        if (deltas.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (delta in deltas) {
                val cursor = db.query(
                    "item_states",
                    arrayOf("item_id", "is_read", "is_saved", "read_at_epoch_ms", "saved_at_epoch_ms"),
                    "item_id = ?",
                    arrayOf(delta.itemId),
                    null,
                    null,
                    null,
                )
                val existing = cursor.use {
                    if (it.moveToFirst()) {
                        Triple(
                            it.getInt(1) == 1,
                            it.getInt(2) == 1,
                            Pair(it.getLong(3), it.getLong(4))
                        )
                    } else null
                }

                if (existing != null) {
                    var newIsRead = existing.first
                    var newIsSaved = existing.second
                    var newReadAt = existing.third.first
                    var newSavedAt = existing.third.second

                    if (delta.readChangedAtEpochMs != null && delta.readChangedAtEpochMs >= newReadAt) {
                        newIsRead = delta.isRead
                        newReadAt = delta.readChangedAtEpochMs
                    }
                    if (delta.savedChangedAtEpochMs != null && delta.savedChangedAtEpochMs >= newSavedAt) {
                        newIsSaved = delta.isSaved
                        newSavedAt = delta.savedChangedAtEpochMs
                    }

                    val values = ContentValues().apply {
                        put("is_read", if (newIsRead) 1 else 0)
                        put("is_saved", if (newIsSaved) 1 else 0)
                        put("read_at_epoch_ms", newReadAt)
                        put("saved_at_epoch_ms", newSavedAt)
                        put("updated_at_epoch_ms", delta.updatedAtEpochMs)
                    }
                    db.update("item_states", values, "item_id = ?", arrayOf(delta.itemId))
                } else {
                    val values = ContentValues().apply {
                        put("item_id", delta.itemId)
                        put("feed_id", "")
                        put("is_read", if (delta.isRead) 1 else 0)
                        put("is_saved", if (delta.isSaved) 1 else 0)
                        put("read_at_epoch_ms", delta.readChangedAtEpochMs ?: delta.updatedAtEpochMs)
                        put("saved_at_epoch_ms", delta.savedChangedAtEpochMs ?: delta.updatedAtEpochMs)
                        put("updated_at_epoch_ms", delta.updatedAtEpochMs)
                    }
                    db.insertWithOnConflict("item_states", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun mergePlaybackProgress(deltas: List<SyncPlaybackProgressDelta>) {
        if (deltas.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (delta in deltas) {
                val key = delta.episodeLocalId ?: delta.contentId
                val cursor = db.query(
                    "playback_progress",
                    arrayOf("item_id", "position_ms", "completed", "last_played_at_epoch_ms", "progress_generation"),
                    "item_id = ?",
                    arrayOf(key),
                    null,
                    null,
                    null,
                )
                val existing = cursor.use {
                    if (it.moveToFirst()) {
                        ProgressEntry(
                            positionMs = it.getLong(1),
                            completed = it.getInt(2) == 1,
                            lastPlayedAt = it.getLong(3),
                            generation = it.getLong(4)
                        )
                    } else null
                }

                if (existing != null) {
                    if (delta.progressGeneration < existing.generation) {
                        continue
                    }
                    if (delta.progressGeneration == existing.generation) {
                        if (existing.completed && !delta.completed && delta.updatedAtEpochMs <= existing.lastPlayedAt) {
                            continue
                        }
                        if (delta.updatedAtEpochMs < existing.lastPlayedAt) {
                            continue
                        }
                    }

                    val values = ContentValues().apply {
                        put("position_ms", delta.positionMs)
                        put("duration_ms", delta.durationMs)
                        put("playback_speed", delta.playbackSpeed)
                        put("completed", if (delta.completed) 1 else 0)
                        put("last_played_at_epoch_ms", delta.updatedAtEpochMs)
                        put("progress_generation", delta.progressGeneration)
                    }
                    db.update("playback_progress", values, "item_id = ?", arrayOf(key))
                } else {
                    val values = ContentValues().apply {
                        put("item_id", key)
                        put("position_ms", delta.positionMs)
                        put("duration_ms", delta.durationMs)
                        put("playback_speed", delta.playbackSpeed)
                        put("completed", if (delta.completed) 1 else 0)
                        put("last_played_at_epoch_ms", delta.updatedAtEpochMs)
                        put("progress_generation", delta.progressGeneration)
                    }
                    db.insertWithOnConflict("playback_progress", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private data class ProgressEntry(
        val positionMs: Long,
        val completed: Boolean,
        val lastPlayedAt: Long,
        val generation: Long,
    )
}

data class ResolvedItemState(
    val isRead: Boolean,
    val isSaved: Boolean,
    val readAt: Long,
    val savedAt: Long,
)

object CloudSyncConflictResolver {
    fun resolveItemState(
        currentIsRead: Boolean,
        currentIsSaved: Boolean,
        currentReadAt: Long,
        currentSavedAt: Long,
        delta: SyncItemStateDelta,
    ): ResolvedItemState {
        var newIsRead = currentIsRead
        var newIsSaved = currentIsSaved
        var newReadAt = currentReadAt
        var newSavedAt = currentSavedAt

        if (delta.readChangedAtEpochMs != null && delta.readChangedAtEpochMs >= newReadAt) {
            newIsRead = delta.isRead
            newReadAt = delta.readChangedAtEpochMs
        }
        if (delta.savedChangedAtEpochMs != null && delta.savedChangedAtEpochMs >= newSavedAt) {
            newIsSaved = delta.isSaved
            newSavedAt = delta.savedChangedAtEpochMs
        }

        return ResolvedItemState(newIsRead, newIsSaved, newReadAt, newSavedAt)
    }

    fun shouldApplyPlaybackProgress(
        currentGen: Long,
        currentCompleted: Boolean,
        currentLastPlayedAt: Long,
        delta: SyncPlaybackProgressDelta,
    ): Boolean {
        if (delta.progressGeneration < currentGen) return false
        if (delta.progressGeneration == currentGen) {
            if (currentCompleted && !delta.completed && delta.updatedAtEpochMs <= currentLastPlayedAt) {
                return false
            }
            if (delta.updatedAtEpochMs < currentLastPlayedAt) {
                return false
            }
        }
        return true
    }
}
