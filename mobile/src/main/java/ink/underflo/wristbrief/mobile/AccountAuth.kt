package ink.underflo.wristbrief.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import ink.underflo.wristbrief.mobile.artifacts.TranscriptCache
import ink.underflo.wristbrief.mobile.sync.CloudSyncOutbox
import ink.underflo.wristbrief.mobile.sync.CloudSyncPreferences
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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

data class AccountSession(
    val sessionToken: String,
    val expiresAt: String,
    val user: AccountUser,
)

data class AccountUser(val id: String)

sealed interface AccountPresentation {
    data object NotConfigured : AccountPresentation
    data object SignedOut : AccountPresentation
    data class SignedIn(val userId: String, val expiresAt: String) : AccountPresentation
}

sealed interface AccountAuthResult {
    data class Success(val session: AccountSession) : AccountAuthResult
    data object SignedOut : AccountAuthResult
    data class Failure(val code: String) : AccountAuthResult
}

class AccountLocalDataCleaner(
    private val transcriptCache: TranscriptCache,
    private val cloudSyncOutbox: CloudSyncOutbox,
    private val cloudSyncPreferences: CloudSyncPreferences,
) {
    fun clear() {
        transcriptCache.clearAll()
        cloudSyncOutbox.clearAll()
        cloudSyncPreferences.resetAll()
    }
}

internal fun clearLocalDataOnAccountSwitch(
    previousUserId: String?,
    result: AccountAuthResult,
    cleaner: AccountLocalDataCleaner?,
) {
    val newUserId = (result as? AccountAuthResult.Success)?.session?.user?.id ?: return
    if (previousUserId != null && previousUserId != newUserId) {
        runCatching { cleaner?.clear() }
    }
}

interface AccountSessionStorage {
    fun getString(key: String): String?
    fun put(token: String, expiresAt: String, userId: String)
    fun clear()
}

class AccountSessionPreferences internal constructor(
    private val storage: AccountSessionStorage,
    private val sessionBridge: AccountSessionBridge? = null,
) {
    // Sessions are stored in the Keystore-backed secure store; a legacy plain
    // SharedPreferences session is migrated transparently on first read.
    constructor(context: Context, sessionBridge: AccountSessionBridge? = null) :
        this(ink.underflo.wristbrief.mobile.auth.MigratingAccountSessionStorage(context), sessionBridge)

    fun read(now: java.time.Instant = java.time.Instant.now()): AccountSession? {
        val token = storage.getString("token") ?: return null
        val expiresAt = storage.getString("expires_at") ?: return null
        val userId = storage.getString("user_id") ?: return null
        if (!validSessionToken(token) || !validOpaqueValue(userId, 128) || !validOpaqueValue(expiresAt, 128)) {
            clear()
            return null
        }
        val isExpired = runCatching {
            java.time.Instant.parse(expiresAt) <= now
        }.getOrDefault(false)
        if (isExpired) {
            clear()
            return null
        }
        return AccountSession(token, expiresAt, AccountUser(userId))
    }

    fun write(session: AccountSession) {
        require(validSession(session))
        storage.put(session.sessionToken, session.expiresAt, session.user.id)
    }

    fun clear() {
        storage.clear()
        sessionBridge?.clear()
    }
}

class GoogleAccountAuthClient(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val credentialManager: CredentialManager = CredentialManager.create(context),
    private val sessionBridge: AccountSessionBridge = GoogleWearAccountSessionBridge(context),
    private val sessionPreferences: AccountSessionPreferences = AccountSessionPreferences(context, sessionBridge),
    private val migrationGrantPreferences: LegacyMigrationGrantPreferences = LegacyMigrationGrantPreferences(context),
    private val localDataCleaner: AccountLocalDataCleaner? = null,
    private val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID,
    private val gatewayBaseUrl: String = BuildConfig.GATEWAY_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun storeLegacyMigrationGrant(grant: String): Boolean {
        if (!validLegacyMigrationGrant(grant)) return false
        migrationGrantPreferences.write(grant)
        return true
    }

    suspend fun prepareLegacyMigration(legacyAuthorization: String): LegacyMigrationHandoffResult =
        LegacyMigrationHandoffClient(
            httpClient = httpClient,
            grantPreferences = migrationGrantPreferences,
            gatewayBaseUrl = gatewayBaseUrl,
        ).prepare(legacyAuthorization)

    suspend fun signIn(): AccountAuthResult {
        val previousUserId = sessionPreferences.read()?.user?.id
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
            val activity = (context as? Activity) ?: (context as? ContextWrapper)?.let {
                var c: Context = it
                while (c is ContextWrapper) {
                    if (c is Activity) break
                    c = c.baseContext
                }
                c as? Activity
            }
            val targetContext = activity ?: context
            val result = credentialManager.getCredential(targetContext, credentialRequest)
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return AccountAuthResult.Failure("unsupported_google_credential")
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: Exception) {
            Log.w("GoogleAccountAuth", "Google sign in error: ${e.javaClass.simpleName} - ${e.message}", e)
            if (e is GetCredentialCancellationException ||
                e.javaClass.name.contains("Cancellation") ||
                e.message?.contains("cancel", ignoreCase = true) == true
            ) {
                return AccountAuthResult.Failure("cancelled")
            }
            return AccountAuthResult.Failure("google_sign_in_failed")
        }

        // The Google ID token stays in memory; a one-time migration grant is app-private and cleared after success.
        val result = exchangeIdToken(config.gatewayBaseUrl, idToken)
        clearLocalDataOnAccountSwitch(previousUserId, result, localDataCleaner)
        return result
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
        migrationGrantPreferences.clear()
        sessionBridge.clear()
        runCatching { localDataCleaner?.clear() }
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // The WristBrief session is already cleared locally; Google chooser state is best-effort.
        }
        AccountAuthResult.SignedOut
    }

    suspend fun deleteAccount(): AccountAuthResult = withContext(Dispatchers.IO) {
        val session = sessionPreferences.read() ?: return@withContext AccountAuthResult.SignedOut
        val config = accountAuthConfig(webClientId, gatewayBaseUrl)
            ?: return@withContext AccountAuthResult.Failure("auth_not_configured")
        val request = Request.Builder()
            .url(config.gatewayBaseUrl + "/v1/auth/delete")
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AccountAuthResult.Failure("account_delete_failed")
                }
            }
        } catch (_: Exception) {
            return@withContext AccountAuthResult.Failure("account_delete_network_error")
        }
        sessionPreferences.clear()
        migrationGrantPreferences.clear()
        sessionBridge.clear()
        runCatching { localDataCleaner?.clear() }
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
        AccountAuthResult.SignedOut
    }

    private suspend fun exchangeIdToken(baseUrl: String, idToken: String): AccountAuthResult = withContext(Dispatchers.IO) {
        if (idToken.isBlank() || idToken.length > 16_384) return@withContext AccountAuthResult.Failure("invalid_google_identity")
        val migrationGrant = migrationGrantPreferences.read()
        val body = googleAuthRequestJson(idToken, migrationGrant)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl + "/v1/auth/google")
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext AccountAuthResult.Failure(parseAuthError(responseBody) ?: "auth_failed")
                }
                val session = parseAccountSession(responseBody)
                    ?: return@withContext AccountAuthResult.Failure("invalid_auth_response")
                sessionPreferences.write(session)
                if (migrationGrant != null) migrationGrantPreferences.clear()
                sessionBridge.publish(session)
                AccountAuthResult.Success(session)
            }
        } catch (_: Exception) {
            AccountAuthResult.Failure("auth_network_error")
        }
    }

    private fun parseAuthError(responseBody: String): String? = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun parseAccountSession(responseBody: String): AccountSession? = runCatching {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val token = root["sessionToken"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val expiresAt = root["expiresAt"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val userId = root["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        AccountSession(token, expiresAt, AccountUser(userId)).takeIf(::validSession)
    }.getOrNull()
}

data class AccountAuthConfig(val webClientId: String, val gatewayBaseUrl: String)

fun accountAuthConfig(webClientId: String, gatewayBaseUrl: String): AccountAuthConfig? {
    val clientId = webClientId.trim()
    val baseUrl = validGatewayOrigin(gatewayBaseUrl) ?: return null
    if (clientId.isEmpty() || clientId.length > 512 || !clientId.endsWith(".apps.googleusercontent.com")) return null
    return AccountAuthConfig(clientId, baseUrl)
}

fun accountPresentation(configured: Boolean, session: AccountSession?): AccountPresentation = when {
    !configured -> AccountPresentation.NotConfigured
    session == null -> AccountPresentation.SignedOut
    else -> AccountPresentation.SignedIn(session.user.id, session.expiresAt)
}

fun validSessionToken(value: String): Boolean = Regex("^wbs_[A-Za-z0-9_-]{43}$").matches(value)

private fun validSession(value: AccountSession): Boolean =
    validSessionToken(value.sessionToken) &&
        validOpaqueValue(value.user.id, 128) &&
        validOpaqueValue(value.expiresAt, 128)

private fun validOpaqueValue(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value == value.trim() && value.none { it.code < 0x20 || it.code == 0x7f }
