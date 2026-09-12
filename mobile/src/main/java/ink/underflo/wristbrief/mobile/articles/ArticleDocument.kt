package ink.underflo.wristbrief.mobile.articles

import ink.underflo.wristbrief.mobile.ArticleContentSanitizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured article document rendered natively by Compose — the mobile twin of
 * the gateway's ArticleDocument. The app never renders arbitrary HTML.
 */
sealed interface ArticleInline {
    val text: String

    data class Text(override val text: String) : ArticleInline
    data class Bold(override val text: String) : ArticleInline
    data class Italic(override val text: String) : ArticleInline
    data class Code(override val text: String) : ArticleInline
    data class Link(override val text: String, val url: String) : ArticleInline
}

sealed interface ArticleBlock {
    data class Paragraph(val spans: List<ArticleInline>) : ArticleBlock
    data class Heading(val level: Int, val spans: List<ArticleInline>) : ArticleBlock
    data class UnorderedList(val items: List<List<ArticleInline>>) : ArticleBlock
    data class OrderedList(val items: List<List<ArticleInline>>) : ArticleBlock
    data class Quote(val spans: List<ArticleInline>) : ArticleBlock
    data class CodeBlock(val text: String) : ArticleBlock
    data class Image(val mediaId: String, val alt: String) : ArticleBlock
    data object Divider : ArticleBlock
}

data class ArticleDocument(
    val title: String,
    val author: String?,
    val publishedAt: String?,
    val canonicalUrl: String,
    val sourceName: String?,
    val blocks: List<ArticleBlock>,
)

/**
 * Builds a safe native document from a feed's full-content field. This keeps
 * public RSS feeds readable while signed out or when the gateway is offline;
 * the gateway remains responsible for page extraction and rich HTML parsing.
 */
fun articleDocumentFromRss(
    canonicalUrl: String,
    rssContent: String?,
    title: String?,
    sourceName: String?,
): ArticleDocument? {
    val sanitized = ArticleContentSanitizer.sanitize(rssContent)
    if (sanitized.plainText.length < MIN_FULL_TEXT_CHARS || sanitized.paragraphs.isEmpty()) return null
    return ArticleDocument(
        title = title.orEmpty(),
        author = null,
        publishedAt = null,
        canonicalUrl = canonicalUrl,
        sourceName = sourceName,
        blocks = sanitized.paragraphs.map { paragraph ->
            ArticleBlock.Paragraph(listOf(ArticleInline.Text(paragraph)))
        },
    )
}

private const val MIN_FULL_TEXT_CHARS = 500

private val json = Json { ignoreUnknownKeys = true }

fun decodeArticleDocument(raw: String): ArticleDocument? = runCatching {
    val root = json.parseToJsonElement(raw).jsonObject
    val document = root["document"]?.jsonObject ?: root
    val blocks = (document["blocks"]?.jsonArray ?: return@runCatching null).mapNotNull { element ->
        val block = element.jsonObject
        when (block["type"]?.jsonPrimitive?.contentOrNull) {
            "paragraph" -> ArticleBlock.Paragraph(block.decodeSpans())
            "heading" -> ArticleBlock.Heading(
                level = (block["level"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 6),
                spans = block.decodeSpans(),
            )
            "unordered_list" -> ArticleBlock.UnorderedList(block.decodeItems())
            "ordered_list" -> ArticleBlock.OrderedList(block.decodeItems())
            "quote" -> ArticleBlock.Quote(block.decodeSpans())
            "code_block" -> ArticleBlock.CodeBlock(block["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null)
            "image" -> ArticleBlock.Image(
                mediaId = block["mediaId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                alt = block["alt"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "divider" -> ArticleBlock.Divider
            else -> null
        }
    }
    ArticleDocument(
        title = document["title"]?.jsonPrimitive?.contentOrNull ?: "",
        author = document["author"]?.jsonPrimitive?.contentOrNull,
        publishedAt = document["publishedAt"]?.jsonPrimitive?.contentOrNull,
        canonicalUrl = document["canonicalUrl"]?.jsonPrimitive?.contentOrNull ?: "",
        sourceName = document["sourceName"]?.jsonPrimitive?.contentOrNull,
        blocks = blocks,
    ).takeIf { it.blocks.isNotEmpty() }
}.getOrNull()

private fun kotlinx.serialization.json.JsonObject.decodeSpans(): List<ArticleInline> =
    (this["spans"]?.jsonArray ?: emptyList()).mapNotNull { spanElement ->
        val span = spanElement.jsonObject
        val text = span["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        when (span["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> ArticleInline.Text(text)
            "bold" -> ArticleInline.Bold(text)
            "italic" -> ArticleInline.Italic(text)
            "code" -> ArticleInline.Code(text)
            "link" -> ArticleInline.Link(text, span["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null)
            else -> null
        }
    }

private fun kotlinx.serialization.json.JsonObject.decodeItems(): List<List<ArticleInline>> =
    (this["items"]?.jsonArray ?: emptyList()).map { item ->
        item.jsonArray.mapNotNull { spanElement ->
            val span = spanElement.jsonObject
            val text = span["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            when (span["type"]?.jsonPrimitive?.contentOrNull) {
                "bold" -> ArticleInline.Bold(text)
                "italic" -> ArticleInline.Italic(text)
                "code" -> ArticleInline.Code(text)
                "link" -> ArticleInline.Link(text, span["url"]?.jsonPrimitive?.contentOrNull ?: "")
                else -> ArticleInline.Text(text)
            }
        }
    }

/** Plain-text projection for AI summarization and reading-time estimates. */
fun ArticleDocument.plainText(): String = blocks.joinToString("\n\n") { block ->
    when (block) {
        is ArticleBlock.Paragraph -> block.spans.joinToString("") { it.text }
        is ArticleBlock.Heading -> block.spans.joinToString("") { it.text }
        is ArticleBlock.UnorderedList -> block.items.joinToString("\n") { spans -> "- " + spans.joinToString("") { it.text } }
        is ArticleBlock.OrderedList -> block.items.mapIndexed { index, spans -> "${index + 1}. " + spans.joinToString("") { it.text } }.joinToString("\n")
        is ArticleBlock.Quote -> block.spans.joinToString("") { it.text }
        is ArticleBlock.CodeBlock -> block.text
        is ArticleBlock.Image -> block.alt
        ArticleBlock.Divider -> ""
    }
}
