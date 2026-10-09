package ink.underflo.wristbrief.sync

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import java.time.Instant
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class WearAccountSession(val token: String, val expiresAt: Instant, val userId: String)

internal object WearAccountSessionRuntime {
    @Volatile private var current: WearAccountSession? = null
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Increments whenever the session is set or cleared so open screens can re-evaluate AI availability. */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    fun initialize(context: Context, now: Instant = Instant.now()) {
        current = WearAccountSessionStore(context).read(now)
        _revision.update { it + 1 }
    }

    fun set(session: WearAccountSession) { current = session; _revision.update { it + 1 } }
    fun clear() { current = null; _revision.update { it + 1 } }

    fun currentToken(now: Instant = Instant.now()): String? {
        val session = current ?: return null
        if (!session.expiresAt.isAfter(now)) {
            current = null
            return null
        }
        return session.token
    }
}

internal class WearAccountSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("wear_account_session", Context.MODE_PRIVATE)

    fun read(now: Instant = Instant.now()): WearAccountSession? {
        val token = preferences.getString("token", null) ?: return null
        val expiresAt = preferences.getString("expires_at", null)?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        } ?: return invalid()
        val userId = preferences.getString("user_id", null) ?: return invalid()
        val session = WearAccountSession(token, expiresAt, userId)
        return if (valid(session, now)) session else invalid()
    }

    fun write(session: WearAccountSession, now: Instant = Instant.now()): Boolean {
        if (!valid(session, now)) return false
        preferences.edit()
            .putString("token", session.token)
            .putString("expires_at", session.expiresAt.toString())
            .putString("user_id", session.userId)
            .apply()
        WearAccountSessionRuntime.set(session)
        return true
    }

    fun currentUserId(): String? = preferences.getString("user_id", null)

    fun clear() {
        preferences.edit().clear().apply()
        WearAccountSessionRuntime.clear()
    }

    private fun invalid(): WearAccountSession? { clear(); return null }

    private fun valid(session: WearAccountSession, now: Instant): Boolean =
        isValidWearAccountSession(session, now)
}

internal fun isValidWearAccountSession(session: WearAccountSession, now: Instant): Boolean =
    Regex("^wbs_[A-Za-z0-9_-]{43}$").matches(session.token) &&
        session.expiresAt.isAfter(now) &&
        session.userId.isNotBlank() && session.userId.length <= 128 &&
        session.userId == session.userId.trim() && session.userId.none { it.code < 0x20 || it.code == 0x7f }

internal object WearAccountSessionMessageCodec {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Message {
        data class Set(val session: WearAccountSession) : Message
        data object Clear : Message
    }

    fun decode(bytes: ByteArray): Message? = runCatching {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(root["version"]?.jsonPrimitive?.intOrNull == 1)
        when (root["operation"]?.jsonPrimitive?.content) {
            "clear" -> Message.Clear
            "set" -> {
                val token = root["sessionToken"]?.jsonPrimitive?.content ?: error("missing token")
                val expiresAt = Instant.parse(root["expiresAt"]?.jsonPrimitive?.content ?: error("missing expiry"))
                val userId = root["userId"]?.jsonPrimitive?.content ?: error("missing user")
                Message.Set(WearAccountSession(token, expiresAt, userId))
            }
            else -> error("unsupported operation")
        }
    }.getOrNull()
}

internal fun applyWearAccountSessionMessage(
    message: WearAccountSessionMessageCodec.Message?,
    currentUserId: String? = null,
    now: Instant = Instant.now(),
    write: (WearAccountSession, Instant) -> Boolean,
    clear: () -> Unit,
    onAccountPurge: () -> Unit = {},
) {
    when (message) {
        is WearAccountSessionMessageCodec.Message.Set -> {
            if (currentUserId != null && currentUserId != message.session.userId) {
                onAccountPurge()
            }
            if (!write(message.session, now)) {
                clear()
                onAccountPurge()
            }
        }
        WearAccountSessionMessageCodec.Message.Clear, null -> {
            clear()
            onAccountPurge()
        }
    }
}

/**
 * Clears the account-scoped data stored on the watch (subscriptions, cached items, read/saved IDs,
 * item-state clocks, sync outbox and persisted podcast progress) on sign-out or account switch.
 *
 * The whole purge runs inside [PlaybackAccountFence.revokeAndRun]: the generation is bumped and the
 * stores are cleared under the fence lock, so a playback checkpoint (periodic, pause/seek,
 * onTaskRemoved, onDestroy) either completed before the purge or is refused; it can never land
 * between the revoke and the clear. Live sessions are asked to stop afterwards.
 *
 * The Continue listening tile reads the raw cached items and podcast progress directly (not the
 * subscription-filtered inbox), so both must be cleared here, not just subscriptions/read/saved.
 */
internal fun purgeWearAccountScopedData(
    feedStore: ink.underflo.wristbrief.data.FeedStore,
    progressStore: ink.underflo.wristbrief.media.PodcastProgressStore,
    clearItemStateClocks: () -> Unit,
    clearSyncOutbox: () -> Unit,
    fence: ink.underflo.wristbrief.media.PlaybackAccountFence = ink.underflo.wristbrief.media.PlaybackAccountFence.process,
) {
    fence.revokeAndRun {
        clearItemStateClocks()
        feedStore.saveSubscriptions(emptyList())
        feedStore.saveCachedItems(emptyList())
        feedStore.saveReadItemIds(emptySet())
        feedStore.saveSavedItemIds(emptySet())
        progressStore.all().forEach { progressStore.delete(it.episodeId) }
        clearSyncOutbox()
    }
}

/** Production purge wiring shared by the Data Layer service and instrumentation tests. */
internal fun purgeWearAccountScopedData(context: android.content.Context) {
    val appContext = context.applicationContext
    purgeWearAccountScopedData(
        feedStore = ink.underflo.wristbrief.data.SharedPreferencesFeedStore(appContext),
        progressStore = ink.underflo.wristbrief.media.SharedPreferencesPodcastProgressStore(appContext),
        clearItemStateClocks = { WearItemStateClockStore(appContext).save(emptyList()) },
        clearSyncOutbox = { SharedPreferencesSyncOutboxStore(appContext).clear() },
    )
    ink.underflo.wristbrief.tile.requestLatestUnreadTileUpdate(appContext)
    ink.underflo.wristbrief.tile.requestContinueListeningTileUpdate(appContext)
    ink.underflo.wristbrief.complication.requestUnreadComplicationUpdate(appContext)
}

class AccountSessionDataLayerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != PATH) return@forEach
            val payload = DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(PAYLOAD_KEY) ?: return@forEach
            val store = WearAccountSessionStore(this)
            val currentUserId = store.currentUserId()
            applyWearAccountSessionMessage(
                message = WearAccountSessionMessageCodec.decode(payload),
                currentUserId = currentUserId,
                write = store::write,
                clear = store::clear,
                onAccountPurge = { purgeWearAccountScopedData(this) },
            )
        }
    }

    companion object {
        const val PATH = "/wristbrief/account-session/v1"
        const val PAYLOAD_KEY = "payload"
    }
}
