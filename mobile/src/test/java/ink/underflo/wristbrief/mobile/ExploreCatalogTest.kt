package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreCatalogTest {
    private val tech = SampleFeed("t", "Android Developers", "https://www.example.org/android.xml", "Tech", "Official news")
    private val news = SampleFeed("n", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "News", "Global news")
    private val pod = SampleFeed("p", "NPR News Now", "https://feeds.npr.org/500005/podcast.xml", "News", "Hourly podcast", isPodcast = true)
    private val catalog = listOf(tech, news, pod)

    @Test
    fun `categories come from the catalog and never include empty ones`() {
        assertEquals(listOf("All", "Technology", "News", "Podcasts"), ExploreCatalog.categories(catalog))
        assertFalse(ExploreCatalog.categories(catalog).contains("Science"))
    }

    @Test
    fun `podcasts chip only appears when a podcast exists`() {
        assertEquals(listOf("All", "Technology", "News"), ExploreCatalog.categories(listOf(tech, news)))
    }

    @Test
    fun `tech and technology are the same category`() {
        assertEquals(listOf(tech), ExploreCatalog.filter(catalog, "", "Technology"))
        assertEquals(listOf(tech), ExploreCatalog.filter(catalog, "", "Tech"))
    }

    @Test
    fun `podcast chip matches podcasts regardless of their own category`() {
        assertEquals(listOf(pod), ExploreCatalog.filter(catalog, "", "Podcasts"))
    }

    @Test
    fun `all and null behave the same`() {
        assertEquals(catalog, ExploreCatalog.filter(catalog, "", null))
        assertEquals(catalog, ExploreCatalog.filter(catalog, "", "All"))
    }

    @Test
    fun `search matches title description category and host`() {
        assertEquals(listOf(news), ExploreCatalog.filter(catalog, "bbc world", null))
        assertEquals(listOf(pod), ExploreCatalog.filter(catalog, "hourly", null))
        assertEquals(listOf(tech), ExploreCatalog.filter(catalog, "tech", null))
        assertEquals(listOf(pod), ExploreCatalog.filter(catalog, "npr.org", null))
        assertTrue(ExploreCatalog.filter(catalog, "nothing-here", null).isEmpty())
    }

    @Test
    fun `host strips www and tolerates bad urls`() {
        assertEquals("example.org", ExploreCatalog.host(tech.url))
        assertEquals("feeds.npr.org", ExploreCatalog.host(pod.url))
        assertEquals("", ExploreCatalog.host("not a url"))
    }

    @Test
    fun `shipped curated catalog yields a non-empty category list`() {
        val categories = ExploreCatalog.categories(SampleFeeds.curatedFeeds)
        assertEquals("All", categories.first())
        assertTrue(categories.size > 1)
        categories.drop(1).forEach { category ->
            assertTrue("category $category must have at least one feed", ExploreCatalog.filter(SampleFeeds.curatedFeeds, "", category).isNotEmpty())
        }
    }
}
