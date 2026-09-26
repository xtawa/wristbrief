package ink.underflo.wristbrief.mobile.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.artifacts.TranscriptJob
import ink.underflo.wristbrief.mobile.artifacts.TranscriptJobStore
import ink.underflo.wristbrief.mobile.artifacts.TranscriptStatus

/**
 * Durable transcript job store backed by the `transcript_jobs` table (migration 5).
 *
 * Only the audio URL, the job identifiers and the request the server needs are kept.
 * The Bearer session token is never written here.
 */
class SqliteTranscriptJobStore(
    private val dbHelper: WristBriefDatabaseHelper,
) : TranscriptJobStore {

    override fun find(audioUrl: String): TranscriptJob? {
        val db = dbHelper.readableDatabase
        return db.query(TABLE_NAME, COLUMNS, "$COL_AUDIO_URL = ?", arrayOf(audioUrl), null, null, null)
            .use { if (it.moveToFirst()) it.toJob() else null }
    }

    override fun listInFlight(limit: Int): List<TranscriptJob> {
        val db = dbHelper.readableDatabase
        return db.query(
            TABLE_NAME,
            COLUMNS,
            "$COL_STATE = ?",
            arrayOf(STATE_IN_FLIGHT),
            null,
            null,
            "$COL_UPDATED_AT ASC",
            limit.toString(),
        ).use { cursor ->
            val jobs = mutableListOf<TranscriptJob>()
            while (cursor.moveToNext()) jobs.add(cursor.toJob())
            jobs
        }
    }

    override fun upsert(job: TranscriptJob) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COL_AUDIO_URL, job.audioUrl)
            put(COL_JOB_ID, job.jobId)
            put(COL_CONTENT_CODE, job.contentCode.orEmpty())
            put(COL_STATE, STATE_IN_FLIGHT)
            put(COL_SOURCE, job.source)
            put(COL_ARTIFACT_ID, job.artifactId)
            put(COL_TITLE, job.title)
            put(COL_FEED_URL, job.feedUrl)
            put(COL_GUID, job.guid)
            put(COL_DURATION_MS, job.durationMs)
            put(COL_ERROR_CODE, job.errorCode)
            put(COL_RETRYABLE, if (job.retryable) 1 else 0)
            put(COL_POLL_AFTER_MS, job.pollAfterMs)
            put(COL_CREATED_AT, job.createdAtEpochMs)
            put(COL_UPDATED_AT, job.updatedAtEpochMs)
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        enforceRowCap(db)
    }

    /**
     * Bounds the table so a job that is never settled cannot grow it without limit: only
     * the most recently updated rows are retained.
     */
    private fun enforceRowCap(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                """
                DELETE FROM $TABLE_NAME WHERE $COL_AUDIO_URL NOT IN (
                    SELECT $COL_AUDIO_URL FROM $TABLE_NAME
                    ORDER BY $COL_UPDATED_AT DESC
                    LIMIT $MAX_RETAINED_ROWS
                );
                """.trimIndent(),
            )
        }
    }

    override fun markCompleted(
        audioUrl: String,
        contentCode: String,
        source: String,
        artifactId: String,
        nowEpochMs: Long,
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COL_STATE, STATE_COMPLETED)
            put(COL_CONTENT_CODE, contentCode)
            put(COL_SOURCE, source)
            put(COL_ARTIFACT_ID, artifactId)
            // putNull, not put(..., null): a bare null is ambiguous across ContentValues overloads.
            putNull(COL_ERROR_CODE)
            put(COL_RETRYABLE, 0)
            put(COL_UPDATED_AT, nowEpochMs)
        }
        db.update(TABLE_NAME, values, "$COL_AUDIO_URL = ?", arrayOf(audioUrl))
    }

    override fun markFailed(
        audioUrl: String,
        errorCode: String,
        message: String,
        retryable: Boolean,
        nowEpochMs: Long,
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COL_STATE, STATE_IN_FLIGHT)
            // The DB stores a single error column for both code and message; the code is
            // what drives the retry decision, so it wins and the message is kept as a
            // readable suffix when it adds information.
            put(COL_ERROR_CODE, if (message.isBlank() || message == errorCode) errorCode else "$errorCode: $message")
            put(COL_RETRYABLE, if (retryable) 1 else 0)
            put(COL_UPDATED_AT, nowEpochMs)
        }
        db.update(TABLE_NAME, values, "$COL_AUDIO_URL = ?", arrayOf(audioUrl))
    }

    override fun clear(audioUrl: String) {
        dbHelper.writableDatabase.delete(TABLE_NAME, "$COL_AUDIO_URL = ?", arrayOf(audioUrl))
    }

    override fun pruneOlderThan(thresholdEpochMs: Long) {
        dbHelper.writableDatabase.delete(TABLE_NAME, "$COL_UPDATED_AT < ?", arrayOf(thresholdEpochMs.toString()))
    }

    private fun Cursor.toJob(): TranscriptJob {
        val errorRaw = getStringOrNull(COL_ERROR_CODE)
        return TranscriptJob(
            audioUrl = getString(getColumnIndexOrThrow(COL_AUDIO_URL)),
            jobId = getString(getColumnIndexOrThrow(COL_JOB_ID)),
            contentCode = getStringOrNull(COL_CONTENT_CODE)?.takeIf { it.isNotEmpty() },
            status = if (getString(getColumnIndexOrThrow(COL_STATE)) == STATE_COMPLETED) {
                TranscriptStatus.COMPLETED
            } else {
                TranscriptStatus.UNKNOWN
            },
            title = getStringOrNull(COL_TITLE),
            feedUrl = getStringOrNull(COL_FEED_URL),
            guid = getStringOrNull(COL_GUID),
            durationMs = getLongOrNull(COL_DURATION_MS),
            source = getStringOrNull(COL_SOURCE),
            artifactId = getStringOrNull(COL_ARTIFACT_ID),
            errorCode = errorRaw?.substringBefore(": ")?.takeIf { it.isNotEmpty() },
            retryable = getInt(getColumnIndexOrThrow(COL_RETRYABLE)) == 1,
            pollAfterMs = getLongOrNull(COL_POLL_AFTER_MS),
            createdAtEpochMs = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
            updatedAtEpochMs = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
        )
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        if (index == -1 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        if (index == -1 || isNull(index)) return null
        return getLong(index)
    }

    companion object {
        const val TABLE_NAME = "transcript_jobs"
        const val COL_AUDIO_URL = "audio_url"
        const val COL_JOB_ID = "job_id"
        const val COL_CONTENT_CODE = "content_code"
        const val COL_STATE = "state"
        const val COL_SOURCE = "source"
        const val COL_ARTIFACT_ID = "artifact_id"
        const val COL_TITLE = "requested_title"
        const val COL_FEED_URL = "requested_feed_url"
        const val COL_GUID = "requested_guid"
        const val COL_DURATION_MS = "requested_duration_ms"
        const val COL_ERROR_CODE = "error_code"
        const val COL_RETRYABLE = "retryable"
        const val COL_POLL_AFTER_MS = "poll_after_ms"
        const val COL_CREATED_AT = "created_at_epoch_ms"
        const val COL_UPDATED_AT = "updated_at_epoch_ms"

        const val STATE_IN_FLIGHT = "in_flight"
        const val STATE_COMPLETED = "completed"

        /** Hard cap on retained job rows; older rows are dropped on the next upsert. */
        const val MAX_RETAINED_ROWS = 25

        private val COLUMNS = arrayOf(
            COL_AUDIO_URL,
            COL_JOB_ID,
            COL_CONTENT_CODE,
            COL_STATE,
            COL_SOURCE,
            COL_ARTIFACT_ID,
            COL_TITLE,
            COL_FEED_URL,
            COL_GUID,
            COL_DURATION_MS,
            COL_ERROR_CODE,
            COL_RETRYABLE,
            COL_POLL_AFTER_MS,
            COL_CREATED_AT,
            COL_UPDATED_AT,
        )
    }
}
