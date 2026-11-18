package com.dotforge.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Animations for alerts that are not a design: reminders, an approaching event, a caller's name. */
object AlertEffects {
    /** Frames of the bell swing in [reminder]. */
    const val SWING_FRAMES = 16

    /** A bell at [angle] radians, swinging from a pivot at its top; (u, v) in -1..1, v down. */
    private fun bell(u: Double, v: Double, angle: Double): Boolean {
        // rotate the point back around the pivot (0, -0.62)
        val px = 0.0
        val py = -0.62
        val c = cos(-angle)
        val s = sin(-angle)
        val x = (u - px) * c - (v - py) * s + px
        val y = (u - px) * s + (v - py) * c + py
        val top = hypot(x, y + 0.5) <= 0.1 && y < -0.42 // the loop at the top
        val dome = y in -0.48..0.28 && abs(x) <= 0.18 + (y + 0.48) * 0.42 && hypot(x, (y + 0.1) * 0.75) <= 0.52
        val lip = y in 0.24..0.36 && abs(x) <= 0.56
        val clapper = hypot(x, y - 0.5) <= 0.11
        return top || dome || lip || clapper
    }

    /**
     * A reminder: a bell swinging while one bright dot sweeps round the ring, then a short rest. It reads as
     * "something is due", different from a chat or a call.
     */
    fun reminder(profile: DeviceProfile): Animation {
        val n = profile.size
        val c = (n - 1) / 2.0
        val ring = profile.ring
        val frames = (0 until SWING_FRAMES).map { i ->
            val angle = 0.42 * sin(2 * PI * i / SWING_FRAMES)
            val f = Frame.blank(profile)
            for (cell in profile.ledCells) {
                val u = (cell % n - c) / c
                val v = (cell / n - c) / c
                if (hypot(u, v) < 0.86 && bell(u, v, angle)) f.pixels[cell] = 255
            }
            val head = ring.size * i / SWING_FRAMES
            for (t in 0 until 6) f.pixels[ring[Math.floorMod(head - t, ring.size)]] = 255 * (6 - t) / 6
            AnimFrame(f, 70)
        }
        val still = frames.first().frame.copy().also { f -> ring.forEach { f.pixels[it] = 0 } }
        return Animation(profile, frames + AnimFrame(still, 600))
    }

    /** How full the ring is and whether it pulses, [remainingMs] before something due in a [leadMs] window. */
    data class Approach(val fraction: Float, val pulse: Boolean)

    /**
     * The ring fills over the [leadMs] before an event and pulses in its last minute; null before the window
     * opens or once the event has started.
     */
    fun approach(remainingMs: Long, leadMs: Long): Approach? {
        if (leadMs <= 0 || remainingMs <= 0 || remainingMs > leadMs) return null
        val fraction = (1f - remainingMs.toFloat() / leadMs).coerceIn(0f, 1f)
        return Approach(fraction, remainingMs <= 60_000)
    }

    /** The approaching ring: filled to [fraction]; in the last minute it breathes full and dim. */
    fun approaching(profile: DeviceProfile, a: Approach): Animation {
        if (!a.pulse) return Animation.single(Effects.ring(profile, a.fraction, on = 255, off = 24), 1000)
        val full = Effects.ring(profile, 1f)
        val steps = 12
        return Animation(profile, (0 until steps).map { i ->
            AnimFrame(full.scaled(0.25f + 0.75f * (0.5f + 0.5f * cos(2 * PI * i / steps).toFloat())), 80)
        })
    }

    /**
     * A caller's name scrolling across the middle, after [prefix] (a glyph) when given. [speed] scales the scroll
     * (1 = normal), [brightness] 10-100 %.
     */
    fun name(profile: DeviceProfile, name: String, prefix: Animation? = null, speed: Float = 1f, brightness: Int = 100): Animation {
        val text = name.trim().ifEmpty { "?" }.take(MAX_NAME)
        val step = (60 / speed.coerceIn(0.25f, 4f)).roundToInt().coerceIn(Animation.MIN_FRAME_MS, 400)
        val scroll = Effects.marquee(profile, text, step).frames
        val lead = prefix?.frames?.let { f -> if (f.sumOf { it.durationMs } < 900) f + f else f }.orEmpty()
        val k = brightness.coerceIn(10, 100) / 100f
        return Animation(profile, (lead + scroll).map { if (k < 1f) AnimFrame(it.frame.scaled(k), it.durationMs) else it })
    }

    const val MAX_NAME = 24
}
