package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlManagerExtensionsTest {
    @Test
    fun import_validatesNewFeeds_skipsExisting_andPreservesDisabledState() = runBlocking {
        val existing = MobileFeedSubscription(
            id = stableFeedId("https://existing.example/rss"),
            title = "Existing",
            url = "https://existing.example/rss",
        )
        val store = MemoryStore(listOf(existing))
        val publisher = RecordingPublisher()
        val manager = MobileFeedManager(
            store = store,
            probe = object : FeedProbe {
                override suspend fun validate(url: String): String? {
                    if (url.contains("broken.example")) error("Feed validation failed")
                    return "Discovered title"
                }
            },
            publisher = publisher,
        )
        val opml = """
            <opml version="2.0"><body>
              <outline text="Existing again" xmlUrl="https://existing.example/rss" />
              <outline xmlUrl="https://new.example/rss" wristbriefEnabled="false" />
              <outline text="Broken" xmlUrl="https://broken.example/rss" />
            </body></opml>
        """.trimIndent()

        val result = manager.importOpml(opml) as OpmlImportResult.Success

        assertEquals(1, result.importedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(1, result.failedValidationCount)
        assertEquals(2, result.feeds.size)
        val imported = result.feeds.first { it.url == "https://new.example/rss" }
        assertEquals("Discovered title", imported.title)
        assertFalse(imported.enabled)
        assertTrue(publisher.snapshots.isNotEmpty())
    }

    @Test
    fun import_malformedOpmlReturnsSafeErrorWithoutPublishing() = runBlocking {
        val store = MemoryStore()
        val publisher = RecordingPublisher()
        val manager = MobileFeedManager(
            store,
            object : FeedProbe { override suspend fun validate(url: String) = "unused" },
            publisher,
        )

        val result = manager.importOpml("<body><outline xmlUrl=\"https://example.com/rss\" /></body>")

        assertTrue(result is OpmlImportResult.Error)
        assertTrue(store.value.isEmpty())
        assertTrue(publisher.snapshots.isEmpty())
    }

    @Test
    fun export_serializesCurrentManagerSubscriptions() {
        val store = MemoryStore(
            listOf(
                MobileFeedSubscription("one", "One", "https://one.example/rss", true),
                MobileFeedSubscription("two", "Two", "https://two.example/rss", false),
            ),
        )
        val manager = MobileFeedManager(
            store,
            object : FeedProbe { override suspend fun validate(url: String) = null },
            RecordingPublisher(),
        )

        val roundTrip = parseOpmlSubscriptions(manager.exportOpml())

        assertEquals(
            listOf(
                OpmlFeedEntry("One", "https://one.example/rss", true),
                OpmlFeedEntry("Two", "https://two.example/rss", false),
            ),
            roundTrip,
        )
    }

    private class MemoryStore(initial: List<MobileFeedSubscription> = emptyList()) : MobileFeedStore {
        var value = initial
        override fun load(): List<MobileFeedSubscription> = value
        override fun save(feeds: List<MobileFeedSubscription>) { value = feeds }
    }

    private class RecordingPublisher : FeedSyncPublisher {
        val snapshots = mutableListOf<List<MobileFeedSubscription>>()
        override fun publish(feeds: List<MobileFeedSubscription>) { snapshots += feeds }
    }
}
