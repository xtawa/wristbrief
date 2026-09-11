package ink.underflo.wristbrief.mobile

import java.util.Locale

data class SanitizedArticle(
    val plainText: String,
    val paragraphs: List<String>,
    val wordCount: Int,
    val readingTimeMinutes: Int,
)

object ArticleContentSanitizer {

    fun sanitize(rawHtmlOrText: String?): SanitizedArticle {
        if (rawHtmlOrText.isNullOrBlank()) {
            return SanitizedArticle(
                plainText = "",
                paragraphs = emptyList(),
                wordCount = 0,
                readingTimeMinutes = 1,
            )
        }

        // 1. Remove script and style blocks
        var text = rawHtmlOrText.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")

        // 2. Convert block-level tags and breaks to newlines
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div|h[1-6]|li|blockquote|section|article)[^>]*>"), "\n\n")

        // 3. Remove all remaining tags
        text = text.replace(Regex("<[^>]+>"), " ")

        // 4. Decode common HTML entities
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")

        // 5. Break into clean paragraphs
        val paragraphs = text.split(Regex("\n+"))
            .map { it.trim().replace(Regex("[ \\t]+"), " ").replace(Regex("\\s+([.,;:!?])"), "$1") }
            .filter { it.isNotBlank() }

        val plainText = paragraphs.joinToString("\n\n")

        // 6. Calculate word/char count and reading time
        // English/Latin words
        val latinWords = plainText.split(Regex("\\s+")).count { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }
        // CJK characters
        val cjkChars = plainText.count { c ->
            c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0x20000..0x2A6DF
        }

        val totalWords = latinWords + cjkChars
        val minutes = ((latinWords / 200.0) + (cjkChars / 350.0)).toInt().coerceAtLeast(1)

        return SanitizedArticle(
            plainText = plainText,
            paragraphs = paragraphs,
            wordCount = totalWords,
            readingTimeMinutes = minutes,
        )
    }

    fun formatReadingTime(minutes: Int, locale: Locale = Locale.getDefault()): String {
        return if (locale.language.startsWith("zh", ignoreCase = true)) {
            "$minutes 分钟阅读"
        } else {
            "$minutes min read"
        }
    }
}
