package ink.underflo.wristbrief.mobile

import android.content.Context

/**
 * Single read point for the "Wear OS synchronization" preference. Every phone→watch
 * Data Layer publisher (subscriptions, item read/saved state, playback progress)
 * consults this before putting a data item, so the Settings toggle is real rather
 * than a persisted flag nothing reads. Watch→phone receivers are unaffected.
 */
fun interface WearSyncGate {
    fun isEnabled(): Boolean

    companion object {
        val AlwaysOn: WearSyncGate = WearSyncGate { true }

        fun fromPreferences(context: Context): WearSyncGate {
            val preferences = AppPreferences(context.applicationContext)
            return WearSyncGate { preferences.isWearSyncEnabled() }
        }
    }
}
