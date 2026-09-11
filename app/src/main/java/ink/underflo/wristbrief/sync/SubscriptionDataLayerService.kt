package ink.underflo.wristbrief.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import ink.underflo.wristbrief.complication.requestUnreadComplicationUpdate
import ink.underflo.wristbrief.data.FeedSubscription
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore
import ink.underflo.wristbrief.data.isValidSubscriptionUrl
import ink.underflo.wristbrief.data.normalizeIdentityUrl
import ink.underflo.wristbrief.data.normalizeWatchKeywords
import ink.underflo.wristbrief.tile.requestLatestUnreadTileUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun subscriptionSnapshotChanged(
    current: List<FeedSubscription>,
    incoming: List<FeedSubscription>,
): Boolean = current != incoming

class SubscriptionDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != PATH) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(PAYLOAD_KEY) ?: return@forEach
            val feeds = decodeSubscriptions(raw) ?: return@forEach
            val store = SharedPreferencesFeedStore(this)
            if (!subscriptionSnapshotChanged(store.subscriptions(), feeds)) return@forEach
            store.saveSubscriptions(feeds)
            requestLatestUnreadTileUpdate(this)
            requestUnreadComplicationUpdate(this)
        }
    }

    companion object {
        const val PATH = "/wristbrief/subscriptions/v1"
        const val PAYLOAD_KEY = "payload"
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeSubscriptions(raw: String): List<FeedSubscription>? = runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            require(root["version"]?.jsonPrimitive?.intOrNull == 1)
            val subscriptions = root["subscriptions"]?.jsonArray ?: error("Missing subscriptions")
            val seenIds = hashSetOf<String>()
            val seenUrls = hashSetOf<String>()
            subscriptions.map { element ->
                val item = element.jsonObject
                val id = item["id"]!!.jsonPrimitive.content.trim()
                val title = item["title"]!!.jsonPrimitive.content.trim()
                val rawUrl = item["url"]!!.jsonPrimitive.content
                require(id.isNotBlank() && title.isNotBlank() && isValidSubscriptionUrl(rawUrl))
                val url = normalizeIdentityUrl(rawUrl) ?: error("Invalid subscription URL")
                require(seenIds.add(id))
                require(seenUrls.add(url))
                val keywords = item["watchKeywords"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                FeedSubscription(
                    id = id,
                    title = title,
                    url = url,
                    enabled = item["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    watchKeywords = normalizeWatchKeywords(keywords),
                )
            }
        }.getOrNull()
    }
}
