package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAuthTest {
    @Test
    fun acceptsExactHttpsGatewayOriginAndGoogleWebClientId() {
        val config = accountAuthConfig(
            "123456789-example.apps.googleusercontent.com",
            "https://gateway.example.com/",
        )

        assertEquals(
            AccountAuthConfig(
                "123456789-example.apps.googleusercontent.com",
                "https://gateway.example.com",
            ),
            config,
        )
    }

    @Test
    fun rejectsNonHttpsOrGatewayUrlsThatCanChangeRequestDestination() {
        val clientId = "123456789-example.apps.googleusercontent.com"
        assertNull(accountAuthConfig(clientId, "http://gateway.example.com"))
        assertNull(accountAuthConfig(clientId, "https://user@gateway.example.com"))
        assertNull(accountAuthConfig(clientId, "https://gateway.example.com/api"))
        assertNull(accountAuthConfig(clientId, "https://gateway.example.com?next=https://evil.example"))
        assertNull(accountAuthConfig(clientId, "https://gateway.example.com#evil"))
        assertNull(accountAuthConfig(clientId, "javascript:alert(1)"))
    }

    @Test
    fun rejectsMissingOrNonWebGoogleClientIds() {
        val gateway = "https://gateway.example.com"
        assertNull(accountAuthConfig("", gateway))
        assertNull(accountAuthConfig("android-client-id", gateway))
        assertNull(accountAuthConfig("example.com", gateway))
    }

    @Test
    fun sessionTokenValidationMatchesServerIssuedOpaqueFormat() {
        val payload = "A".repeat(43)
        assertTrue(validSessionToken("wbs_$payload"))
        assertFalse(validSessionToken(payload))
        assertFalse(validSessionToken("wbs_${"A".repeat(42)}"))
        assertFalse(validSessionToken("wbs_${"A".repeat(44)}"))
        assertFalse(validSessionToken("wbs_${"A".repeat(42)}!"))
        assertFalse(validSessionToken("Bearer wbs_$payload"))
    }

    @Test
    fun accountPresentationRequiresConfigurationBeforeOfferingSignIn() {
        val session = AccountSession(
            sessionToken = "wbs_${"A".repeat(43)}",
            expiresAt = "2026-10-01T00:00:00Z",
            user = AccountUser("usr_123"),
        )

        assertEquals(AccountPresentation.NotConfigured, accountPresentation(false, session))
        assertEquals(AccountPresentation.SignedOut, accountPresentation(true, null))
        assertEquals(
            AccountPresentation.SignedIn("usr_123", "2026-10-01T00:00:00Z"),
            accountPresentation(true, session),
        )
    }
}
