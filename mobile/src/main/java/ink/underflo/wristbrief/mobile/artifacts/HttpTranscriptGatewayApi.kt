package ink.underflo.wristbrief.mobile.artifacts

import ink.underflo.wristbrief.mobile.validGatewayOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_GATEWAY_RESPONSE_CHARS = 1_048_576

private fun BufferedReader.readTextLimited(): String {
    val result = StringBuilder()
    val buffer = CharArray(8_192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return result.toString()
        if (result.length + count > MAX_GATEWAY_RESPONSE_CHARS) throw IllegalStateException("response_too_large")
        result.append(buffer, 0, count)
    }
}

class HttpTranscriptGatewayApi(
    private val baseUrl: String,
    private val sessionTokenProvider: suspend () -> String? = { null },
) : TranscriptGatewayApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun requestTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val gatewayOrigin = validGatewayOrigin(baseUrl)
                    ?: return@withContext TranscriptFetchResult.Failure("INVALID_GATEWAY_URL", "Invalid gateway URL")
                val token = sessionTokenProvider()
                val url = URL("$gatewayOrigin/v1/transcripts/request")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    if (token != null) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                val body = buildJsonObject {
                    put("episode", buildJsonObject {
                        put("audioUrl", request.audioUrl)
                        if (request.feedUrl != null) put("feedUrl", request.feedUrl)
                        if (request.guid != null) put("guid", request.guid)
                        if (request.title != null) put("title", request.title)
                        if (request.durationMs != null) put("durationMs", request.durationMs)
                    })
                }.toString()

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorStream = conn.errorStream ?: conn.inputStream
                    val errorMsg = BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readTextLimited() }
                    return@withContext TranscriptResponseParser.parseErrorResponse(responseCode, errorMsg)
                }

                val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
                TranscriptResponseParser.parseRequestResponse(responseBody) { code ->
                    fetchTranscriptContent(code, token)
                }
            } catch (e: Exception) {
                TranscriptFetchResult.Failure("NETWORK_ERROR", e.message ?: "Failed to connect")
            }
        }

    override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val gatewayOrigin = validGatewayOrigin(baseUrl)
                    ?: return@withContext TranscriptFetchResult.Failure("INVALID_GATEWAY_URL", "Invalid gateway URL")
                val token = sessionTokenProvider()
                val url = URL("$gatewayOrigin/v1/transcripts/jobs/$jobId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    if (token != null) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("Accept", "application/json")
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorStream = conn.errorStream ?: conn.inputStream
                    val errorMsg = BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readTextLimited() }
                    // 404 job_not_found means the id is unknown OR belongs to another user.
                    // Either way it is a real "not yours / gone" outcome, not a transient error.
                    return@withContext TranscriptResponseParser.parseErrorResponse(responseCode, errorMsg)
                }

                val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
                // The server's requested cadence is honoured when it sends one; otherwise the
                // caller falls back to its own steady cadence rather than a guessed value.
                TranscriptResponseParser.parseJobStatusResponse(
                    jsonText = responseBody,
                    fetchContent = { code -> fetchTranscriptContent(code, token) },
                    retryAfterMs = TranscriptResponseParser.parseRetryAfterMs(conn.getHeaderField("Retry-After")),
                )
            } catch (e: Exception) {
                TranscriptFetchResult.Failure("NETWORK_ERROR", e.message ?: "Failed to connect")
            }
        }

    private suspend fun fetchTranscriptContent(contentCode: String, token: String?): TranscriptPayload? =
        withContext(Dispatchers.IO) {
            try {
                val gatewayOrigin = validGatewayOrigin(baseUrl) ?: return@withContext null
                val url = URL("$gatewayOrigin/v1/transcripts/$contentCode")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    if (token != null) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val content = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
                    val root = json.parseToJsonElement(content).jsonObject
                    root["transcript"]?.jsonObject?.let { TranscriptPayload.fromJsonString(it.toString()) }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
}
