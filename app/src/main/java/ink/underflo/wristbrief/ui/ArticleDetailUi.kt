package ink.underflo.wristbrief.ui

/** Display-ready article/podcast detail state for the Wear reader. */
data class ArticleDetailUi(
    val id: String,
    val title: String,
    val source: String,
    val body: String,
    val timeLabel: String,
    val isPodcast: Boolean,
    val isOffline: Boolean
)

fun InboxItemUi.toArticleDetailUi(isOffline: Boolean): ArticleDetailUi = ArticleDetailUi(
    id = id,
    title = title.cleanText().ifBlank { "Untitled" },
    source = source.cleanText().ifBlank { "Feed" },
    body = summary.cleanText().ifBlank {
        if (isPodcast) "Podcast episode" else "No readable preview is available for this item."
    },
    timeLabel = timeLabel.cleanText(),
    isPodcast = isPodcast,
    isOffline = isOffline
)

internal fun String.cleanText(): String =
    replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
