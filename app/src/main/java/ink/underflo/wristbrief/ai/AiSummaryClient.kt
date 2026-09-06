package ink.underflo.wristbrief.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AiSummaryClient(private val client: OkHttpClient = OkHttpClient()) {
    fun summarize(gatewayUrl: String, gatewayToken: String, title: String?, content: String): String {
        require(gatewayUrl.startsWith("https://")) { "Gateway must use HTTPS" }
        require(gatewayToken.isNotBlank()) { "Gateway token is required" }

        val payload = JSONObject()
            .put("title", title)
            .put("content", content)
            .toString()

        val request = Request.Builder()
            .url(gatewayUrl.trimEnd('/') + "/v1/summary")
            .header("Authorization", "Bearer $gatewayToken")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Summary request failed: ${response.code}" }
            val body = response.body?.string() ?: error("Empty summary response")
            return JSONObject(body).getString("summary")
        }
    }
}
