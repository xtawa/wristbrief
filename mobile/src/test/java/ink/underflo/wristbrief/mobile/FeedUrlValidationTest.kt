package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedUrlValidationTest {

    @Test
    fun emptyOrBlankUrl_returnsEmpty() {
        assertEquals(FeedUrlValidationResult.Empty, validateFeedUrlInput("", emptySet()))
        assertEquals(FeedUrlValidationResult.Empty, validateFeedUrlInput("   ", emptySet()))
    }

    @Test
    fun nonHttpsUrl_returnsNotHttps() {
        assertEquals(FeedUrlValidationResult.NotHttps, validateFeedUrlInput("http://example.com/feed.xml", emptySet()))
        assertEquals(FeedUrlValidationResult.NotHttps, validateFeedUrlInput("ftp://example.com/feed.xml", emptySet()))
        assertEquals(FeedUrlValidationResult.NotHttps, validateFeedUrlInput("feed://example.com/feed.xml", emptySet()))
    }

    @Test
    fun invalidUrlSyntax_returnsInvalidFormat() {
        assertEquals(FeedUrlValidationResult.InvalidFormat, validateFeedUrlInput("https://", emptySet()))
        assertEquals(FeedUrlValidationResult.InvalidFormat, validateFeedUrlInput("https:// user:pass@example.com/feed", emptySet()))
        assertEquals(FeedUrlValidationResult.InvalidFormat, validateFeedUrlInput("https://...", emptySet()))
    }

    @Test
    fun duplicateUrl_returnsDuplicate() {
        val existing = setOf("https://example.com/feed.xml", "https://another.com/rss")
        assertEquals(
            FeedUrlValidationResult.Duplicate,
            validateFeedUrlInput("https://example.com/feed.xml", existing),
        )
        // With trailing slash or case differences normalized
        assertEquals(
            FeedUrlValidationResult.Duplicate,
            validateFeedUrlInput("HTTPS://EXAMPLE.COM/feed.xml", existing),
        )
    }

    @Test
    fun editingExistingFeed_allowsSameUrl() {
        val existing = setOf("https://example.com/feed.xml", "https://another.com/rss")
        assertEquals(
            FeedUrlValidationResult.Valid,
            validateFeedUrlInput("https://example.com/feed.xml", existing, currentFeedUrl = "https://example.com/feed.xml"),
        )
    }

    @Test
    fun validUniqueHttpsUrl_returnsValid() {
        val existing = setOf("https://example.com/feed.xml")
        assertEquals(
            FeedUrlValidationResult.Valid,
            validateFeedUrlInput("https://newsite.org/feed.xml", existing),
        )
    }
}
