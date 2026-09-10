package ink.underflo.wristbrief.ai

import ink.underflo.wristbrief.sync.WearAccountSessionRuntime
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AiSummaryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun summarize(gatewayUrl: String, gatewayToken: String, title: String?, content: String): AiBrief {
        require(gatewayUrl.startsWith("https://")) { "Gateway must use HTTPS" }
        val scopedSession = gatewayToken.takeIf { it.isNotBlank() } ?: WearAccountSessionRuntime.currentToken()
        require(!scopedSession.isNullOrBlank()) { "Gateway session is required" }

        val payload = JSONObject()
            .put("title", title)
            .put("content", content)
            .toString()

        val request = Request.Builder()
            .url(gatewayUrl.trimEnd('/') + "/v1/summary")
            .header("Authorization", "Bearer $scopedSession")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiSummaryException(classifyFailure(response.code, body))
                }
                if (body.isBlank()) throw AiSummaryException(AiSummaryFailure.InvalidResponse)
                return parseAiBrief(body)
            }
        } catch (error: AiSummaryException) {
            throw error
        } catch (_: IOException) {
            throw AiSummaryException(AiSummaryFailure.Network)
        }
    }

    private fun classifyFailure(status: Int, body: String): AiSummaryFailure {
        val code = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return when {
            status == 429 || code == "quota_exceeded" -> AiSummaryFailure.Quota
            status == 401 || status == 403 -> AiSummaryFailure.Unauthorized
            status == 502 || status == 503 || status == 504 ||
                code == "provider_timeout" || code == "provider_not_configured" || code == "provider_error" ->
                AiSummaryFailure.ProviderUnavailable
            else -> AiSummaryFailure.InvalidResponse
        }
    }
}
