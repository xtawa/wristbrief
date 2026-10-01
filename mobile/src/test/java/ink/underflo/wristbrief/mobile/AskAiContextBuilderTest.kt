package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAiContextBuilderTest {
    private fun item(id: String, title: String = "Title $id", description: String? = "<p>Body $id</p>", feedTitle: String = "Feed") =
        MobileFeedItem(
            id = id,
            feedId = "feed",
            feedTitle = feedTitle,
            title = title,
            link = "https://example.com/$id",
            description = description,
            published = null,
            audioUrl = null,
            cachedAtEpochMs = 0L,
        )

    private val all = (1..10).map { item("a$it") }
    private val unread = all.take(3)
    private val saved = listOf(all[5])

    @Test
    fun `today scope uses the brief-eligible set, not the whole inbox`() {
        val items = AskAiContextBuilder.itemsForScope(AskAiScope.Today, all, unread, saved)
        assertEquals(unread, items)
    }

    @Test
    fun `today scope falls back to all items once everything is read`() {
        val items = AskAiContextBuilder.itemsForScope(AskAiScope.Today, all, emptyList(), saved)
        assertEquals(all, items)
    }

    @Test
    fun `unread and saved scopes return their own lists`() {
        assertEquals(unread, AskAiContextBuilder.itemsForScope(AskAiScope.Unread, all, unread, saved))
        assertEquals(saved, AskAiContextBuilder.itemsForScope(AskAiScope.Saved, all, unread, saved))
        assertEquals(all, AskAiContextBuilder.itemsForScope(AskAiScope.All, all, unread, saved))
    }

    @Test
    fun `source count and cited items reflect what was sent, capped at the request limit`() {
        val context = AskAiContextBuilder.build(AskAiScope.All, "q", all, unread, saved, prefilledContent = "")
        assertEquals(AskAiContextBuilder.MAX_ITEMS, context.items.size)
        assertEquals(AskAiContextBuilder.MAX_ITEMS, context.sourceCount)
        assertFalse(context.usesPrefilledContent)
        assertTrue(context.content.contains("Title: Title a1"))
        assertTrue(context.content.contains("Summary: Body a1"))
        assertFalse("html must be stripped", context.content.contains("<p>"))
        assertFalse("items beyond the limit are not cited", context.items.any { it.id == "a7" })
    }

    @Test
    fun `article scope sends the handed-over content and cites no inbox items`() {
        val context = AskAiContextBuilder.build(AskAiScope.Article, "q", all, unread, saved, prefilledContent = "clip text")
        assertTrue(context.usesPrefilledContent)
        assertEquals("clip text", context.content)
        assertTrue(context.items.isEmpty())
        assertEquals(1, context.sourceCount)
    }

    @Test
    fun `switching away from article scope drops the prefilled content`() {
        val context = AskAiContextBuilder.build(AskAiScope.Saved, "q", all, unread, saved, prefilledContent = "clip text")
        assertFalse(context.usesPrefilledContent)
        assertEquals(saved, context.items)
        assertFalse(context.content.contains("clip text"))
    }

    @Test
    fun `empty scope sends only the question and reports zero sources`() {
        val context = AskAiContextBuilder.build(AskAiScope.Saved, "what changed?", all, unread, emptyList(), prefilledContent = "")
        assertEquals("what changed?", context.content)
        assertEquals(0, context.sourceCount)
        assertTrue(context.items.isEmpty())
    }

    @Test
    fun `article scope without prefilled content behaves like an empty scope`() {
        val context = AskAiContextBuilder.build(AskAiScope.Article, "q", all, unread, saved, prefilledContent = "")
        assertFalse(context.usesPrefilledContent)
        assertEquals("q", context.content)
    }
}
