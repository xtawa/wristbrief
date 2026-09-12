package ink.underflo.wristbrief.mobile.artifacts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class TranscriptSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("startMs", startMs)
        put("endMs", endMs)
        put("text", text)
        if (speaker != null) put("speaker", speaker)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): TranscriptSegment = TranscriptSegment(
            id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            startMs = obj["startMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            endMs = obj["endMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            text = obj["text"]?.jsonPrimitive?.content ?: "",
            speaker = obj["speaker"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
        )
    }
}

data class TranscriptPayload(
    val contentCode: String,
    val language: String,
    val durationMs: Long,
    val fullText: String,
    val segments: List<TranscriptSegment>,
) {
    fun toJsonString(): String = buildJsonObject {
        put("schemaVersion", "1")
        put("contentCode", contentCode)
        put("language", language)
        put("durationMs", durationMs)
        put("fullText", fullText)
        put("segments", buildJsonArray {
            segments.forEach { add(it.toJsonObject()) }
        })
    }.toString()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJsonString(raw: String): TranscriptPayload {
            val obj = json.parseToJsonElement(raw).jsonObject
            val segmentsArr = obj["segments"]?.jsonArray
            val list = mutableListOf<TranscriptSegment>()
            segmentsArr?.forEach { elem ->
                list.add(TranscriptSegment.fromJsonObject(elem.jsonObject))
            }
            return TranscriptPayload(
                contentCode = obj["contentCode"]?.jsonPrimitive?.content ?: "",
                language = obj["language"]?.jsonPrimitive?.content ?: "auto",
                durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                fullText = obj["fullText"]?.jsonPrimitive?.content ?: "",
                segments = list,
            )
        }
    }
}

data class TranscriptQuotaInfo(
    val normalUnits: Int,
    val multiplier: Float,
    val chargedUnits: Float,
)

sealed interface TranscriptFetchResult {
    data class Ready(
        val contentCode: String,
        val artifactId: String,
        val source: String,
        val quota: TranscriptQuotaInfo,
        val payload: TranscriptPayload?,
    ) : TranscriptFetchResult

    data class Processing(
        val contentCode: String,
        val jobId: String,
    ) : TranscriptFetchResult

    data class Failure(
        val errorCode: String,
        val message: String,
    ) : TranscriptFetchResult
}
