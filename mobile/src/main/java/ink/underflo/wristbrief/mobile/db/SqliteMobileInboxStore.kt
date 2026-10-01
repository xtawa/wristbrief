package ink.underflo.wristbrief.mobile.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.MobileFeedItem
import ink.underflo.wristbrief.mobile.MobileInboxStore

class SqliteMobileInboxStore(
    private val dbHelper: WristBriefDatabaseHelper,
    private val maxRetentionItems: Int = 500,
) : MobileInboxStore {

    override fun load(): List<MobileFeedItem> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT id, feed_id, feed_title, title, link, description, published, audio_url, cached_at_epoch_ms
            FROM feed_items
            ORDER BY cached_at_epoch_ms DESC
            """.trimIndent(),
            null,
        )

        val result = mutableListOf<MobileFeedItem>()
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("id")
            val feedIdIdx = it.getColumnIndexOrThrow("feed_id")
            val feedTitleIdx = it.getColumnIndexOrThrow("feed_title")
            val titleIdx = it.getColumnIndexOrThrow("title")
            val linkIdx = it.getColumnIndexOrThrow("link")
            val descIdx = it.getColumnIndexOrThrow("description")
            val pubIdx = it.getColumnIndexOrThrow("published")
            val audioIdx = it.getColumnIndexOrThrow("audio_url")
            val cachedIdx = it.getColumnIndexOrThrow("cached_at_epoch_ms")

            while (it.moveToNext()) {
                result.add(
                    MobileFeedItem(
                        id = it.getString(idIdx),
                        feedId = it.getString(feedIdIdx),
                        feedTitle = it.getString(feedTitleIdx),
                        title = it.getString(titleIdx),
                        link = if (it.isNull(linkIdx)) null else it.getString(linkIdx),
                        description = if (it.isNull(descIdx)) null else it.getString(descIdx),
                        published = if (it.isNull(pubIdx)) null else it.getString(pubIdx),
                        audioUrl = if (it.isNull(audioIdx)) null else it.getString(audioIdx),
                        cachedAtEpochMs = it.getLong(cachedIdx),
                    ),
                )
            }
        }
        return result
    }

    override fun deleteItemsForFeed(feedId: String) {
        val db = dbHelper.writableDatabase
        db.delete("feed_items", "feed_id = ?", arrayOf(feedId))
    }

    override fun save(items: List<MobileFeedItem>) = saveRetaining(items, emptySet())

    override fun saveRetaining(items: List<MobileFeedItem>, protectedIds: Set<String>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Callers such as migration may supply oldest-first or mixed batches.
            // Sort before pruning so the retention cap never discards newer items.
            val sorted = items.distinctBy { it.id }.sortedByDescending { it.cachedAtEpochMs }
            val targetItems = sorted.filter { it.id in protectedIds } +
                sorted.filterNot { it.id in protectedIds }.take(maxRetentionItems)
            // Snapshot replacement in one transaction also avoids SQLite's bind
            // parameter limit when the saved collection grows beyond 999 items.
            db.delete("feed_items", null, null)

            for (item in targetItems) {
                val values = ContentValues().apply {
                    put("id", item.id)
                    put("feed_id", item.feedId)
                    put("feed_title", item.feedTitle)
                    put("title", item.title)
                    put("link", item.link)
                    put("description", item.description)
                    put("published", item.published)
                    put("audio_url", item.audioUrl)
                    put("cached_at_epoch_ms", item.cachedAtEpochMs)
                }
                db.insertWithOnConflict("feed_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
