package ink.underflo.wristbrief.mobile

import java.util.Locale

data class SanitizedArticle(
    val plainText: String,
    val paragraphs: List<String>,
    val wordCount: Int,
    val readingTimeMinutes: Int,
)

object ArticleContentSanitizer {
    private val htmlEntityPattern = Regex("&(?:#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val namedHtmlEntities = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&rsquo;" to "’",
        "&lsquo;" to "‘",
        "&rdquo;" to "”",
        "&ldquo;" to "“",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&middot;" to "·",
        "&copy;" to "©",
        "&reg;" to "®",
        "&trade;" to "™",
    )

    private fun decodeHtmlEntities(value: String): String = htmlEntityPattern.replace(value) { match ->
        val entity = match.value
        when {
            entity.startsWith("&#x", ignoreCase = true) -> {
                val codePoint = entity.drop(3).dropLast(1).toIntOrNull(16)
                if (codePoint != null && codePoint in 0..0x10FFFF) String(Character.toChars(codePoint)) else entity
            }
            entity.startsWith("&#") -> {
                val codePoint = entity.drop(2).dropLast(1).toIntOrNull()
                if (codePoint != null && codePoint in 0..0x10FFFF) String(Character.toChars(codePoint)) else entity
            }
            else -> namedHtmlEntities[entity] ?: entity
        }
    }

    fun sanitize(rawHtmlOrText: String?): SanitizedArticle {
        if (rawHtmlOrText.isNullOrBlank()) {
            return SanitizedArticle(
                plainText = "",
                paragraphs = emptyList(),
                wordCount = 0,
                readingTimeMinutes = 1,
            )
        }

        var text = rawHtmlOrText.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div|h[1-6]|li|blockquote|section|article)[^>]*>"), "\n\n")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = decodeHtmlEntities(text)

        val paragraphs = text.split(Regex("\n+"))
            .map { it.trim().replace(Regex("[ \\t]+"), " ").replace(Regex("\\s+([.,;:!?])"), "$1") }
            .filter { it.isNotBlank() }
        val plainText = paragraphs.joinToString("\n\n")

        val latinWords = plainText.split(Regex("\\s+")).count { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }
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
