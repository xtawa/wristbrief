package ink.underflo.wristbrief.mobile

data class KeywordWatchUiState(
    val keywords: List<String>,
)

fun keywordWatchUiState(keywords: Iterable<String>): KeywordWatchUiState =
    KeywordWatchUiState(normalizeWatchKeywords(keywords))

fun keywordWatchEditorText(keywords: Iterable<String>): String =
    normalizeWatchKeywords(keywords).joinToString(", ")
