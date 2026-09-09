package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlTest {
    @Test
    fun import_parsesNestedOutlines_decodesEntities_andDeduplicatesNormalizedUrls() {
        val opml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <body>
                <outline text="Tech">
                  <outline text="Tech &amp; News" xmlUrl="HTTPS://EXAMPLE.COM:443/feed.xml#latest" />
                  <outline title="Duplicate" xmlUrl="https://example.com/feed.xml" />
                </outline>
                <outline title="Paused podcast" xmlUrl="https://pod.example/rss" wristbriefEnabled="false" wristbriefSendToWatch="false" />
              </body>
            </opml>
        """.trimIndent()

        val feeds = parseOpmlSubscriptions(opml)

        assertEquals(2, feeds.size)
        assertEquals("Tech & News", feeds[0].title)
        assertEquals("https://example.com/feed.xml", feeds[0].url)
        assertTrue(feeds[0].sendToWatch)
        assertEquals("Paused podcast", feeds[1].title)
        assertFalse(feeds[1].enabled)
        assertFalse(feeds[1].sendToWatch)
    }

    @Test
    fun import_ignoresNonHttpsAndNonFeedOutlines() {
        val opml = """
            <opml version="2.0"><body>
              <outline text="Folder only" />
              <outline text="Insecure" xmlUrl="http://example.com/rss" />
              <outline text="Secure" xmlUrl="https://example.com/rss" />
            </body></opml>
        """.trimIndent()

        assertEquals(
            listOf(OpmlFeedEntry("Secure", "https://example.com/rss", true, true)),
            parseOpmlSubscriptions(opml),
        )
    }

    @Test
    fun import_rejectsDoctypeAndExternalEntityDeclarations() {
        val malicious = """
            <!DOCTYPE opml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <opml version="2.0"><body><outline text="&xxe;" xmlUrl="https://example.com/rss" /></body></opml>
        """.trimIndent()

        val failure = runCatching { parseOpmlSubscriptions(malicious) }.exceptionOrNull()
        assertTrue(failure is OpmlFormatException)
    }

    @Test
    fun export_roundTripsTitlesUrlsEnabledAndSendToWatchState() {
        val subscriptions = listOf(
            MobileFeedSubscription("one", "A & B \"Daily\"", "https://example.com/feed", enabled = true, sendToWatch = true),
            MobileFeedSubscription("two", "Phone only", "https://pod.example/rss", enabled = false, sendToWatch = false),
        )

        val exported = exportOpmlSubscriptions(subscriptions)
        val imported = parseOpmlSubscriptions(exported)

        assertTrue(exported.contains("version=\"2.0\""))
        assertTrue(exported.contains("A &amp; B &quot;Daily&quot;"))
        assertTrue(exported.contains("wristbriefSendToWatch=\"false\""))
        assertEquals(
            listOf(
                OpmlFeedEntry("A & B \"Daily\"", "https://example.com/feed", true, true),
                OpmlFeedEntry("Phone only", "https://pod.example/rss", false, false),
            ),
            imported,
        )
    }
}
