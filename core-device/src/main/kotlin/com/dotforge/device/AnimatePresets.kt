package com.dotforge.device

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Animate this": turns one picture into a short animation. Every preset gives ordinary frames, so the result
 * stays editable frame by frame.
 */
enum class AnimatePreset(val label: String, val hint: String) {
    BLINK("Blink", "On, off, on"),
    PULSE("Pulse", "Fades in and out"),
    ROTATE("Rotate", "Turns once around the middle"),
    SLIDE_LEFT("Slide in from left", "Moves in from the left edge"),
    SLIDE_RIGHT("Slide in from right", "Moves in from the right edge"),
    SLIDE_TOP("Slide in from top", "Drops in from the top"),
    SLIDE_BOTTOM("Slide in from bottom", "Rises from the bottom"),
    GROW("Grow from the centre", "Starts small and grows"),
    WIPE("Wipe", "Appears column by column"),
    MARQUEE("Scroll", "Moves right to left, like scrolling text"),
    SPARKLE("Sparkle", "Twinkles in place"),
    ;

    fun apply(frame: Frame, random: Random = Random(7)): Animation = AnimatePresets.make(this, frame, random)
}

object AnimatePresets {
    fun make(preset: AnimatePreset, f: Frame, random: Random = Random(7)): Animation {
        val p = f.profile
        val n = p.size
        return when (preset) {
            AnimatePreset.BLINK -> anim(p, listOf(f.copy() to 400, Frame.blank(p) to 250))
            AnimatePreset.PULSE -> {
                val levels = (1..8).map { it / 8f } + (7 downTo 1).map { it / 8f }
                anim(p, levels.map { k -> f.scaled(k) to if (k == 1f) 300 else 60 })
            }
            AnimatePreset.ROTATE -> anim(p, (0 until 12).map { i -> rotate(f, i * PI / 6) to 80 })
            AnimatePreset.SLIDE_LEFT -> slide(f, n, -1, 0)
            AnimatePreset.SLIDE_RIGHT -> slide(f, n, 1, 0)
            AnimatePreset.SLIDE_TOP -> slide(f, n, 0, -1)
            AnimatePreset.SLIDE_BOTTOM -> slide(f, n, 0, 1)
            AnimatePreset.GROW -> {
                val steps = 10
                anim(p, (1..steps).map { i -> scale(f, i / steps.toDouble()) to if (i == steps) 600 else 50 })
            }
            AnimatePreset.WIPE -> {
                val cols = (0 until n).filter { x -> (0 until n).any { y -> p.isActive(x, y) } }
                anim(p, cols.mapIndexed { i, x ->
                    Frame.of(p, IntArray(p.cellCount) { c -> if (c % n <= x) f.pixels[c] else 0 }) to if (i == cols.lastIndex) 600 else 40
                })
            }
            AnimatePreset.MARQUEE -> {
                // in from the right edge, across, and out at the left: the picture passes once
                anim(p, (n downTo -n).map { dx -> shift(f, dx, 0) to 60 })
            }
            AnimatePreset.SPARKLE -> {
                val lit = p.ledCells.filter { f.pixels[it] > 0 }
                anim(p, List(10) {
                    val out = f.pixels.copyOf()
                    for (c in lit) if (random.nextInt(4) == 0) out[c] = (f.pixels[c] * 0.3).roundToInt()
                    repeat(3) {
                        val c = p.ledCells[random.nextInt(p.ledCount)]
                        if (f.pixels[c] == 0) out[c] = 180
                    }
                    Frame.of(p, out) to 90
                })
            }
        }
    }

    private fun anim(p: DeviceProfile, frames: List<Pair<Frame, Int>>) = Animation(p, frames.map { AnimFrame(it.first, it.second) })

    /** Slide in from one side: starts just off that edge and ends where the picture is. */
    private fun slide(f: Frame, n: Int, sx: Int, sy: Int): Animation {
        val steps = 12
        val frames = (steps downTo 0).map { i ->
            val k = i / steps.toDouble()
            shift(f, (sx * n * k).roundToInt(), (sy * n * k).roundToInt()) to if (i == 0) 600 else 40
        }
        return anim(f.profile, frames)
    }

    /** [f] moved by ([dx], [dy]) cells; what leaves the grid is lost. */
    fun shift(f: Frame, dx: Int, dy: Int): Frame {
        val p = f.profile
        val n = p.size
        val out = IntArray(p.cellCount)
        for (y in 0 until n) for (x in 0 until n) {
            val sx = x - dx
            val sy = y - dy
            if (sx in 0 until n && sy in 0 until n) out[y * n + x] = f.pixels[sy * n + sx]
        }
        return Frame.of(p, out)
    }

    private fun sample(f: Frame, transform: (x: Double, y: Double) -> Pair<Double, Double>): Frame {
        val p = f.profile
        val n = p.size
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) {
            val (sx, sy) = transform((cell % n).toDouble(), (cell / n).toDouble())
            val ix = sx.roundToInt()
            val iy = sy.roundToInt()
            if (ix in 0 until n && iy in 0 until n) out[cell] = f.pixels[iy * n + ix]
        }
        return Frame.of(p, out)
    }

    fun rotate(f: Frame, angle: Double): Frame {
        if (angle == 0.0) return f.copy()
        val c = (f.profile.size - 1) / 2.0
        val cs = cos(-angle)
        val sn = sin(-angle)
        return sample(f) { x, y -> (c + (x - c) * cs - (y - c) * sn) to (c + (x - c) * sn + (y - c) * cs) }
    }

    fun scale(f: Frame, k: Double): Frame {
        if (k >= 1.0) return f.copy()
        val c = (f.profile.size - 1) / 2.0
        return sample(f) { x, y -> (c + (x - c) / k) to (c + (y - c) / k) }
    }
}
