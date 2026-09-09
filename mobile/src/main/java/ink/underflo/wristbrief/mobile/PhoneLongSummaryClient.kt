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

internal interface PhoneSummaryTransport {
    fun post(endpoint: String, bearerToken: String, jsonBody: String): String
}

internal class OkHttpPhoneSummaryTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) : PhoneSummaryTransport {
    override fun post(endpoint: String, bearerToken: String, jsonBody: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearerToken")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
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

class PhoneLongSummaryClient internal constructor(
    private val transport: PhoneSummaryTransport,
) {
    constructor() : this(OkHttpPhoneSummaryTransport())

    fun summarize(
        gatewayUrl: String,
        gatewayToken: String,
        title: String?,
        content: String,
    ): PhoneLongSummary {
        val base = gatewayUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid Gateway URL")
        require(base.isHttps) { "Gateway must use HTTPS" }
        require(base.host.isNotBlank()) { "Gateway host is required" }
        require(gatewayToken.isNotBlank()) { "Gateway token is required" }
        require(content.isNotBlank()) { "Summary content is required" }

        val endpoint = base.newBuilder()
            .addPathSegments("v1/summary")
            .build()
            .toString()

        val payload = buildJsonObject {
            title?.let { put("title", it) }
            put("content", content)
        }.toString()

        val raw = transport.post(endpoint, gatewayToken, payload)
        return try {
            parsePhoneLongSummaryResponse(raw)
        } catch (_: Exception) {
            throw PhoneSummaryRequestException(PhoneSummaryFailure.InvalidResponse)
        }
    }
}
