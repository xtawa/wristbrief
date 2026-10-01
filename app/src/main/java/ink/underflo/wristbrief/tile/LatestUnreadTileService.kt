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
import ink.underflo.wristbrief.R
import ink.underflo.wristbrief.data.visibleInboxItems
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore

class LatestUnreadTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val store = SharedPreferencesFeedStore(this@LatestUnreadTileService)
        val subscriptions = store.subscriptions()
        val enabledFeedIds = subscriptions
            .asSequence()
            .filter { it.enabled }
            .mapTo(linkedSetOf()) { it.id }
        val data = mapLatestUnreadTileData(
            // Same visibility rule as the Inbox (enabled feed + watch keywords) so counts agree.
            cachedItems = visibleInboxItems(subscriptions, store.cachedItems()),
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
            0 -> this@LatestUnreadTileService.getString(R.string.tile_caught_up)
            1 -> this@LatestUnreadTileService.getString(R.string.tile_unread_one)
            else -> this@LatestUnreadTileService.getString(R.string.tile_unread_many, data.unreadCount)
        }
        val primary = data.titles.firstOrNull() ?: this@LatestUnreadTileService.getString(R.string.tile_open_inbox_prompt)
        val openInboxLabel = this@LatestUnreadTileService.getString(R.string.tile_open_inbox)
        val openInboxDescription = this@LatestUnreadTileService.getString(R.string.tile_open_inbox_description)
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
                                modifier = LayoutModifier.contentDescription(openInboxDescription)
                            ) {
                                text(openInboxLabel.layoutString)
                            }
                        }
                    )
                )
            )
            .build()
    }
}
