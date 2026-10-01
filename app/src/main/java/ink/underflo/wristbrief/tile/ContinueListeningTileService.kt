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
        val title = data?.title ?: this@ContinueListeningTileService.getString(R.string.tile_nothing_to_resume)
        val resume = data?.let { this@ContinueListeningTileService.getString(R.string.tile_resume_label, it.positionLabel, it.speedLabel) }
            ?: this@ContinueListeningTileService.getString(R.string.tile_start_podcast)
        val heading = this@ContinueListeningTileService.getString(R.string.tile_continue_listening)
        val openLabel = this@ContinueListeningTileService.getString(R.string.tile_open)
        val openDescription = this@ContinueListeningTileService.getString(R.string.tile_open_app_description)

        return Tile.Builder()
            .setTileTimeline(
                Timeline.fromLayoutElement(
                    primaryLayout(
                        titleSlot = { text(heading.layoutString) },
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
                                modifier = LayoutModifier.contentDescription(openDescription)
                            ) { text(openLabel.layoutString) }
                        }
                    )
                )
            )
            .build()
    }
}
