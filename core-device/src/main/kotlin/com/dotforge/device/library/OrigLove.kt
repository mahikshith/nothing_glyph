package com.dotforge.device.library

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Love and emoji faces, drawn from scratch. */
internal object OrigLove {
    private fun face(f: Frame, p: DeviceProfile, dx: Double = 0.0, dy: Double = 0.0) { f.ring(p.mid() + dx, p.mid() + dy, 10.5, 0.6, 255) }

    private fun eyes(f: Frame, p: DeviceProfile, open: Boolean = true, left: Boolean = true, right: Boolean = true, dy: Double = 0.0) {
        val y = p.mid() - 3 + dy
        if (left) if (open) f.disc(p.mid() - 4, y, 1.3, 255) else f.line(p.mid() - 5.5, y, p.mid() - 2.5, y, 255)
        if (right) if (open) f.disc(p.mid() + 4, y, 1.3, 255) else f.line(p.mid() + 2.5, y, p.mid() + 5.5, y, 255)
    }

    private fun smile(f: Frame, p: DeviceProfile, depth: Double = 3.5, dy: Double = 3.0, v: Int = 255) {
        for (k in -12..12) { val x = k / 12.0; f.put((p.mid() + x * 5.5).roundToInt(), (p.mid() + dy + (1 - x * x) * depth - 0.5).roundToInt(), v) }
    }

    fun heartFill(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        val level = if (t < 0.8) t / 0.8 else 1.0
        val outline = Frame.blank(p).also { it.art(Art.HEART, p.mid().toInt(), p.mid().toInt(), 2.0) }
        val top = p.mid() + 8; val bottom = p.mid() - 8
        p.cells { x, y -> val v = outline.pixels[p.index(x, y)]; if (v == 0) 0 else if (y >= top - level * 17) 255 else 60 }
    }

    fun heartRain(p: DeviceProfile): Animation {
        val cols = List(6) { doubleArrayOf(3.0 + it * 3.6, it * 0.17, 1.0 + it % 2) }
        return p.frames(60, 60) { _, t ->
            val f = Frame.blank(p)
            for ((x, o, k) in cols) {
                val y = ((t * k + o) % 1.0) * (p.size + 6) - 3
                f.art(listOf(".#.#.", "#####", ".###.", "..#.."), x.roundToInt(), y.roundToInt())
            }
            f
        }
    }

    fun brokenHeart(p: DeviceProfile) = p.frames(48, 60) { _, t ->
        val gap = smooth((t - 0.3) / 0.4) * 3
        val f = Frame.blank(p)
        val src = Frame.blank(p).also { it.art(Art.HEART, p.mid().toInt(), p.mid().toInt() - 1, 2.0) }
        for (cell in p.ledCells) {
            val v = src.pixels[cell]; if (v == 0) continue
            val x = cell % p.size; val y = cell / p.size
            val left = x < p.mid() + (y - p.mid()) * 0.3 * 0 + (if ((y / 3) % 2 == 0) 0.5 else -0.5)
            f.put(x + (if (left) -gap.roundToInt() else gap.roundToInt()), y + (gap * (if (left) 0.3 else 0.8)).roundToInt(), v)
        }
        f
    }

    fun heartArrow(p: DeviceProfile) = p.frames(36, 55) { _, t ->
        val f = Frame.blank(p)
        val flash = if (t > 0.45 && t < 0.6) 255 else 190
        f.art(Art.HEART, p.mid().toInt(), p.mid().toInt(), 2.0)
        for (i in f.pixels.indices) if (f.pixels[i] > 0) f.pixels[i] = flash
        val x = -4 + smooth(t / 0.5) * (p.size + 6)
        val h = x.coerceAtMost(p.size + 6.0)
        f.line(h - 10, p.size - 5.0 - (h - 10) * 0.6 + 0, h, p.size - 5.0 - h * 0.6 + 0, 255)
        f.put(h.roundToInt() + 1, (p.size - 5 - h * 0.6).roundToInt() - 1, 255)
        f
    }

    fun kiss(p: DeviceProfile) = p.frames(48, 60) { _, t ->
        val f = Frame.blank(p)
        face(f, p, -2.0, 1.0); eyes(f, p, true, true, false, 1.0)
        f.line(p.mid() - 8, p.mid() - 1, p.mid() - 5, p.mid() - 3, 255)
        f.disc(p.mid() - 3, p.mid() + 5, 1.2, 255)
        val k = (t * 1.2) % 1.0
        f.art(listOf(".#.#.", "#####", ".###.", "..#.."), (p.mid() + 3 + k * 7).roundToInt(), (p.mid() + 3 - k * 14).roundToInt())
        f
    }

    fun wink(p: DeviceProfile) = p.frames(48, 70) { i, t ->
        val f = Frame.blank(p); face(f, p)
        val winking = t in 0.4..0.65
        eyes(f, p, true, true, !winking)
        if (winking) f.line(p.mid() + 2.5, p.mid() - 3, p.mid() + 5.5, p.mid() - 3, 255)
        smile(f, p); f
    }

    fun cryLaugh(p: DeviceProfile) = p.frames(36, 55) { i, t ->
        val shake = if (i % 2 == 0) 1.0 else -1.0
        val f = Frame.blank(p); face(f, p, shake * 0.0 + shake, 0.0)
        eyes(f, p, false, dy = 0.0); f.line(p.mid() - 5.5 + shake, p.mid() - 2, p.mid() - 2.5 + shake, p.mid() - 4, 255); f.line(p.mid() + 5.5 + shake, p.mid() - 2, p.mid() + 2.5 + shake, p.mid() - 4, 255)
        for (k in 0 until 3) { val r = (t * 2 + k * 0.33) % 1.0
            f.put((p.mid() - 6 - r * 5).roundToInt(), (p.mid() - 2 + r * 4 - 5 * r * (1 - r)).roundToInt(), br(1 - r)); f.put((p.mid() + 6 + r * 5).roundToInt(), (p.mid() - 2 + r * 4 - 5 * r * (1 - r)).roundToInt(), br(1 - r)) }
        f.disc(p.mid() + shake, p.mid() + 5, 2.6, 255)
        f
    }

    fun sleepyZzz(p: DeviceProfile) = p.frames(60, 70) { _, t ->
        val f = Frame.blank(p); face(f, p, -3.0, 3.0)
        eyes(f, p, false, dy = 1.5); f.line(p.mid() - 5, p.mid() + 6, p.mid() - 1, p.mid() + 6, 255)
        for (k in 0 until 3) { val r = (t * 1.0 + k / 3.0) % 1.0; f.art(Art.ZZZ, (p.mid() + 6 + r * 4).roundToInt(), (p.mid() - 2 - r * 10).roundToInt(), 1.0); if (k == 2) break }
        f
    }

    fun surprise(p: DeviceProfile) = p.frames(36, 70) { _, t ->
        val f = Frame.blank(p); face(f, p)
        val up = if (t in 0.3..0.7) -1.5 else 0.0
        f.ring(p.mid() - 4, p.mid() - 3 + up, 1.9, 0.5, 255); f.ring(p.mid() + 4, p.mid() - 3 + up, 1.9, 0.5, 255)
        f.disc(p.mid() - 4, p.mid() - 3 + up, 0.7, 255); f.disc(p.mid() + 4, p.mid() - 3 + up, 0.7, 255)
        f.line(p.mid() - 6, p.mid() - 7 + up, p.mid() - 2, p.mid() - 7 + up, 255); f.line(p.mid() + 2, p.mid() - 7 + up, p.mid() + 6, p.mid() - 7 + up, 255)
        f.ring(p.mid(), p.mid() + 6, 1.8, 0.5, 255)
        f
    }

    fun eyeTracker(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        val f = Frame.blank(p)
        for (x in -10..10) { val h = Math.sqrt((1 - (x / 10.0) * (x / 10.0)).coerceAtLeast(0.0)) * 6; f.put(p.mid().toInt() + x, (p.mid() - h).roundToInt(), 255); f.put(p.mid().toInt() + x, (p.mid() + h).roundToInt(), 255) }
        val px = 4.5 * sin(TAU * t); val py = 1.8 * sin(TAU * t * 2)
        f.disc(p.mid() + px, p.mid() + py, 3.2, 255); f.disc(p.mid() + px, p.mid() + py, 1.4, 0)
        f
    }

    fun thumbsUp(p: DeviceProfile) = p.frames(36, 55) { _, t ->
        val f = Frame.blank(p)
        val pop = thump(t * 1.0, 1)
        f.art(Art.THUMB, p.mid().toInt(), p.mid().toInt() + 1 - (pop * 2).roundToInt(), 2.0)
        for (k in 0 until 4) { val a = TAU * k / 4 + PI / 4; val r = 9 + pop * 3; if (pop > 0.2) f.put((p.mid() + r * cos(a)).roundToInt(), (p.mid() - 4 + r * sin(a)).roundToInt(), br(pop)) }
        f
    }

    fun sparkleCheer(p: DeviceProfile): Animation {
        val spots = List(7) { Triple(4 + (it * 7) % 17, 4 + (it * 11) % 15, it) }
        return p.frames(48, 60) { i, t ->
            val f = Frame.blank(p)
            for ((x, y, k) in spots) { val ph = ((t * 2 + k / 7.0) % 1.0); val s = if (ph < 0.5) tri(ph * 2) else 0.0
                if (s > 0.1) { f.put(x, y, 255); if (s > 0.5) { f.put(x + 1, y, 160); f.put(x - 1, y, 160); f.put(x, y + 1, 160); f.put(x, y - 1, 160) } } }
            f
        }
    }

    fun roseSpin(p: DeviceProfile) = p.frames(48, 60) { _, t ->
        val f = Frame.blank(p)
        val w = abs(cos(TAU * t))
        val src = Frame.blank(p).also { it.art(Art.ROSE, p.mid().toInt(), p.mid().toInt(), 2.0) }
        for (cell in p.ledCells) {
            val v = src.pixels[cell]; if (v == 0) continue
            val x = cell % p.size; val y = cell / p.size
            f.put((p.mid() + (x - p.mid()) * w.coerceAtLeast(0.15)).roundToInt(), y, v)
        }
        f
    }
}
