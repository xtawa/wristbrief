package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.complication.UnreadComplicationSnapshot
import ink.underflo.wristbrief.complication.compactComplicationText
import ink.underflo.wristbrief.tile.normalizeTileTitle

/**
 * Captures only the data currently rendered by the unread Tile and complication.
 * Transient inbox state and content outside those glance surfaces intentionally do not
 * participate so successful feed refreshes do not wake the system surfaces unnecessarily.
 */
internal data class GlanceSurfaceUpdateFingerprint(
    val tileUnreadCount: Int,
    val tileTitles: List<String>,
    val complicationShortText: String,
    val complicationLongText: String,
)

internal fun InboxUiState.glanceSurfaceUpdateFingerprint(): GlanceSurfaceUpdateFingerprint {
    val unreadItems = items.filterNot { it.isRead }
    val tileTitleSource = if (unreadItems.isNotEmpty()) unreadItems else items
    val complication = UnreadComplicationSnapshot(
        unreadCount = unreadItems.size,
        latestTitle = unreadItems.firstOrNull()?.title?.compactComplicationText(),
    )
    return GlanceSurfaceUpdateFingerprint(
        tileUnreadCount = unreadItems.size,
        tileTitles = tileTitleSource
            .asSequence()
            .map { normalizeTileTitle(it.title) }
            .filter { it.isNotBlank() }
            .take(2)
            .toList(),
        complicationShortText = complication.shortText,
        complicationLongText = complication.longText,
    )
}
