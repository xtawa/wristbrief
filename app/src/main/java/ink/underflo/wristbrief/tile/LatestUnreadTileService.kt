package ink.underflo.wristbrief.tile

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textDataCard
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import ink.underflo.wristbrief.MainActivity
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore

class LatestUnreadTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val store = SharedPreferencesFeedStore(this@LatestUnreadTileService)
        val enabledFeedIds = store.subscriptions()
            .asSequence()
            .filter { it.enabled }
            .mapTo(linkedSetOf()) { it.id }
        val data = mapLatestUnreadTileData(
            cachedItems = store.cachedItems(),
            enabledFeedIds = enabledFeedIds,
            readItemIds = store.readItemIds()
        )
        val openInbox = clickable(
            action = launchAction(
                ComponentName(this@LatestUnreadTileService, MainActivity::class.java)
            ),
            id = "open_inbox"
        )
        val title = when (data.unreadCount) {
            0 -> "WristBrief · caught up"
            1 -> "1 unread brief"
            else -> "${data.unreadCount} unread briefs"
        }
        val primary = data.titles.firstOrNull() ?: "Open Inbox for your latest briefs"
        val secondary = data.titles.getOrNull(1)

        return Tile.Builder()
            .setTileTimeline(
                Timeline.fromLayoutElement(
                    primaryLayout(
                        titleSlot = { text(title.layoutString) },
                        mainSlot = {
                            textDataCard(
                                onClick = openInbox,
                                title = { text(primary.layoutString) },
                                modifier = LayoutModifier.contentDescription(
                                    if (secondary == null) primary else "$primary. $secondary"
                                ),
                                content = secondary?.let { second ->
                                    { text(second.layoutString) }
                                }
                            )
                        },
                        bottomSlot = {
                            textEdgeButton(
                                onClick = openInbox,
                                modifier = LayoutModifier.contentDescription("Open WristBrief Inbox")
                            ) {
                                text("Open Inbox".layoutString)
                            }
                        }
                    )
                )
            )
            .build()
    }
}
