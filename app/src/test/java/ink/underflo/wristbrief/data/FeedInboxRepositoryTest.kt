package ink.underflo.wristbrief.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedInboxRepositoryTest {
    @Test
    fun successfulRefresh_replacesOnlySuccessfulFeedCache() {
        val store = FakeStore(
            subscriptions = listOf(
                FeedSubscription("a", "Feed A", "https://a.example/feed"),
                FeedSubscription("b", "Feed B", "https://b.example/feed")
            ),
            cached = listOf(
                cached("old-a", "a", "Feed A", 1L),
                cached("old-b", "b", "Feed B", 2L)
            )
        )
        val loader = FakeLoader(
            mapOf(
                "https://a.example/feed" to listOf(feed("new-a", "https://a.example/new")),
                "https://b.example/feed" to listOf(feed("new-b", "https://b.example/new"))
            )
        )

        val result = FeedInboxRepository(loader, store) { 100L }.refresh()

        assertFalse(result.isOfflineFallback)
        assertEquals(setOf("new-a", "new-b"), result.items.map { it.title }.toSet())
        assertEquals(setOf("new-a", "new-b"), store.cached.map { it.title }.toSet())
    }

    @Test
    fun guidWinsOverChangingLinksAndDeduplicatesRefresh() {
        val store = FakeStore(
            subscriptions = listOf(FeedSubscription("a", "Feed A", "https://a.example/feed"))
        )
        val loader = FakeLoader(
            mapOf(
                "https://a.example/feed" to listOf(
                    feed("First representation", "https://a.example/article?source=one", guid = "stable-guid"),
                    feed("Duplicate representation", "https://a.example/article?source=two", guid = "stable-guid")
                )
            )
        )

        val result = FeedInboxRepository(loader, store) { 100L }.refresh()

        assertEquals(1, result.items.size)
        assertEquals("guid:stable-guid", result.items.single().id)
    }

    @Test
    fun canonicalLinkNormalizationDeduplicatesObviousUrlVariants() {
        val store = FakeStore(
            subscriptions = listOf(FeedSubscription("a", "Feed A", "https://a.example/feed"))
        )
        val loader = FakeLoader(
            mapOf(
                "https://a.example/feed" to listOf(
                    feed("One", "HTTPS://Example.COM:443/story/#section"),
                    feed("Two", "https://example.com/story")
                )
            )
        )

        val result = FeedInboxRepository(loader, store) { 100L }.refresh()

        assertEquals(1, result.items.size)
        assertEquals("link:https://example.com/story", result.items.single().id)
    }

    @Test
    fun identityNormalizationPreservesQueryParameters() {
        assertEquals(
            "https://example.com/story?ref=a",
            normalizeIdentityUrl("HTTPS://EXAMPLE.COM:443/story/?ref=a#fragment")
        )
        assertEquals(
            "https://example.com/story?ref=b",
            normalizeIdentityUrl("https://example.com/story?ref=b")
        )
    }

    @Test
    fun addSubscription_normalizesAndPersists() {
        val store = FakeStore()
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), store)

        val result = repository.addSubscription(
            FeedSubscription("a", "  Example Feed  ", "HTTPS://EXAMPLE.COM:443/feed/#fragment")
        )

        assertEquals(SubscriptionMutationResult.Success, result)
        assertEquals(
            FeedSubscription("a", "Example Feed", "https://example.com/feed"),
            store.subscriptions().single()
        )
    }

    @Test
    fun addSubscription_rejectsDuplicateLogicalUrl() {
        val store = FakeStore(
            subscriptions = listOf(FeedSubscription("a", "Existing", "https://example.com/feed"))
        )
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), store)

        val result = repository.addSubscription(
            FeedSubscription("b", "Duplicate", "HTTPS://EXAMPLE.COM:443/feed/#section")
        )

        assertEquals(SubscriptionMutationResult.DuplicateSubscription("a"), result)
        assertEquals(listOf("a"), store.subscriptions().map { it.id })
    }

    @Test
    fun addSubscription_rejectsInvalidHttpsUrlWithExplicitResult() {
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), FakeStore())

        val http = repository.addSubscription(FeedSubscription("x", "Bad", "http://example.com/feed"))
        val missingHost = repository.addSubscription(FeedSubscription("y", "Bad", "https:///feed"))

        assertEquals(SubscriptionMutationResult.InvalidUrl("http://example.com/feed"), http)
        assertEquals(SubscriptionMutationResult.InvalidUrl("https:///feed"), missingHost)
    }

    @Test
    fun updateRenameAndEnableDisable_preserveCachedItems() {
        val store = FakeStore(
            subscriptions = listOf(FeedSubscription("a", "Old", "https://example.com/feed")),
            cached = listOf(cached("cached-a", "a", "Old", 1L))
        )
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), store)

        assertEquals(SubscriptionMutationResult.Success, repository.renameSubscription("a", "  Renamed  "))
        assertEquals(SubscriptionMutationResult.Success, repository.setSubscriptionEnabled("a", false))
        assertTrue(repository.cachedItems().isEmpty())
        assertEquals(1, store.cached.size)
        assertEquals("Renamed", store.subscriptions().single().title)
        assertFalse(store.subscriptions().single().enabled)

        assertEquals(SubscriptionMutationResult.Success, repository.setSubscriptionEnabled("a", true))
        assertEquals(listOf("cached-a"), repository.cachedItems().map { it.title })
    }

    @Test
    fun updateSubscription_rejectsUrlOwnedByAnotherSubscription() {
        val store = FakeStore(
            subscriptions = listOf(
                FeedSubscription("a", "A", "https://a.example/feed"),
                FeedSubscription("b", "B", "https://b.example/feed")
            )
        )
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), store)

        val result = repository.updateSubscription(
            FeedSubscription("b", "B", "HTTPS://A.EXAMPLE:443/feed/")
        )

        assertEquals(SubscriptionMutationResult.DuplicateSubscription("a"), result)
        assertEquals("https://b.example/feed", store.subscriptions().first { it.id == "b" }.url)
    }

    @Test
    fun missingSubscriptionOperations_returnExplicitMissingResult() {
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), FakeStore())

        assertEquals(SubscriptionMutationResult.MissingSubscription("missing"), repository.renameSubscription("missing", "Name"))
        assertEquals(SubscriptionMutationResult.MissingSubscription("missing"), repository.setSubscriptionEnabled("missing", false))
        assertEquals(SubscriptionMutationResult.MissingSubscription("missing"), repository.removeSubscription("missing"))
        assertEquals(
            SubscriptionMutationResult.MissingSubscription("missing"),
            repository.updateSubscription(FeedSubscription("missing", "Name", "https://example.com/feed"))
        )
    }

    @Test
    fun removeSubscription_removesSubscriptionAndOwnedCacheOnly() {
        val store = FakeStore(
            subscriptions = listOf(
                FeedSubscription("a", "A", "https://a.example/feed"),
                FeedSubscription("b", "B", "https://b.example/feed")
            ),
            cached = listOf(
                cached("old-a", "a", "A", 1L),
                cached("old-b", "b", "B", 2L)
            )
        )
        val repository = FeedInboxRepository(FakeLoader(emptyMap()), store)

        val result = repository.removeSubscription("a")

        assertEquals(SubscriptionMutationResult.Success, result)
        assertEquals(listOf("b"), store.subscriptions().map { it.id })
        assertEquals(listOf("b"), store.cached.map { it.feedId })
    }

    @Test
    fun failedFeed_keepsItsCachedItemsWhileOtherFeedsRefresh() {
        val store = FakeStore(
            subscriptions = listOf(
                FeedSubscription("a", "Feed A", "https://a.example/feed"),
                FeedSubscription("b", "Feed B", "https://b.example/feed")
            ),
            cached = listOf(
                cached("old-a", "a", "Feed A", 1L),
                cached("old-b", "b", "Feed B", 2L)
            )
        )
        val loader = object : FeedLoader {
            override fun load(url: String): List<FeedItem> {
                if (url.contains("a.example")) error("offline")
                return listOf(feed("new-b", "https://b.example/new"))
            }
        }

        val result = FeedInboxRepository(loader, store) { 100L }.refresh()

        assertTrue(result.isOfflineFallback)
        assertEquals(setOf("a"), result.failedFeedIds)
        assertEquals(setOf("old-a", "new-b"), result.items.map { it.title }.toSet())
    }

    @Test
    fun disabledFeed_isRetainedInStorageButHiddenFromInbox() {
        val store = FakeStore(
            subscriptions = listOf(
                FeedSubscription("a", "Feed A", "https://a.example/feed", enabled = true),
                FeedSubscription("b", "Feed B", "https://b.example/feed", enabled = false)
            ),
            cached = listOf(
                cached("old-a", "a", "Feed A", 1L),
                cached("old-b", "b", "Feed B", 2L)
            )
        )
        val loader = FakeLoader(
            mapOf("https://a.example/feed" to listOf(feed("new-a", "https://a.example/new")))
        )
        val repository = FeedInboxRepository(loader, store) { 100L }

        assertEquals(listOf("old-a"), repository.cachedItems().map { it.title })

        val result = repository.refresh()

        assertEquals(listOf("new-a"), result.items.map { it.title })
        assertTrue(store.cached.any { it.feedId == "b" && it.title == "old-b" })
    }

    private fun feed(title: String, link: String, guid: String? = null) = FeedItem(
        title = title,
        link = link,
        description = "summary",
        published = null,
        audioUrl = null,
        guid = guid
    )

    private fun cached(title: String, feedId: String, feedTitle: String, cachedAt: Long) =
        CachedFeedItem(
            id = "$feedId:$title",
            feedId = feedId,
            feedTitle = feedTitle,
            title = title,
            link = "https://example.com/$feedId/$title",
            description = "cached",
            published = null,
            audioUrl = null,
            cachedAtEpochMs = cachedAt
        )

    private class FakeLoader(
        private val responses: Map<String, List<FeedItem>>
    ) : FeedLoader {
        override fun load(url: String): List<FeedItem> = responses[url] ?: error("Missing $url")
    }

    private class FakeStore(
        private var subscriptions: List<FeedSubscription> = emptyList(),
        var cached: List<CachedFeedItem> = emptyList()
    ) : FeedStore {
        override fun subscriptions(): List<FeedSubscription> = subscriptions
        override fun saveSubscriptions(subscriptions: List<FeedSubscription>) {
            this.subscriptions = subscriptions
        }

        override fun cachedItems(): List<CachedFeedItem> = cached
        override fun saveCachedItems(items: List<CachedFeedItem>) {
            cached = items
        }
    }
}
