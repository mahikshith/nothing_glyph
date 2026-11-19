package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Alerts, notifications and weather. All drawn from scratch for DotForge. */
internal object OrigAlerts {
    fun ripplePing(p: DeviceProfile) = p.frames(36, 45) { i, t ->
        val f = Frame.blank(p)
        val r = t * 1.4 * p.size / 2
        if (r < p.size / 2.0 + 2) f.ring(p.mid(), p.mid(), r, 1.0, br(1 - r / (p.size / 2.0 + 2)))
        f.disc(p.mid(), p.mid(), 1.0 + (if (i < 3) 1.0 else 0.0), 255)
        f
    }

    fun edgeChase(p: DeviceProfile) = p.frames(60, 35) { i, _ ->
        val f = Frame.blank(p)
        val ring = p.ring
        for (k in 0 until 14) f.pixels[ring[Math.floorMod(i * ring.size / 60 - k, ring.size)]] = br(1 - k / 14.0)
        f
    }

    fun pulseThump(p: DeviceProfile) = p.frames(36, 40) { _, t ->
        val k = thump(t, 2)
        val f = Frame.blank(p)
        f.disc(p.mid(), p.mid(), 2 + k * (p.size / 2.0 - 3), br(0.35 + 0.65 * k))
        f
    }

    fun callRings(p: DeviceProfile) = p.frames(36, 55) { _, t ->
        val f = Frame.blank(p)
        for (k in 0 until 3) {
            val on = ((t * 3 - k * 0.5) % 1.0).let { if (it < 0.6) 1.0 - it / 0.6 else 0.0 }
            f.ring(p.mid(), p.mid() - 2, 4.0 + k * 3.5, 0.9, br(on))
        }
        f.art(Art.PHONE, p.mid().toInt(), p.mid().toInt() + 9)
        f
    }

    fun messagePop(p: DeviceProfile) = p.frames(48, 60) { i, t ->
        val f = Frame.blank(p)
        val s = smooth(t / 0.3)
        val w = (14 * s).roundToInt(); val h = (9 * s).roundToInt()
        val x0 = (p.mid() - w / 2.0).roundToInt(); val y0 = (p.mid() - h / 2.0).roundToInt() - 1
        for (x in 0..w) { f.put(x0 + x, y0, 255); f.put(x0 + x, y0 + h, 255) }
        for (y in 0..h) { f.put(x0, y0 + y, 255); f.put(x0 + w, y0 + y, 255) }
        if (s >= 1.0) {
            f.put(x0 + 2, y0 + h + 1, 255); f.put(x0 + 1, y0 + h + 2, 255)
            for (d in 0 until 3) f.put(x0 + 4 + d * 3, y0 + h / 2 - ((t * 9 + d * 0.5) % 1.5).let { if (it < 0.7) 1 else 0 }, 255)
        }
        f
    }

    fun envelope(p: DeviceProfile) = p.frames(36, 60) { _, t ->
        val f = Frame.blank(p)
        val open = smooth(t / 0.4) * (1 - smooth((t - 0.8) / 0.2))
        f.art(Art.ENVELOPE.drop(3), p.mid().toInt(), p.mid().toInt() + 3)
        if (open > 0.05) {
            f.line(p.mid() - 5, p.mid() + 1, p.mid(), p.mid() + 1 - 5 * open, 255); f.line(p.mid() + 5, p.mid() + 1, p.mid(), p.mid() + 1 - 5 * open, 255)
            f.dot(p.mid(), p.mid() + 1 - 7 * open * smooth((t - 0.3) / 0.4), 255, 0.6)
        } else f.line(p.mid() - 5, p.mid() + 1, p.mid(), p.mid() + 5, 255).also { f.line(p.mid() + 5, p.mid() + 1, p.mid(), p.mid() + 5, 255) }
        f
    }

    /** The bell's eight drawn frames (resources/library/bell.txt), upright first. */
    private val bellFrames by lazy {
        val text = OrigAlerts::class.java.getResourceAsStream("/library/bell.txt")?.bufferedReader()?.use { it.readText() }
            ?: error("missing library asset bell.txt")
        GlyphArt.parse(text).single()
    }

    /** Bell swing: the drawn bell rocks out and back twice, then rests upright. */
    fun bell(p: DeviceProfile): Animation {
        val frames = bellFrames.frames.map { GlyphArt.picture(p, it) }
        val swing = (frames + frames).map { AnimFrame(it, 120) }
        return Animation(p, swing + AnimFrame(frames[0], 900))
    }

    /** The upright bell: Bell swing's poster. */
    fun bellPoster(p: DeviceProfile): Frame = GlyphArt.picture(p, bellFrames.frames[0])

    fun siren(p: DeviceProfile) = p.frames(24, 90) { i, _ ->
        val left = (i / 3) % 2 == 0
        val flash = i % 6 == 0
        p.cells { x, y -> if (flash) 255 else if ((x < p.mid()) == left && hypot(x - p.mid(), y - p.mid()) < p.size / 2.0 - 1) 255 else 0 }
    }

    fun countdown(p: DeviceProfile) = p.frames(48, 80) { i, t ->
        val f = Frame.blank(p)
        val step = (t * 4).toInt()
        val local = t * 4 - step
        if (step < 3) f.digits("${3 - step}", p.size / 2 - 3, p.size / 2 - 5, 2, br(1 - local * 0.6))
        else f.ring(p.mid(), p.mid(), local * p.size / 2.0, 1.2, br(1 - local))
        f
    }

    fun chargingBolt(p: DeviceProfile) = p.frames(36, 60) { i, t ->
        val f = Frame.blank(p)
        val fill = (t * 1.2).coerceAtMost(1.0)
        val rows = Art.BOLT
        val cx = p.mid().toInt(); val cy = p.mid().toInt()
        val full = Frame.blank(p); full.art(rows, cx, cy, 2.0)
        for (cell in p.ledCells) {
            val v = full.pixels[cell]; if (v == 0) continue
            val y = cell / p.size
            val lvl = (cy + rows.size) - y
            f.pixels[cell] = if (lvl <= fill * rows.size * 2 + 1) 255 else 50
        }
        f
    }

    fun lowBattery(p: DeviceProfile) = p.frames(24, 120) { i, _ ->
        val f = Frame.blank(p)
        f.art(Art.BATTERY, p.mid().toInt(), p.mid().toInt(), 2.0)
        if ((i / 4) % 2 == 0) for (x in 0..1) for (y in -3..2) f.put(p.mid().toInt() - 9 + x + 1, p.mid().toInt() + y, 255)
        f
    }

    fun truck(p: DeviceProfile) = p.frames(48, 55) { i, t ->
        val f = Frame.blank(p)
        val x = (-14 + t * (p.size + 28)).roundToInt()
        f.art(Art.TRUCK, x, p.mid().toInt() - (if (i % 4 < 2) 0 else 1))
        for (k in 0 until p.size step 4) f.put(((k - t * p.size * 2).roundToInt() + p.size * 2) % p.size, p.mid().toInt() + 6, 70)
        f
    }

    fun missedCall(p: DeviceProfile) = p.frames(36, 70) { i, t ->
        val f = Frame.blank(p)
        f.art(Art.PHONE, p.mid().toInt() - 2, p.mid().toInt() + 2)
        val rise = (t * 2) % 1.0
        val cx = p.mid() + 6; val cy = p.mid() - 8 + (1 - rise) * 2
        f.line(cx - 2, cy - 2, cx + 2, cy + 2, br(0.4 + 0.6 * rise)); f.line(cx - 2, cy + 2, cx + 2, cy - 2, br(0.4 + 0.6 * rise))
        f
    }

    fun calendarTick(p: DeviceProfile) = p.frames(36, 65) { _, t ->
        val f = Frame.blank(p)
        val flip = smooth(t / 0.35)
        f.art(Art.CALENDAR, p.mid().toInt(), p.mid().toInt(), 2.0)
        if (flip < 1.0) for (y in 0 until (8 * (1 - flip)).roundToInt()) for (x in -7..7) f.put(p.mid().toInt() + x, p.mid().toInt() - 2 + y, 0)
        if (t > 0.5) f.disc(p.mid() + 3, p.mid() + 4, 1.4, br(0.5 + 0.5 * sin(TAU * t * 4)))
        f
    }

    fun rimBreathing(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        val f = Frame.blank(p)
        val k = 0.15 + 0.85 * (0.5 - 0.5 * cos(TAU * t))
        for (c in p.ring) f.pixels[c] = br(k)
        f
    }

    fun morse(p: DeviceProfile): Animation {
        // SOS: ... --- ...  (dot = 2 frames, dash = 6, gap = 2)
        val on = ArrayList<Boolean>()
        for (sym in listOf(2, 2, 2, 6, 6, 6, 2, 2, 2)) { repeat(sym) { on += true }; repeat(2) { on += false } }
        repeat(6) { on += false }
        return p.frames(on.size, 80) { i, _ ->
            val f = Frame.blank(p)
            if (on[i]) f.disc(p.mid(), p.mid(), 3.0, 255) else f.ring(p.mid(), p.mid(), 4.0, 0.4, 40)
            f
        }
    }

    fun spotlight(p: DeviceProfile) = p.frames(72, 45) { _, t ->
        val x = p.mid() + (p.mid() - 5) * sin(TAU * t * 3); val y = p.mid() + (p.mid() - 5) * sin(TAU * t * 2 + 1)
        p.field { u, v ->
            val d = hypot(u * p.mid() + p.mid() - x, v * p.mid() + p.mid() - y)
            (1 - d / 6.0).coerceAtLeast(0.0).pow(0.8).coerceAtLeast(0.04)
        }
    }

    // ---- nature and weather ----

    fun sunPulse(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        val f = Frame.blank(p)
        val pulse = 0.5 + 0.5 * sin(TAU * t)
        f.disc(p.mid(), p.mid(), 4.0 + pulse * 0.8, 255)
        for (k in 0 until 8) {
            val a = TAU * (k / 8.0 + t / 8)
            for (r in 7..(9 + (pulse * 2).roundToInt())) f.put((p.mid() + r * sin(a)).roundToInt(), (p.mid() - r * cos(a)).roundToInt(), br(0.6 + 0.4 * pulse))
        }
        f
    }

    fun sunrise(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        val f = Frame.blank(p)
        val horizon = p.mid().toInt() + 5
        val cy = horizon + 5 - 12 * smooth(t / 0.8)
        f.disc(p.mid(), cy, 4.5, 255)
        val rays = smooth((t - 0.4) / 0.5)
        for (k in 0 until 7) { val a = PI * (k + 0.5) / 7 - PI; for (r in 7..(7 + (rays * 3).roundToInt())) f.put((p.mid() + r * cos(a)).roundToInt(), (cy + r * sin(a)).roundToInt(), 200) }
        for (y in horizon until p.size) for (x in 0 until p.size) if (p.isActive(x, y)) f.pixels[p.index(x, y)] = if (y == horizon) 255 else 0
        f
    }

    fun twinklingStars(p: DeviceProfile): Animation {
        val stars = List(60) { Triple(it * 37 % p.size, it * 11 % p.size + (it * 7) % 3, it % 12) }
        return p.frames(48, 80) { i, t ->
            val f = Frame.blank(p)
            for ((x, y, ph) in stars) { val k = (0.5 + 0.5 * sin(TAU * (t * (1 + ph % 3) + ph / 12.0))).pow(3.0); f.put(x, y, br(k)) }
            f
        }
    }

    fun shootingStar(p: DeviceProfile) = p.frames(36, 45) { _, t ->
        val f = Frame.blank(p)
        for (c in 0 until 12) { val k = t * 1.5 - c * 0.025 - 0.1; f.put((2 + k * 28).roundToInt(), (2 + k * 20).roundToInt(), br(1 - c / 12.0)) }
        for (k in 0 until 14) f.put((k * 7 + 3) % p.size, (k * 5 + 1) % p.size, 40)
        f
    }

    fun windGust(p: DeviceProfile) = p.frames(48, 50) { i, t ->
        val f = Frame.blank(p)
        for (k in 0 until 6) {
            val y = 4 + k * 3 + (k % 2); val x0 = ((t * (1 + k % 3) * 1.0 + k * 0.2) % 1.0) * (p.size + 12) - 8
            for (c in 0 until 7) f.put((x0 + c).roundToInt(), y + (sin(c * 0.8 + t * TAU) * 1.2).roundToInt(), br(c / 7.0))
        }
        val a = TAU * t * 2
        f.dot(p.mid() + 7 * cos(a) * 0.9, p.mid() + 3 * sin(a * 2), 255, 0.7)
        f
    }

    fun tornado(p: DeviceProfile) = p.frames(48, 50) { _, t ->
        val f = Frame.blank(p)
        for (y in 3 until p.size - 3) {
            val k = (y - 3) / (p.size - 6.0)
            val w = 2 + k * 8
            for (s in 0 until 2) f.dot(p.mid() + w * sin(TAU * (t * 2 + k * 1.5 + s * 0.5)) * (1 - 0.0), y.toDouble(), br(0.5 + 0.5 * cos(TAU * (t * 2 + k * 1.5 + s * 0.5))), 0.5)
        }
        f
    }

    fun rainbowArcs(p: DeviceProfile) = p.frames(48, 70) { _, t ->
        val f = Frame.blank(p)
        val shown = (t * 1.4 * 5).toInt().coerceAtMost(5)
        for (k in 0 until shown) f.ring(p.mid(), p.mid() + 7, 11.0 - k * 1.8, 0.7, br(0.5 + 0.1 * (k % 2) * 5 + 0.1))
        f
    }

    fun fallingLeaves(p: DeviceProfile): Animation {
        val leaves = List(5) { doubleArrayOf((3 + it * 4.5), it * 0.2, 1.0 + it % 2) }
        return p.frames(60, 60) { i, t ->
            val f = Frame.blank(p)
            for ((x0, o, k) in leaves) {
                val y = ((t * k + o) % 1.0) * (p.size + 6) - 3
                val sway = 3 * sin(TAU * (t * k + o) * 2)
                val a = (x0 + sway)
                f.put(a.roundToInt(), y.roundToInt(), 255); f.put(a.roundToInt() + 1, y.roundToInt(), 190); f.put(a.roundToInt(), y.roundToInt() + 1, 120)
            }
            f
        }
    }

    fun fogDrift(p: DeviceProfile) = p.frames(60, 80) { _, t ->
        p.field { u, v -> (0.18 + 0.35 * fbm(u * 1.6 + 7, v * 3.0 + 7, t, 71)).coerceIn(0.0, 0.6) }
    }

    fun bloomingFlower(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        val f = Frame.blank(p)
        val grow = smooth(t / 0.4); val bloom = smooth((t - 0.4) / 0.4)
        val top = p.size - 4 - grow * 12
        for (y in top.roundToInt()..p.size - 4) f.put(p.mid().toInt(), y, 150)
        if (grow > 0.9) {
            for (k in 0 until 6) { val a = TAU * k / 6; f.dot(p.mid() + bloom * 4 * cos(a), top + bloom * 4 * sin(a), 255, 1.0) }
            f.disc(p.mid(), top, 1.5, 255)
        } else f.dot(p.mid(), top, 255, 0.7)
        f
    }

    fun rainSplash(p: DeviceProfile): Animation {
        val drops = List(8) { doubleArrayOf(2.0 + it * 2.9, it * 0.13) }
        return p.frames(48, 55) { _, t ->
            val f = Frame.blank(p)
            val ground = p.size - 5
            for ((x, o) in drops) {
                val ph = (t * 2 + o) % 1.0
                if (ph < 0.8) { val y = ph / 0.8 * ground; f.put(x.roundToInt(), y.roundToInt(), 255); f.put(x.roundToInt(), y.roundToInt() - 1, 120) }
                else { val s = (ph - 0.8) / 0.2; f.put((x - 1 - s * 2).roundToInt(), ground - (s * (1 - s) * 6).roundToInt(), br(1 - s)); f.put((x + 1 + s * 2).roundToInt(), ground - (s * (1 - s) * 6).roundToInt(), br(1 - s)) }
            }
            for (x in 3 until p.size - 3) f.put(x, ground + 1, 50)
            f
        }
    }

    fun waterSlosh(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        val tilt = sin(TAU * t) * 0.35
        p.cells { x, y ->
            val surface = p.mid() + (x - p.mid()) * tilt + sin(TAU * (t * 3 + x / 12.0)) * 0.6
            if (abs(y - surface) < 0.8) 255 else if (y > surface) 85 else 0
        }
    }
}
