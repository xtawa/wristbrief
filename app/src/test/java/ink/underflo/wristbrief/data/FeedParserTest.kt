package ink.underflo.wristbrief.data

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class FeedParserTest {
    private val subject = FeedParser()

    @Test
    fun parsesRssArticleAndPodcastEnclosure() {
        val xml = """
            <rss version="2.0">
              <channel>
                <title>WristBrief Test</title>
                <item>
                  <title>Android update</title>
                  <link>https://example.com/android</link>
                  <description>Important Wear OS news.</description>
                  <pubDate>Sun, 06 Sep 2026 10:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>Episode 42</title>
                  <description>Podcast episode</description>
                  <enclosure url="https://cdn.example.com/42.mp3" type="audio/mpeg" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = subject.parse(parserFor(xml))

        assertEquals(2, items.size)
        assertEquals("Android update", items[0].title)
        assertEquals("https://example.com/android", items[0].link)
        assertNull(items[0].audioUrl)
        assertEquals("Episode 42", items[1].title)
        assertEquals("https://cdn.example.com/42.mp3", items[1].audioUrl)
    }

    @Test
    fun parsesAtomAlternateLinkAndAudioEnclosure() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Test</title>
              <entry>
                <title>Wear briefing</title>
                <link rel="alternate" href="https://example.com/brief" />
                <link rel="enclosure" type="audio/ogg" href="https://cdn.example.com/brief.ogg" />
                <summary>A concise summary.</summary>
                <updated>2026-09-06T10:00:00Z</updated>
              </entry>
            </feed>
        """.trimIndent()

        val items = subject.parse(parserFor(xml))

        assertEquals(1, items.size)
        assertEquals("Wear briefing", items.single().title)
        assertEquals("https://example.com/brief", items.single().link)
        assertEquals("https://cdn.example.com/brief.ogg", items.single().audioUrl)
        assertEquals("A concise summary.", items.single().description)
    }

    @Test
    fun ignoresEntriesWithoutTitles() {
        val xml = """
            <rss version="2.0">
              <channel>
                <item><description>No title</description></item>
              </channel>
            </rss>
        """.trimIndent()

        assertEquals(emptyList<FeedItem>(), subject.parse(parserFor(xml)))
    }

    private fun parserFor(xml: String): XmlPullParser = KXmlParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(StringReader(xml))
    }
}
