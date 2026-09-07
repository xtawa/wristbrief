package ink.underflo.wristbrief.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleDetailUiTest {
    @Test
    fun cleanText_removes_markup_script_style_and_decodes_entities() {
        val raw = """
            <style>body { display:none }</style>
            <p>Hello&nbsp;<strong>Wear</strong> &amp; RSS</p>
            <script>alert('x')</script>
            <div>&lt;safe&gt; &#39;quote&#39;</div>
        """.trimIndent()

        val clean = raw.cleanText()

        assertEquals("Hello Wear & RSS <safe> 'quote'", clean)
        assertFalse(clean.contains("display:none"))
        assertFalse(clean.contains("alert"))
        assertFalse(clean.contains("<script"))
    }

    @Test
    fun toArticleDetailUi_preserves_metadata_and_offline_state() {
        val inbox = InboxItemUi(
            id = "item-1",
            title = "<b>Title</b>",
            source = "Example &amp; Co",
            summary = "<p>Cached <em>preview</em></p>",
            timeLabel = "2026-09-08",
            isPodcast = false
        )

        val detail = inbox.toArticleDetailUi(isOffline = true)

        assertEquals("item-1", detail.id)
        assertEquals("Title", detail.title)
        assertEquals("Example & Co", detail.source)
        assertEquals("Cached preview", detail.body)
        assertEquals("2026-09-08", detail.timeLabel)
        assertTrue(detail.isOffline)
        assertFalse(detail.isPodcast)
    }

    @Test
    fun toArticleDetailUi_uses_safe_fallback_for_blank_body() {
        val detail = InboxItemUi(
            id = "item-2",
            title = "Story",
            source = "Feed",
            summary = "   ",
            timeLabel = "",
            isPodcast = false
        ).toArticleDetailUi(isOffline = false)

        assertEquals("No readable preview is available for this item.", detail.body)
    }
}
