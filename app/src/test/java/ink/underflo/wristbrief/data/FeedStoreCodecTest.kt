package ink.underflo.wristbrief.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStoreCodecTest {
    @Test
    fun subscriptions_roundTripUnicodeAndControlCharacters() {
        val input = listOf(
            FeedSubscription(
                id = "feed-1",
                title = "科技\t新闻\nDaily",
                url = "https://example.com/feed.xml",
                enabled = true
            ),
            FeedSubscription(
                id = "feed-2",
                title = "Podcast 🎧",
                url = "https://example.com/podcast.xml",
                enabled = false
            )
        )

        assertEquals(input, FeedStoreCodec.decodeSubscriptions(FeedStoreCodec.encodeSubscriptions(input)))
    }

    @Test
    fun cachedItems_roundTripNullableFields() {
        val input = listOf(
            CachedFeedItem(
                id = "item-1",
                feedId = "feed-1",
                feedTitle = "Example",
                title = "Hello",
                link = null,
                description = "A & B",
                published = null,
                audioUrl = "https://cdn.example.com/a.mp3",
                cachedAtEpochMs = 1234L
            )
        )

        assertEquals(input, FeedStoreCodec.decodeItems(FeedStoreCodec.encodeItems(input)))
    }

    @Test
    fun readItemIds_roundTripStableIds() {
        val input = setOf(
            "guid:episode-42",
            "link:https://example.com/story?ref=a",
            "fallback:科技:一"
        )

        assertEquals(input, FeedStoreCodec.decodeItemIds(FeedStoreCodec.encodeItemIds(input)))
    }

    @Test
    fun unknownOrMalformedPayload_isIgnoredSafely() {
        assertTrue(FeedStoreCodec.decodeSubscriptions("v2\nanything").isEmpty())
        assertTrue(FeedStoreCodec.decodeItems("v1\nnot\tenough\tfields").isEmpty())
        assertTrue(FeedStoreCodec.decodeItemIds("v2\nanything").isEmpty())
    }
}
