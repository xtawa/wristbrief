package ink.underflo.wristbrief.mobile

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

internal class PhoneItemStateClockStore(context: Context) {
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
        const val PREFS = "wristbrief_mobile_item_state_sync"
        const val KEY = "clocks_v1"
    }
}

class PhoneItemStateSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val clockStore = PhoneItemStateClockStore(appContext)
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
    fun applyWearPayload(raw: String): Boolean {
        val remote = runCatching { ItemStateWireContract.decode(raw) }.getOrNull() ?: return false
        val merged = clockStore.load().associateBy { it.itemId }.toMutableMap()
        remote.forEach { state -> merged[state.itemId] = mergeItemState(merged[state.itemId], state) }
        clockStore.save(merged.values)
        return true
    }

    fun state(itemId: String): ItemStateClock? = clockStore.load().firstOrNull { it.itemId == itemId }

    private fun mutateLocal(itemId: String, read: Boolean?, saved: Boolean?, nowEpochMs: Long) {
        val states = clockStore.load().associateBy { it.itemId }.toMutableMap()
        states[itemId] = localMutation(states[itemId], itemId, read, saved, nowEpochMs, SyncOrigin.PHONE)
        clockStore.save(states.values)
        publishOwned(states.values)
    }

    private fun publishOwned(states: Collection<ItemStateClock>) {
        val payload = ItemStateWireContract.encode(ownedStates(states, SyncOrigin.PHONE))
        val request = PutDataMapRequest.create(ItemStateWireContract.PHONE_PATH).apply {
            dataMap.putString(ItemStateWireContract.PAYLOAD_KEY, payload)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }
}

class ItemStateDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != ItemStateWireContract.WEAR_PATH) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(ItemStateWireContract.PAYLOAD_KEY) ?: return@forEach
            PhoneItemStateSyncManager(this).applyWearPayload(raw)
        }
    }
}
