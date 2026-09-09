package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SyncFeed(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean,
    val watchKeywords: List<String> = emptyList(),
)
data class WearSyncPayload(
    val version: Int = CURRENT_VERSION,
    val subscriptions: List<SyncFeed>,
    val readItemIds: Set<String> = emptySet(),
    val savedItemIds: Set<String> = emptySet(),
) {
    companion object { const val CURRENT_VERSION = 1 }
}

object WearDataLayerContract {
    const val PATH = "/wristbrief/subscriptions/v1"
    const val PAYLOAD_KEY = "payload"
    const val MAX_STATE_IDS = 500
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: WearSyncPayload): String = buildJsonObject {
        put("version", payload.version)
        put("subscriptions", buildJsonArray {
            payload.subscriptions.forEach { feed -> add(buildJsonObject {
                put("id", feed.id)
                put("title", feed.title)
                put("url", feed.url)
                put("enabled", feed.enabled)
                val keywords = normalizeWatchKeywords(feed.watchKeywords)
                if (keywords.isNotEmpty()) put("watchKeywords", stringArray(keywords))
            }) }
        })
        put("readItemIds", stringArray(payload.readItemIds.take(MAX_STATE_IDS)))
        put("savedItemIds", stringArray(payload.savedItemIds.take(MAX_STATE_IDS)))
    }.toString()

    fun decode(raw: String): WearSyncPayload {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: error("Missing version")
        require(version == WearSyncPayload.CURRENT_VERSION) { "Unsupported sync payload version" }
        val feeds = root["subscriptions"]?.jsonArray?.map { element ->
            val item = element.jsonObject
            SyncFeed(
                id = item.requiredString("id"),
                title = item.requiredString("title"),
                url = item.requiredString("url"),
                enabled = item["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                watchKeywords = normalizeWatchKeywords(item.stringList("watchKeywords")),
            )
        } ?: emptyList()
        return WearSyncPayload(
            version = version,
            subscriptions = feeds,
            readItemIds = root.stringSet("readItemIds"),
            savedItemIds = root.stringSet("savedItemIds"),
        )
    }

    private fun stringArray(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun JsonObject.requiredString(key: String) = this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: error("Missing $key")
    private fun JsonObject.stringSet(key: String) = this[key]?.jsonArray?.mapTo(linkedSetOf()) { it.jsonPrimitive.content } ?: emptySet()
    private fun JsonObject.stringList(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
}
