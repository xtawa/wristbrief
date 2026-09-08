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
import ink.underflo.wristbrief.media.SharedPreferencesPodcastProgressStore

class ContinueListeningTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val feedStore = SharedPreferencesFeedStore(this@ContinueListeningTileService)
        val progressStore = SharedPreferencesPodcastProgressStore(this@ContinueListeningTileService)
        val data = mapContinueListeningTileData(
            cachedItems = feedStore.cachedItems(),
            progress = progressStore.all()
        )
        val openApp = clickable(
            action = launchAction(ComponentName(this@ContinueListeningTileService, MainActivity::class.java)),
            id = "open_continue_listening"
        )
        val title = data?.title ?: "Nothing to resume"
        val resume = data?.resumeLabel ?: "Start a podcast in WristBrief"

        return Tile.Builder()
            .setTileTimeline(
                Timeline.fromLayoutElement(
                    primaryLayout(
                        titleSlot = { text("Continue listening".layoutString) },
                        mainSlot = {
                            textDataCard(
                                onClick = openApp,
                                title = { text(title.layoutString) },
                                content = { text(resume.layoutString) },
                                modifier = LayoutModifier.contentDescription("$title. $resume")
                            )
                        },
                        bottomSlot = {
                            textEdgeButton(
                                onClick = openApp,
                                modifier = LayoutModifier.contentDescription("Open WristBrief")
                            ) { text("Open".layoutString) }
                        }
                    )
                )
            )
            .build()
    }
}
