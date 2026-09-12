package ink.underflo.wristbrief.mobile

import ink.underflo.wristbrief.mobile.sync.CloudSyncOutbox
import ink.underflo.wristbrief.mobile.sync.OutboxMutation
import ink.underflo.wristbrief.mobile.sync.SyncEntityType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapFeedStore : MobileFeedStore {
    var feeds = emptyList<MobileFeedSubscription>()
    override fun load(): List<MobileFeedSubscription> = feeds
    override fun save(feeds: List<MobileFeedSubscription>) {
        this.feeds = feeds
    }
}

private class MapProbe : FeedProbe {
    override suspend fun validate(url: String): String? = "Discovered"
}

private class NoopPublisher : FeedSyncPublisher {
    override fun publish(feeds: List<MobileFeedSubscription>) {}
}

private class NoopOutbox : CloudSyncOutbox {
    override fun enqueue(entityType: SyncEntityType, entityId: String, payloadJson: String, updatedAtEpochMs: Long, isDeleted: Boolean) {}
    override fun getPending(limit: Int, nowEpochMs: Long): List<OutboxMutation> = emptyList()
    override fun remove(ids: List<String>) {}
    override fun count(): Int = 0
    override fun incrementRetry(ids: List<String>, nowEpochMs: Long) {}
    override fun clearAll() {}
}

class OpmlImportTest {
    @Test
    fun localPreviewParsesWithoutNetworkAndReportsCounts() {
        val raw = """
            <opml version="2.0"><body>
            <outline text="News" title="News">
            <outline type="rss" xmlUrl="https://a.example/rss" text="A" />
            <outline type="rss" xmlUrl="https://b.example/rss" text="B" />
            </outline>
            <outline type="rss" xmlUrl="https://a.example/rss" text="Duplicate" />
            </body></opml>
        """.trimIndent()
        val outcome = previewLocalOpml(OpmlImportSource.LocalFile("content://document.opml"), raw)
        assertTrue(outcome is OpmlPreviewOutcome.Ready)
        val preview = (outcome as OpmlPreviewOutcome.Ready).preview
        // The parser merges the in-document duplicate.
        assertEquals(listOf("https://a.example/rss", "https://b.example/rss"), preview.feeds.map { it.url })
        assertEquals("News", preview.feeds[0].category)

        val invalid = previewLocalOpml(OpmlImportSource.LocalFile("content://document.opml"), "<html>nope</html>")
        assertTrue(invalid is OpmlPreviewOutcome.Error)
    }

    @Test
    fun remotePreviewResponseIsParsedIntoThePreviewModel() {
        val api = HttpOpmlPreviewApi("https://gateway.example.com") { null }
        val body = """
            {"source":{"type":"url","displayUrl":"https://example.com/sub.opml"},
             "feeds":[{"url":"https://a.example/rss","title":"A","enabled":false,"sendToWatch":true,"category":"Tech","watchKeywords":["kotlin"]}],
             "rejected":[{"url":"http://insecure.example/rss","reason":"INVALID_FEED_URL"}],
             "warnings":["1 duplicate feed URL(s) inside the document were merged"],
             "summary":{"total":3,"valid":1,"duplicate":1,"rejected":1}}
        """.trimIndent()
        val outcome = api.parsePreviewResponse(OpmlImportSource.RemoteUrl("https://example.com/sub.opml"), body)
        assertTrue(outcome is OpmlPreviewOutcome.Ready)
        val preview = (outcome as OpmlPreviewOutcome.Ready).preview
        assertEquals(1, preview.feeds.size)
        assertEquals(false, preview.feeds[0].enabled)
        assertEquals(listOf("kotlin"), preview.feeds[0].watchKeywords)
        assertEquals(1, preview.duplicateCount)
        assertEquals(1, preview.rejectedCount)
        assertEquals(1, preview.warnings.size)
    }

    @Test
    fun applyPreviewSkipsDuplicatesAndPersistsNewFeeds() = runBlocking {
        val store = MapFeedStore()
        val manager = MobileFeedManager(store, MapProbe(), NoopPublisher(), cloudOutbox = NoopOutbox())
        store.feeds = listOf(MobileFeedSubscription("existing-1", "Existing", "https://a.example/rss"))

        val preview = OpmlImportPreview(
            source = OpmlImportSource.RemoteUrl("https://example.com/sub.opml"),
            feeds = listOf(
                OpmlFeedEntry("A", "https://a.example/rss"), // duplicate of existing
                OpmlFeedEntry("B", "https://b.example/rss"),
            ),
            duplicateCount = 0,
            rejectedCount = 0,
        )
        val result = manager.applyOpmlPreview(preview)

        assertTrue(result is OpmlImportResult.Success)
        assertEquals(1, (result as OpmlImportResult.Success).importedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(2, store.feeds.size)
    }

    @Test
    fun errorCodesMapToFriendlyStrings() {
        assertEquals(R.string.opml_preview_error_blocked, opmlPreviewErrorMessageRes("FETCH_BLOCKED_HOST"))
        assertEquals(R.string.opml_preview_error_blocked, opmlPreviewErrorMessageRes("REDIRECT_BLOCKED"))
        assertEquals(R.string.opml_preview_error_format, opmlPreviewErrorMessageRes("MISSING_ROOT"))
        assertEquals(R.string.opml_preview_error_unauthorized, opmlPreviewErrorMessageRes("unauthorized"))
        assertEquals(R.string.opml_preview_error_generic, opmlPreviewErrorMessageRes("anything_else"))
    }
}
