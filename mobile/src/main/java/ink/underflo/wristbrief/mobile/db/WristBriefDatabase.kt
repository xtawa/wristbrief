package ink.underflo.wristbrief.mobile.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

open class WristBriefDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS feed_sources (
                feed_id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                category TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                send_to_watch INTEGER NOT NULL DEFAULT 1,
                watch_keywords TEXT,
                etag TEXT,
                last_modified TEXT,
                last_attempt_epoch_ms INTEGER,
                last_success_epoch_ms INTEGER,
                last_error TEXT
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS feed_items (
                id TEXT PRIMARY KEY,
                feed_id TEXT NOT NULL,
                feed_title TEXT NOT NULL,
                title TEXT NOT NULL,
                link TEXT,
                description TEXT,
                published TEXT,
                audio_url TEXT,
                cached_at_epoch_ms INTEGER NOT NULL
            );
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_feed_items_feed_id ON feed_items(feed_id);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_feed_items_cached_at ON feed_items(cached_at_epoch_ms DESC);")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS item_states (
                item_id TEXT PRIMARY KEY,
                feed_id TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                is_saved INTEGER NOT NULL DEFAULT 0,
                read_at_epoch_ms INTEGER,
                saved_at_epoch_ms INTEGER,
                updated_at_epoch_ms INTEGER NOT NULL
            );
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_item_states_feed_id ON item_states(feed_id);")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playback_progress (
                item_id TEXT PRIMARY KEY,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                is_playing INTEGER NOT NULL DEFAULT 0,
                last_played_at_epoch_ms INTEGER NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0,
                playback_speed REAL NOT NULL DEFAULT 1.0,
                progress_generation INTEGER NOT NULL DEFAULT 1,
                playback_session_id TEXT
            );
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_sync_outbox (
                id TEXT PRIMARY KEY,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at_epoch_ms INTEGER NOT NULL,
                next_attempt_epoch_ms INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloud_sync_outbox_created ON cloud_sync_outbox(created_at_epoch_ms ASC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloud_sync_outbox_next_attempt ON cloud_sync_outbox(next_attempt_epoch_ms ASC);")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transcript_cache (
                content_code TEXT PRIMARY KEY,
                artifact_id TEXT NOT NULL,
                language TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                full_text TEXT NOT NULL,
                segments_json TEXT NOT NULL,
                cached_at_epoch_ms INTEGER NOT NULL
            );
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE playback_progress ADD COLUMN playback_speed REAL NOT NULL DEFAULT 1.0;")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE playback_progress ADD COLUMN progress_generation INTEGER NOT NULL DEFAULT 1;")
            db.execSQL("ALTER TABLE playback_progress ADD COLUMN playback_session_id TEXT;")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cloud_sync_outbox (
                    id TEXT PRIMARY KEY,
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    is_deleted INTEGER NOT NULL DEFAULT 0,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    created_at_epoch_ms INTEGER NOT NULL,
                    next_attempt_epoch_ms INTEGER NOT NULL DEFAULT 0
                );
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloud_sync_outbox_created ON cloud_sync_outbox(created_at_epoch_ms ASC);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloud_sync_outbox_next_attempt ON cloud_sync_outbox(next_attempt_epoch_ms ASC);")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS transcript_cache (
                    content_code TEXT PRIMARY KEY,
                    artifact_id TEXT NOT NULL,
                    language TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    full_text TEXT NOT NULL,
                    segments_json TEXT NOT NULL,
                    cached_at_epoch_ms INTEGER NOT NULL
                );
                """.trimIndent(),
            )
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE cloud_sync_outbox ADD COLUMN next_attempt_epoch_ms INTEGER NOT NULL DEFAULT 0;")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloud_sync_outbox_next_attempt ON cloud_sync_outbox(next_attempt_epoch_ms ASC);")
            } catch (_: Exception) {
                // Ignore if column already exists
            }
        }
    }

    companion object {
        const val DATABASE_NAME = "wristbrief.db"
        const val DATABASE_VERSION = 4
    }
}
