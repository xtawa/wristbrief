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
                    return@withContext TranscriptFetchResult.Failure("HTTP_$responseCode", errorMsg)
                }

                val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
                parseTranscriptResponse(responseBody, token)
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
                    return@withContext TranscriptFetchResult.Failure("HTTP_$responseCode", errorMsg)
                }

                val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
                parseJobStatusResponse(responseBody, token)
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

    private suspend fun parseTranscriptResponse(jsonText: String, token: String?): TranscriptFetchResult {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val status = root["status"]?.jsonPrimitive?.content ?: ""
        val contentCode = root["contentCode"]?.jsonPrimitive?.content ?: ""

        return when (status.lowercase()) {
            "ready" -> {
                val artifactId = root["artifactId"]?.jsonPrimitive?.content ?: ""
                val source = root["source"]?.jsonPrimitive?.content ?: "unknown"
                val quotaObj = root["quota"]?.jsonObject
                val normalUnits = quotaObj?.get("normalUnits")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val multiplier = quotaObj?.get("multiplier")?.jsonPrimitive?.floatOrNull ?: 1.0f
                val chargedUnits = quotaObj?.get("chargedUnits")?.jsonPrimitive?.floatOrNull ?: multiplier * normalUnits

                val payloadObj = root["payload"]?.jsonObject
                val payload = if (payloadObj != null) {
                    TranscriptPayload.fromJsonString(payloadObj.toString())
                } else if (contentCode.isNotEmpty()) {
                    fetchTranscriptContent(contentCode, token)
                } else {
                    null
                }

                TranscriptFetchResult.Ready(
                    contentCode = contentCode,
                    artifactId = artifactId,
                    source = source,
                    quota = TranscriptQuotaInfo(
                        normalUnits = normalUnits,
                        multiplier = multiplier,
                        chargedUnits = chargedUnits,
                    ),
                    payload = payload,
                )
            }
            "processing" -> {
                val jobId = root["jobId"]?.jsonPrimitive?.content ?: ""
                TranscriptFetchResult.Processing(
                    contentCode = contentCode,
                    jobId = jobId,
                )
            }
            else -> {
                val error = root["error"]?.jsonPrimitive?.content ?: "UNKNOWN_RESPONSE"
                val message = root["message"]?.jsonPrimitive?.content ?: jsonText
                TranscriptFetchResult.Failure(error, message)
            }
        }
    }

    private suspend fun parseJobStatusResponse(jsonText: String, token: String?): TranscriptFetchResult {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val status = root["status"]?.jsonPrimitive?.content ?: ""
        val jobId = root["jobId"]?.jsonPrimitive?.content ?: ""
        val contentCode = root["contentCode"]?.jsonPrimitive?.content ?: ""

        return when (status.lowercase()) {
            "completed" -> {
                val artifactId = root["artifactId"]?.jsonPrimitive?.content ?: ""
                val quotaObj = root["quota"]?.jsonObject
                val normalUnits = quotaObj?.get("normalUnits")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val multiplier = quotaObj?.get("multiplier")?.jsonPrimitive?.floatOrNull ?: 1.0f
                val chargedUnits = quotaObj?.get("chargedUnits")?.jsonPrimitive?.floatOrNull ?: multiplier * normalUnits

                val payloadObj = root["payload"]?.jsonObject
                val payload = if (payloadObj != null) {
                    TranscriptPayload.fromJsonString(payloadObj.toString())
                } else if (contentCode.isNotEmpty()) {
                    fetchTranscriptContent(contentCode, token)
                } else {
                    null
                }

                TranscriptFetchResult.Ready(
                    contentCode = contentCode,
                    artifactId = artifactId,
                    source = "generation",
                    quota = TranscriptQuotaInfo(
                        normalUnits = normalUnits,
                        multiplier = multiplier,
                        chargedUnits = chargedUnits,
                    ),
                    payload = payload,
                )
            }
            "queued", "running", "processing" -> {
                TranscriptFetchResult.Processing(
                    contentCode = contentCode,
                    jobId = jobId,
                )
            }
            "failed" -> {
                val error = root["errorCode"]?.jsonPrimitive?.content ?: "JOB_FAILED"
                TranscriptFetchResult.Failure("JOB_FAILED", error)
            }
            else -> {
                val error = root["error"]?.jsonPrimitive?.content ?: "UNKNOWN_STATUS"
                TranscriptFetchResult.Failure(error, jsonText)
            }
        }
    }
}
