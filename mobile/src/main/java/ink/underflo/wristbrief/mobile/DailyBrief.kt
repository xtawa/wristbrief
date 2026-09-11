package ink.underflo.wristbrief.mobile

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
)

interface DailyBriefStore {
    fun getLatest(): DailyBriefRecord?
    fun save(record: DailyBriefRecord)
}

class SharedPreferencesDailyBriefStore(context: Context) : DailyBriefStore {
    private val prefs = context.applicationContext.getSharedPreferences("wristbrief_daily_brief", Context.MODE_PRIVATE)

    override fun getLatest(): DailyBriefRecord? {
        val raw = prefs.getString("latest_record_v1", null) ?: return null
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
            )
        }.getOrNull()
    }

    override fun save(record: DailyBriefRecord) {
        val json = buildJsonObject {
            put("dateKey", record.dateKey)
            put("title", record.title)
            put("tiny", record.tiny)
            put("long", record.long)
            putJsonArray("bullets") { record.bullets.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("topics") { record.topics.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("generatedAtEpochMs", record.generatedAtEpochMs.toString())
            put("sourceCount", record.sourceCount.toString())
        }.toString()
        prefs.edit().putString("latest_record_v1", json).apply()
    }
}

data class DailyBriefInput(
    val title: String,
    val content: String,
    val itemCount: Int,
)

object DailyBriefInputBuilder {
    private const val MAX_TOTAL_CHARS = 12_000
    private const val MAX_ITEMS = 6

    fun build(items: List<MobileFeedItem>, now: Date = Date()): DailyBriefInput? {
        val candidates = items.take(MAX_ITEMS)
        if (candidates.isEmpty()) return null

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
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
        return DailyBriefInput(
            title = title,
            content = sb.toString().trim(),
            itemCount = currentCount,
        )
    }

    fun todayKey(now: Date = Date()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
    }
}
