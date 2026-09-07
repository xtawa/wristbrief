package ink.underflo.wristbrief.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WearUiReviewTest {
    @Test
    fun loading_hasHighestPriority() {
        val state = InboxUiState(
            isLoading = true,
            isOfflineFallback = true,
            unreadCount = 8,
            errorMessage = "Network failed"
        )

        assertEquals("Refreshing…", state.wearStatusLine())
    }

    @Test
    fun offline_status_keepsUnreadCountCompact() {
        val state = InboxUiState(
            isOfflineFallback = true,
            unreadCount = 12
        )

        assertEquals("Offline · 12 unread", state.wearStatusLine())
    }

    @Test
    fun nonEmptyReadInbox_reportsCaughtUp() {
        val state = InboxUiState(
            items = listOf(
                InboxItemUi(
                    id = "id",
                    title = "一个很长的中文标题，用来覆盖 CJK 文本场景",
                    source = "来源",
                    summary = "摘要",
                    timeLabel = "",
                    isPodcast = false,
                    isRead = true
                )
            ),
            unreadCount = 0
        )

        assertEquals("All caught up", state.wearStatusLine())
    }

    @Test
    fun emptyDetail_prefersConcreteError() {
        val state = InboxUiState(
            hasSubscriptions = true,
            errorMessage = "Could not refresh feeds"
        )

        assertEquals("Could not refresh feeds", state.wearEmptyDetail())
    }
}
