package ink.underflo.wristbrief.mobile

sealed interface OpmlImportResult {
    data class Success(
        val feeds: List<MobileFeedSubscription>,
        val importedCount: Int,
        val duplicateCount: Int,
        val failedValidationCount: Int,
    ) : OpmlImportResult

    data class Error(val message: String) : OpmlImportResult
}

suspend fun MobileFeedManager.importOpml(raw: String): OpmlImportResult {
    val entries = runCatching { parseOpmlSubscriptions(raw) }
        .getOrElse { return OpmlImportResult.Error(it.message ?: "Could not read OPML") }
    val knownUrls = feeds().mapNotNull { normalizeFeedUrl(it.url) }.toMutableSet()
    var imported = 0
    var duplicates = 0
    var failed = 0

    entries.forEach { entry ->
        if (!knownUrls.add(entry.url)) {
            duplicates += 1
            return@forEach
        }
        when (val result = add(entry.url, entry.title)) {
            is FeedMutationResult.Error -> {
                knownUrls.remove(entry.url)
                failed += 1
            }
            is FeedMutationResult.Success -> {
                imported += 1
                if (!entry.enabled) {
                    val importedFeed = result.feeds.firstOrNull { normalizeFeedUrl(it.url) == entry.url }
                    if (importedFeed != null) setEnabled(importedFeed.id, false)
                }
            }
        }
    }

    return OpmlImportResult.Success(
        feeds = feeds(),
        importedCount = imported,
        duplicateCount = duplicates,
        failedValidationCount = failed,
    )
}

fun MobileFeedManager.exportOpml(): String = exportOpmlSubscriptions(feeds())
