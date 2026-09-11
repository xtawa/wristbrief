package ink.underflo.wristbrief.mobile

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LegacyMigrationGrantPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("legacy_migration", Context.MODE_PRIVATE)

    fun read(): String? {
        val grant = preferences.getString("grant", null) ?: return null
        return grant.takeIf(::validLegacyMigrationGrant) ?: run {
            clear()
            null
        }
    }

    fun write(grant: String) {
        require(validLegacyMigrationGrant(grant))
        preferences.edit().putString("grant", grant).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}

fun validLegacyMigrationGrant(value: String): Boolean =
    Regex("^wbm_[A-Za-z0-9_-]{43}$").matches(value)

fun googleAuthRequestJson(idToken: String, migrationGrant: String?): String = buildJsonObject {
    put("idToken", idToken)
    if (migrationGrant != null && validLegacyMigrationGrant(migrationGrant)) {
        put("migrationGrant", migrationGrant)
    }
}.toString()
