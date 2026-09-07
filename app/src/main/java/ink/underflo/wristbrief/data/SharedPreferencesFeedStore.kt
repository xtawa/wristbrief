package ink.underflo.wristbrief.data

import android.content.Context

class SharedPreferencesFeedStore(context: Context) : FeedStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun subscriptions(): List<FeedSubscription> =
        FeedStoreCodec.decodeSubscriptions(preferences.getString(KEY_SUBSCRIPTIONS, null))

    @Synchronized
    override fun saveSubscriptions(subscriptions: List<FeedSubscription>) {
        preferences.edit()
            .putString(KEY_SUBSCRIPTIONS, FeedStoreCodec.encodeSubscriptions(subscriptions))
            .apply()
    }

    @Synchronized
    override fun cachedItems(): List<CachedFeedItem> =
        FeedStoreCodec.decodeItems(preferences.getString(KEY_ITEMS, null))

    @Synchronized
    override fun saveCachedItems(items: List<CachedFeedItem>) {
        preferences.edit()
            .putString(KEY_ITEMS, FeedStoreCodec.encodeItems(items))
            .apply()
    }

    @Synchronized
    override fun readItemIds(): Set<String> =
        FeedStoreCodec.decodeItemIds(preferences.getString(KEY_READ_ITEM_IDS, null))

    @Synchronized
    override fun saveReadItemIds(itemIds: Set<String>) {
        preferences.edit()
            .putString(KEY_READ_ITEM_IDS, FeedStoreCodec.encodeItemIds(itemIds))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "wristbrief_feed_store"
        const val KEY_SUBSCRIPTIONS = "subscriptions_v1"
        const val KEY_ITEMS = "items_v1"
        const val KEY_READ_ITEM_IDS = "read_item_ids_v1"
    }
}
