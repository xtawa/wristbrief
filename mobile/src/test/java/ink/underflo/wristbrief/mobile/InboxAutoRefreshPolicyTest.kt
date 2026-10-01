package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxAutoRefreshPolicyTest {
    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun `manual never auto refreshes even without a previous refresh`() {
        assertFalse(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.MANUAL, null, now))
        assertFalse(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.MANUAL, now - 100 * hour, now))
    }

    @Test
    fun `first launch with an interval refreshes immediately`() {
        assertTrue(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.THREE_HOURS, null, now))
    }

    @Test
    fun `a last refresh in the future (clock rollback) counts as stale`() {
        assertTrue(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.SIX_HOURS, now + hour, now))
        assertFalse("manual still never refreshes", InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.MANUAL, now + hour, now))
    }

    @Test
    fun `refresh is due only once the interval has elapsed`() {
        assertFalse(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.THREE_HOURS, now - 2 * hour, now))
        assertTrue(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.THREE_HOURS, now - 3 * hour, now))
        assertFalse(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.ONE_HOUR, now - 59 * 60 * 1000L, now))
        assertTrue(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.ONE_HOUR, now - hour, now))
        assertFalse(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.SIX_HOURS, now - 5 * hour, now))
        assertTrue(InboxAutoRefreshPolicy.shouldRefresh(RefreshInterval.SIX_HOURS, now - 7 * hour, now))
    }
}
