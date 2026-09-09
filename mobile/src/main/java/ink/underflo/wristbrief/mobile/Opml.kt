package ink.underflo.wristbrief.mobile

private const val MAX_OPML_CHARS = 2_000_000
private const val MAX_OPML_FEEDS = 1_000

data class OpmlFeedEntry(
    val title: String,
    val url: String,
    val enabled: Boolean = true,
)

class OpmlFormatException(message: String) : IllegalArgumentException(message)

fun parseOpmlSubscriptions(raw: String): List<OpmlFeedEntry> {
    if (raw.length > MAX_OPML_CHARS) throw OpmlFormatException("OPML file is too large")
    if (!Regex("<\\s*opml\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
        throw OpmlFormatException("Missing OPML root element")
    }
    if (Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
        throw OpmlFormatException("External declarations are not supported")
    }

    val entries = mutableListOf<OpmlFeedEntry>()
    val seenUrls = linkedSetOf<String>()
    val outline = Regex("<\\s*outline\\b", RegexOption.IGNORE_CASE)
    var offset = 0
    while (true) {
        val match = outline.find(raw, offset) ?: break
        val tagEnd = findTagEnd(raw, match.range.last + 1)
        if (tagEnd < 0) throw OpmlFormatException("Malformed OPML outline")
        val attributes = parseAttributes(raw.substring(match.range.first, tagEnd + 1))
        val xmlUrl = attributes["xmlurl"]?.let(::decodeXmlAttribute)
        if (xmlUrl != null) {
            val normalized = normalizeFeedUrl(xmlUrl)
            if (normalized != null && seenUrls.add(normalized)) {
                if (entries.size >= MAX_OPML_FEEDS) throw OpmlFormatException("OPML contains too many feeds")
                val title = decodeXmlAttribute(attributes["title"] ?: attributes["text"].orEmpty())
                    .trim()
                    .take(160)
                val enabled = !attributes["wristbriefenabled"].equals("false", ignoreCase = true)
                entries += OpmlFeedEntry(title = title, url = normalized, enabled = enabled)
            }
        }
        offset = tagEnd + 1
    }
    return entries
}

fun exportOpmlSubscriptions(feeds: List<MobileFeedSubscription>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<opml version=\"2.0\">\n")
    append("  <head><title>WristBrief subscriptions</title></head>\n")
    append("  <body>\n")
    feeds.forEach { feed ->
        val normalized = normalizeFeedUrl(feed.url) ?: return@forEach
        val title = escapeXmlAttribute(feed.title.ifBlank { normalized })
        append("    <outline text=\"")
        append(title)
        append("\" title=\"")
        append(title)
        append("\" type=\"rss\" xmlUrl=\"")
        append(escapeXmlAttribute(normalized))
        append("\" wristbriefEnabled=\"")
        append(feed.enabled)
        append("\" />\n")
    }
    append("  </body>\n")
    append("</opml>\n")
}

private val attributePattern = Regex("""([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*(?:\"([^\"]*)\"|'([^']*)')""")
private val entityPattern = Regex("&(#x[0-9A-Fa-f]+|#\\d+|amp|quot|apos|lt|gt);", RegexOption.IGNORE_CASE)

private fun parseAttributes(tag: String): Map<String, String> = buildMap {
    attributePattern.findAll(tag).forEach { match ->
        val value = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        put(match.groupValues[1].lowercase(), value)
    }
}

private fun findTagEnd(raw: String, start: Int): Int {
    var quote: Char? = null
    for (index in start until raw.length) {
        val ch = raw[index]
        when {
            quote != null && ch == quote -> quote = null
            quote == null && (ch == '\'' || ch == '"') -> quote = ch
            quote == null && ch == '>' -> return index
        }
    }
    return -1
}

private fun decodeXmlAttribute(value: String): String = entityPattern.replace(value) { match ->
    when (val entity = match.groupValues[1].lowercase()) {
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        else -> {
            val codePoint = when {
                entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                else -> null
            }
            codePoint?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
    }
}

private fun escapeXmlAttribute(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        append(
            when (ch) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> ch
            },
        )
    }
}
