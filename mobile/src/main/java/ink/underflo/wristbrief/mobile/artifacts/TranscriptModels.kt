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
        /**
         * Raw gateway job status (`queued`, `running`, `processing`). A queued job is
         * waiting for a worker while running/processing means conversion or
         * recognition is under way, and the phone must be able to tell them apart.
         * Null when the phone has not yet observed a server status.
         */
        val status: String? = null,
        /**
         * Cadence the server asked for: a `pollAfterMs` body field or a `Retry-After`
         * response header. Null when the server did not ask for one, in which case the
         * caller keeps its own steady cadence instead of inventing a value.
         */
        val pollAfterMs: Long? = null,
    ) : TranscriptFetchResult

    data class Failure(
        val errorCode: String,
        val message: String,
        /**
         * Optional so existing construction sites keep compiling; read failures through
         * [isRetryable] rather than assuming a classification.
         */
        val kind: TranscriptFailureKind? = null,
    ) : TranscriptFetchResult {
        val isRetryable: Boolean
            get() = (kind ?: TranscriptGatewayErrors.classify(errorCode)).retryable
    }
}

/**
 * Whether asking the gateway again can change the outcome.
 *
 * This is the discriminator the UI uses to decide whether RETRY may be offered: a terminal
 * failure would only fail again, so showing a retry action for it would be a false promise.
 */
enum class TranscriptFailureKind(val retryable: Boolean) {
    /** Transport error, timeout, HTTP 5xx, HTTP 429: retrying can help. */
    RETRYABLE(true),

    /** Bad request, gone/not-yours job, missing grant: retrying cannot help. */
    TERMINAL(false),

    /** The caller has no usable session; a retry needs a sign-in first. */
    UNAUTHORIZED(false),
}
