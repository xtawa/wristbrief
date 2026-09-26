package ink.underflo.wristbrief.mobile.audio

import ink.underflo.wristbrief.mobile.validGatewayOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the existing gateway endpoint `POST /v1/audio/speech`.
 *
 * The endpoint takes a short text sample and returns MP3. It is deliberately limited to
 * short previews: long-form narration needs chunking, storage and its own usage policy
 * before it can be shown as a feature (see `docs/next/06-longform-tts-design.md`).
 */
interface SpeechPreviewApi {
    suspend fun generate(request: SpeechPreviewRequest): SpeechPreviewResult
}

class HttpSpeechPreviewApi(
    private val baseUrl: String,
    private val sessionTokenProvider: suspend () -> String? = { null },
) : SpeechPreviewApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(request: SpeechPreviewRequest): SpeechPreviewResult =
        withContext(Dispatchers.IO) {
            if (!request.isWithinLimit) {
                return@withContext SpeechPreviewResult.Failure(
                    code = "invalid_text",
                    httpStatus = 400,
                    message = "Text exceeds the ${SpeechPreviewRequest.MAX_CHARACTERS}-character preview limit",
                )
            }
            try {
                val gatewayOrigin = validGatewayOrigin(baseUrl)
                    ?: return@withContext SpeechPreviewResult.Failure(
                        code = "invalid_request",
                        httpStatus = null,
                        message = "Invalid gateway URL",
                    )
                val token = sessionTokenProvider()
                val conn = (URL("$gatewayOrigin/v1/audio/speech").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    if (token != null) setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "audio/mpeg, application/json")
                }

                val body = buildJsonObject { put("text", request.text) }.toString()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    return@withContext parseFailure(conn, responseCode)
                }

                val contentType = conn.contentType?.substringBefore(';')?.trim().orEmpty()
                val audio = conn.inputStream.use { readAudioLimited(it) }
                if (audio.isEmpty()) {
                    return@withContext SpeechPreviewResult.Failure(
                        code = "tts_generation_failed",
                        httpStatus = responseCode,
                        message = "The gateway returned no audio",
                    )
                }
                SpeechPreviewResult.Success(
                    audio = audio,
                    mimeType = contentType.ifBlank { "audio/mpeg" },
                )
            } catch (e: Exception) {
                SpeechPreviewResult.Failure(
                    code = "network_error",
                    httpStatus = null,
                    message = e.message ?: "Failed to reach the gateway",
                )
            }
        }

    private fun parseFailure(conn: HttpURLConnection, responseCode: Int): SpeechPreviewResult.Failure {
        val body = runCatching {
            val stream = conn.errorStream ?: conn.inputStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readTextLimited() }
        }.getOrDefault("")
        val rawCode = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        val code = SpeechPreviewErrors.normalizeUploadFailure(responseCode, rawCode)
        return SpeechPreviewResult.Failure(
            code = code,
            httpStatus = responseCode,
            message = body.take(MAX_ERROR_BODY_CHARS),
        )
    }

    private fun readAudioLimited(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) return out.toByteArray()
            if (out.size() + count > MAX_AUDIO_BYTES) throw IllegalStateException("audio_too_large")
            out.write(buffer, 0, count)
        }
    }

    private fun BufferedReader.readTextLimited(): String {
        val result = StringBuilder()
        val buffer = CharArray(4_096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return result.toString()
            if (result.length + count > MAX_ERROR_BODY_CHARS) return result.toString()
            result.append(buffer, 0, count)
        }
    }

    companion object {
        /** Bounded download: a short preview must never become an unbounded buffer. */
        const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
        const val MAX_ERROR_BODY_CHARS = 4_096
    }
}
