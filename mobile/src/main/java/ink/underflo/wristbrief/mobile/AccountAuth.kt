package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AccountSession(
    val sessionToken: String,
    val expiresAt: String,
    val user: AccountUser,
)

@Serializable
data class AccountUser(val id: String)

@Serializable
private data class GoogleAuthRequest(val idToken: String)

@Serializable
private data class AuthError(@SerialName("error") val code: String? = null)

sealed interface AccountAuthResult {
    data class Success(val session: AccountSession) : AccountAuthResult
    data class Failure(val code: String) : AccountAuthResult
}

class AccountSessionPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)

    fun read(): AccountSession? {
        val token = preferences.getString("token", null) ?: return null
        val expiresAt = preferences.getString("expires_at", null) ?: return null
        val userId = preferences.getString("user_id", null) ?: return null
        return if (validSessionToken(token) && validOpaqueValue(userId, 128) && validOpaqueValue(expiresAt, 128)) {
            AccountSession(token, expiresAt, AccountUser(userId))
        } else {
            clear()
            null
        }
    }

    fun write(session: AccountSession) {
        require(validSessionToken(session.sessionToken))
        require(validOpaqueValue(session.user.id, 128))
        require(validOpaqueValue(session.expiresAt, 128))
        preferences.edit()
            .putString("token", session.sessionToken)
            .putString("expires_at", session.expiresAt)
            .putString("user_id", session.user.id)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}

class GoogleAccountAuthClient(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val credentialManager: CredentialManager = CredentialManager.create(context),
    private val sessionPreferences: AccountSessionPreferences = AccountSessionPreferences(context),
    private val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID,
    private val gatewayBaseUrl: String = BuildConfig.GATEWAY_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun signIn(): AccountAuthResult {
        val config = accountAuthConfig(webClientId, gatewayBaseUrl)
            ?: return AccountAuthResult.Failure("auth_not_configured")

        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(config.webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val credentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val idToken = try {
            val result = credentialManager.getCredential(context, credentialRequest)
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return AccountAuthResult.Failure("unsupported_google_credential")
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: Exception) {
            return AccountAuthResult.Failure("google_sign_in_failed")
        }

        // The Google ID token stays in memory only long enough to exchange it for a WristBrief session.
        return exchangeIdToken(config.gatewayBaseUrl, idToken)
    }

    suspend fun signOut(): AccountAuthResult = withContext(Dispatchers.IO) {
        val session = sessionPreferences.read()
        val config = accountAuthConfig(webClientId, gatewayBaseUrl)
        if (session != null && config != null) {
            try {
                val request = Request.Builder()
                    .url(config.gatewayBaseUrl + "/v1/auth/logout")
                    .post(ByteArray(0).toRequestBody(null))
                    .header("Authorization", "Bearer ${session.sessionToken}")
                    .build()
                httpClient.newCall(request).execute().use { /* Local sign-out proceeds even if remote revoke fails. */ }
            } catch (_: Exception) {
                // Local credential removal is still required when the network is unavailable.
            }
        }
        sessionPreferences.clear()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // The WristBrief session is already cleared locally; Google chooser state is best-effort.
        }
        AccountAuthResult.Success(AccountSession("", "", AccountUser("")))
    }

    private suspend fun exchangeIdToken(baseUrl: String, idToken: String): AccountAuthResult = withContext(Dispatchers.IO) {
        if (idToken.isBlank() || idToken.length > 16_384) return@withContext AccountAuthResult.Failure("invalid_google_identity")
        val body = json.encodeToString(GoogleAuthRequest.serializer(), GoogleAuthRequest(idToken))
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl + "/v1/auth/google")
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = runCatching { json.decodeFromString(AuthError.serializer(), responseBody).code }.getOrNull()
                    return@withContext AccountAuthResult.Failure(code ?: "auth_failed")
                }
                val session = runCatching { json.decodeFromString(AccountSession.serializer(), responseBody) }.getOrNull()
                    ?: return@withContext AccountAuthResult.Failure("invalid_auth_response")
                if (!validSession(session)) return@withContext AccountAuthResult.Failure("invalid_auth_response")
                sessionPreferences.write(session)
                AccountAuthResult.Success(session)
            }
        } catch (_: Exception) {
            AccountAuthResult.Failure("auth_network_error")
        }
    }
}

data class AccountAuthConfig(val webClientId: String, val gatewayBaseUrl: String)

fun accountAuthConfig(webClientId: String, gatewayBaseUrl: String): AccountAuthConfig? {
    val clientId = webClientId.trim()
    val baseUrl = gatewayBaseUrl.trim().trimEnd('/')
    if (clientId.isEmpty() || clientId.length > 512 || !clientId.endsWith(".apps.googleusercontent.com")) return null
    val parsed = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
    if (parsed.path != null && parsed.path.isNotEmpty() && parsed.path != "/") return null
    return AccountAuthConfig(clientId, baseUrl)
}

fun validSessionToken(value: String): Boolean = Regex("^wbs_[A-Za-z0-9_-]{43}$").matches(value)

private fun validSession(value: AccountSession): Boolean =
    validSessionToken(value.sessionToken) &&
        validOpaqueValue(value.user.id, 128) &&
        validOpaqueValue(value.expiresAt, 128)

private fun validOpaqueValue(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value == value.trim() && value.none { it.code < 0x20 || it.code == 0x7f }
