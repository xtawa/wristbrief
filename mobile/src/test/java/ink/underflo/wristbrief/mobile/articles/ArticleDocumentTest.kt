package ink.underflo.wristbrief.mobile.articles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleDocumentTest {
    @Test
    fun decodesTheGatewayDocumentPayload() {
        val raw = """
            {"articleKey":"abc","source":"rss","document":{
              "title":"Big Story","author":"Ada Lovelace","publishedAt":"2026-09-12T08:00:00Z",
              "canonicalUrl":"https://news.example.com/story","sourceName":"Example News",
              "blocks":[
                {"type":"paragraph","spans":[{"type":"text","text":"Intro with "},{"type":"bold","text":"emphasis"},{"type":"text","text":" and "},{"type":"link","text":"a link","url":"https://example.com"},{"type":"text","text":"."}]},
                {"type":"heading","level":2,"spans":[{"type":"text","text":"Section"}]},
                {"type":"unordered_list","items":[[{"type":"text","text":"One"}],[{"type":"text","text":"Two"}]]},
                {"type":"ordered_list","items":[[{"type":"text","text":"First"}]]},
                {"type":"quote","spans":[{"type":"italic","text":"Wise words"}]},
                {"type":"code_block","text":"val x = 1"},
                {"type":"image","mediaId":"aHR0cHM6Ly9leGFtcGxlLmNvbS9waWMucG5n","alt":"A chart"},
                {"type":"divider"}
              ],
              "extractionVersion":1}}
        """.trimIndent()
        val document = decodeArticleDocument(raw)!!
        assertEquals("Big Story", document.title)
        assertEquals("Ada Lovelace", document.author)
        assertEquals(8, document.blocks.size)

        val paragraph = document.blocks[0] as ArticleBlock.Paragraph
        assertEquals(5, paragraph.spans.size)
        assertEquals(ArticleInline.Bold("emphasis"), paragraph.spans[1])
        assertEquals("https://example.com", (paragraph.spans[3] as ArticleInline.Link).url)

        val heading = document.blocks[1] as ArticleBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("aHR0cHM6Ly9leGFtcGxlLmNvbS9waWMucG5n", (document.blocks[6] as ArticleBlock.Image).mediaId)
        assertTrue(document.blocks[7] is ArticleBlock.Divider)
    }

    @Test
    fun plainTextProjectionCoversEveryBlockType() {
        val document = ArticleDocument(
            title = "T", author = null, publishedAt = null, canonicalUrl = "https://x.example/p", sourceName = null,
            blocks = listOf(
                ArticleBlock.Paragraph(listOf(ArticleInline.Text("Hello "), ArticleInline.Bold("world"))),
                ArticleBlock.Heading(1, listOf(ArticleInline.Text("Head"))),
                ArticleBlock.UnorderedList(listOf(listOf(ArticleInline.Text("a")))),
                ArticleBlock.OrderedList(listOf(listOf(ArticleInline.Text("b")))),
                ArticleBlock.Quote(listOf(ArticleInline.Text("q"))),
                ArticleBlock.CodeBlock("code"),
                ArticleBlock.Image("media-id", "alt text"),
                ArticleBlock.Divider,
            ),
        )
        val text = document.plainText()
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("Head"))
        assertTrue(text.contains("• a"))
        assertTrue(text.contains("1. b"))
        assertTrue(text.contains("code"))
        assertTrue(text.contains("alt text"))
    }

    @Test
    fun rejectsMalformedPayloads() {
        assertNull(decodeArticleDocument("not json"))
        assertNull(decodeArticleDocument("""{"document":{"title":"x","blocks":null}}"""))
        assertNull(decodeArticleDocument("""{"document":{"title":"x","blocks":[{"type":"unknown_thing"}]}}"""))
    }
}
