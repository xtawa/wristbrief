package ink.underflo.wristbrief.tile

import ink.underflo.wristbrief.data.CachedFeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestUnreadTileDataTest {
    @Test
    fun mapsEnabledCachedData_withoutNetworkState() {
        val items = listOf(
            item(id = "new", feedId = "enabled", title = "  Latest   brief  ", cachedAt = 30),
            item(id = "older", feedId = "enabled", title = "Older brief", cachedAt = 20),
            item(id = "disabled", feedId = "disabled", title = "Hidden", cachedAt = 40)
        )

        val data = mapLatestUnreadTileData(
            cachedItems = items,
            enabledFeedIds = setOf("enabled"),
            readItemIds = setOf("older")
        )

        assertEquals(1, data.unreadCount)
        assertEquals(listOf("Latest brief"), data.titles)
    }

    @Test
    fun showsLatestCurrentTitles_whenEverythingIsRead() {
        val items = listOf(
            item(id = "one", title = "One", cachedAt = 10),
            item(id = "two", title = "Two", cachedAt = 20),
            item(id = "three", title = "Three", cachedAt = 30)
        )

        val data = mapLatestUnreadTileData(
            cachedItems = items,
            enabledFeedIds = setOf("feed"),
            readItemIds = setOf("one", "two", "three"),
            maxTitles = 2
        )

        assertEquals(0, data.unreadCount)
        assertEquals(listOf("Three", "Two"), data.titles)
    }

    @Test
    fun capsTitlesForGlanceability() {
        val items = (1..5).map { index ->
            item(id = "$index", title = "Brief $index", cachedAt = index.toLong())
        }

        val data = mapLatestUnreadTileData(
            cachedItems = items,
            enabledFeedIds = setOf("feed"),
            readItemIds = emptySet(),
            maxTitles = 2
        )

        assertEquals(5, data.unreadCount)
        assertEquals(listOf("Brief 5", "Brief 4"), data.titles)
    }

    @Test
    fun compactsLongTitlesForRoundTileReadability() {
        val title = "A deliberately long podcast and RSS headline that would otherwise dominate a small round tile"

        val data = mapLatestUnreadTileData(
            cachedItems = listOf(item(id = "long", title = title, cachedAt = 1)),
            enabledFeedIds = setOf("feed"),
            readItemIds = emptySet()
        )

        val compact = data.titles.single()
        assertEquals(44, compact.codePointCount(0, compact.length))
        assertTrue(compact.endsWith("…"))
        assertEquals("A deliberately long podcast and RSS headlin…", compact)
    }

    @Test
    fun compactsWithoutSplittingSupplementaryUnicodeCharacters() {
        val title = "a".repeat(42) + "😀" + "tail"

        val compact = normalizeTileTitle(title)

        assertEquals("a".repeat(42) + "😀…", compact)
        assertEquals(44, compact.codePointCount(0, compact.length))
        assertFalse(compact.any { Character.isSurrogate(it) } && compact.codePoints().count() == compact.length.toLong())
        assertTrue(Character.isSurrogatePair(compact[42], compact[43]))
    }

    private fun item(
        id: String,
        feedId: String = "feed",
        title: String,
        cachedAt: Long
    ) = CachedFeedItem(
        id = id,
        feedId = feedId,
        feedTitle = "Feed",
        title = title,
        link = null,
        description = null,
        published = null,
        audioUrl = null,
        cachedAtEpochMs = cachedAt
    )
}
