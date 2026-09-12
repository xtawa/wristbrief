package ink.underflo.wristbrief.mobile

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ink.underflo.wristbrief.mobile.db.SqlitePodcastProgressStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import ink.underflo.wristbrief.mobile.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.mobile.media.PodcastProgressStore

class PhonePlaybackSyncManager internal constructor(
    private val progressStore: PodcastProgressStore,
    private val publishCallback: ((String) -> Unit)?,
) {
    constructor(context: Context) : this(
        progressStore = SqlitePodcastProgressStore(WristBriefDatabaseHelper(context.applicationContext)),
        publishCallback = { payload ->
            val request = PutDataMapRequest.create(PlaybackWireContract.PHONE_PATH).apply {
                dataMap.putString(PlaybackWireContract.PAYLOAD_KEY, payload)
                dataMap.putLong("updatedAt", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
        },
    )

    @Synchronized
    fun publishLocalProgress(progress: PodcastEpisodeProgress) {
        val payload = PlaybackWireContract.encode(progress, SyncOrigin.PHONE)
        publishCallback?.invoke(payload)
    }

    @Synchronized
    fun applyWearProgress(raw: String): Boolean {
        val remote = runCatching {
            PlaybackWireContract.decodeOwned(raw, SyncOrigin.WEAR)
        }.getOrNull() ?: return false

        val local = progressStore.get(remote.episodeId)
        val resolved = PlaybackWireContract.resolvePlaybackConflict(local, remote)
        if (resolved != local) {
            progressStore.save(resolved)
            return true
        }
        return false
    }
}

class PlaybackDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != PlaybackWireContract.WEAR_PATH) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(PlaybackWireContract.PAYLOAD_KEY) ?: return@forEach
            PhonePlaybackSyncManager(this).applyWearProgress(raw)
        }
    }
}
