package com.dotforge.device.library

import com.dotforge.device.Animation
import com.dotforge.device.ClockStyle
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.ToyFaces
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Loaders, music, time, abstract toys. */
internal object OrigMore {
    // ---- loaders ----
    fun radialFill(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        val k = if (t < 0.85) t / 0.85 else 1.0
        p.cells { x, y -> val d = hypot(x - p.mid(), y - p.mid()); if (d < p.size / 2.0 - 1) (if (p.angleOf(x.toDouble(), y.toDouble()) / TAU <= k) 255 else 35) else 0 }
    }

    fun ringProgress(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        val k = if (t < 0.9) t / 0.9 else 1.0
        val f = Effects.ring(p, k.toFloat())
        f.digits("${(k * 100).toInt()}".takeLast(3), (p.size - com.dotforge.device.BigDigits.width("${(k * 100).toInt()}", 1)) / 2, p.size / 2 - 2, 1, 255)
        f
    }

    fun pulseDots(p: DeviceProfile) = p.frames(36, 50) { _, t ->
        val f = Frame.blank(p)
        for (d in 0 until 3) { val k = (0.5 + 0.5 * sin(TAU * (t - d / 6.0))).pow(2.0); f.disc(p.mid() - 6 + d * 6, p.mid(), 1.0 + k * 1.5, br(0.4 + 0.6 * k)) }
        f
    }

    fun barWave(p: DeviceProfile) = p.frames(36, 50) { _, t ->
        val f = Frame.blank(p)
        for (b in 0 until 7) { val h = 2 + 8 * (0.5 + 0.5 * sin(TAU * (t - b / 7.0))); val x0 = 3 + b * 3
            for (x in x0..x0 + 1) for (y in 0 until h.roundToInt()) { f.put(x, (p.mid() + h / 2 - y).roundToInt(), 255) } }
        f
    }

    fun hourglass(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        val f = Frame.blank(p)
        val k = if (t < 0.9) t / 0.9 else 1.0
        val cx = p.mid().toInt() - 9; val cy = p.mid().toInt() - 9
        fun block(rx: Int, ry: Int, v: Int) { for (dy in 0..1) for (dx in 0..1) f.put(cx + rx * 2 + dx, cy + ry * 2 + dy, v) }
        val glass = Frame.blank(p).also { it.art(Art.HOURGLASS, 4, 4) } // only used for the outline cells
        for (ry in 0..8) for (rx in 0..8) {
            val inRow = if (ry <= 4) rx in ry..8 - ry else rx in 8 - ry..ry
            val edge = ry == 0 || ry == 8 || rx == (if (ry <= 4) ry else 8 - ry) || rx == (if (ry <= 4) 8 - ry else ry)
            if (inRow && edge) block(rx, ry, 255)
            else if (inRow && ry in 1..3 && ry >= 1 + (k * 3).roundToInt()) block(rx, ry, 150)
            else if (inRow && ry in 5..7 && ry >= 8 - (k * 3).roundToInt()) block(rx, ry, 150)
        }
        if (k < 1.0) block(4, 4, 255)
        f
    }

    fun snakeLoader(p: DeviceProfile) = p.frames(72, 40) { i, _ ->
        val f = Frame.blank(p)
        val path = ArrayList<Pair<Int, Int>>()
        var x0 = 3; var y0 = 3; var x1 = p.size - 4; var y1 = p.size - 4
        while (x0 <= x1 && y0 <= y1) {
            for (x in x0..x1) path += x to y0; for (y in y0 + 1..y1) path += x1 to y
            for (x in x1 - 1 downTo x0) path += x to y1; for (y in y1 - 1 downTo y0 + 2) path += x0 to y
            x0 += 2; y0 += 2; x1 -= 2; y1 -= 2
        }
        val head = i * path.size / 72
        for (k in 0 until 10) { val (x, y) = path[Math.floorMod(head - k, path.size)]; f.put(x, y, br(1 - k / 10.0)) }
        f
    }

    fun scanLine(p: DeviceProfile) = p.frames(36, 40) { _, t ->
        p.cells { _, y -> val d = y - t * (p.size + 4) + 2; if (d in -3.0..0.0) br(1 + d / 3.5) else if (d > 0 && d < 1) 255 else 0 }
    }

    fun dotGridRipple(p: DeviceProfile) = p.frames(36, 50) { _, t ->
        val cx = p.mid() + 6 * sin(TAU * t); val cy = p.mid() + 6 * cos(TAU * t)
        p.cells { x, y -> if (x % 3 == 0 && y % 3 == 0) br(0.12 + 0.88 * (0.5 + 0.5 * sin(hypot(x - cx, y - cy) * 0.9 - TAU * t * 3))) else 0 }
    }

    fun downloadArrow(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        val f = Frame.blank(p)
        val y = -5 + (t * 1.1).coerceAtMost(1.0) * 16
        f.art(Art.ARROW_DOWN, p.mid().toInt(), y.roundToInt())
        for (x in 5 until p.size - 5) { f.put(x, p.size - 6, 255) }
        for (y2 in p.size - 9 until p.size - 6) { f.put(5, y2, 255); f.put(p.size - 6, y2, 255) }
        val fill = ((t - 0.5) * 2).coerceIn(0.0, 1.0)
        for (r in 0 until (fill * 3).roundToInt()) for (x in 6 until p.size - 6) f.put(x, p.size - 7 - r, 140)
        f
    }

    fun batteryFill(p: DeviceProfile) = p.frames(48, 60) { _, t ->
        val f = Frame.blank(p)
        val k = if (t < 0.8) t / 0.8 else 1.0
        f.art(Art.BATTERY, p.mid().toInt(), p.mid().toInt(), 2.0)
        val left = p.mid().toInt() - 10
        for (x in 0 until (k * 16).roundToInt()) for (y in -3..2) f.put(left + 2 + x, p.mid().toInt() + y, 255)
        f
    }

    fun pomodoro(p: DeviceProfile) = p.frames(60, 80) { _, t ->
        p.cells { x, y -> val d = hypot(x - p.mid(), y - p.mid()); if (d < p.size / 2.0 - 1.5) (if (p.angleOf(x.toDouble(), y.toDouble()) / TAU > t) 255 else 0) else if (d < p.size / 2.0) 40 else 0 }
    }

    // ---- music ----
    fun circularSpectrum(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        p.cells { x, y ->
            val d = hypot(x - p.mid(), y - p.mid()); val a = p.angleOf(x.toDouble(), y.toDouble()) / TAU
            val h = 3 + 6 * (0.4 * noiseLoop(a * 14, 0.5, t, 1.0, 81) + 0.6 * abs(sin(TAU * (a * 7 + t * 2))) * noiseLoop(a * 9, 2.0, t, 1.0, 82))
            if (d in 3.0..3.0 + h) 255 else if (d < 3.0) 60 else 0
        }
    }

    fun beatPulse(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        val k = thump(t, 4)
        val f = Frame.blank(p)
        f.disc(p.mid(), p.mid(), 3 + 7 * k, br(0.4 + 0.6 * k))
        f
    }

    fun vinyl(p: DeviceProfile) = p.frames(48, 45) { _, t ->
        p.cells { x, y ->
            val d = hypot(x - p.mid(), y - p.mid()); val a = p.angleOf(x.toDouble(), y.toDouble()) / TAU
            when {
                d < 1.5 -> 0
                d < 3.5 -> if (((a - t) % 1.0) < 0.18) 255 else 90
                d < p.size / 2.0 - 1 -> if (d.toInt() % 2 == 0) 110 else 30
                else -> 0
            }
        }.also { it.line(p.size - 3.0, 1.0, p.mid() + 5, p.mid() - 4, 255) }
    }

    fun decibel(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        val level = (0.45 + 0.4 * sin(TAU * t * 2) * sin(TAU * t * 5 + 1)).coerceIn(0.0, 1.0)
        val peak = (0.8 + 0.1 * sin(TAU * t)).coerceIn(0.0, 1.0)
        p.cells { x, y ->
            val d = hypot(x - p.mid(), y - p.mid())
            val a = ((p.angleOf(x.toDouble(), y.toDouble()) / TAU) + 0.375) % 1.0 / 0.75 // 270 degree gauge, gap at the bottom
            if (d in 7.0..11.0 && ((p.angleOf(x.toDouble(), y.toDouble()) / TAU) + 0.375) % 1.0 <= 0.75) (if (a <= level) 255 else if (abs(a - peak) < 0.03) 255 else 40) else 0
        }
    }

    fun notesFloat(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        val f = Frame.blank(p)
        for (k in 0 until 3) { val r = (t + k / 3.0) % 1.0; f.art(Art.NOTE, (p.mid() - 6 + k * 6 + sin(TAU * r * 2) * 2).roundToInt(), (p.size + 4 - r * (p.size + 8)).roundToInt()) }
        f
    }

    fun bassRipple(p: DeviceProfile) = p.frames(48, 50) { _, t ->
        val f = Frame.blank(p)
        for (k in 0 until 2) { val r = ((t * 2 + k * 0.5) % 1.0); f.ring(p.mid(), p.mid(), r * p.size * 0.6, 1.2 + (1 - r), br(1 - r)) }
        f.disc(p.mid(), p.mid(), 1.0 + thump(t, 2) * 2, 255)
        f
    }

    fun tunnelPulse(p: DeviceProfile) = p.frames(36, 40) { _, t ->
        val beat = thump(t, 2)
        p.field { u, v -> val d = hypot(u, v); (0.5 + 0.5 * sin(d * 11 - TAU * t * 3 - beat * 2)).pow(2.0) * (0.4 + 0.6 * beat) }
    }

    // ---- time ----
    fun digitalClock(p: DeviceProfile) = p.frames(60, 80) { i, _ -> ToyFaces.clock(p, ClockStyle.DIGITAL, 10, i % 60).also { if (i % 2 == 0) it.put(p.mid().toInt(), p.mid().toInt(), 255) } }
    fun analogClock(p: DeviceProfile) = p.frames(60, 80) { i, _ -> ToyFaces.analog(p, 10, i) }
    fun binaryClock(p: DeviceProfile) = p.frames(60, 80) { i, _ -> ToyFaces.binary(p, 10, i % 60) }
    fun moonPhases(p: DeviceProfile) = p.frames(48, 100) { i, _ -> ToyFaces.moon(p, i / 48.0) }

    fun secondsRing(p: DeviceProfile) = p.frames(60, 60) { i, _ ->
        val f = Frame.blank(p)
        val ring = p.ring
        for ((k, cell) in ring.withIndex()) f.pixels[cell] = if (k * 60 / ring.size <= i) 255 else 35
        if (i == 59) f.fill(255)
        f
    }

    fun stopwatch(p: DeviceProfile) = p.frames(60, 50) { i, t ->
        val f = Frame.blank(p)
        val a = TAU * t
        for (k in 0 until 12) f.put((p.mid() + 11 * kotlin.math.sin(TAU * k / 12)).roundToInt(), (p.mid() - 11 * cos(TAU * k / 12)).roundToInt(), 80)
        for (r in 0..10) f.put((p.mid() + r * sin(a)).roundToInt(), (p.mid() - r * cos(a)).roundToInt(), br(1 - r / 14.0))
        f.digits("${(i * 100 / 60).toString().padStart(2, '0')}", p.size / 2 - 3, p.size - 9, 1, 160)
        f
    }

    fun countdownTimer(p: DeviceProfile) = p.frames(60, 100) { i, t -> ToyFaces.timer(p, (1 - t).toFloat(), (60 - i).toLong()) }

    fun sunClock(p: DeviceProfile) = p.frames(60, 70) { _, t ->
        val f = Frame.blank(p)
        val a = TAU * t
        val r = p.mid() - 4
        f.ring(p.mid(), p.mid(), r, 0.3, 40)
        f.disc(p.mid() + r * sin(a), p.mid() - r * cos(a), 2.2, 255)
        f.dot(p.mid() - r * sin(a), p.mid() + r * cos(a), 140, 1.2)
        f
    }

    fun dateFlip(p: DeviceProfile) = p.frames(48, 70) { _, t ->
        val phase = (t * 2) % 1.0
        val day = 14 + (t * 2).toInt() + (if (phase < 0.3) 0 else 1) - 1 + (if ((t * 2).toInt() == 0 && phase < 0.3) 0 else 0)
        val squash = (abs(phase - 0.3) / 0.3).coerceIn(0.0, 1.0)
        val src = Frame.blank(p)
        src.digits("$day", (p.size - com.dotforge.device.BigDigits.width("$day", 2)) / 2, p.size / 2 - 5, 2)
        val f = Frame.blank(p)
        for (y in 0 until p.size) {
            val sy = ((y - p.mid()) / squash.coerceAtLeast(0.08) + p.mid()).roundToInt()
            if (squash > 0.08 && sy in 0 until p.size) for (x in 0 until p.size) f.put(x, y, src.pixels[p.index(x, sy)])
        }
        f.line(5.0, p.mid() - 8, p.size - 6.0, p.mid() - 8, 255)
        f
    }

    fun sleepTimer(p: DeviceProfile): Animation {
        val rnd = Random(77)
        val stars = List(24) { p.ledCells[rnd.nextInt(p.ledCount)] }
        val order = stars.indices.shuffled(rnd)
        return p.frames(72, 80) { i, t ->
            val f = Frame.blank(p)
            for ((k, s) in stars.withIndex()) if (order.indexOf(k) * 72 / 24 > i) f.pixels[s] = br(0.5 + 0.5 * sin(TAU * (i / 18.0 + k / 5.0)))
            f
        }
    }

    // ---- abstract ----
    fun starWarp(p: DeviceProfile): Animation {
        val rnd = Random(90)
        val stars = List(40) { doubleArrayOf(rnd.nextDouble() * TAU, rnd.nextDouble()) }
        return p.frames(48, 45) { _, t ->
            val f = Frame.blank(p)
            for ((a, o) in stars) { val k = (o + t) % 1.0; val r = k * k * (p.size / 2.0 + 2)
                f.put((p.mid() + r * cos(a)).roundToInt(), (p.mid() + r * sin(a)).roundToInt(), br(0.3 + k))
                if (k > 0.5) f.put((p.mid() + (r - 1.5) * cos(a)).roundToInt(), (p.mid() + (r - 1.5) * sin(a)).roundToInt(), br(k * 0.5)) }
            f
        }
    }

    fun fidgetSpinner(p: DeviceProfile) = p.frames(60, 40) { _, t ->
        val f = Frame.blank(p)
        val speed = 1 + 1.5 * (1 - t) // slows down while it coasts
        val a = TAU * (t * (1 + 2 * (1 - t / 2)))
        for (k in 0 until 3) { val b = a + TAU * k / 3; f.line(p.mid(), p.mid(), p.mid() + 7 * sin(b), p.mid() - 7 * cos(b), 200); f.disc(p.mid() + 7.5 * sin(b), p.mid() - 7.5 * cos(b), 2.4, 255) }
        f.disc(p.mid(), p.mid(), 2.0, 255); f.disc(p.mid(), p.mid(), 0.8, 0)
        f
    }

    fun breathingCircle(p: DeviceProfile) = p.frames(60, 90) { _, t ->
        val f = Frame.blank(p)
        val k = 0.5 - 0.5 * cos(TAU * t)
        f.disc(p.mid(), p.mid(), 2.5 + k * (p.size / 2.0 - 4), br(0.35 + 0.65 * k))
        f
    }
}
