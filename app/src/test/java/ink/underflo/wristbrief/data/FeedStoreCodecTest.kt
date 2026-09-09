package ink.underflo.wristbrief.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStoreCodecTest {
    @Test
    fun subscriptions_roundTripUnicodeControlCharactersAndKeywords() {
        val input = listOf(
            FeedSubscription(
                id = "feed-1",
                title = "科技\t新闻\nDaily",
                url = "https://example.com/feed.xml",
                enabled = true,
                watchKeywords = listOf("AI", "Wear OS"),
            ),
            FeedSubscription(
                id = "feed-2",
                title = "Podcast 🎧",
                url = "https://example.com/podcast.xml",
                enabled = false,
            )
        )

        assertEquals(input, FeedStoreCodec.decodeSubscriptions(FeedStoreCodec.encodeSubscriptions(input)))
    }

    @Test
    fun subscriptions_readsLegacyV1WithoutKeywords() {
        fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val legacy = "v1\n${enc("feed-1")}\t${enc("Legacy")}\t${enc("https://example.com/feed.xml")}\t1\n"
        val decoded = FeedStoreCodec.decodeSubscriptions(legacy).single()
        assertEquals("Legacy", decoded.title)
        assertTrue(decoded.watchKeywords.isEmpty())
    }

    @Test
    fun cachedItems_roundTripTranscriptMetadata() {
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
                transcript = PodcastTranscript(
                    url = "https://cdn.example.com/a.vtt",
                    type = "text/vtt",
                    language = "zh-CN",
                    rel = "captions"
                ),
                cachedAtEpochMs = 1234L
            )
        )

        assertEquals(input, FeedStoreCodec.decodeItems(FeedStoreCodec.encodeItems(input)))
    }

    @Test
    fun cachedItems_readsLegacyV1WithoutTranscript() {
        val current = CachedFeedItem(
            id = "legacy-item",
            feedId = "legacy-feed",
            feedTitle = "Legacy",
            title = "Old cache",
            link = "https://example.com/old",
            description = null,
            published = null,
            audioUrl = "https://cdn.example.com/old.mp3",
            cachedAtEpochMs = 42L
        )
        val v2 = FeedStoreCodec.encodeItems(listOf(current))
        val fields = v2.lineSequence().drop(1).first().split('\t')
        val legacy = buildString {
            appendLine("v1")
            append(fields.take(8).joinToString("\t"))
            append('\t').append("42").append('\n')
        }

        val decoded = FeedStoreCodec.decodeItems(legacy).single()
        assertEquals(current.copy(transcript = null), decoded)
        assertNull(decoded.transcript)
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
    fun savedItemIds_roundTripStableIds() {
        val input = setOf(
            "guid:saved-episode",
            "audio:https://cdn.example.com/episode.mp3",
            "fallback:收藏:文章"
        )
        assertEquals(input, FeedStoreCodec.decodeItemIds(FeedStoreCodec.encodeItemIds(input)))
    }

    @Test
    fun unknownOrMalformedPayload_isIgnoredSafely() {
        assertTrue(FeedStoreCodec.decodeSubscriptions("v3\nanything").isEmpty())
        assertTrue(FeedStoreCodec.decodeItems("v3\nanything").isEmpty())
        assertTrue(FeedStoreCodec.decodeItems("v1\nnot\tenough\tfields").isEmpty())
        assertTrue(FeedStoreCodec.decodeItemIds("v2\nanything").isEmpty())
    }
}
