package ink.underflo.wristbrief.mobile.artifacts

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

interface TranscriptCache {
    fun get(contentCode: String): TranscriptPayload?
    fun put(contentCode: String, artifactId: String, payload: TranscriptPayload)
    fun remove(contentCode: String)
}

class TranscriptCacheStore(
    private val dbHelper: WristBriefDatabaseHelper,
) : TranscriptCache {
    override fun get(contentCode: String): TranscriptPayload? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            COLUMNS,
            "$COL_CONTENT_CODE = ?",
            arrayOf(contentCode),
            null,
            null,
            null,
        )
        return cursor.use {
            if (it.moveToFirst()) it.toTranscriptPayload() else null
        }
    }

    override fun put(contentCode: String, artifactId: String, payload: TranscriptPayload) {
        val db = dbHelper.writableDatabase
        val segmentsJson = buildJsonArray {
            payload.segments.forEach { add(it.toJsonObject()) }
        }.toString()

        val values = ContentValues().apply {
            put(COL_CONTENT_CODE, contentCode)
            put(COL_ARTIFACT_ID, artifactId)
            put(COL_LANGUAGE, payload.language)
            put(COL_DURATION_MS, payload.durationMs)
            put(COL_FULL_TEXT, payload.fullText)
            put(COL_SEGMENTS_JSON, segmentsJson)
            put(COL_CACHED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun remove(contentCode: String) {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, "$COL_CONTENT_CODE = ?", arrayOf(contentCode))
    }

    private fun Cursor.toTranscriptPayload(): TranscriptPayload {
        val code = getString(getColumnIndexOrThrow(COL_CONTENT_CODE))
        val language = getString(getColumnIndexOrThrow(COL_LANGUAGE))
        val durationMs = getLong(getColumnIndexOrThrow(COL_DURATION_MS))
        val fullText = getString(getColumnIndexOrThrow(COL_FULL_TEXT))
        val segmentsJson = getString(getColumnIndexOrThrow(COL_SEGMENTS_JSON))

        val segmentsArr = Json.parseToJsonElement(segmentsJson).jsonArray
        val list = mutableListOf<TranscriptSegment>()
        segmentsArr.forEach {
            list.add(TranscriptSegment.fromJsonObject(it.jsonObject))
        }

        return TranscriptPayload(
            contentCode = code,
            language = language,
            durationMs = durationMs,
            fullText = fullText,
            segments = list,
        )
    }

    companion object {
        const val TABLE_NAME = "transcript_cache"
        const val COL_CONTENT_CODE = "content_code"
        const val COL_ARTIFACT_ID = "artifact_id"
        const val COL_LANGUAGE = "language"
        const val COL_DURATION_MS = "duration_ms"
        const val COL_FULL_TEXT = "full_text"
        const val COL_SEGMENTS_JSON = "segments_json"
        const val COL_CACHED_AT = "cached_at_epoch_ms"

        val COLUMNS = arrayOf(
            COL_CONTENT_CODE,
            COL_ARTIFACT_ID,
            COL_LANGUAGE,
            COL_DURATION_MS,
            COL_FULL_TEXT,
            COL_SEGMENTS_JSON,
            COL_CACHED_AT,
        )
    }
}
