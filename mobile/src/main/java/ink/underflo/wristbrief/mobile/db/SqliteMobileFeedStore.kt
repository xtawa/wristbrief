package ink.underflo.wristbrief.mobile.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import ink.underflo.wristbrief.mobile.MobileFeedStore
import ink.underflo.wristbrief.mobile.MobileFeedSubscription
import ink.underflo.wristbrief.mobile.normalizeFeedCategory
import ink.underflo.wristbrief.mobile.normalizeWatchKeywords

class SqliteMobileFeedStore(
    private val dbHelper: WristBriefDatabaseHelper,
) : MobileFeedStore {

    override fun load(): List<MobileFeedSubscription> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT feed_id, url, title, category, enabled, send_to_watch, watch_keywords
            FROM feed_sources
            ORDER BY rowid ASC
            """.trimIndent(),
            null,
        )

        val result = mutableListOf<MobileFeedSubscription>()
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("feed_id")
            val urlIdx = it.getColumnIndexOrThrow("url")
            val titleIdx = it.getColumnIndexOrThrow("title")
            val catIdx = it.getColumnIndexOrThrow("category")
            val enabledIdx = it.getColumnIndexOrThrow("enabled")
            val sendToWatchIdx = it.getColumnIndexOrThrow("send_to_watch")
            val keywordsIdx = it.getColumnIndexOrThrow("watch_keywords")

            while (it.moveToNext()) {
                val keywordsRaw = if (it.isNull(keywordsIdx)) "" else it.getString(keywordsIdx)
                val keywords = if (keywordsRaw.isBlank()) emptyList() else keywordsRaw.split(",").map { kw -> kw.trim() }.filter { kw -> kw.isNotEmpty() }
                result.add(
                    MobileFeedSubscription(
                        id = it.getString(idIdx),
                        title = it.getString(titleIdx),
                        url = it.getString(urlIdx),
                        enabled = it.getInt(enabledIdx) == 1,
                        sendToWatch = it.getInt(sendToWatchIdx) == 1,
                        category = normalizeFeedCategory(if (it.isNull(catIdx)) null else it.getString(catIdx)),
                        watchKeywords = normalizeWatchKeywords(keywords),
                    ),
                )
            }
        }
        return result
    }

    override fun save(feeds: List<MobileFeedSubscription>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Remove feeds no longer present
            val currentIds = feeds.map { it.id }.toSet()
            if (currentIds.isEmpty()) {
                db.delete("feed_sources", null, null)
            } else {
                val placeholders = currentIds.joinToString(",") { "?" }
                db.delete("feed_sources", "feed_id NOT IN ($placeholders)", currentIds.toTypedArray())
            }

            for (feed in feeds) {
                val values = ContentValues().apply {
                    put("feed_id", feed.id)
                    put("url", feed.url)
                    put("title", feed.title)
                    put("category", normalizeFeedCategory(feed.category))
                    put("enabled", if (feed.enabled) 1 else 0)
                    put("send_to_watch", if (feed.sendToWatch) 1 else 0)
                    put("watch_keywords", feed.watchKeywords.joinToString(","))
                }
                db.insertWithOnConflict("feed_sources", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
