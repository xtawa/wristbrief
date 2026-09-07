package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.data.FeedSubscription

data class FeedManagementItemUi(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean,
    val statusLabel: String,
    val toggleLabel: String
)

fun FeedSubscription.toFeedManagementItemUi(): FeedManagementItemUi = FeedManagementItemUi(
    id = id,
    title = title.ifBlank { url },
    url = url,
    enabled = enabled,
    statusLabel = if (enabled) "Enabled" else "Paused",
    toggleLabel = if (enabled) "Pause" else "Enable"
)

fun List<FeedSubscription>.toFeedManagementItemsUi(): List<FeedManagementItemUi> =
    sortedWith(compareByDescending<FeedSubscription> { it.enabled }.thenBy { it.title.lowercase() })
        .map(FeedSubscription::toFeedManagementItemUi)
