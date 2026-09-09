package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordWatchFilterMobileTest {
    @Test fun normalization_trimsDeduplicatesAndBoundsRules() {
        val rules = normalizeWatchKeywords(
            listOf("  AI   News ", "ai news", "Kotlin", "x".repeat(80)) + (1..20).map { "tag-$it" },
        )

        assertEquals("AI News", rules[0])
        assertEquals("Kotlin", rules[1])
        assertEquals(48, rules[2].length)
        assertEquals(12, rules.size)
    }

    @Test fun rawNormalization_acceptsCommaAndNewlineSeparatedKeywords() {
        assertEquals(listOf("AI", "Wear OS", "Podcast"), normalizeWatchKeywords("AI, Wear   OS\nPodcast"))
    }

    @Test fun uiState_explainsAllItemsAndNormalizedOrMatching() {
        val empty = keywordWatchUiState(emptyList())
        assertEquals("All items", empty.summary)
        assertTrue(empty.supportingText.contains("No keyword filter"))

        val filtered = keywordWatchUiState(listOf(" AI ", "ai", " Wear   OS "))
        assertEquals("AI · Wear OS", filtered.summary)
        assertTrue(filtered.supportingText.contains("title or description"))
        assertEquals("AI, Wear OS", keywordWatchEditorText(listOf(" AI ", "Wear   OS")))
    }

    @Test fun codec_defaultsLegacyRowsToNoKeywordFilter_andRoundTripsRules() {
        val legacy = """[{"id":"a","title":"A","url":"https://example.com/a"}]"""
        assertTrue(decodeMobileFeedSubscriptions(legacy).single().watchKeywords.isEmpty())

        val original = listOf(
            MobileFeedSubscription(
                id = "a",
                title = "A",
                url = "https://example.com/a",
                category = "Tech",
                watchKeywords = listOf("AI", "Wear OS"),
            ),
        )
        assertEquals(original, decodeMobileFeedSubscriptions(encodeMobileFeedSubscriptions(original)))
    }

    @Test fun manager_persistsNormalizedRules_withoutChangingExistingRulesOnOrdinaryEdit() = runBlocking {
        val store = MemoryStore()
        val manager = MobileFeedManager(store, AcceptingProbe, NoopPublisher)
        val added = manager.add(
            rawUrl = "https://example.com/feed",
            title = "Example",
            watchKeywords = listOf("  AI ", "ai", " Wear   OS "),
        ) as FeedMutationResult.Success
        val id = added.feeds.single().id
        assertEquals(listOf("AI", "Wear OS"), added.feeds.single().watchKeywords)

        val edited = manager.update(id, "https://example.com/feed", "Renamed") as FeedMutationResult.Success
        assertEquals(listOf("AI", "Wear OS"), edited.feeds.single().watchKeywords)

        val changed = manager.setWatchKeywords(id, listOf("Podcast", " podcast ")) as FeedMutationResult.Success
        assertEquals(listOf("Podcast"), changed.feeds.single().watchKeywords)
    }

    private class MemoryStore : MobileFeedStore {
        private var value = emptyList<MobileFeedSubscription>()
        override fun load() = value
        override fun save(feeds: List<MobileFeedSubscription>) { value = feeds }
    }

    private object AcceptingProbe : FeedProbe {
        override suspend fun validate(url: String): String = "Example"
    }

    private object NoopPublisher : FeedSyncPublisher {
        override fun publish(feeds: List<MobileFeedSubscription>) = Unit
    }
}
