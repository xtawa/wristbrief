package ink.underflo.wristbrief.mobile.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import kotlin.random.Random

interface CloudSyncOutbox {
    fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        payloadJson: String,
        updatedAtEpochMs: Long,
        isDeleted: Boolean = false,
    )
    fun getPending(limit: Int = 50, nowEpochMs: Long = System.currentTimeMillis()): List<OutboxMutation>
    fun remove(ids: List<String>)
    fun count(): Int
    fun incrementRetry(ids: List<String>, nowEpochMs: Long = System.currentTimeMillis()) {}
    fun clearAll() {}
}

class CloudSyncOutboxStore(
    private val dbHelper: WristBriefDatabaseHelper,
) : CloudSyncOutbox {
    override fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        payloadJson: String,
        updatedAtEpochMs: Long,
        isDeleted: Boolean,
    ) {
        val db = dbHelper.writableDatabase
        val id = "out_${System.currentTimeMillis()}_${entityType.name}_$entityId"
        val values = ContentValues().apply {
            put(COL_ID, id)
            put(COL_ENTITY_TYPE, entityType.name)
            put(COL_ENTITY_ID, entityId)
            put(COL_PAYLOAD_JSON, payloadJson)
            put(COL_UPDATED_AT, updatedAtEpochMs)
            put(COL_IS_DELETED, if (isDeleted) 1 else 0)
            put(COL_RETRY_COUNT, 0)
            put(COL_CREATED_AT, System.currentTimeMillis())
            put(COL_NEXT_ATTEMPT, 0L)
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getPending(limit: Int, nowEpochMs: Long): List<OutboxMutation> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            COLUMNS,
            "$COL_NEXT_ATTEMPT <= ?",
            arrayOf(nowEpochMs.toString()),
            null,
            null,
            "$COL_CREATED_AT ASC",
            limit.toString(),
        )
        return cursor.use {
            val list = mutableListOf<OutboxMutation>()
            while (it.moveToNext()) {
                list.add(it.toOutboxMutation())
            }
            list
        }
    }

    override fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = dbHelper.writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.delete(TABLE_NAME, "$COL_ID IN ($placeholders)", ids.toTypedArray())
    }

    override fun incrementRetry(ids: List<String>, nowEpochMs: Long) {
        if (ids.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val cursor = db.query(
                    TABLE_NAME,
                    arrayOf(COL_RETRY_COUNT),
                    "$COL_ID = ?",
                    arrayOf(id),
                    null,
                    null,
                    null,
                )
                val currentRetry = cursor.use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                val newRetry = currentRetry + 1
                // Exponential backoff with jitter: 1s, 2s, 4s, 8s, up to 5 min + 0-500ms jitter
                val shift = minOf(newRetry, 10)
                val baseDelayMs = minOf(300_000L, 1000L * (1 shl shift))
                val jitterMs = Random.nextLong(500)
                val nextAttempt = nowEpochMs + baseDelayMs + jitterMs

                val values = ContentValues().apply {
                    put(COL_RETRY_COUNT, newRetry)
                    put(COL_NEXT_ATTEMPT, nextAttempt)
                }
                db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clearAll() {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, null, null)
    }

    override fun count(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun Cursor.toOutboxMutation(): OutboxMutation {
        val nextAttemptIdx = getColumnIndex(COL_NEXT_ATTEMPT)
        val nextAttempt = if (nextAttemptIdx >= 0 && !isNull(nextAttemptIdx)) getLong(nextAttemptIdx) else 0L

        return OutboxMutation(
            id = getString(getColumnIndexOrThrow(COL_ID)),
            entityType = SyncEntityType.valueOf(getString(getColumnIndexOrThrow(COL_ENTITY_TYPE))),
            entityId = getString(getColumnIndexOrThrow(COL_ENTITY_ID)),
            payloadJson = getString(getColumnIndexOrThrow(COL_PAYLOAD_JSON)),
            updatedAtEpochMs = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
            isDeleted = getInt(getColumnIndexOrThrow(COL_IS_DELETED)) == 1,
            retryCount = getInt(getColumnIndexOrThrow(COL_RETRY_COUNT)),
            createdAtEpochMs = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
            nextAttemptEpochMs = nextAttempt,
        )
    }

    companion object {
        const val TABLE_NAME = "cloud_sync_outbox"
        const val COL_ID = "id"
        const val COL_ENTITY_TYPE = "entity_type"
        const val COL_ENTITY_ID = "entity_id"
        const val COL_PAYLOAD_JSON = "payload_json"
        const val COL_UPDATED_AT = "updated_at_epoch_ms"
        const val COL_IS_DELETED = "is_deleted"
        const val COL_RETRY_COUNT = "retry_count"
        const val COL_CREATED_AT = "created_at_epoch_ms"
        const val COL_NEXT_ATTEMPT = "next_attempt_epoch_ms"

        val COLUMNS = arrayOf(
            COL_ID,
            COL_ENTITY_TYPE,
            COL_ENTITY_ID,
            COL_PAYLOAD_JSON,
            COL_UPDATED_AT,
            COL_IS_DELETED,
            COL_RETRY_COUNT,
            COL_CREATED_AT,
            COL_NEXT_ATTEMPT,
        )
    }
}
