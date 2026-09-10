package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class SyncOrigin { PHONE, WEAR }

data class VersionedFlag(val value: Boolean, val changedAtEpochMs: Long, val origin: SyncOrigin)

data class ItemStateClock(
    val itemId: String,
    val read: VersionedFlag? = null,
    val saved: VersionedFlag? = null,
)

fun mergeItemState(local: ItemStateClock?, remote: ItemStateClock): ItemStateClock {
    require(remote.itemId.isNotBlank()) { "itemId must not be blank" }
    require(local == null || local.itemId == remote.itemId) { "Cannot merge different items" }
    return ItemStateClock(remote.itemId, chooseFlag(local?.read, remote.read), chooseFlag(local?.saved, remote.saved))
}

fun localMutation(
    local: ItemStateClock?,
    itemId: String,
    read: Boolean?,
    saved: Boolean?,
    nowEpochMs: Long,
    origin: SyncOrigin,
): ItemStateClock {
    require(itemId.isNotBlank()) { "itemId must not be blank" }
    require(read != null || saved != null) { "At least one field must change" }
    require(nowEpochMs >= 0) { "Timestamp must be non-negative" }
    val base = local ?: ItemStateClock(itemId)
    require(base.itemId == itemId) { "Cannot mutate a different item" }
    return base.copy(
        read = read?.let { VersionedFlag(it, monotonicTimestamp(base.read, nowEpochMs), origin) } ?: base.read,
        saved = saved?.let { VersionedFlag(it, monotonicTimestamp(base.saved, nowEpochMs), origin) } ?: base.saved,
    )
}

fun ownedStates(states: Collection<ItemStateClock>, origin: SyncOrigin): List<ItemStateClock> =
    states.mapNotNull { state ->
        val owned = state.copy(
            read = state.read?.takeIf { it.origin == origin },
            saved = state.saved?.takeIf { it.origin == origin },
        )
        owned.takeIf { it.read != null || it.saved != null }
    }

private fun monotonicTimestamp(existing: VersionedFlag?, nowEpochMs: Long): Long = maxOf(nowEpochMs, (existing?.changedAtEpochMs ?: -1L) + 1L)

private fun chooseFlag(local: VersionedFlag?, remote: VersionedFlag?): VersionedFlag? = when {
    local == null -> remote
    remote == null -> local
    remote.changedAtEpochMs > local.changedAtEpochMs -> remote
    remote.changedAtEpochMs < local.changedAtEpochMs -> local
    remote.origin.ordinal > local.origin.ordinal -> remote
    else -> local
}

object ItemStateWireContract {
    const val PHONE_PATH = "/wristbrief/item-state/v1/phone"
    const val WEAR_PATH = "/wristbrief/item-state/v1/wear"
    const val PAYLOAD_KEY = "payload"
    const val VERSION = 1
    const val MAX_ITEMS = 500
    private val json = Json { ignoreUnknownKeys = true }

    fun pathFor(origin: SyncOrigin): String = if (origin == SyncOrigin.PHONE) PHONE_PATH else WEAR_PATH
    fun remotePathFor(localOrigin: SyncOrigin): String = if (localOrigin == SyncOrigin.PHONE) WEAR_PATH else PHONE_PATH

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
            val itemId = item["itemId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: error("Missing itemId")
            ItemStateClock(itemId, item["read"]?.jsonObject?.decodeFlag(), item["saved"]?.jsonObject?.decodeFlag())
        }
    }

    private fun encodeFlag(flag: VersionedFlag): JsonObject = buildJsonObject {
        put("value", flag.value); put("changedAtEpochMs", flag.changedAtEpochMs); put("origin", flag.origin.name)
    }

    private fun JsonObject.decodeFlag(): VersionedFlag {
        val value = this["value"]?.jsonPrimitive?.booleanOrNull ?: error("Missing flag value")
        val changedAt = this["changedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: error("Missing flag timestamp")
        require(changedAt >= 0) { "Invalid flag timestamp" }
        val origin = this["origin"]?.jsonPrimitive?.content?.let { SyncOrigin.valueOf(it) } ?: error("Missing flag origin")
        return VersionedFlag(value, changedAt, origin)
    }
}
