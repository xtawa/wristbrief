package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ArticleContentSanitizerTest {

    @Test
    fun stripsScriptsAndHtmlTagsCorrectly() {
        val html = """
            <div>
                <script>alert('malicious')</script>
                <style>body { color: red; }</style>
                <h1>Article Headline</h1>
                <p>First paragraph with <b>bold</b> text and &amp; ampersand.</p>
                <p>Second paragraph with <a href="https://example.com">a link</a>.</p>
            </div>
        """.trimIndent()

        val sanitized = ArticleContentSanitizer.sanitize(html)
        assertTrue(sanitized.paragraphs.size >= 3)
        assertEquals("Article Headline", sanitized.paragraphs[0])
        assertEquals("First paragraph with bold text and & ampersand.", sanitized.paragraphs[1])
        assertEquals("Second paragraph with a link.", sanitized.paragraphs[2])
        assertTrue(!sanitized.plainText.contains("script"))
        assertTrue(!sanitized.plainText.contains("style"))
    }

    @Test
    fun computesReadingTimeForEnglish() {
        // 450 words should be ~2-3 min read
        val text = (1..450).joinToString(" ") { "word$it" }
        val sanitized = ArticleContentSanitizer.sanitize(text)
        assertEquals(2, sanitized.readingTimeMinutes)
        assertEquals("2 min read", ArticleContentSanitizer.formatReadingTime(sanitized.readingTimeMinutes, Locale.ENGLISH))
    }

    @Test
    fun decodesNamedAndNumericHtmlEntitiesWithoutDoubleDecoding() {
        val sanitized = ArticleContentSanitizer.sanitize(
            "<p>isn&rsquo;t &amp; &quot;ok&quot; &#39;x&#39; &#x27;y&#x27; &amp;rsquo; &unknown;</p>"
        )

        assertEquals("isn’t & \"ok\" 'x' 'y' &rsquo; &unknown;", sanitized.plainText)
    }

    @Test
    fun computesReadingTimeForChinese() {
        val text = "这是一篇关于科技与未来的长文章。".repeat(50) // 800 CJK chars
        val sanitized = ArticleContentSanitizer.sanitize(text)
        assertEquals(2, sanitized.readingTimeMinutes)
        assertEquals("2 分钟阅读", ArticleContentSanitizer.formatReadingTime(sanitized.readingTimeMinutes, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun handlesNullAndEmptyInput() {
        val empty = ArticleContentSanitizer.sanitize(null)
        assertEquals("", empty.plainText)
        assertEquals(0, empty.paragraphs.size)
        assertEquals(1, empty.readingTimeMinutes)
    }
}
