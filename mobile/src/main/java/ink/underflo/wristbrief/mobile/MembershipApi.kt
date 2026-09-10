package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface MembershipRestoreResult {
    data class Success(val restoredCount: Int, val plan: String?) : MembershipRestoreResult
    data object SignedOut : MembershipRestoreResult
    data class Failure(val code: String) : MembershipRestoreResult
}

data class ServerMembershipSnapshot(
    val userId: String,
    val plan: String,
    val source: String,
    val expiresAt: String?,
    val managedAiLimit: Int?,
    val managedAiUsed: Int,
    val managedAiRemaining: Int?,
)

sealed interface MembershipSnapshotResult {
    data class Success(val snapshot: ServerMembershipSnapshot) : MembershipSnapshotResult
    data object SignedOut : MembershipSnapshotResult
    data class Failure(val code: String) : MembershipSnapshotResult
}

class MembershipApiClient(
    private val sessionProvider: () -> AccountSession?,
    private val gatewayBaseUrl: String,
    private val packageName: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun loadMembership(): MembershipSnapshotResult = withContext(Dispatchers.IO) {
        val session = sessionProvider() ?: return@withContext MembershipSnapshotResult.SignedOut
        val request = buildMembershipSnapshotRequest(gatewayBaseUrl, session.sessionToken)
            ?: return@withContext MembershipSnapshotResult.Failure("membership_not_configured")
        try {
            httpClient.newCall(request).execute().use { response ->
                parseMembershipSnapshotResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            MembershipSnapshotResult.Failure("membership_network_error")
        }
    }

    suspend fun restorePurchases(purchases: List<BillingPurchase>): MembershipRestoreResult = withContext(Dispatchers.IO) {
        val session = sessionProvider() ?: return@withContext MembershipRestoreResult.SignedOut
        val baseUrl = validatedGatewayBaseUrl(gatewayBaseUrl)
            ?: return@withContext MembershipRestoreResult.Failure("membership_not_configured")
        val completed = purchases.filterNot(BillingPurchase::pending)
        if (completed.isEmpty()) return@withContext MembershipRestoreResult.Failure("no_completed_purchases")

        var restored = 0
        var latestPlan: String? = null
        for (purchase in completed) {
            val productId = purchase.productIds.singleOrNull()?.trim().orEmpty()
            if (productId.isEmpty() || purchase.purchaseToken.isBlank()) {
                return@withContext MembershipRestoreResult.Failure("invalid_purchase")
            }
            val request = buildMembershipRestoreRequest(
                baseUrl = baseUrl,
                sessionToken = session.sessionToken,
                packageName = packageName,
                productId = productId,
                purchaseToken = purchase.purchaseToken,
            ) ?: return@withContext MembershipRestoreResult.Failure("invalid_purchase")

            val result = try {
                httpClient.newCall(request).execute().use { response ->
                    parseMembershipRestoreResponse(response.code, response.body?.string().orEmpty())
                }
            } catch (_: Exception) {
                MembershipRestoreResult.Failure("membership_network_error")
            }

            when (result) {
                is MembershipRestoreResult.Success -> {
                    restored += result.restoredCount
                    latestPlan = result.plan ?: latestPlan
                }
                MembershipRestoreResult.SignedOut -> return@withContext result
                is MembershipRestoreResult.Failure -> return@withContext result
            }
        }
        MembershipRestoreResult.Success(restored, latestPlan)
    }
}

internal fun buildMembershipSnapshotRequest(baseUrl: String, sessionToken: String): Request? {
    val validated = validatedGatewayBaseUrl(baseUrl) ?: return null
    if (!validSessionToken(sessionToken)) return null
    return Request.Builder()
        .url(validated + "/v1/me")
        .get()
        .header("Authorization", "Bearer $sessionToken")
        .build()
}

internal fun parseMembershipSnapshotResponse(status: Int, body: String): MembershipSnapshotResult {
    if (status == 401) return MembershipSnapshotResult.SignedOut
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    if (status !in 200..299) {
        val error = root?.get("error")?.jsonPrimitive?.contentOrNull
            ?.takeIf { Regex("^[a-z0-9_]{1,64}$").matches(it) }
        return MembershipSnapshotResult.Failure(error ?: "membership_load_failed")
    }
    val userId = root?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        ?.takeIf { validDisplayValue(it, 128) }
        ?: return MembershipSnapshotResult.Failure("invalid_membership_response")
    val entitlement = root["entitlement"]?.jsonObject
        ?: return MembershipSnapshotResult.Failure("invalid_membership_response")
    val plan = entitlement["plan"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it == "FREE" || it == "PRO" }
        ?: return MembershipSnapshotResult.Failure("invalid_membership_response")
    val source = entitlement["source"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { validDisplayValue(it, 32) }
        ?: return MembershipSnapshotResult.Failure("invalid_membership_response")
    val expiresAt = entitlement["expiresAt"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { validDisplayValue(it, 128) }
    val quota = root["managedAiQuota"]?.jsonObject
        ?: return MembershipSnapshotResult.Failure("invalid_membership_response")
    val used = quota["used"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: return MembershipSnapshotResult.Failure("invalid_membership_response")
    val limit = quota["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 }
    val remaining = quota["remaining"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 }
    if (limit != null && used > limit) return MembershipSnapshotResult.Failure("invalid_membership_response")
    if (limit != null && remaining != (limit - used).coerceAtLeast(0)) return MembershipSnapshotResult.Failure("invalid_membership_response")
    if (limit == null && remaining != null) return MembershipSnapshotResult.Failure("invalid_membership_response")
    return MembershipSnapshotResult.Success(
        ServerMembershipSnapshot(userId, plan, source, expiresAt, limit, used, remaining),
    )
}

internal fun buildMembershipRestoreRequest(
    baseUrl: String,
    sessionToken: String,
    packageName: String,
    productId: String,
    purchaseToken: String,
): Request? {
    if (validatedGatewayBaseUrl(baseUrl) == null || !validSessionToken(sessionToken)) return null
    if (!validRestoreValue(packageName, 200) || !validRestoreValue(productId, 200)) return null
    if (purchaseToken.isBlank() || purchaseToken.length > 4096 || purchaseToken != purchaseToken.trim()) return null
    val body = buildJsonObject {
        put("packageName", packageName)
        put("productId", productId)
        put("purchaseToken", purchaseToken)
    }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    return Request.Builder()
        .url(baseUrl.trim().trimEnd('/') + "/v1/billing/restore")
        .post(body)
        .header("Authorization", "Bearer $sessionToken")
        .build()
}

internal fun parseMembershipRestoreResponse(status: Int, body: String): MembershipRestoreResult {
    if (status == 401) return MembershipRestoreResult.SignedOut
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    if (status !in 200..299) {
        val error = root?.get("error")?.jsonPrimitive?.contentOrNull
            ?.takeIf { Regex("^[a-z0-9_]{1,64}$").matches(it) }
        return MembershipRestoreResult.Failure(error ?: "membership_restore_failed")
    }
    val plan = root?.get("entitlement")?.jsonObject?.get("plan")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it == "FREE" || it == "PRO" }
        ?: return MembershipRestoreResult.Failure("invalid_membership_response")
    return MembershipRestoreResult.Success(restoredCount = 1, plan = plan)
}

fun validatedGatewayBaseUrl(raw: String): String? {
    val baseUrl = raw.trim().trimEnd('/')
    val parsed = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
    if (parsed.path != null && parsed.path.isNotEmpty() && parsed.path != "/") return null
    return baseUrl
}

private fun validRestoreValue(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value == value.trim() && value.none { it.code < 0x20 || it.code == 0x7f }

private fun validDisplayValue(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value == value.trim() && value.none { it.code < 0x20 || it.code == 0x7f }
