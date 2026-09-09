package ink.underflo.wristbrief.mobile

import java.net.URI

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
    val current = feeds()
    val knownUrls = current.mapNotNull { normalizeFeedUrl(it.url) }.toMutableSet()
    val additions = mutableListOf<MobileFeedSubscription>()
    var duplicates = 0
    var failed = 0

    entries.forEach { entry ->
        if (!knownUrls.add(entry.url)) {
            duplicates += 1
            return@forEach
        }

        val discovered = runCatching { validateForBulkImport(entry.url) }.getOrElse {
            knownUrls.remove(entry.url)
            failed += 1
            return@forEach
        }
        val title = entry.title.trim().ifBlank { discovered ?: URI(entry.url).host }
        additions += MobileFeedSubscription(
            id = stableFeedId(entry.url),
            title = title,
            url = entry.url,
            enabled = entry.enabled,
        )
    }

    val merged = if (additions.isEmpty()) {
        current
    } else {
        persistBulkImport(current + additions).feeds
    }
    return OpmlImportResult.Success(
        feeds = merged,
        importedCount = additions.size,
        duplicateCount = duplicates,
        failedValidationCount = failed,
    )
}

fun MobileFeedManager.exportOpml(): String = exportOpmlSubscriptions(feeds())
