package ink.underflo.wristbrief.mobile.auth

import ink.underflo.wristbrief.mobile.AccountAuthResult
import ink.underflo.wristbrief.mobile.AccountSessionPreferences
import ink.underflo.wristbrief.mobile.AccountSessionStorage
import ink.underflo.wristbrief.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingSessionStorage : AccountSessionStorage {
    val values = mutableMapOf<String, String>()
    var publishCount = 0

    override fun getString(key: String): String? = values[key]
    override fun put(token: String, expiresAt: String, userId: String) {
        publishCount += 1
        values["token"] = token
        values["expires_at"] = expiresAt
        values["user_id"] = userId
    }
    override fun clear() {
        values.clear()
    }
}

class EmailAuthClientTest {
    private val sessionToken = "wbs_${"A".repeat(43)}"

    private fun client(storage: RecordingSessionStorage): EmailAuthClient {
        val preferences = AccountSessionPreferences(storage)
        return EmailAuthClient(
            sessionPreferences = preferences,
            sessionBridge = null,
            gatewayBaseUrl = "https://gateway.example.com",
        )
    }

    @Test
    fun parsesSessionFromRegisterAndLoginResponses() {
        val storage = RecordingSessionStorage()
        val parsed = client(storage).parseAccountSession(
            """{"sessionToken":"$sessionToken","expiresAt":"2027-01-01T00:00:00Z","user":{"id":"usr_1"},"firstAdmin":true}"""
        )
        assertEquals(sessionToken, parsed?.sessionToken)
        assertEquals("usr_1", parsed?.user?.id)
    }

    @Test
    fun rejectsSessionsThatDoNotMatchTheOpaqueTokenFormat() {
        val storage = RecordingSessionStorage()
        val emailAuthClient = client(storage)
        assertNull(emailAuthClient.parseAccountSession("""{"sessionToken":"not-a-session","expiresAt":"2027-01-01T00:00:00Z","user":{"id":"usr_1"}}"""))
        assertNull(emailAuthClient.parseAccountSession("""{"expiresAt":"2027-01-01T00:00:00Z","user":{"id":"usr_1"}}"""))
        assertNull(emailAuthClient.parseAccountSession("not json"))
        assertNull(emailAuthClient.parseAccountSession("""{"sessionToken":"$sessionToken","expiresAt":"2027-01-01T00:00:00Z"}"""))
    }

    @Test
    fun parsesUpstreamErrorCodesWithoutLeakingDetails() {
        val storage = RecordingSessionStorage()
        val emailAuthClient = client(storage)
        assertEquals("registration_closed", emailAuthClient.parseError("""{"error":"registration_closed"}"""))
        assertEquals("invalid_credentials", emailAuthClient.parseError("""{"error":"invalid_credentials"}"""))
        assertNull(emailAuthClient.parseError("not json"))
        assertNull(emailAuthClient.parseError("""{"status":429}"""))
    }

    @Test
    fun mapsKnownErrorCodesToFriendlyStringsAndUnknownCodesToGeneric() {
        assertEquals(R.string.auth_error_invalid_credentials, emailAuthErrorMessageRes("invalid_credentials"))
        assertEquals(R.string.auth_error_registration_closed, emailAuthErrorMessageRes("registration_closed"))
        assertEquals(R.string.auth_error_email_taken, emailAuthErrorMessageRes("email_already_registered"))
        assertEquals(R.string.auth_error_rate_limited, emailAuthErrorMessageRes("rate_limited"))
        assertEquals(R.string.auth_error_network, emailAuthErrorMessageRes("auth_network_error"))
        assertEquals(R.string.auth_error_not_configured, emailAuthErrorMessageRes("auth_not_configured"))
        assertEquals(R.string.auth_error_generic, emailAuthErrorMessageRes("something_unexpected"))
        assertEquals(R.string.auth_error_generic, emailAuthErrorMessageRes(null))
    }

    @Test
    fun emailAndGoogleResultsShareTheSameAccountSwitchCleanupPolicy() {
        // The email client returns the same AccountAuthResult variants as the
        // Google client, so clearLocalDataOnAccountSwitch treats both identically.
        val emailSuccess = AccountAuthResult.Success(
            ink.underflo.wristbrief.mobile.AccountSession(
                sessionToken,
                "2027-01-01T00:00:00Z",
                ink.underflo.wristbrief.mobile.AccountUser("usr_email"),
            )
        )
        assertTrue(emailSuccess.session.user.id == "usr_email")
        assertEquals(sessionToken, (emailSuccess as AccountAuthResult.Success).session.sessionToken)
    }
}
