package ink.underflo.wristbrief.mobile.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.mobile.media.PodcastProgressStore
import ink.underflo.wristbrief.mobile.media.SUPPORTED_PLAYBACK_SPEEDS

/**
 * SQLite-backed persistent store for podcast playback progress.
 */
class SqlitePodcastProgressStore(
    private val dbHelper: WristBriefDatabaseHelper,
) : PodcastProgressStore {

    override fun get(episodeId: String): PodcastEpisodeProgress? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            COLUMNS,
            "$COL_ITEM_ID = ?",
            arrayOf(episodeId),
            null,
            null,
            null,
        )
        return cursor.use {
            if (it.moveToFirst()) it.toPodcastEpisodeProgress() else null
        }
    }

    override fun all(): List<PodcastEpisodeProgress> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            COLUMNS,
            null,
            null,
            null,
            null,
            "$COL_LAST_PLAYED_AT DESC",
        )
        return cursor.use {
            val list = mutableListOf<PodcastEpisodeProgress>()
            while (it.moveToNext()) {
                list.add(it.toPodcastEpisodeProgress())
            }
            list
        }
    }

    override fun save(progress: PodcastEpisodeProgress) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COL_ITEM_ID, progress.episodeId)
            put(COL_POSITION_MS, progress.positionMs)
            put(COL_DURATION_MS, progress.durationMs)
            put(COL_IS_PLAYING, if (progress.isPlaying) 1 else 0)
            put(COL_LAST_PLAYED_AT, progress.lastPlayedAtEpochMs)
            put(COL_COMPLETED, if (progress.completed) 1 else 0)
            put(COL_PLAYBACK_SPEED, progress.playbackSpeed)
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getLatestActive(): PodcastEpisodeProgress? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            COLUMNS,
            "$COL_COMPLETED = 0 AND $COL_POSITION_MS > 0",
            null,
            null,
            null,
            "$COL_LAST_PLAYED_AT DESC",
            "1",
        )
        return cursor.use {
            if (it.moveToFirst()) it.toPodcastEpisodeProgress() else null
        }
    }

    override fun delete(episodeId: String) {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, "$COL_ITEM_ID = ?", arrayOf(episodeId))
    }

    private fun Cursor.toPodcastEpisodeProgress(): PodcastEpisodeProgress {
        val episodeId = getString(getColumnIndexOrThrow(COL_ITEM_ID))
        val positionMs = getLong(getColumnIndexOrThrow(COL_POSITION_MS))
        val durationMs = getLong(getColumnIndexOrThrow(COL_DURATION_MS))
        val isPlaying = getInt(getColumnIndexOrThrow(COL_IS_PLAYING)) == 1
        val lastPlayedAt = getLong(getColumnIndexOrThrow(COL_LAST_PLAYED_AT))
        val completed = getInt(getColumnIndexOrThrow(COL_COMPLETED)) == 1
        val speedIndex = getColumnIndex(COL_PLAYBACK_SPEED)
        val playbackSpeed = if (speedIndex != -1 && !isNull(speedIndex)) getFloat(speedIndex) else 1f

        return PodcastEpisodeProgress(
            episodeId = episodeId,
            positionMs = positionMs,
            playbackSpeed = if (playbackSpeed in SUPPORTED_PLAYBACK_SPEEDS) playbackSpeed else 1f,
            durationMs = durationMs,
            isPlaying = isPlaying,
            lastPlayedAtEpochMs = lastPlayedAt,
            completed = completed,
        )
    }

    companion object {
        private const val TABLE_NAME = "playback_progress"
        private const val COL_ITEM_ID = "item_id"
        private const val COL_POSITION_MS = "position_ms"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_IS_PLAYING = "is_playing"
        private const val COL_LAST_PLAYED_AT = "last_played_at_epoch_ms"
        private const val COL_COMPLETED = "completed"
        private const val COL_PLAYBACK_SPEED = "playback_speed"

        private val COLUMNS = arrayOf(
            COL_ITEM_ID,
            COL_POSITION_MS,
            COL_DURATION_MS,
            COL_IS_PLAYING,
            COL_LAST_PLAYED_AT,
            COL_COMPLETED,
            COL_PLAYBACK_SPEED,
        )
    }
}
