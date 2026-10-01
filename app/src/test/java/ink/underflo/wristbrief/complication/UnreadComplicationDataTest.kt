package ink.underflo.wristbrief.complication

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("1 unread · Newest", snapshot.englishLongText())
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
        assertEquals("All caught up", snapshot.englishLongText())
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

    @Test
    fun title_compaction_preserves_supplementary_unicode_at_boundary() {
        val compact = ("a".repeat(26) + "😀" + "tail").compactComplicationText()

        assertEquals("a".repeat(26) + "😀…", compact)
        assertEquals(28, compact.codePointCount(0, compact.length))
        assertTrue(Character.isSurrogatePair(compact[26], compact[27]))
    }

    @Test
    fun snapshot_applies_watch_keywords_like_the_inbox() {
        val keywordFeeds = listOf(
            FeedSubscription("enabled", "Enabled", "https://example.com/feed", enabled = true, watchKeywords = listOf("wear"))
        )
        val snapshot = buildUnreadComplicationSnapshot(
            subscriptions = keywordFeeds,
            cachedItems = listOf(
                item("match", "enabled", "Wear OS tiles update", 10),
                item("other", "enabled", "Unrelated headline", 20)
            ),
            readItemIds = emptySet()
        )

        assertEquals(1, snapshot.unreadCount)
        assertEquals("Wear OS tiles update", snapshot.latestTitle)
    }

    @Test
    fun long_text_without_title_uses_count_formatter() {
        assertEquals("4 unread", UnreadComplicationSnapshot(4, null).englishLongText())
    }

    private fun UnreadComplicationSnapshot.englishLongText() = longText(
        caughtUp = { "All caught up" },
        unread = { "$it unread" },
        unreadWithTitle = { count, title -> "$count unread · $title" },
    )

    @Test
    fun zero_budget_returns_empty_text() {
        assertEquals("", "headline".compactComplicationText(maxCodePoints = 0))
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
