package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMigrationHandoffTest {
    @Test
    fun validatesRuntimeLegacyAuthorizationWithoutAssumingItsSecretFormat() {
        assertTrue(validLegacyAuthorization("Bearer legacy-secret"))
        assertTrue(validLegacyAuthorization("Bearer token with spaces"))
        assertFalse(validLegacyAuthorization("legacy-secret"))
        assertFalse(validLegacyAuthorization("Bearer "))
        assertFalse(validLegacyAuthorization("Bearer secret\nnext"))
    }

    @Test
    fun parsesOnlyOpaqueMigrationGrantResponses() {
        val valid = "wbm_${"A".repeat(43)}"
        assertEquals(valid, parseMigrationGrantResponse("{\"migrationGrant\":\"$valid\"}"))
        assertNull(parseMigrationGrantResponse("{\"migrationGrant\":\"bad\"}"))
        assertNull(parseMigrationGrantResponse("{\"error\":\"legacy_auth_required\"}"))
        assertNull(parseMigrationGrantResponse("not-json"))
    }

    @Test
    fun gatewayOriginIsStrictHttpsOriginOnly() {
        assertEquals("https://gateway.example.com", validGatewayOrigin("https://gateway.example.com/"))
        assertNull(validGatewayOrigin("http://gateway.example.com"))
        assertNull(validGatewayOrigin("https://user@gateway.example.com"))
        assertNull(validGatewayOrigin("https://gateway.example.com/api"))
        assertNull(validGatewayOrigin("https://gateway.example.com?next=x"))
    }
}
