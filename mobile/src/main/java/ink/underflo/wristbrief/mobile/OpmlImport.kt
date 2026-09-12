package ink.underflo.wristbrief.mobile

import android.content.Context
import ink.underflo.wristbrief.mobile.validGatewayOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Where an OPML import comes from: a picked local file, or a fetched HTTPS URL. */
sealed interface OpmlImportSource {
    /** The document URI as a string (kept as String so previews are JVM-testable). */
    data class LocalFile(val uriString: String) : OpmlImportSource
    data class RemoteUrl(val url: String) : OpmlImportSource
}

/** User-confirmed-before-apply import preview (never applied sight unseen). */
data class OpmlImportPreview(
    val source: OpmlImportSource,
    val feeds: List<OpmlFeedEntry>,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val warnings: List<String> = emptyList(),
)

sealed interface OpmlPreviewOutcome {
    data class Ready(val preview: OpmlImportPreview) : OpmlPreviewOutcome
    data class Error(val code: String) : OpmlPreviewOutcome
}

/**
 * Local-file preview: parse with the existing Opml.kt parser (no network).
 * The parser already merges in-document duplicates.
 */
fun previewLocalOpml(source: OpmlImportSource.LocalFile, raw: String): OpmlPreviewOutcome = try {
    val entries = parseOpmlSubscriptions(raw)
    OpmlPreviewOutcome.Ready(
        OpmlImportPreview(source = source, feeds = entries, duplicateCount = 0, rejectedCount = 0)
    )
} catch (error: OpmlFormatException) {
    OpmlPreviewOutcome.Error(error.message ?: "invalid_opml")
}

/**
 * Client for POST /v1/opml/preview-url. The remote document is fetched by the
 * gateway (SSRF-safe); the phone never fetches the URL itself.
 */
class HttpOpmlPreviewApi(
    private val baseUrl: String,
    private val sessionTokenProvider: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun preview(url: String): OpmlPreviewOutcome = withContext(Dispatchers.IO) {
        val sessionToken = sessionTokenProvider() ?: return@withContext OpmlPreviewOutcome.Error("unauthorized")
        val gatewayOrigin = validGatewayOrigin(baseUrl) ?: return@withContext OpmlPreviewOutcome.Error("auth_not_configured")
        runCatching {
            val conn = (URL("$gatewayOrigin/v1/opml/preview-url").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $sessionToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(buildJsonObject { put("url", url) }.toString())
            }
            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (responseCode !in 200..299) {
                val code = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "opml_preview_failed"
                return@runCatching OpmlPreviewOutcome.Error(code)
            }
            parsePreviewResponse(OpmlImportSource.RemoteUrl(url), body)
        }.getOrElse { OpmlPreviewOutcome.Error("network_error") }
    }

    internal fun parsePreviewResponse(source: OpmlImportSource.RemoteUrl, body: String): OpmlPreviewOutcome = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val feeds = (root["feeds"]?.jsonArray ?: return@runCatching OpmlPreviewOutcome.Error("invalid_response")).map { element ->
            val obj = element.jsonObject
            OpmlFeedEntry(
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                enabled = obj["enabled"]?.let { it.jsonPrimitive.booleanOrNull } ?: true,
                sendToWatch = obj["sendToWatch"]?.let { it.jsonPrimitive.booleanOrNull } ?: true,
                category = obj["category"]?.jsonPrimitive?.contentOrNull,
                watchKeywords = obj["watchKeywords"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }.filter { it.url.isNotBlank() }
        val summary = root["summary"]?.jsonObject
        OpmlPreviewOutcome.Ready(
            OpmlImportPreview(
                source = source,
                feeds = feeds,
                duplicateCount = summary?.get("duplicate")?.jsonPrimitive?.int ?: 0,
                rejectedCount = summary?.get("rejected")?.jsonPrimitive?.int ?: 0,
                warnings = root["warnings"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        )
    }.getOrElse { OpmlPreviewOutcome.Error("invalid_response") }
}

/**
 * Apply a confirmed preview: probe each not-yet-subscribed feed (same rules as
 * the existing OPML import), persist in one batch, and let the feed manager's
 * lifecycle hooks enqueue the cloud sync mutations.
 */
suspend fun MobileFeedManager.applyOpmlPreview(preview: OpmlImportPreview): OpmlImportResult {
    val current = feeds()
    val knownUrls = current.mapNotNull { normalizeFeedUrl(it.url) }.toMutableSet()
    val additions = mutableListOf<MobileFeedSubscription>()
    var duplicates = 0
    var failed = 0
    preview.feeds.forEach { entry ->
        val normalized = normalizeFeedUrl(entry.url) ?: run { failed += 1; return@forEach }
        if (!knownUrls.add(normalized)) { duplicates += 1; return@forEach }
        val discovered = runCatching { validateForBulkImport(normalized) }.getOrElse { knownUrls.remove(normalized); failed += 1; return@forEach }
        val title = entry.title.trim().ifBlank { discovered ?: java.net.URI(normalized).host }
        additions += MobileFeedSubscription(
            stableFeedId(normalized),
            title,
            normalized,
            entry.enabled,
            entry.sendToWatch,
            normalizeFeedCategory(entry.category),
            normalizeWatchKeywords(entry.watchKeywords),
        )
    }
    val merged = if (additions.isEmpty()) current else persistBulkImport(current + additions).feeds
    return OpmlImportResult.Success(merged, additions.size, duplicates, failed)
}
