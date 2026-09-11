package ink.underflo.wristbrief.mobile.db

import android.content.Context
import ink.underflo.wristbrief.mobile.SharedPreferencesMobileFeedStore
import ink.underflo.wristbrief.mobile.SharedPreferencesMobileInboxStore
import ink.underflo.wristbrief.mobile.media.SharedPreferencesPodcastProgressStore

object LegacyDataMigration {

    private const val PREFS_NAME = "wristbrief_migration"
    private const val KEY_MIGRATED = "v1_sqlite_migrated"

    fun performIfNeeded(
        context: Context,
        sqliteFeedStore: SqliteMobileFeedStore,
        sqliteInboxStore: SqliteMobileInboxStore,
        sqliteProgressStore: SqlitePodcastProgressStore? = null,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        runCatching {
            val legacyFeedStore = SharedPreferencesMobileFeedStore(context)
            val legacyInboxStore = SharedPreferencesMobileInboxStore(context)

            val existingFeedsInDb = sqliteFeedStore.load()
            if (existingFeedsInDb.isEmpty()) {
                val legacyFeeds = legacyFeedStore.load()
                if (legacyFeeds.isNotEmpty()) {
                    sqliteFeedStore.save(legacyFeeds)
                }
            }

            val existingItemsInDb = sqliteInboxStore.load()
            if (existingItemsInDb.isEmpty()) {
                val legacyItems = legacyInboxStore.load()
                if (legacyItems.isNotEmpty()) {
                    sqliteInboxStore.save(legacyItems)
                }
            }

            if (sqliteProgressStore != null) {
                val legacyProgressStore = SharedPreferencesPodcastProgressStore(context)
                val legacyProgress = legacyProgressStore.all()
                if (legacyProgress.isNotEmpty()) {
                    legacyProgress.forEach { item ->
                        if (sqliteProgressStore.get(item.episodeId) == null) {
                            sqliteProgressStore.save(item)
                        }
                    }
                }
            }

            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }
}
