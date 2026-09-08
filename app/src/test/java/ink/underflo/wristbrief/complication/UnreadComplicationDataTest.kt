package ink.underflo.wristbrief.complication

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnreadComplicationDataTest {
    private val feeds = listOf(
        FeedSubscription("enabled", "Enabled", "https://example.com/feed", enabled = true),
        FeedSubscription("disabled", "Disabled", "https://example.com/paused", enabled = false)
    )

    @Test
    fun snapshot_counts_only_unread_items_from_enabled_feeds() {
        val snapshot = buildUnreadComplicationSnapshot(
            subscriptions = feeds,
            cachedItems = listOf(
                item("new", "enabled", "Newest", 30),
                item("read", "enabled", "Read", 20),
                item("paused", "disabled", "Paused", 40)
            ),
            readItemIds = setOf("read")
        )

        assertEquals(1, snapshot.unreadCount)
        assertEquals("Newest", snapshot.latestTitle)
        assertEquals("1", snapshot.shortText)
        assertEquals("1 unread · Newest", snapshot.longText)
    }

    @Test
    fun snapshot_all_read_is_glanceable_and_has_no_latest_title() {
        val snapshot = buildUnreadComplicationSnapshot(
            subscriptions = feeds,
            cachedItems = listOf(item("read", "enabled", "Read", 20)),
            readItemIds = setOf("read")
        )

        assertEquals(0, snapshot.unreadCount)
        assertNull(snapshot.latestTitle)
        assertEquals("All caught up", snapshot.longText)
    }

    @Test
    fun short_text_caps_large_counts_and_title_is_compacted() {
        val items = (0 until 105).map { index ->
            item("id-$index", "enabled", "  A   very long headline that should stay glanceable  ", index.toLong())
        }
        val snapshot = buildUnreadComplicationSnapshot(feeds, items, emptySet())

        assertEquals("99+", snapshot.shortText)
        assertEquals("A very long headline that s…", snapshot.latestTitle)
    }

    private fun item(id: String, feedId: String, title: String, cachedAt: Long) = CachedFeedItem(
        id = id,
        feedId = feedId,
        feedTitle = feedId,
        title = title,
        link = null,
        description = null,
        published = null,
        audioUrl = null,
        cachedAtEpochMs = cachedAt
    )
}
