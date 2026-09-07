package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.data.FeedItem

/** Small, display-ready model for the Wear OS inbox. */
data class InboxItemUi(
    val id: String,
    val title: String,
    val source: String,
    val summary: String,
    val timeLabel: String,
    val isPodcast: Boolean
)

fun FeedItem.toInboxItemUi(sourceName: String = "Feed"): InboxItemUi {
    val cleanTitle = title.cleanText().ifBlank { "Untitled" }
    val cleanSummary = description.orEmpty().cleanText()
    val stableId = link?.takeIf { it.isNotBlank() }
        ?: audioUrl?.takeIf { it.isNotBlank() }
        ?: cleanTitle

    return InboxItemUi(
        id = stableId,
        title = cleanTitle,
        source = sourceName.cleanText().ifBlank { "Feed" },
        summary = cleanSummary.ifBlank {
            if (audioUrl != null) "Podcast episode" else "Open to read more"
        },
        timeLabel = published.orEmpty().cleanText(),
        isPodcast = audioUrl != null
    )
}

internal fun String.cleanText(): String =
    replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
