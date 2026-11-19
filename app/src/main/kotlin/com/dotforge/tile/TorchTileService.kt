package com.dotforge.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority

/** Quick Settings tile: the whole matrix as a soft torch, off by itself after 5 minutes. */
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
        /** The default auto-off, so a forgotten torch never stays on. The Torch page sets it (1-30 min, or never). */
        const val AUTO_OFF_MS = 5 * 60_000L

        /** When the torch last went on (Home counts down to its auto-off), epoch ms. */
        @Volatile var onSince = 0L

        /** How long the torch stays on now; null = until turned off. */
        fun autoOffMs(): Long? = runCatching { AppGraph.features.torchMinutes.value }.getOrDefault(5).takeIf { it > 0 }?.let { it * 60_000L }

        fun toggle() = set(!AppGraph.arbiter.has(ID))

        /** Turns the torch on (from now) or off. */
        fun set(on: Boolean) {
            if (!on) {
                AppGraph.arbiter.remove(ID)
                return
            }
            onSince = System.currentTimeMillis()
            post(autoOffMs())
        }

        /** The Torch page changed: a torch that is on takes the new brightness and auto-off, counted from when it went on. */
        fun restyle(now: Long = System.currentTimeMillis()) {
            if (!AppGraph.arbiter.has(ID)) return
            val off = autoOffMs()
            val left = off?.let { it - (now - onSince) }
            if (left != null && left <= 0) AppGraph.arbiter.remove(ID) else post(left, fade = false)
        }

        private fun post(offMs: Long?, fade: Boolean = true) {
            val t = runCatching { AppGraph.features.timing(com.dotforge.system.FeatureTiming.TORCH).value }.getOrDefault(com.dotforge.system.FeatureTiming.TORCH.default)
            val a = TorchLight.animation(AppGraph.profile, t.brightness, t.speed, offMs)
            AppGraph.arbiter.post(DisplayRequest(ID, Priority.TORCH, if (fade) a else Animation(a.profile, a.frames.takeLast(1)), loop = true, timeoutMs = offMs, label = "Torch"))
        }
    }
}

/** The torch's frames (pure, tested): a fade up to [brightness] % at [speed], then the light held until it turns off. */
object TorchLight {
    /** The fade at 1x; 0.5x takes twice as long, 2x half. */
    const val FADE_MS = 400
    private const val STEPS = 6
    /** With no auto-off the held frame lasts this long before the arbiter loops it (an invisible re-send). */
    private const val HOLD_MS = 6 * 60 * 60_000

    fun animation(p: com.dotforge.device.DeviceProfile, brightness: Int, speed: Float, offMs: Long?): Animation {
        val level = (255 * brightness.coerceIn(10, 100) / 100f).toInt()
        val step = (FADE_MS / speed.coerceIn(0.25f, 4f) / STEPS).toInt().coerceAtLeast(Animation.MIN_FRAME_MS)
        val fade = (1 until STEPS).map { i -> com.dotforge.device.AnimFrame(Frame.blank(p).fill(level * i / STEPS), step) }
        val hold = com.dotforge.device.AnimFrame(Frame.blank(p).fill(level), (offMs ?: HOLD_MS.toLong()).coerceAtMost(HOLD_MS.toLong()).toInt())
        return Animation(p, fade + hold)
    }
}
