package ink.underflo.wristbrief.complication

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedSubscription

data class UnreadComplicationSnapshot(
    val unreadCount: Int,
    val latestTitle: String?
) {
    val shortText: String
        get() = if (unreadCount > 99) "99+" else unreadCount.toString()

    val longText: String
        get() = when {
            unreadCount <= 0 -> "All caught up"
            latestTitle.isNullOrBlank() -> "$unreadCount unread"
            else -> "$unreadCount unread · $latestTitle"
        }
}

internal fun buildUnreadComplicationSnapshot(
    subscriptions: List<FeedSubscription>,
    cachedItems: List<CachedFeedItem>,
    readItemIds: Set<String>
): UnreadComplicationSnapshot {
    val enabledFeedIds = subscriptions.asSequence()
        .filter { it.enabled }
        .mapTo(hashSetOf()) { it.id }
    val unread = cachedItems.asSequence()
        .filter { it.feedId in enabledFeedIds && it.id !in readItemIds }
        .sortedByDescending { it.cachedAtEpochMs }
        .toList()
    return UnreadComplicationSnapshot(
        unreadCount = unread.size,
        latestTitle = unread.firstOrNull()?.title?.compactComplicationText()
    )
}

internal fun String.compactComplicationText(maxCodePoints: Int = 28): String {
    if (maxCodePoints <= 0) return ""
    val normalized = trim().replace(Regex("\\s+"), " ")
    val codePointCount = normalized.codePointCount(0, normalized.length)
    if (codePointCount <= maxCodePoints) return normalized
    val endIndex = normalized.offsetByCodePoints(0, maxCodePoints - 1)
    return normalized.substring(0, endIndex).trimEnd() + "…"
}
