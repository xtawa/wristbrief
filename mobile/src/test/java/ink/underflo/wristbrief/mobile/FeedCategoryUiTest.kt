package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedCategoryUiTest {
    @Test
    fun `groups feeds by normalized case-insensitive category and keeps uncategorized last`() {
        val feeds = listOf(
            MobileFeedSubscription("1", "One", "https://one.example/feed", category = " Tech  News "),
            MobileFeedSubscription("2", "Two", "https://two.example/feed", category = null),
            MobileFeedSubscription("3", "Three", "https://three.example/feed", category = "tech news"),
            MobileFeedSubscription("4", "Four", "https://four.example/feed", category = "Audio"),
        )

        val groups = groupMobileFeedsByCategory(feeds)

        assertEquals(listOf("Audio", "Tech News", null), groups.map { it.category })
        assertEquals(listOf("4"), groups[0].feeds.map { it.id })
        assertEquals(listOf("1", "3"), groups[1].feeds.map { it.id })
        assertEquals(listOf("2"), groups[2].feeds.map { it.id })
    }

    @Test
    fun `preserves feed order inside the same category`() {
        val feeds = listOf(
            MobileFeedSubscription("a", "A", "https://a.example/feed", category = "News"),
            MobileFeedSubscription("b", "B", "https://b.example/feed", category = "News"),
        )

        val groups = groupMobileFeedsByCategory(feeds)

        assertEquals(1, groups.size)
        assertEquals("News", groups.single().category)
        assertEquals(listOf("a", "b"), groups.single().feeds.map { it.id })
    }

    @Test
    fun `blank categories render as uncategorized`() {
        val groups = groupMobileFeedsByCategory(
            listOf(MobileFeedSubscription("1", "One", "https://one.example/feed", category = "   ")),
        )

        assertEquals(null, groups.single().category)
    }
}
