package ink.underflo.wristbrief.sync

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ink.underflo.wristbrief.complication.requestUnreadComplicationUpdate
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore
import ink.underflo.wristbrief.tile.requestLatestUnreadTileUpdate

internal class WearItemStateClockStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<ItemStateClock> = runCatching {
        prefs.getString(KEY, null)?.let(ItemStateWireContract::decode).orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(states: Collection<ItemStateClock>) {
        prefs.edit().putString(KEY, ItemStateWireContract.encode(states)).apply()
    }

    private companion object {
        const val PREFS = "wristbrief_item_state_sync"
        const val KEY = "clocks_v1"
    }
}

internal data class AppliedItemFlags(
    val readItemIds: Set<String>,
    val savedItemIds: Set<String>,
) {
    fun unreadSurfacesChangedFrom(previousReadItemIds: Set<String>): Boolean = readItemIds != previousReadItemIds
}

internal fun applyItemStateFlags(
    currentReadItemIds: Set<String>,
    currentSavedItemIds: Set<String>,
    states: Collection<ItemStateClock>,
): AppliedItemFlags {
    val readIds = currentReadItemIds.toMutableSet()
    val savedIds = currentSavedItemIds.toMutableSet()
    states.forEach { state ->
        state.read?.let { if (it.value) readIds += state.itemId else readIds -= state.itemId }
        state.saved?.let { if (it.value) savedIds += state.itemId else savedIds -= state.itemId }
    }
    return AppliedItemFlags(readIds, savedIds)
}

class WearItemStateSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val clockStore = WearItemStateClockStore(appContext)
    private val feedStore = SharedPreferencesFeedStore(appContext)
    private val dataClient = Wearable.getDataClient(appContext)

    @Synchronized
    fun recordRead(itemId: String, value: Boolean, nowEpochMs: Long = System.currentTimeMillis()) {
        mutateLocal(itemId, read = value, saved = null, nowEpochMs = nowEpochMs)
    }

    @Synchronized
    fun recordSaved(itemId: String, value: Boolean, nowEpochMs: Long = System.currentTimeMillis()) {
        mutateLocal(itemId, read = null, saved = value, nowEpochMs = nowEpochMs)
    }

    @Synchronized
    fun applyPhonePayload(raw: String): Boolean {
        val remote = runCatching { ItemStateWireContract.decodeOwned(raw, SyncOrigin.PHONE) }.getOrNull() ?: return false
        val merged = clockStore.load().associateBy { it.itemId }.toMutableMap()
        remote.forEach { state -> merged[state.itemId] = mergeItemState(merged[state.itemId], state) }
        clockStore.save(merged.values)
        val unreadSurfacesChanged = applyMergedState(remote.mapNotNull { merged[it.itemId] })
        if (unreadSurfacesChanged) {
            requestLatestUnreadTileUpdate(appContext)
            requestUnreadComplicationUpdate(appContext)
        }
        return true
    }

    private fun mutateLocal(itemId: String, read: Boolean?, saved: Boolean?, nowEpochMs: Long) {
        val states = clockStore.load().associateBy { it.itemId }.toMutableMap()
        val next = localMutation(states[itemId], itemId, read, saved, nowEpochMs, SyncOrigin.WEAR)
        states[itemId] = next
        clockStore.save(states.values)
        publishOwned(states.values)
    }

    private fun applyMergedState(states: Collection<ItemStateClock>): Boolean {
        val currentReadIds = feedStore.readItemIds()
        val currentSavedIds = feedStore.savedItemIds()
        val applied = applyItemStateFlags(currentReadIds, currentSavedIds, states)
        if (applied.readItemIds != currentReadIds) feedStore.saveReadItemIds(applied.readItemIds)
        if (applied.savedItemIds != currentSavedIds) feedStore.saveSavedItemIds(applied.savedItemIds)
        return applied.unreadSurfacesChangedFrom(currentReadIds)
    }

    private fun publishOwned(states: Collection<ItemStateClock>) {
        val payload = ItemStateWireContract.encode(ownedStates(states, SyncOrigin.WEAR))
        val request = PutDataMapRequest.create(ItemStateWireContract.WEAR_PATH).apply {
            dataMap.putString(ItemStateWireContract.PAYLOAD_KEY, payload)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }
}

class ItemStateDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != ItemStateWireContract.PHONE_PATH) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(ItemStateWireContract.PAYLOAD_KEY) ?: return@forEach
            WearItemStateSyncManager(this).applyPhonePayload(raw)
        }
    }
}
