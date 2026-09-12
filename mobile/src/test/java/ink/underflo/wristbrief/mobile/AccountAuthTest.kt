package ink.underflo.wristbrief.mobile

import ink.underflo.wristbrief.mobile.artifacts.TranscriptCache
import ink.underflo.wristbrief.mobile.artifacts.TranscriptPayload
import ink.underflo.wristbrief.mobile.sync.CloudSyncOutbox
import ink.underflo.wristbrief.mobile.sync.CloudSyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAuthTest {
    @Test
    fun localDataCleanerClearsTranscriptCacheOutboxAndSyncPreferences() {
        var transcriptCleared = false
        var outboxCleared = false
        var preferencesCleared = false
        val transcriptCache = object : TranscriptCache {
            override fun get(contentCode: String): TranscriptPayload? = null
            override fun put(contentCode: String, artifactId: String, payload: TranscriptPayload) {}
            override fun remove(contentCode: String) {}
            override fun clearAll() { transcriptCleared = true }
        }
        val outbox = object : CloudSyncOutbox {
            override fun enqueue(entityType: ink.underflo.wristbrief.mobile.sync.SyncEntityType, entityId: String, payloadJson: String, updatedAtEpochMs: Long, isDeleted: Boolean) {}
            override fun getPending(limit: Int, nowEpochMs: Long) = emptyList<ink.underflo.wristbrief.mobile.sync.OutboxMutation>()
            override fun remove(ids: List<String>) {}
            override fun count(): Int = 0
            override fun clearAll() { outboxCleared = true }
        }
        val preferences = object : CloudSyncPreferences {
            override fun getOrCreateDeviceId(): String = "device"
            override fun getCursor(deviceId: String): Long = 0
            override fun saveCursor(deviceId: String, cursor: Long) {}
            override fun resetAll() { preferencesCleared = true }
        }

        AccountLocalDataCleaner(transcriptCache, outbox, preferences).clear()

        assertTrue(transcriptCleared)
        assertTrue(outboxCleared)
        assertTrue(preferencesCleared)
    }

    @Test
    fun accountSwitchClearsLocalDataOnlyAfterSuccessfulDifferentUserLogin() {
        var clearCount = 0
        val cleaner = AccountLocalDataCleaner(
            transcriptCache = object : TranscriptCache {
                override fun get(contentCode: String): TranscriptPayload? = null
                override fun put(contentCode: String, artifactId: String, payload: TranscriptPayload) {}
                override fun remove(contentCode: String) {}
                override fun clearAll() { clearCount++ }
            },
            cloudSyncOutbox = object : CloudSyncOutbox {
                override fun enqueue(entityType: ink.underflo.wristbrief.mobile.sync.SyncEntityType, entityId: String, payloadJson: String, updatedAtEpochMs: Long, isDeleted: Boolean) {}
                override fun getPending(limit: Int, nowEpochMs: Long) = emptyList<ink.underflo.wristbrief.mobile.sync.OutboxMutation>()
                override fun remove(ids: List<String>) {}
                override fun count(): Int = 0
            },
            cloudSyncPreferences = object : CloudSyncPreferences {
                override fun getOrCreateDeviceId(): String = "device"
                override fun getCursor(deviceId: String): Long = 0
                override fun saveCursor(deviceId: String, cursor: Long) {}
                override fun resetAll() {}
            },
        )
        fun success(userId: String) = AccountAuthResult.Success(
            AccountSession("wbs_${"A".repeat(43)}", "2026-10-01T00:00:00Z", AccountUser(userId))
        )

        clearLocalDataOnAccountSwitch("user-a", success("user-a"), cleaner)
        clearLocalDataOnAccountSwitch("user-a", AccountAuthResult.Failure("auth_failed"), cleaner)
        clearLocalDataOnAccountSwitch(null, success("user-b"), cleaner)
        assertEquals(0, clearCount)

        clearLocalDataOnAccountSwitch("user-a", success("user-b"), cleaner)
        assertEquals(1, clearCount)
    }

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

    @Test
    fun sessionPreferencesAutoClearsAndNotifiesBridgeWhenExpired() {
        val memoryStorage = object : AccountSessionStorage {
            val map = mutableMapOf<String, String>()
            override fun getString(key: String): String? = map[key]
            override fun put(token: String, expiresAt: String, userId: String) {
                map["token"] = token
                map["expires_at"] = expiresAt
                map["user_id"] = userId
            }
            override fun clear() { map.clear() }
        }

        var bridgeClearCalled = 0
        val mockBridge = object : AccountSessionBridge {
            override fun publish(session: AccountSession) {}
            override fun clear() { bridgeClearCalled++ }
        }

        val preferences = AccountSessionPreferences(memoryStorage, mockBridge)
        val validToken = "wbs_${"A".repeat(43)}"
        val session = AccountSession(validToken, "2026-09-10T12:00:00Z", AccountUser("usr_test"))
        preferences.write(session)

        // Read before expiry -> returns session
        val before = preferences.read(java.time.Instant.parse("2026-09-10T11:59:59Z"))
        assertEquals(session, before)
        assertEquals(0, bridgeClearCalled)

        // Read at/after expiry -> auto-clears and notifies bridge
        val after = preferences.read(java.time.Instant.parse("2026-09-10T12:00:01Z"))
        assertNull(after)
        assertEquals(1, bridgeClearCalled)
        assertNull(memoryStorage.getString("token"))
    }
}
