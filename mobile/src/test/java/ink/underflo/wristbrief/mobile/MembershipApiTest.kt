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
