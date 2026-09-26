package ink.underflo.wristbrief.mobile.artifacts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parsing for the transcript endpoints, kept separate from the HTTP transport so the
 * frozen response contract can be tested directly (the client only talks to HTTPS, so a
 * loopback server cannot be used from a unit test).
 *
 * Frozen contract:
 * - `POST /v1/transcripts/request` answers `ready` (no work needed) or `processing` with a
 *   job id that is pollable BY THE USER WHO RECEIVED IT.
 * - `GET /v1/transcripts/jobs/:id` answers only `queued | running | completed | failed`.
 *   `queued` and `running` are two distinct non-terminal states; `completed` and `failed`
 *   are terminal. `artifactId` and `quota` are present only when the status is `completed`
 *   AND the caller may read the artifact, so their absence is normal, not an error.
 * - A 404 `{"error":"job_not_found"}` means the id is unknown OR belongs to another user:
 *   terminal, and it deliberately leaks nothing about existence.
 * - `GET /v1/transcripts/{contentCode}` returns the payload in `transcript`, and answers 404
 *   with no `transcript` key when the caller has no grant.
 */
internal object TranscriptResponseParser {

    private const val SOURCE_EXISTING_ACCESS = "existing_access"
    private const val SOURCE_SHARED_CACHE = "shared_cache"
    private const val SOURCE_GENERATED = "generated"

    private val json = Json { ignoreUnknownKeys = true }

    /** `POST /v1/transcripts/request`. */
    suspend fun parseRequestResponse(
        jsonText: String,
        fetchContent: suspend (contentCode: String) -> TranscriptPayload?,
    ): TranscriptFetchResult {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val status = root["status"]?.jsonPrimitive?.content.orEmpty()
        val contentCode = root["contentCode"]?.jsonPrimitive?.content.orEmpty()

        return when (status.lowercase()) {
            "ready", "completed" -> TranscriptFetchResult.Ready(
                contentCode = contentCode,
                artifactId = root["artifactId"]?.jsonPrimitive?.content.orEmpty(),
                source = root["source"]?.jsonPrimitive?.content.orEmpty().ifBlank { SOURCE_GENERATED },
                quota = quotaFrom(root["quota"]?.jsonObject),
                // An inline `let` inherits the suspend context, so the suspend fetch can be
                // invoked from inside it even though `let` itself takes a plain Function1.
                payload = root["payload"]?.jsonObject?.let { TranscriptPayload.fromJsonString(it.toString()) }
                    ?: contentCode.takeIf { it.isNotEmpty() }?.let { fetchContent(it) },
            )
            "processing", "queued", "running" -> TranscriptFetchResult.Processing(
                contentCode = contentCode,
                jobId = root["jobId"]?.jsonPrimitive?.content.orEmpty(),
                status = status.lowercase(),
            )
            else -> TranscriptFetchResult.Failure(
                errorCode = root["error"]?.jsonPrimitive?.content ?: TranscriptGatewayErrors.UNKNOWN_RESPONSE,
                message = root["message"]?.jsonPrimitive?.content ?: jsonText,
            )
        }
    }

    /** `GET /v1/transcripts/jobs/:id` at HTTP 200. */
    suspend fun parseJobStatusResponse(
        jsonText: String,
        fetchContent: suspend (contentCode: String) -> TranscriptPayload?,
        retryAfterMs: Long? = null,
    ): TranscriptFetchResult {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val status = root["status"]?.jsonPrimitive?.content.orEmpty()
        val jobId = root["jobId"]?.jsonPrimitive?.content.orEmpty()
        val contentCode = root["contentCode"]?.jsonPrimitive?.content.orEmpty()
        val pollAfterMs = serverCadence(root, retryAfterMs)

        return when (status.lowercase()) {
            "completed" -> TranscriptFetchResult.Ready(
                contentCode = contentCode,
                artifactId = root["artifactId"]?.jsonPrimitive?.content.orEmpty(),
                source = SOURCE_GENERATED,
                quota = quotaFrom(root["quota"]?.jsonObject),
                // A missing artifactId/transcript is normal for a caller without a grant.
                payload = root["payload"]?.jsonObject?.let { TranscriptPayload.fromJsonString(it.toString()) }
                    ?: contentCode.takeIf { it.isNotEmpty() }?.let { fetchContent(it) },
            )
            "queued", "running" -> TranscriptFetchResult.Processing(
                contentCode = contentCode,
                jobId = jobId,
                status = status.lowercase(),
                pollAfterMs = pollAfterMs,
            )
            // Defensive only: the frozen status endpoint never returns this word, and
            // treating it as non-terminal is safer than stopping the poll.
            "processing" -> TranscriptFetchResult.Processing(
                contentCode = contentCode,
                jobId = jobId,
                status = "processing",
                pollAfterMs = pollAfterMs,
            )
            "failed" -> TranscriptFetchResult.Failure(
                errorCode = root["errorCode"]?.jsonPrimitive?.content ?: TranscriptGatewayErrors.JOB_FAILED,
                message = jsonText,
            )
            else -> TranscriptFetchResult.Failure(
                errorCode = root["error"]?.jsonPrimitive?.content ?: TranscriptGatewayErrors.UNKNOWN_STATUS,
                message = jsonText,
            )
        }
    }

    /**
     * Maps a non-2xx response onto a stable, SEMANTIC code. A 404 on a job is
     * `job_not_found` regardless of an unusable body, because that is what the absence of
     * the job means, and a bare 401 must not surface as the mechanical `HTTP_401`: the UI
     * acts on `UNAUTHORIZED` to ask for a fresh sign-in.
     */
    fun parseErrorResponse(responseCode: Int, body: String): TranscriptFetchResult.Failure {
        val bodyCode = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val code = when {
            bodyCode != null -> TranscriptGatewayErrors.normalize(bodyCode, body)
            responseCode == 404 -> TranscriptGatewayErrors.NOT_FOUND
            else -> "HTTP_$responseCode"
        }
        return TranscriptFetchResult.Failure(
            errorCode = code,
            message = body,
            kind = TranscriptGatewayErrors.classify(code),
        )
    }

    /** `Retry-After` (seconds or HTTP-date) or a `pollAfterMs` body field. */
    fun parseRetryAfterMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { seconds -> return (seconds * 1000L).coerceAtLeast(0L) }
        return runCatching {
            val zoned = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)
            (java.time.Instant.from(zoned).toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    /**
     * The cadence the server asked for: the `pollAfterMs` body field wins, then any
     * `Retry-After` header. Null means the server asked for nothing, in which case the
     * caller keeps its own steady cadence rather than inventing a value.
     */
    private fun serverCadence(root: kotlinx.serialization.json.JsonObject, retryAfterMs: Long?): Long? {
        val fromBody = root["pollAfterMs"]?.jsonPrimitive?.longOrNull
            ?: root["pollAfter"]?.jsonPrimitive?.longOrNull
        return fromBody?.takeIf { it > 0L } ?: retryAfterMs?.takeIf { it > 0L }
    }

    private fun quotaFrom(quotaObj: kotlinx.serialization.json.JsonObject?): TranscriptQuotaInfo {
        val normalUnits = quotaObj?.get("normalUnits")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val multiplier = quotaObj?.get("multiplier")?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
        val chargedUnits = quotaObj?.get("chargedUnits")?.jsonPrimitive?.content?.toFloatOrNull()
            ?: multiplier * normalUnits
        return TranscriptQuotaInfo(normalUnits = normalUnits, multiplier = multiplier, chargedUnits = chargedUnits)
    }

    /** Known provenance values; anything else is reported as unknown rather than guessed. */
    fun sourceLabel(source: String): String = when (source.trim().lowercase()) {
        SOURCE_EXISTING_ACCESS -> SOURCE_EXISTING_ACCESS
        SOURCE_SHARED_CACHE -> SOURCE_SHARED_CACHE
        SOURCE_GENERATED -> SOURCE_GENERATED
        else -> "unknown"
    }
}
