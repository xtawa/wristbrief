package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMigrationTest {
    @Test
    fun validatesOpaqueMigrationGrantFormat() {
        assertTrue(validLegacyMigrationGrant("wbm_${"A".repeat(43)}"))
        assertFalse(validLegacyMigrationGrant("wbm_${"A".repeat(42)}"))
        assertFalse(validLegacyMigrationGrant("wbm_${"A".repeat(44)}"))
        assertFalse(validLegacyMigrationGrant("Bearer wbm_${"A".repeat(43)}"))
    }

    @Test
    fun googleAuthBodyIncludesOnlyValidMigrationGrant() {
        val valid = "wbm_${"B".repeat(43)}"
        val bodyWithGrant = googleAuthRequestJson("google-token", valid)
        assertTrue(bodyWithGrant.contains("\"idToken\":\"google-token\""))
        assertTrue(bodyWithGrant.contains("\"migrationGrant\":\"$valid\""))

        val bodyWithoutGrant = googleAuthRequestJson("google-token", "invalid")
        assertFalse(bodyWithoutGrant.contains("migrationGrant"))
    }
}
