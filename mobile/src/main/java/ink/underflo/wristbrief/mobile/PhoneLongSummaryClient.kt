package ink.underflo.wristbrief.mobile

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val BYOK_API_KEY_HEADER = "X-WristBrief-BYOK-Key"
private const val MAX_BYOK_MODEL_CHARS = 200
private const val MAX_BYOK_API_KEY_CHARS = 4096

internal interface PhoneSummaryTransport {
    fun post(endpoint: String, bearerToken: String, jsonBody: String): String

    fun postWithHeaders(
        endpoint: String,
        bearerToken: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): String = post(endpoint, bearerToken, jsonBody)
}

internal class OkHttpPhoneSummaryTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) : PhoneSummaryTransport {
    override fun post(endpoint: String, bearerToken: String, jsonBody: String): String =
        postWithHeaders(endpoint, bearerToken, jsonBody, emptyMap())

    override fun postWithHeaders(
        endpoint: String,
        bearerToken: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): String {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearerToken")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PhoneSummaryRequestException(mapStatus(response.code))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) throw PhoneSummaryRequestException(PhoneSummaryFailure.InvalidResponse)
                return body
            }
        } catch (error: PhoneSummaryRequestException) {
            throw error
        } catch (_: IOException) {
            throw PhoneSummaryRequestException(PhoneSummaryFailure.Network)
        }
    }

    private fun mapStatus(status: Int): PhoneSummaryFailure = when (status) {
        401, 403 -> PhoneSummaryFailure.Unauthorized
        429 -> PhoneSummaryFailure.Quota
        502, 503, 504 -> PhoneSummaryFailure.ProviderUnavailable
        else -> PhoneSummaryFailure.InvalidResponse
    }
}

enum class PhoneSummaryFailure {
    Network,
    Unauthorized,
    Quota,
    ProviderUnavailable,
    InvalidResponse,
}

class PhoneSummaryRequestException(
    val failure: PhoneSummaryFailure,
) : RuntimeException("Phone summary request failed")

enum class PhoneByokProvider(val wireId: String) {
    OpenRouter("openrouter"),
    Gemini("gemini"),
}

data class PhoneByokConfig(
    val provider: PhoneByokProvider,
    val model: String,
    val apiKey: String,
)

class PhoneLongSummaryClient internal constructor(
    private val transport: PhoneSummaryTransport,
) {
    constructor() : this(OkHttpPhoneSummaryTransport())

    fun summarize(
        gatewayUrl: String,
        gatewayToken: String,
        title: String?,
        content: String,
    ): PhoneLongSummary = summarizeInternal(
        gatewayUrl = gatewayUrl,
        gatewayToken = gatewayToken,
        path = "v1/summary",
        title = title,
        content = content,
        byok = null,
    )

    fun summarizeByok(
        gatewayUrl: String,
        gatewayToken: String,
        title: String?,
        content: String,
        config: PhoneByokConfig,
    ): PhoneLongSummary = summarizeInternal(
        gatewayUrl = gatewayUrl,
        gatewayToken = gatewayToken,
        path = "v1/byok/summary",
        title = title,
        content = content,
        byok = normalizeByokConfig(config),
    )

    private fun summarizeInternal(
        gatewayUrl: String,
        gatewayToken: String,
        path: String,
        title: String?,
        content: String,
        byok: PhoneByokConfig?,
    ): PhoneLongSummary {
        val base = gatewayUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid Gateway URL")
        require(base.isHttps) { "Gateway must use HTTPS" }
        require(base.host.isNotBlank()) { "Gateway host is required" }
        require(gatewayToken.isNotBlank()) { "Gateway token is required" }
        require(content.isNotBlank()) { "Summary content is required" }

        val endpoint = base.newBuilder()
            .addPathSegments(path)
            .build()
            .toString()

        val payload = buildJsonObject {
            title?.let { put("title", it) }
            put("content", content)
            byok?.let {
                put("provider", it.provider.wireId)
                put("model", it.model)
            }
        }.toString()

        val raw = if (byok == null) {
            transport.post(endpoint, gatewayToken, payload)
        } else {
            transport.postWithHeaders(
                endpoint = endpoint,
                bearerToken = gatewayToken,
                jsonBody = payload,
                headers = mapOf(BYOK_API_KEY_HEADER to byok.apiKey),
            )
        }
        return try {
            parsePhoneLongSummaryResponse(raw)
        } catch (_: Exception) {
            throw PhoneSummaryRequestException(PhoneSummaryFailure.InvalidResponse)
        }
    }

    private fun normalizeByokConfig(config: PhoneByokConfig): PhoneByokConfig {
        val model = config.model.trim()
        require(model.isNotEmpty()) { "BYOK model is required" }
        require(model.length <= MAX_BYOK_MODEL_CHARS && !model.hasControlCharacters()) {
            "Invalid BYOK model"
        }

        val apiKey = config.apiKey.trim()
        require(apiKey.isNotEmpty()) { "BYOK API key is required" }
        require(apiKey.length <= MAX_BYOK_API_KEY_CHARS && !apiKey.hasControlCharacters()) {
            "Invalid BYOK API key"
        }
        return config.copy(model = model, apiKey = apiKey)
    }
}

private fun String.hasControlCharacters(): Boolean = any { it.code in 0..31 || it.code == 127 }
