package ink.underflo.wristbrief.mobile

import android.content.Context
import android.content.SharedPreferences

internal enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

internal enum class RefreshInterval(val hours: Int) {
    ONE_HOUR(1),
    THREE_HOURS(3),
    SIX_HOURS(6),
    MANUAL(0),
}

internal interface PreferencesStorage {
    fun getString(key: String, defValue: String?): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

internal class SharedPrefsStorage(private val prefs: SharedPreferences) : PreferencesStorage {
    override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

internal class AppPreferences(private val storage: PreferencesStorage) {
    constructor(context: Context) : this(
        SharedPrefsStorage(context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE))
    )

    fun getThemeMode(): AppThemeMode {
        val raw = storage.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        return runCatching { AppThemeMode.valueOf(raw) }.getOrDefault(AppThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: AppThemeMode) {
        storage.putString(KEY_THEME_MODE, mode.name)
    }

    fun getRefreshInterval(): RefreshInterval {
        val raw = storage.getString(KEY_REFRESH_INTERVAL, RefreshInterval.THREE_HOURS.name) ?: RefreshInterval.THREE_HOURS.name
        return runCatching { RefreshInterval.valueOf(raw) }.getOrDefault(RefreshInterval.THREE_HOURS)
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        storage.putString(KEY_REFRESH_INTERVAL, interval.name)
    }

    fun isWifiOnly(): Boolean {
        return storage.getBoolean(KEY_WIFI_ONLY, true)
    }

    fun setWifiOnly(enabled: Boolean) {
        storage.putBoolean(KEY_WIFI_ONLY, enabled)
    }

    fun isWearSyncEnabled(): Boolean {
        return storage.getBoolean(KEY_WEAR_SYNC, true)
    }

    fun setWearSyncEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_WEAR_SYNC, enabled)
    }

    fun isNotificationsEnabled(): Boolean {
        return storage.getBoolean(KEY_NOTIFICATIONS, true)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_NOTIFICATIONS, enabled)
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_WEAR_SYNC = "wear_sync"
        private const val KEY_NOTIFICATIONS = "notifications"
    }
}
