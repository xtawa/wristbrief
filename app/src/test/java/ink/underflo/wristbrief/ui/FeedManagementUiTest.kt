package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.data.FeedSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedManagementUiTest {
    @Test
    fun enabledFeed_hasPauseAction() {
        val ui = FeedSubscription(
            id = "android",
            title = "Android Developers",
            url = "https://example.com/feed.xml",
            enabled = true
        ).toFeedManagementItemUi()

        assertTrue(ui.enabled)
        assertEquals("Enabled", ui.statusLabel)
        assertEquals("Pause", ui.toggleLabel)
        assertEquals("Android Developers", ui.title)
    }

    @Test
    fun disabledFeed_hasEnableActionAndBlankTitleFallsBackToUrl() {
        val ui = FeedSubscription(
            id = "paused",
            title = "",
            url = "https://example.com/paused.xml",
            enabled = false
        ).toFeedManagementItemUi()

        assertFalse(ui.enabled)
        assertEquals("Paused", ui.statusLabel)
        assertEquals("Enable", ui.toggleLabel)
        assertEquals("https://example.com/paused.xml", ui.title)
    }

    @Test
    fun list_placesEnabledFeedsFirstThenSortsByTitle() {
        val items = listOf(
            FeedSubscription("3", "Zulu", "https://example.com/z", enabled = false),
            FeedSubscription("2", "Beta", "https://example.com/b", enabled = true),
            FeedSubscription("1", "Alpha", "https://example.com/a", enabled = true)
        ).toFeedManagementItemsUi()

        assertEquals(listOf("Alpha", "Beta", "Zulu"), items.map { it.title })
        assertEquals(listOf(true, true, false), items.map { it.enabled })
    }
}
