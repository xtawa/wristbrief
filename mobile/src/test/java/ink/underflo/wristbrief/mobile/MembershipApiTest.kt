package ink.underflo.wristbrief.mobile

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipApiTest {
    private val sessionToken = "wbs_${"A".repeat(43)}"

    @Test
    fun buildsFixedGatewayMembershipSnapshotRequestWithSessionAuth() {
        val request = buildMembershipSnapshotRequest("https://gateway.example.com", sessionToken)!!
        assertEquals("https://gateway.example.com/v1/me", request.url.toString())
        assertEquals("GET", request.method)
        assertEquals("Bearer $sessionToken", request.header("Authorization"))
        assertNull(request.body)
    }

    @Test
    fun parsesAuthoritativeMembershipAndQuotaSnapshot() {
        val response = parseMembershipSnapshotResponse(
            200,
            """{"user":{"id":"usr_123"},"entitlement":{"plan":"PRO","source":"billing","expiresAt":"2026-10-01T00:00:00Z"},"managedAiQuota":{"limit":100,"used":12,"remaining":88}}""",
        )
        assertEquals(
            MembershipSnapshotResult.Success(
                ServerMembershipSnapshot("usr_123", "PRO", "billing", "2026-10-01T00:00:00Z", 100, 12, 88),
            ),
            response,
        )
        assertEquals(
            MembershipSnapshotResult.SignedOut,
            parseMembershipSnapshotResponse(401, """{"error":"unauthorized"}"""),
        )
    }

    @Test
    fun rejectsInconsistentOrClientLikeMembershipSnapshots() {
        assertEquals(
            MembershipSnapshotResult.Failure("invalid_membership_response"),
            parseMembershipSnapshotResponse(
                200,
                """{"user":{"id":"usr_123"},"entitlement":{"plan":"PRO","source":"billing"},"managedAiQuota":{"limit":10,"used":2,"remaining":99}}""",
            ),
        )
        assertEquals(
            MembershipSnapshotResult.Failure("invalid_membership_response"),
            parseMembershipSnapshotResponse(
                200,
                """{"user":{"id":"usr_123"},"entitlement":{"plan":"SUPERPRO","source":"billing"},"managedAiQuota":{"limit":null,"used":0,"remaining":null}}""",
            ),
        )
    }

    @Test
    fun buildsFixedGatewayRestoreRequestWithSessionAuth() {
        val request = buildMembershipRestoreRequest(
            baseUrl = "https://gateway.example.com",
            sessionToken = sessionToken,
            packageName = "ink.underflo.wristbrief.mobile",
            productId = "wristbrief_pro",
            purchaseToken = "purchase-secret",
        )!!

        assertEquals("https://gateway.example.com/v1/billing/restore", request.url.toString())
        assertEquals("Bearer $sessionToken", request.header("Authorization"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains("\"packageName\":\"ink.underflo.wristbrief.mobile\""))
        assertTrue(body.contains("\"productId\":\"wristbrief_pro\""))
        assertTrue(body.contains("\"purchaseToken\":\"purchase-secret\""))
    }

    @Test
    fun rejectsUnsafeRestoreDestinationsAndInvalidSessions() {
        assertNull(
            buildMembershipRestoreRequest(
                "http://gateway.example.com",
                sessionToken,
                "ink.underflo.wristbrief.mobile",
                "wristbrief_pro",
                "token",
            ),
        )
        assertNull(
            buildMembershipRestoreRequest(
                "https://gateway.example.com/redirect",
                sessionToken,
                "ink.underflo.wristbrief.mobile",
                "wristbrief_pro",
                "token",
            ),
        )
        assertNull(
            buildMembershipRestoreRequest(
                "https://gateway.example.com",
                "not-a-session",
                "ink.underflo.wristbrief.mobile",
                "wristbrief_pro",
                "token",
            ),
        )
    }

    @Test
    fun parsesOnlyBoundedServerOwnedMembershipResponses() {
        assertEquals(
            MembershipRestoreResult.Success(1, "PRO"),
            parseMembershipRestoreResponse(200, """{"entitlement":{"plan":"PRO"}}"""),
        )
        assertEquals(
            MembershipRestoreResult.SignedOut,
            parseMembershipRestoreResponse(401, """{"error":"unauthorized"}"""),
        )
        assertEquals(
            MembershipRestoreResult.Failure("purchase_already_linked"),
            parseMembershipRestoreResponse(409, """{"error":"purchase_already_linked"}"""),
        )
        val untrusted = parseMembershipRestoreResponse(500, """{"error":"purchase-secret leaked here"}""")
        assertEquals(MembershipRestoreResult.Failure("membership_restore_failed"), untrusted)
        assertFalse(untrusted.toString().contains("purchase-secret"))
    }

    @Test
    fun validatesOnlyExactHttpsGatewayOrigins() {
        assertEquals("https://gateway.example.com", validatedGatewayBaseUrl(" https://gateway.example.com/ "))
        assertNull(validatedGatewayBaseUrl("http://gateway.example.com"))
        assertNull(validatedGatewayBaseUrl("https://user@gateway.example.com"))
        assertNull(validatedGatewayBaseUrl("https://gateway.example.com/path"))
        assertNull(validatedGatewayBaseUrl("https://gateway.example.com?next=evil"))
    }
}
