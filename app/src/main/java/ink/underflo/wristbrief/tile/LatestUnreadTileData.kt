package ink.underflo.wristbrief.tile

import ink.underflo.wristbrief.data.CachedFeedItem

private const val MAX_TILE_TITLE_CHARS = 44

data class LatestUnreadTileData(
    val unreadCount: Int,
    val titles: List<String>
)

fun mapLatestUnreadTileData(
    cachedItems: List<CachedFeedItem>,
    enabledFeedIds: Set<String>,
    readItemIds: Set<String>,
    maxTitles: Int = 2
): LatestUnreadTileData {
    require(maxTitles >= 0) { "maxTitles must be non-negative" }
    val current = cachedItems
        .asSequence()
        .filter { it.feedId in enabledFeedIds }
        .sortedByDescending { it.cachedAtEpochMs }
        .toList()
    val unread = current.filter { it.id !in readItemIds }
    val titleSource = if (unread.isNotEmpty()) unread else current
    return LatestUnreadTileData(
        unreadCount = unread.size,
        titles = titleSource
            .asSequence()
            .map { normalizeTileTitle(it.title) }
            .filter { it.isNotBlank() }
            .take(maxTitles)
            .toList()
    )
}

internal fun normalizeTileTitle(value: String): String {
    val normalized = value.trim().replace(Regex("\\s+"), " ")
    if (normalized.length <= MAX_TILE_TITLE_CHARS) return normalized
    return normalized.take(MAX_TILE_TITLE_CHARS - 1).trimEnd() + "…"
}
