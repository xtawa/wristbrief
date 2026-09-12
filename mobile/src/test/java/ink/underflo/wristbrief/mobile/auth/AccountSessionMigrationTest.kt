package ink.underflo.wristbrief.mobile.auth

import ink.underflo.wristbrief.mobile.AccountSessionStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSessionStorage : AccountSessionStorage {
    val values = mutableMapOf<String, String>()
    var cleared = false

    override fun getString(key: String): String? = values[key]
    override fun put(token: String, expiresAt: String, userId: String) {
        values["token"] = token
        values["expires_at"] = expiresAt
        values["user_id"] = userId
    }
    override fun clear() {
        values.clear()
        cleared = true
    }
}

/** A secure store that fails read-back once, simulating a broken write. */
private class FailingVerificationStorage : AccountSessionStorage {
    val values = mutableMapOf<String, String>()
    var failReadBack = true

    override fun getString(key: String): String? = if (failReadBack) null else values[key]
    override fun put(token: String, expiresAt: String, userId: String) {
        values["token"] = token
        values["expires_at"] = expiresAt
        values["user_id"] = userId
    }
    override fun clear() {
        values.clear()
    }
}

class AccountSessionMigrationTest {
    private val sessionToken = "wbs_${"A".repeat(43)}"

    @Test
    fun movesLegacySessionToSecureStorageOnlyAfterVerification() {
        val legacy = FakeSessionStorage()
        val secure = FakeSessionStorage()
        legacy.put(sessionToken, "2027-01-01T00:00:00Z", "usr_1")

        val outcome = SessionStoreMigrations.migrate(legacy, secure)

        assertEquals(SessionStoreMigrations.Outcome.Moved, outcome)
        assertEquals(sessionToken, secure.values["token"])
        assertEquals("usr_1", secure.values["user_id"])
        assertTrue(legacy.values.isEmpty())
    }

    @Test
    fun noLegacySessionIsANoOp() {
        val legacy = FakeSessionStorage()
        val secure = FakeSessionStorage()
        assertEquals(SessionStoreMigrations.Outcome.NoLegacySession, SessionStoreMigrations.migrate(legacy, secure))
        assertTrue(secure.values.isEmpty())
        assertFalse(secure.cleared)
    }

    @Test
    fun secureAlreadyPopulatedKeepsTheSecureValueAndNeverClearsIt() {
        val legacy = FakeSessionStorage()
        val secure = FakeSessionStorage()
        legacy.put(sessionToken, "2027-01-01T00:00:00Z", "usr_legacy")
        secure.put("wbs_${"B".repeat(43)}", "2027-02-01T00:00:00Z", "usr_secure")

        val outcome = SessionStoreMigrations.migrate(legacy, secure)

        assertEquals(SessionStoreMigrations.Outcome.SecureAlreadyPopulated, outcome)
        assertEquals("usr_secure", secure.values["user_id"])
        // The legacy value stays but is never read again by the composite store.
    }

    @Test
    fun verificationFailureNeverDeletesTheLegacyToken() {
        val legacy = FakeSessionStorage()
        val secure = FailingVerificationStorage()
        legacy.put(sessionToken, "2027-01-01T00:00:00Z", "usr_1")

        val outcome = SessionStoreMigrations.migrate(legacy, secure)

        assertEquals(SessionStoreMigrations.Outcome.VerificationFailed, outcome)
        assertEquals(sessionToken, legacy.values["token"])
        assertFalse(legacy.cleared)
    }

    @Test
    fun partialLegacyStateIsNotMigrated() {
        val legacy = FakeSessionStorage()
        val secure = FakeSessionStorage()
        legacy.values["token"] = sessionToken
        legacy.values["expires_at"] = "2027-01-01T00:00:00Z"
        // user_id missing
        assertEquals(SessionStoreMigrations.Outcome.NoLegacySession, SessionStoreMigrations.migrate(legacy, secure))
        assertTrue(secure.values.isEmpty())
    }

    @Test
    fun migratingStorageReadsThroughLegacyUntilFirstAccessThenPrefersSecure() {
        val legacy = FakeSessionStorage()
        val secure = FakeSessionStorage()
        legacy.put(sessionToken, "2027-01-01T00:00:00Z", "usr_1")
        val composite = MigratingAccountSessionStorage(legacy, secure)

        // First read triggers the migration transparently.
        assertEquals(sessionToken, composite.getString("token"))
        assertEquals("usr_1", composite.getString("user_id"))
        assertTrue(legacy.values.isEmpty())
        assertEquals(sessionToken, secure.values["token"])
    }

    @Test
    fun migratingStorageWriteGoesToSecureAndClearsLegacyOnlyAfterVerification() {
        val legacy = FakeSessionStorage()
        val secure = FailingVerificationStorage()
        legacy.put("wbs_${"C".repeat(43)}", "2027-01-01T00:00:00Z", "usr_old")
        val composite = MigratingAccountSessionStorage(legacy, secure)

        composite.put(sessionToken, "2027-06-01T00:00:00Z", "usr_new")

        // The read-back failed, so the legacy token must still be present.
        assertEquals("usr_old", legacy.values["user_id"])

        secure.failReadBack = false
        composite.put(sessionToken, "2027-06-01T00:00:00Z", "usr_new")
        assertNull(legacy.values["user_id"])
        assertEquals(sessionToken, secure.values["token"])
    }

    @Test
    fun clearRemovesBothStores() {
        val legacy = FakeSessionStorage()
        val secure = FakeSessionStorage()
        legacy.put(sessionToken, "2027-01-01T00:00:00Z", "usr_1")
        secure.put(sessionToken, "2027-01-01T00:00:00Z", "usr_1")
        val composite = MigratingAccountSessionStorage(legacy, secure)

        composite.clear()

        assertTrue(legacy.values.isEmpty())
        assertTrue(secure.values.isEmpty())
    }
}
