package ink.underflo.wristbrief.mobile.auth

import ink.underflo.wristbrief.mobile.AccountAuthResult
import ink.underflo.wristbrief.mobile.AccountLocalDataCleaner
import ink.underflo.wristbrief.mobile.AccountSession
import ink.underflo.wristbrief.mobile.AccountSessionBridge
import ink.underflo.wristbrief.mobile.AccountSessionPreferences
import ink.underflo.wristbrief.mobile.AccountUser
import ink.underflo.wristbrief.mobile.BuildConfig
import ink.underflo.wristbrief.mobile.clearLocalDataOnAccountSwitch
import ink.underflo.wristbrief.mobile.validGatewayOrigin
import ink.underflo.wristbrief.mobile.validSessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Outcome for flows that do not produce a session (verify, forgot, reset, linking). */
sealed interface EmailFlowResult {
    data object Success : EmailFlowResult
    data class Failure(val code: String) : EmailFlowResult
}

/**
 * Email/password authentication against the gateway's email auth endpoints
 * (under /v1/auth/email). Register and login issue the same opaque `wbs_` session as Google sign-in and
 * reuse the existing session store, Wear session bridge, and account-switch
 * cleanup policy — there is deliberately no second login state in the app.
 */
class EmailAuthClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val sessionPreferences: AccountSessionPreferences,
    private val sessionBridge: AccountSessionBridge? = null,
    private val localDataCleaner: AccountLocalDataCleaner? = null,
    private val gatewayBaseUrl: String = BuildConfig.GATEWAY_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun register(email: String, password: String): AccountAuthResult =
        sessionFlow("/v1/auth/email/register", mapOf("email" to email, "password" to password))

    suspend fun login(email: String, password: String): AccountAuthResult =
        sessionFlow("/v1/auth/email/login", mapOf("email" to email, "password" to password))

    suspend fun requestVerification(email: String): EmailFlowResult =
        flow("/v1/auth/email/verify/request", mapOf("email" to email))

    suspend fun confirmVerification(token: String): EmailFlowResult =
        flow("/v1/auth/email/verify/confirm", mapOf("token" to token))

    suspend fun forgotPassword(email: String): EmailFlowResult =
        flow("/v1/auth/email/password/forgot", mapOf("email" to email))

    suspend fun resetPassword(token: String, newPassword: String): EmailFlowResult =
        flow("/v1/auth/email/password/reset", mapOf("token" to token, "password" to newPassword))

    /** Scenario A: the signed-in user adds email sign-in to the current account. */
    suspend fun linkEmailIdentity(email: String, password: String): EmailFlowResult =
        authenticatedFlow("/v1/account/identities/email", mapOf("email" to email, "password" to password))

    /** Scenario B: the signed-in user links a verified Google identity. No auto-merge by email. */
    suspend fun linkGoogleIdentity(idToken: String): EmailFlowResult =
        authenticatedFlow("/v1/account/identities/google", mapOf("idToken" to idToken))

    private suspend fun sessionFlow(path: String, payload: Map<String, String>): AccountAuthResult {
        val previousUserId = sessionPreferences.read()?.user?.id
        val result = postSession(path, payload)
        clearLocalDataOnAccountSwitch(previousUserId, result, localDataCleaner)
        return result
    }

    private suspend fun postSession(path: String, payload: Map<String, String>): AccountAuthResult = withContext(Dispatchers.IO) {
        val baseUrl = validGatewayOrigin(gatewayBaseUrl) ?: return@withContext AccountAuthResult.Failure("auth_not_configured")
        val body = buildJsonBody(payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body)
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext AccountAuthResult.Failure(parseError(responseBody) ?: "auth_failed")
                }
                val session = parseAccountSession(responseBody)
                    ?: return@withContext AccountAuthResult.Failure("invalid_auth_response")
                sessionPreferences.write(session)
                sessionBridge?.publish(session)
                AccountAuthResult.Success(session)
            }
        } catch (_: Exception) {
            AccountAuthResult.Failure("auth_network_error")
        }
    }

    private suspend fun flow(path: String, payload: Map<String, String>): EmailFlowResult = withContext(Dispatchers.IO) {
        val baseUrl = validGatewayOrigin(gatewayBaseUrl) ?: return@withContext EmailFlowResult.Failure("auth_not_configured")
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(buildJsonBody(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) return@withContext EmailFlowResult.Success
                EmailFlowResult.Failure(parseError(responseBody) ?: "auth_failed")
            }
        } catch (_: Exception) {
            EmailFlowResult.Failure("auth_network_error")
        }
    }

    private suspend fun authenticatedFlow(path: String, payload: Map<String, String>): EmailFlowResult {
        val session = sessionPreferences.read() ?: return EmailFlowResult.Failure("unauthorized")
        return withContext(Dispatchers.IO) {
            val baseUrl = validGatewayOrigin(gatewayBaseUrl) ?: return@withContext EmailFlowResult.Failure("auth_not_configured")
            val request = Request.Builder()
                .url(baseUrl + path)
                .post(buildJsonBody(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Authorization", "Bearer ${session.sessionToken}")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) return@withContext EmailFlowResult.Success
                    EmailFlowResult.Failure(parseError(responseBody) ?: "auth_failed")
                }
            } catch (_: Exception) {
                EmailFlowResult.Failure("auth_network_error")
            }
        }
    }

    private fun buildJsonBody(payload: Map<String, String>): String =
        kotlinx.serialization.json.buildJsonObject {
            for ((key, value) in payload) put(key, kotlinx.serialization.json.JsonPrimitive(value))
        }.toString()

    internal fun parseError(responseBody: String): String? = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    internal fun parseAccountSession(responseBody: String): AccountSession? = runCatching {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val token = root["sessionToken"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val expiresAt = root["expiresAt"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val userId = root["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        AccountSession(token, expiresAt, AccountUser(userId)).takeIf {
            validSessionToken(it.sessionToken) &&
                it.user.id.length <= 128 &&
                it.expiresAt.length <= 128
        }
    }.getOrNull()
}
