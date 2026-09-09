package ink.underflo.wristbrief.mobile

data class KeywordWatchUiState(
    val summary: String,
    val supportingText: String,
)

fun keywordWatchUiState(keywords: Iterable<String>): KeywordWatchUiState {
    val normalized = normalizeWatchKeywords(keywords)
    return if (normalized.isEmpty()) {
        KeywordWatchUiState(
            summary = "All items",
            supportingText = "No keyword filter; every item from this feed can appear on Wear.",
        )
    } else {
        KeywordWatchUiState(
            summary = normalized.joinToString(" · "),
            supportingText = "Wear shows items whose title or description matches any keyword.",
        )
    }
}

fun keywordWatchEditorText(keywords: Iterable<String>): String = normalizeWatchKeywords(keywords).joinToString(", ")
