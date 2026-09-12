package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class MobileFeedParserTest {
    private val parser = MobileFeedParser()

    @Test
    fun parsesRssFeedWithEnclosureAndGuid() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
                <channel>
                    <title>Test Tech Podcast</title>
                    <item>
                        <title>Episode 42: Modern Android</title>
                        <link>https://example.com/ep42</link>
                        <guid>https://example.com/ep42-guid</guid>
                        <description>Discussion on modern Android development.</description>
                        <pubDate>Mon, 08 Sep 2026 12:00:00 GMT</pubDate>
                        <enclosure url="https://example.com/audio/ep42.mp3" type="audio/mpeg" length="12345" />
                    </item>
                    <item>
                        <title>Article: Calm Computing</title>
                        <link>https://example.com/calm</link>
                        <description>Why calm computing matters.</description>
                    </item>
                </channel>
            </rss>
        """.trimIndent()

        val items = parser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(2, items.size)

        val ep = items[0]
        assertEquals("Episode 42: Modern Android", ep.title)
        assertEquals("https://example.com/ep42", ep.link)
        assertEquals("https://example.com/ep42-guid", ep.guid)
        assertEquals("https://example.com/audio/ep42.mp3", ep.audioUrl)
        assertEquals("Discussion on modern Android development.", ep.description)

        val article = items[1]
        assertEquals("Article: Calm Computing", article.title)
        assertEquals("https://example.com/calm", article.link)
        assertNull(article.audioUrl)
    }

    @Test
    fun parsesAtomFeedWithEntries() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
                <title>Example Atom Feed</title>
                <entry>
                    <title>Atom Post 1</title>
                    <id>urn:uuid:1225c695-cfb8-4ebb-aaaa-80da344efa6a</id>
                    <link href="https://example.com/atom1" rel="alternate"/>
                    <summary>A brief summary of atom post 1</summary>
                    <updated>2026-09-08T18:30:02Z</updated>
                </entry>
            </feed>
        """.trimIndent()

        val items = parser.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(1, items.size)
        val entry = items[0]
        assertEquals("Atom Post 1", entry.title)
        assertEquals("urn:uuid:1225c695-cfb8-4ebb-aaaa-80da344efa6a", entry.guid)
        assertEquals("https://example.com/atom1", entry.link)
        assertEquals("A brief summary of atom post 1", entry.description)
        assertEquals("2026-09-08T18:30:02Z", entry.published)
    }

    @Test
    fun keepsNamespacedFullContentInsteadOfShortSummary() {
        val fullContent = "<p>" + "Full article body ".repeat(80) + "</p>"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                <channel><item>
                    <title>Long article</title>
                    <link>https://example.com/long</link>
                    <description>Short summary.</description>
                    <content:encoded><![CDATA[$fullContent]]></content:encoded>
                </item></channel>
            </rss>
        """.trimIndent()

        val item = parser.parse(ByteArrayInputStream(xml.toByteArray())).single()

        assertEquals(fullContent, item.description)
    }
}
