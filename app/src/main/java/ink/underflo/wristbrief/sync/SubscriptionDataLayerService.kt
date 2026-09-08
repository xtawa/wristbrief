package ink.underflo.wristbrief.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import ink.underflo.wristbrief.complication.requestUnreadComplicationUpdate
import ink.underflo.wristbrief.data.FeedSubscription
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore
import ink.underflo.wristbrief.tile.requestLatestUnreadTileUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != PATH) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(PAYLOAD_KEY) ?: return@forEach
            val feeds = decodeSubscriptions(raw) ?: return@forEach
            SharedPreferencesFeedStore(this).saveSubscriptions(feeds)
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
            root["subscriptions"]?.jsonArray?.map { element ->
                val item = element.jsonObject
                val id = item["id"]!!.jsonPrimitive.content
                val title = item["title"]!!.jsonPrimitive.content
                val url = item["url"]!!.jsonPrimitive.content
                require(id.isNotBlank() && title.isNotBlank() && url.startsWith("https://"))
                FeedSubscription(id, title, url, item["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)
            } ?: emptyList()
        }.getOrNull()
    }
}
