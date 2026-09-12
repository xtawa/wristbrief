package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleFeedsTest {

    @Test
    fun curatedFeeds_areAvailableByDefault() {
        assertTrue(SampleFeeds.curatedFeeds.isNotEmpty())
    }

    @Test
    fun curatedFeeds_coverCoreDiscoveryCategories() {
        val categories = SampleFeeds.curatedFeeds.map { it.category }.toSet()
        val hasPodcast = SampleFeeds.curatedFeeds.any { it.isPodcast }

        assertTrue("Technology", categories.any { it.equals("Tech", ignoreCase = true) })
        assertTrue("News", categories.any { it.equals("News", ignoreCase = true) })
        assertTrue("Podcasts", hasPodcast)
    }

    @Test
    fun allSampleFeeds_useHttps() {
        SampleFeeds.curatedFeeds.forEach { feed ->
            assertTrue("Feed ${feed.id} must use HTTPS: ${feed.url}", feed.url.startsWith("https://", ignoreCase = true))
            assertNotNull("Feed ${feed.url} must have valid normalized URL", normalizeFeedUrl(feed.url))
        }
    }

    @Test
    fun allSampleFeeds_haveUniqueIdsAndUrls() {
        val ids = SampleFeeds.curatedFeeds.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        val urls = SampleFeeds.curatedFeeds.mapNotNull { normalizeFeedUrl(it.url) }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun allSampleFeeds_haveNonBlankMetadata() {
        SampleFeeds.curatedFeeds.forEach { feed ->
            assertTrue("Title must not be blank", feed.title.isNotBlank())
            assertTrue("Category must not be blank", feed.category.isNotBlank())
            assertTrue("Description must not be blank", feed.description.isNotBlank())
        }
    }
}
