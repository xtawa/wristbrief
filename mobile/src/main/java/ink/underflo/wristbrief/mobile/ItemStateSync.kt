package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Device identifier used only as a deterministic conflict tie-breaker. */
enum class SyncOrigin { PHONE, WEAR }

data class VersionedFlag(
    val value: Boolean,
    val changedAtEpochMs: Long,
    val origin: SyncOrigin,
)

data class ItemStateClock(
    val itemId: String,
    val read: VersionedFlag? = null,
    val saved: VersionedFlag? = null,
)

/**
 * Merge read and saved independently. Newer timestamps win; equal timestamps use
 * a stable origin ordering so both devices converge regardless of delivery order.
 */
fun mergeItemState(local: ItemStateClock?, remote: ItemStateClock): ItemStateClock {
    require(remote.itemId.isNotBlank()) { "itemId must not be blank" }
    require(local == null || local.itemId == remote.itemId) { "Cannot merge different items" }
    return ItemStateClock(
        itemId = remote.itemId,
        read = chooseFlag(local?.read, remote.read),
        saved = chooseFlag(local?.saved, remote.saved),
    )
}

private fun chooseFlag(local: VersionedFlag?, remote: VersionedFlag?): VersionedFlag? = when {
    local == null -> remote
    remote == null -> local
    remote.changedAtEpochMs > local.changedAtEpochMs -> remote
    remote.changedAtEpochMs < local.changedAtEpochMs -> local
    remote.origin.ordinal > local.origin.ordinal -> remote
    else -> local
}

/** Versioned, bounded Data Layer wire format for read/saved state. */
object ItemStateWireContract {
    const val PATH = "/wristbrief/item-state/v1"
    const val PAYLOAD_KEY = "payload"
    const val VERSION = 1
    const val MAX_ITEMS = 500
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(states: Collection<ItemStateClock>): String = buildJsonObject {
        put("version", VERSION)
        put("items", buildJsonArray {
            states.asSequence().filter { it.itemId.isNotBlank() }.take(MAX_ITEMS).forEach { state ->
                add(buildJsonObject {
                    put("itemId", state.itemId)
                    state.read?.let { put("read", encodeFlag(it)) }
                    state.saved?.let { put("saved", encodeFlag(it)) }
                })
            }
        })
    }.toString()

    fun decode(raw: String): List<ItemStateClock> {
        val root = json.parseToJsonElement(raw).jsonObject
        require(root["version"]?.jsonPrimitive?.longOrNull == VERSION.toLong()) { "Unsupported item-state version" }
        val items = root["items"]?.jsonArray ?: error("Missing items")
        require(items.size <= MAX_ITEMS) { "Too many item-state records" }
        return items.map { element ->
            val item = element.jsonObject
            val itemId = item["itemId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("Missing itemId")
            ItemStateClock(
                itemId = itemId,
                read = item["read"]?.jsonObject?.decodeFlag(),
                saved = item["saved"]?.jsonObject?.decodeFlag(),
            )
        }
    }

    private fun encodeFlag(flag: VersionedFlag): JsonObject = buildJsonObject {
        put("value", flag.value)
        put("changedAtEpochMs", flag.changedAtEpochMs)
        put("origin", flag.origin.name)
    }

    private fun JsonObject.decodeFlag(): VersionedFlag {
        val value = this["value"]?.jsonPrimitive?.booleanOrNull ?: error("Missing flag value")
        val changedAt = this["changedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: error("Missing flag timestamp")
        require(changedAt >= 0) { "Invalid flag timestamp" }
        val origin = this["origin"]?.jsonPrimitive?.content?.let { SyncOrigin.valueOf(it) }
            ?: error("Missing flag origin")
        return VersionedFlag(value, changedAt, origin)
    }
}
