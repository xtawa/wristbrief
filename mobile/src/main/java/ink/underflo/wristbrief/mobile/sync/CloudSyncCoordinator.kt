package ink.underflo.wristbrief.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

interface CloudSyncPreferences {
    fun getOrCreateDeviceId(): String
    fun getCursor(deviceId: String): Long
    fun saveCursor(deviceId: String, cursor: Long)
    fun resetAll()
}

class SharedPreferencesCloudSyncPreferences(
    private val prefs: SharedPreferences,
) : CloudSyncPreferences {
    constructor(context: Context) : this(
        context.getSharedPreferences("wristbrief_cloud_sync", Context.MODE_PRIVATE)
    )

    override fun getOrCreateDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = "dev_${UUID.randomUUID()}"
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    override fun getCursor(deviceId: String): Long {
        return prefs.getLong("cursor_$deviceId", 0L)
    }

    override fun saveCursor(deviceId: String, cursor: Long) {
        prefs.edit().putLong("cursor_$deviceId", cursor).apply()
    }

    override fun resetAll() {
        prefs.edit().clear().apply()
    }
}

fun interface WearSyncNotifier {
    fun notifyCanonicalStateChanged()
}

class CloudSyncCoordinator(
    private val api: CloudSyncApi,
    private val outbox: CloudSyncOutbox,
    private val target: CloudSyncTarget,
    private val preferences: CloudSyncPreferences,
    private val wearPublisher: WearSyncNotifier? = null,
) {
    constructor(
        api: CloudSyncApi,
        outbox: CloudSyncOutbox,
        merge: CloudSyncMerge,
        preferences: CloudSyncPreferences,
        wearPublisher: WearSyncNotifier? = null,
    ) : this(api, outbox, merge as CloudSyncTarget, preferences, wearPublisher)

    suspend fun syncOnce(sessionToken: String, nowEpochMs: Long = System.currentTimeMillis()): SyncCycleResult {
        val deviceId = preferences.getOrCreateDeviceId()

        // 1. Drain pending outbox mutations that have reached their nextAttemptEpochMs
        val pending = outbox.getPending(limit = 50, nowEpochMs = nowEpochMs)
        if (pending.isNotEmpty()) {
            val pushRes = api.push(sessionToken, deviceId, pending)
            if (pushRes.isSuccess) {
                outbox.remove(pending.map { it.id })
            } else {
                outbox.incrementRetry(pending.map { it.id }, nowEpochMs)
            }
        }

        // 2. Pull remote deltas using monotonic cursor
        var currentCursor = preferences.getCursor(deviceId)
        var hasMore = true
        var totalMerged = 0

        while (hasMore) {
            val pullRes = api.pull(sessionToken, deviceId, currentCursor, limit = 100)
            if (pullRes.isFailure) {
                return SyncCycleResult.PartialFailure(pullRes.exceptionOrNull()?.message ?: "Pull failed")
            }
            val data = pullRes.getOrThrow()
            target.mergeSubscriptions(data.subscriptions)
            target.mergeItemStates(data.itemStates)
            target.mergePlaybackProgress(data.playbackProgress)

            val count = data.subscriptions.size + data.itemStates.size + data.playbackProgress.size
            totalMerged += count

            currentCursor = data.cursor
            preferences.saveCursor(deviceId, currentCursor)
            hasMore = data.hasMore && count > 0
        }

        // 3. Notify Wear Data Layer when new remote state is applied
        if (totalMerged > 0) {
            wearPublisher?.notifyCanonicalStateChanged()
        }

        return SyncCycleResult.Success(cursor = currentCursor, mergedCount = totalMerged)
    }

    fun clearUserData() {
        outbox.clearAll()
        preferences.resetAll()
    }
}
