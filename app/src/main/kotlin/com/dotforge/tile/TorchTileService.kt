package com.dotforge.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority

/** Quick Settings tile: the whole matrix as a soft torch. */
class TorchTileService : TileService() {

    override fun onStartListening() = update(AppGraph.arbiter.has(ID))

    override fun onClick() {
        // has() is a snapshot and toggle() is applied asynchronously, so show the state we asked for
        val on = !AppGraph.arbiter.has(ID)
        toggle()
        update(on)
    }

    private fun update(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        const val ID = "torch"

        fun toggle() {
            val arbiter = AppGraph.arbiter
            if (arbiter.has(ID)) {
                arbiter.remove(ID)
            } else {
                val full = Frame.blank(AppGraph.profile).fill(255)
                arbiter.post(DisplayRequest(ID, Priority.TORCH, Animation.single(full), loop = true, label = "Torch"))
            }
        }
    }
}
