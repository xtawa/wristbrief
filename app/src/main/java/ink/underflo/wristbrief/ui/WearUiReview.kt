package ink.underflo.wristbrief.ui

/**
 * Compact status copy for 192–240dp-class Wear displays.
 *
 * Keep transient state in one short line so large font scales and CJK titles do not compete
 * with duplicate headers. Detailed errors remain available in the empty/error action card.
 */
fun InboxUiState.wearStatusLine(): String = when {
    isLoading -> "Refreshing…"
    isOfflineFallback -> if (unreadCount > 0) "Offline · $unreadCount unread" else "Offline · cached"
    errorMessage != null -> "Refresh issue · cached"
    unreadCount > 0 -> "$unreadCount unread"
    items.isNotEmpty() -> "All caught up"
    else -> ""
}

/** Detail text used by the empty-state action, kept separate for deterministic UI tests. */
fun InboxUiState.wearEmptyDetail(): String = when {
    errorMessage != null -> errorMessage
    hasSubscriptions -> "Refresh to fetch your latest briefs"
    else -> "Add feeds from the phone companion"
}
