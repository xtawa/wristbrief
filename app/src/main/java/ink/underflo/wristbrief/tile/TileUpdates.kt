package ink.underflo.wristbrief.tile

import android.content.Context
import androidx.wear.tiles.TileService

fun requestLatestUnreadTileUpdate(context: Context) {
    TileService.getUpdater(context.applicationContext)
        .requestUpdate(LatestUnreadTileService::class.java)
}
