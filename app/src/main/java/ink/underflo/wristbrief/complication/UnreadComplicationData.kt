package ink.underflo.wristbrief.complication

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedSubscription
import ink.underflo.wristbrief.data.visibleInboxItems

data class UnreadComplicationSnapshot(
    val unreadCount: Int,
    val latestTitle: String?
) {
    val shortText: String
        get() = if (unreadCount > 99) "99+" else unreadCount.toString()
}

/** Localized long text; the caller supplies resource-backed formatters. */
internal fun UnreadComplicationSnapshot.longText(
    caughtUp: () -> String,
    unread: (Int) -> String,
    unreadWithTitle: (Int, String) -> String,
): String = when {
    unreadCount <= 0 -> caughtUp()
    latestTitle.isNullOrBlank() -> unread(unreadCount)
    else -> unreadWithTitle(unreadCount, latestTitle)
}

/** Unread items exactly as the Inbox counts them (enabled feed + watch keywords), newest first. */
internal fun buildUnreadComplicationSnapshot(
    subscriptions: List<FeedSubscription>,
    cachedItems: List<CachedFeedItem>,
    readItemIds: Set<String>
): UnreadComplicationSnapshot {
    val unread = visibleInboxItems(subscriptions, cachedItems)
        .asSequence()
        .filter { it.id !in readItemIds }
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
