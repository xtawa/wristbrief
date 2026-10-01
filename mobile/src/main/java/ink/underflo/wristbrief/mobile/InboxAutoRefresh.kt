package ink.underflo.wristbrief.mobile

/**
 * Foreground auto-refresh policy behind the "Feed refresh frequency" preference.
 *
 * The preference used to be persisted but never read. There is still no background
 * scheduler in this app; the interval is honored when the app starts or returns to
 * the foreground: if the last completed refresh is older than the interval, the
 * inbox is refreshed once. [RefreshInterval.MANUAL] never auto-refreshes.
 */
internal object InboxAutoRefreshPolicy {
    fun shouldRefresh(interval: RefreshInterval, lastRefreshEpochMs: Long?, nowEpochMs: Long): Boolean {
        if (interval == RefreshInterval.MANUAL) return false
        if (lastRefreshEpochMs == null) return true
        // A recorded time in the future means the device clock moved backwards;
        // treat the record as unknown so the policy recovers instead of waiting.
        if (lastRefreshEpochMs > nowEpochMs) return true
        val maxAgeMs = interval.hours * 60L * 60L * 1000L
        return nowEpochMs - lastRefreshEpochMs >= maxAgeMs
    }
}
