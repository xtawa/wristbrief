package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedItem

private const val WEAR_INBOX_SUMMARY_CODE_POINTS = 160

/** Small, display-ready model for the Wear OS inbox. */
data class InboxItemUi(
    val id: String,
    val title: String,
    val source: String,
    val summary: String,
    val timeLabel: String,
    val isPodcast: Boolean,
    val isRead: Boolean = false,
    val isSaved: Boolean = false,
    val audioUrl: String? = null
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
        }.ellipsizeCodePoints(WEAR_INBOX_SUMMARY_CODE_POINTS),
        timeLabel = published.orEmpty().cleanText(),
        isPodcast = audioUrl != null,
        audioUrl = audioUrl
    )
}

fun CachedFeedItem.toInboxItemUi(isRead: Boolean, isSaved: Boolean = false): InboxItemUi =
    asFeedItem().toInboxItemUi(feedTitle).copy(
        id = id,
        isRead = isRead,
        isSaved = isSaved
    )

/** Truncate by Unicode code point so emoji/surrogate pairs are never split for small Wear cards. */
internal fun String.ellipsizeCodePoints(maxCodePoints: Int): String {
    require(maxCodePoints > 0)
    if (codePointCount(0, length) <= maxCodePoints) return this
    val endIndex = offsetByCodePoints(0, maxCodePoints)
    return substring(0, endIndex).trimEnd() + "…"
}
