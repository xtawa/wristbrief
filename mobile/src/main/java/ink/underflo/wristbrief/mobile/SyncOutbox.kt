package ink.underflo.wristbrief.mobile

import android.content.Context
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class OutboxEntityType {
    ITEM_STATE,
    PLAYBACK,
    SUBSCRIPTION,
}

data class OutboxEntry(
    val id: String = UUID.randomUUID().toString(),
    val entityType: OutboxEntityType,
    val entityKey: String,
    val payload: String,
    val attempts: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val nextAttemptAtEpochMs: Long = createdAtEpochMs,
)

interface SyncOutboxStore {
    fun enqueue(entry: OutboxEntry)
    fun pending(nowEpochMs: Long = System.currentTimeMillis()): List<OutboxEntry>
    fun recordAttempt(id: String, maxAttempts: Int = 5, backoffBaseMs: Long = 1000L, nowEpochMs: Long = System.currentTimeMillis())
    fun dequeue(id: String)
    fun clear()
    fun size(): Int
}

class InMemorySyncOutboxStore : SyncOutboxStore {
    private val entries = mutableListOf<OutboxEntry>()

    @Synchronized
    override fun enqueue(entry: OutboxEntry) {
        entries.removeAll { it.entityType == entry.entityType && it.entityKey == entry.entityKey }
        entries.add(entry)
    }

    @Synchronized
    override fun pending(nowEpochMs: Long): List<OutboxEntry> =
        entries.filter { it.nextAttemptAtEpochMs <= nowEpochMs }

    @Synchronized
    override fun recordAttempt(id: String, maxAttempts: Int, backoffBaseMs: Long, nowEpochMs: Long) {
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return
        val existing = entries[index]
        val nextAttempts = existing.attempts + 1
        if (nextAttempts >= maxAttempts) {
            entries.removeAt(index)
        } else {
            val backoff = (backoffBaseMs * (2.0.pow(min(existing.attempts, 6)))).toLong()
            entries[index] = existing.copy(
                attempts = nextAttempts,
                nextAttemptAtEpochMs = nowEpochMs + backoff,
            )
        }
    }

    @Synchronized
    override fun dequeue(id: String) {
        entries.removeAll { it.id == id }
    }

    @Synchronized
    override fun clear() {
        entries.clear()
    }

    @Synchronized
    override fun size(): Int = entries.size
}

class SharedPreferencesSyncOutboxStore(
    context: Context,
    prefsName: String = "wristbrief_sync_outbox",
) : SyncOutboxStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun enqueue(entry: OutboxEntry) {
        val current = loadEntries().toMutableList()
        // Replace existing pending entry for the same entity if present
        current.removeAll { it.entityType == entry.entityType && it.entityKey == entry.entityKey }
        current.add(entry)
        saveEntries(current)
    }

    @Synchronized
    override fun pending(nowEpochMs: Long): List<OutboxEntry> =
        loadEntries().filter { it.nextAttemptAtEpochMs <= nowEpochMs }

    @Synchronized
    override fun recordAttempt(id: String, maxAttempts: Int, backoffBaseMs: Long, nowEpochMs: Long) {
        val current = loadEntries().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return

        val existing = current[index]
        val nextAttempts = existing.attempts + 1
        if (nextAttempts >= maxAttempts) {
            current.removeAt(index)
        } else {
            val backoff = (backoffBaseMs * (2.0.pow(min(existing.attempts, 6)))).toLong()
            current[index] = existing.copy(
                attempts = nextAttempts,
                nextAttemptAtEpochMs = nowEpochMs + backoff,
            )
        }
        saveEntries(current)
    }

    @Synchronized
    override fun dequeue(id: String) {
        val current = loadEntries().toMutableList()
        if (current.removeAll { it.id == id }) {
            saveEntries(current)
        }
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().apply()
    }

    @Synchronized
    override fun size(): Int = loadEntries().size

    private fun loadEntries(): List<OutboxEntry> = runCatching {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val root = json.parseToJsonElement(raw).jsonArray
        root.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val typeStr = obj["entityType"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val type = runCatching { OutboxEntityType.valueOf(typeStr) }.getOrNull() ?: return@mapNotNull null
            val key = obj["entityKey"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val payload = obj["payload"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val attempts = obj["attempts"]?.jsonPrimitive?.intOrNull ?: 0
            val created = obj["createdAtEpochMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val nextAttempt = obj["nextAttemptAtEpochMs"]?.jsonPrimitive?.longOrNull ?: 0L
            OutboxEntry(id, type, key, payload, attempts, created, nextAttempt)
        }
    }.getOrDefault(emptyList())

    private fun saveEntries(entries: List<OutboxEntry>) {
        val array = buildJsonArray {
            entries.forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.id)
                    put("entityType", entry.entityType.name)
                    put("entityKey", entry.entityKey)
                    put("payload", entry.payload)
                    put("attempts", entry.attempts)
                    put("createdAtEpochMs", entry.createdAtEpochMs)
                    put("nextAttemptAtEpochMs", entry.nextAttemptAtEpochMs)
                })
            }
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "entries_v1"
    }
}
