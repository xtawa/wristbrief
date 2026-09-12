package ink.underflo.wristbrief.sync

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ink.underflo.wristbrief.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.media.PodcastProgressStore
import ink.underflo.wristbrief.media.SharedPreferencesPodcastProgressStore
import ink.underflo.wristbrief.tile.requestContinueListeningTileUpdate

class WearPlaybackSyncManager internal constructor(
    private val progressStore: PodcastProgressStore,
    private val onTileUpdateRequested: (() -> Unit)?,
    private val publishCallback: ((String) -> Unit)?,
) {
    constructor(context: Context) : this(
        progressStore = SharedPreferencesPodcastProgressStore(context.applicationContext),
        onTileUpdateRequested = { requestContinueListeningTileUpdate(context.applicationContext) },
        publishCallback = { payload ->
            val request = PutDataMapRequest.create(PlaybackWireContract.WEAR_PATH).apply {
                dataMap.putString(PlaybackWireContract.PAYLOAD_KEY, payload)
                dataMap.putLong("updatedAt", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
        },
    )

    @Synchronized
    fun publishLocalProgress(progress: PodcastEpisodeProgress) {
        val payload = PlaybackWireContract.encode(progress, SyncOrigin.WEAR)
        publishCallback?.invoke(payload)
    }

    @Synchronized
    fun applyPhoneProgress(raw: String): Boolean {
        val remote = runCatching {
            PlaybackWireContract.decodeOwned(raw, SyncOrigin.PHONE)
        }.getOrNull() ?: return false

        val local = progressStore.get(remote.episodeId)
        val resolved = PlaybackWireContract.resolvePlaybackConflict(local, remote)
        if (resolved != local) {
            progressStore.save(resolved)
            onTileUpdateRequested?.invoke()
            return true
        }
        return false
    }
}

class PlaybackDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != PlaybackWireContract.PHONE_PATH) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(PlaybackWireContract.PAYLOAD_KEY) ?: return@forEach
            WearPlaybackSyncManager(this).applyPhoneProgress(raw)
        }
    }
}
