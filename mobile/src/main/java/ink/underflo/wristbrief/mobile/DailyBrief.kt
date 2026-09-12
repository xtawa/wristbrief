package ink.underflo.wristbrief.mobile

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DailyBriefRecord(
    val dateKey: String,
    val title: String,
    val tiny: String,
    val long: String,
    val bullets: List<String>,
    val topics: List<String>,
    val generatedAtEpochMs: Long,
    val sourceCount: Int,
    val inputHash: String = "",
)

interface DailyBriefStore {
    fun getLatest(): DailyBriefRecord?
    fun get(dateKey: String): DailyBriefRecord?
    fun history(limit: Int = 7): List<DailyBriefRecord>
    fun save(record: DailyBriefRecord)
}

class InMemoryDailyBriefStore : DailyBriefStore {
    private val records = mutableMapOf<String, DailyBriefRecord>()

    override fun getLatest(): DailyBriefRecord? {
        return records.values.maxByOrNull { it.generatedAtEpochMs }
    }

    override fun get(dateKey: String): DailyBriefRecord? {
        return records[dateKey]
    }

    override fun history(limit: Int): List<DailyBriefRecord> {
        return records.values.sortedByDescending { it.generatedAtEpochMs }.take(limit)
    }

    override fun save(record: DailyBriefRecord) {
        records[record.dateKey] = record
        if (records.size > 7) {
            val oldest = records.values.sortedBy { it.generatedAtEpochMs }.take(records.size - 7)
            oldest.forEach { records.remove(it.dateKey) }
        }
    }
}

class SharedPreferencesDailyBriefStore(context: Context) : DailyBriefStore {
    private val prefs = context.applicationContext.getSharedPreferences("wristbrief_daily_brief", Context.MODE_PRIVATE)

    override fun getLatest(): DailyBriefRecord? {
        val latestKey = prefs.getString("latest_date_key_v2", null)
        if (latestKey != null) {
            val record = get(latestKey)
            if (record != null) return record
        }
        val raw = prefs.getString("latest_record_v1", null) ?: return null
        return decodeRecord(raw)
    }

    override fun get(dateKey: String): DailyBriefRecord? {
        val raw = prefs.getString("brief_record_$dateKey", null) ?: return null
        return decodeRecord(raw)
    }

    override fun history(limit: Int): List<DailyBriefRecord> {
        val keys = getHistoryKeys()
        return keys
            .mapNotNull { get(it) }
            .sortedByDescending { it.generatedAtEpochMs }
            .take(limit)
    }

    override fun save(record: DailyBriefRecord) {
        val json = encodeRecord(record)
        val currentKeys = getHistoryKeys().toMutableSet()
        currentKeys.add(record.dateKey)

        val editor = prefs.edit()
        editor.putString("brief_record_${record.dateKey}", json)
        editor.putString("latest_record_v1", json)
        editor.putString("latest_date_key_v2", record.dateKey)

        // Prune older than 7 records
        val allRecords = currentKeys.mapNotNull { key ->
            val r = if (key == record.dateKey) record else get(key)
            if (r != null) key to r else null
        }
        if (allRecords.size > 7) {
            val toRemove = allRecords.sortedBy { it.second.generatedAtEpochMs }.take(allRecords.size - 7)
            for ((keyToRemove, _) in toRemove) {
                currentKeys.remove(keyToRemove)
                editor.remove("brief_record_$keyToRemove")
            }
        }

        editor.putStringSet("history_keys_v2", currentKeys)
        editor.apply()
    }

    private fun getHistoryKeys(): Set<String> {
        return prefs.getStringSet("history_keys_v2", emptySet()) ?: emptySet()
    }

    private fun decodeRecord(raw: String): DailyBriefRecord? {
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            DailyBriefRecord(
                dateKey = root["dateKey"]?.jsonPrimitive?.content.orEmpty(),
                title = root["title"]?.jsonPrimitive?.content.orEmpty(),
                tiny = root["tiny"]?.jsonPrimitive?.content.orEmpty(),
                long = root["long"]?.jsonPrimitive?.content.orEmpty(),
                bullets = root["bullets"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
                topics = root["topics"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
                generatedAtEpochMs = root["generatedAtEpochMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sourceCount = root["sourceCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                inputHash = root["inputHash"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.getOrNull()
    }

    private fun encodeRecord(record: DailyBriefRecord): String {
        return buildJsonObject {
            put("dateKey", record.dateKey)
            put("title", record.title)
            put("tiny", record.tiny)
            put("long", record.long)
            putJsonArray("bullets") { record.bullets.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("topics") { record.topics.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("generatedAtEpochMs", record.generatedAtEpochMs.toString())
            put("sourceCount", record.sourceCount.toString())
            put("inputHash", record.inputHash)
        }.toString()
    }
}

data class DailyBriefInput(
    val title: String,
    val content: String,
    val itemCount: Int,
    val inputHash: String = "",
)

object DailyBriefInputBuilder {
    private const val MAX_TOTAL_CHARS = 12_000
    private const val MAX_ITEMS = 6

    fun build(items: List<MobileFeedItem>, now: Date = Date()): DailyBriefInput? {
        val candidates = items.take(MAX_ITEMS)
        if (candidates.isEmpty()) return null

        val displayDate = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        val title = "Daily Brief - $displayDate"

        val sb = StringBuilder()
        var currentCount = 0

        for (item in candidates) {
            val sanitized = ArticleContentSanitizer.sanitize(item.description).plainText
            val excerpt = sanitized.take(1500).trim()
            val entry = buildString {
                append("Source: ").append(item.feedTitle.ifBlank { "Feed" }).append("\n")
                append("Title: ").append(item.title).append("\n")
                if (excerpt.isNotBlank()) {
                    append("Summary: ").append(excerpt).append("\n")
                }
                append("\n")
            }
            if (sb.length + entry.length > MAX_TOTAL_CHARS) break
            sb.append(entry)
            currentCount++
        }

        if (currentCount == 0 || sb.isBlank()) return null
        val content = sb.toString().trim()
        val inputHash = computeInputHash(content)
        return DailyBriefInput(
            title = title,
            content = content,
            itemCount = currentCount,
            inputHash = inputHash,
        )
    }

    fun computeInputHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun todayKey(now: Date = Date()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
    }
}
