package com.dotforge.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dotforge.AppGraph

/** Quick Settings tile: the next face for DF Always-on (Clock, Gallery, Moon, Now playing). */
class HubTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        AppGraph.toys.hubFace = AppGraph.toys.hubFace.next()
        update()
    }

    private fun update() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            subtitle = AppGraph.toys.hubFace.label
            updateTile()
        }
    }
}
