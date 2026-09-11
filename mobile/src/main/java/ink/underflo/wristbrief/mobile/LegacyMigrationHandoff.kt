package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface LegacyMigrationHandoffResult {
    data object Ready : LegacyMigrationHandoffResult
    data class Failure(val code: String) : LegacyMigrationHandoffResult
}

class LegacyMigrationHandoffClient(
    private val httpClient: OkHttpClient,
    private val grantPreferences: LegacyMigrationGrantPreferences,
    private val gatewayBaseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(legacyAuthorization: String): LegacyMigrationHandoffResult = withContext(Dispatchers.IO) {
        val baseUrl = validGatewayOrigin(gatewayBaseUrl)
            ?: return@withContext LegacyMigrationHandoffResult.Failure("auth_not_configured")
        if (!validLegacyAuthorization(legacyAuthorization)) {
            return@withContext LegacyMigrationHandoffResult.Failure("legacy_auth_required")
        }

        val request = Request.Builder()
            .url(baseUrl + "/v1/auth/legacy-migration-grant")
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", legacyAuthorization)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LegacyMigrationHandoffResult.Failure(
                        parseError(responseBody) ?: "legacy_migration_failed",
                    )
                }
                val grant = parseMigrationGrantResponse(responseBody)
                    ?: return@withContext LegacyMigrationHandoffResult.Failure("invalid_migration_response")
                grantPreferences.write(grant)
                LegacyMigrationHandoffResult.Ready
            }
        } catch (_: Exception) {
            LegacyMigrationHandoffResult.Failure("auth_network_error")
        }
    }

    private fun parseError(responseBody: String): String? = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

fun validLegacyAuthorization(value: String): Boolean {
    if (!value.startsWith("Bearer ") || value.length !in 8..16_384) return false
    if (value != value.trim() || value.any { it.code < 0x20 || it.code == 0x7f }) return false
    return value.removePrefix("Bearer ").isNotBlank()
}

fun parseMigrationGrantResponse(responseBody: String): String? = runCatching {
    val grant = Json.parseToJsonElement(responseBody).jsonObject["migrationGrant"]
        ?.jsonPrimitive?.contentOrNull
        ?: return@runCatching null
    grant.takeIf(::validLegacyMigrationGrant)
}.getOrNull()

fun validGatewayOrigin(value: String): String? {
    val baseUrl = value.trim().trimEnd('/')
    val parsed = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return null
    if (parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
    if (parsed.path != null && parsed.path.isNotEmpty() && parsed.path != "/") return null
    return baseUrl
}
