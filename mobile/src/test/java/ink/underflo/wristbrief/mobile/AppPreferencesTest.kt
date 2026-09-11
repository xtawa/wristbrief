package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryPreferencesStorage : PreferencesStorage {
    private val strings = mutableMapOf<String, String>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getString(key: String, defValue: String?): String? = strings[key] ?: defValue
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = booleans[key] ?: defValue
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
}

class AppPreferencesTest {
    private lateinit var storage: InMemoryPreferencesStorage
    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        storage = InMemoryPreferencesStorage()
        preferences = AppPreferences(storage)
    }

    @Test
    fun defaultPreferencesMatchExpectedOutOfTheBoxBehavior() {
        assertEquals(AppThemeMode.SYSTEM, preferences.getThemeMode())
        assertEquals(RefreshInterval.THREE_HOURS, preferences.getRefreshInterval())
        assertTrue(preferences.isWifiOnly())
        assertTrue(preferences.isWearSyncEnabled())
        assertTrue(preferences.isNotificationsEnabled())
    }

    @Test
    fun themeModeUpdatesAndPersists() {
        preferences.setThemeMode(AppThemeMode.DARK)
        assertEquals(AppThemeMode.DARK, preferences.getThemeMode())

        preferences.setThemeMode(AppThemeMode.LIGHT)
        assertEquals(AppThemeMode.LIGHT, preferences.getThemeMode())
    }

    @Test
    fun refreshIntervalUpdatesAndPersists() {
        preferences.setRefreshInterval(RefreshInterval.ONE_HOUR)
        assertEquals(RefreshInterval.ONE_HOUR, preferences.getRefreshInterval())

        preferences.setRefreshInterval(RefreshInterval.MANUAL)
        assertEquals(RefreshInterval.MANUAL, preferences.getRefreshInterval())
    }

    @Test
    fun booleanTogglesUpdateAndPersist() {
        preferences.setWifiOnly(false)
        assertFalse(preferences.isWifiOnly())

        preferences.setWearSyncEnabled(false)
        assertFalse(preferences.isWearSyncEnabled())

        preferences.setNotificationsEnabled(false)
        assertFalse(preferences.isNotificationsEnabled())
    }
}
