package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Small drawing helpers shared by the generators. Every generator is deterministic: fixed seeds, no clock. */
internal const val TAU = 2 * PI

internal fun DeviceProfile.mid() = (size - 1) / 2.0

/** 0..1 to a 0..255 brightness. */
internal fun br(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).roundToInt()

internal fun smooth(t: Double): Double { val x = t.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }

/** Triangle wave 0..1..0 over one period of t in 0..1. */
internal fun tri(t: Double): Double { val f = t - floor(t); return 1 - abs(2 * f - 1) }

/** Position of a clock hand: angle in radians from 12 o'clock, clockwise, of a cell. */
internal fun DeviceProfile.angleOf(x: Double, y: Double): Double {
    val a = atan2(x - mid(), -(y - mid()))
    return if (a < 0) a + TAU else a
}

/** Builds [n] frames of [ms] each; [draw] gets the frame number and the loop position t in 0 until 1. */
internal fun DeviceProfile.frames(n: Int, ms: Int, draw: (i: Int, t: Double) -> Frame): Animation =
    Animation(this, List(n) { AnimFrame(draw(it, it.toDouble() / n), ms) })

/** A frame from a per-cell brightness (0..1) of the cell position in -1..1 (u right, v down). */
internal fun DeviceProfile.field(f: (u: Double, v: Double) -> Double): Frame {
    val out = IntArray(cellCount)
    val c = mid()
    for (cell in ledCells) out[cell] = br(f((cell % size - c) / c, (cell / size - c) / c))
    return Frame(this, out)
}

/** A frame from a per-cell function of the integer cell position returning a 0..255 value. */
internal fun DeviceProfile.cells(f: (x: Int, y: Int) -> Int): Frame {
    val out = IntArray(cellCount)
    for (cell in ledCells) out[cell] = f(cell % size, cell / size).coerceIn(0, 255)
    return Frame(this, out)
}

/** Lights a cell to at least [v]. */
internal fun Frame.put(x: Int, y: Int, v: Int) {
    if (profile.isActive(x, y)) {
        val i = profile.index(x, y)
        if (v > pixels[i]) pixels[i] = v.coerceAtMost(255)
    }
}

/** A soft round dot at a fractional position, radius [r] cells. */
internal fun Frame.dot(x: Double, y: Double, v: Int = 255, r: Double = 1.0) {
    val reach = r + 1
    for (cy in floor(y - reach).toInt()..floor(y + reach).toInt()) for (cx in floor(x - reach).toInt()..floor(x + reach).toInt()) {
        val d = hypot(cx - x, cy - y)
        if (d <= r + 0.5) put(cx, cy, (v * (1 - (d / (r + 0.5)).coerceIn(0.0, 1.0) * 0.55).coerceIn(0.0, 1.0)).roundToInt())
    }
}

internal fun Frame.disc(cx: Double, cy: Double, r: Double, v: Int = 255) {
    for (y in floor(cy - r).toInt()..floor(cy + r).toInt() + 1) for (x in floor(cx - r).toInt()..floor(cx + r).toInt() + 1) {
        if (hypot(x - cx, y - cy) <= r) put(x, y, v)
    }
}

internal fun Frame.ring(cx: Double, cy: Double, r: Double, width: Double = 0.8, v: Int = 255) {
    val reach = r + width + 1
    for (y in floor(cy - reach).toInt()..floor(cy + reach).toInt()) for (x in floor(cx - reach).toInt()..floor(cx + reach).toInt()) {
        val k = 1 - abs(hypot(x - cx, y - cy) - r) / (width + 0.3)
        if (k > 0) put(x, y, (v * k).roundToInt())
    }
}

/** Straight line between two fractional points. */
internal fun Frame.line(x0: Double, y0: Double, x1: Double, y1: Double, v: Int = 255) {
    val steps = (maxOf(abs(x1 - x0), abs(y1 - y0)) * 2).toInt().coerceAtLeast(1)
    for (i in 0..steps) put((x0 + (x1 - x0) * i / steps).roundToInt(), (y0 + (y1 - y0) * i / steps).roundToInt(), v)
}

/**
 * Stamps picture rows centred on cell ([cx], [cy]): '#' is full, 'o' about half, '+' faint; '.' and ' ' are empty.
 * Digits 1-9 give that tenth of full brightness.
 */
internal fun Frame.art(rows: List<String>, cx: Int, cy: Int, scale: Double = 1.0) {
    val h = rows.size
    val w = rows.maxOf { it.length }
    val px = scale.coerceAtLeast(1.0).roundToInt()
    for ((ry, row) in rows.withIndex()) for ((rx, ch) in row.withIndex()) {
        val v = when (ch) { '#' -> 255; 'o' -> 140; '+' -> 70; in '1'..'9' -> (ch - '0') * 28; else -> 0 }
        if (v == 0) continue
        for (sy in 0 until px) for (sx in 0 until px) put(cx - w * px / 2 + rx * px + sx, cy - h * px / 2 + ry * px + sy, v)
    }
}

internal fun Frame.scaledBy(k: Double): Frame = scaled(k.toFloat())

internal fun Frame.merge(other: Frame): Frame {
    for (i in pixels.indices) if (other.pixels[i] > pixels[i]) pixels[i] = other.pixels[i]
    return this
}

internal fun Frame.shifted(dx: Int, dy: Int): Frame {
    val out = Frame.blank(profile)
    for (cell in profile.ledCells) {
        val v = pixels[cell]
        if (v > 0) out.put(cell % profile.size + dx, cell / profile.size + dy, v)
    }
    return out
}

/** Integer hash to 0..1 (stable across platforms). */
internal fun hash(a: Int, b: Int = 0, seed: Int = 0): Double {
    var h = a * 374761393 + b * 668265263 + seed * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216.0
}

/** Smooth value noise in 0..1; [z] lets the field evolve in time. */
internal fun noise(x: Double, y: Double, seed: Int = 0): Double {
    val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
    val fx = smooth(x - x0); val fy = smooth(y - y0)
    val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed)
    val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy
}

/** Loops seamlessly: noise sampled on a circle of radius [rad] in two extra dimensions. */
internal fun noiseLoop(x: Double, y: Double, t: Double, rad: Double = 1.0, seed: Int = 0): Double =
    (noise(x + rad * sin(TAU * t) * 3, y + rad * kotlin.math.cos(TAU * t) * 3, seed) +
        noise(x * 1.7 - rad * kotlin.math.cos(TAU * t) * 2 + 40, y * 1.7 + rad * sin(TAU * t) * 2 + 40, seed + 1) * 0.6) / 1.6

internal fun fbm(x: Double, y: Double, t: Double, seed: Int = 0): Double =
    (noiseLoop(x, y, t, 1.0, seed) * 0.6 + noiseLoop(x * 2, y * 2, t, 0.7, seed + 7) * 0.3 + noiseLoop(x * 4, y * 4, t, 0.5, seed + 13) * 0.1)

internal fun dist(u: Double, v: Double) = hypot(u, v)

internal fun maker(vararg tags: String) = tags.toList()

/** Draws [s] in the 5x5 pixel font with its top-left corner at ([x], [y]). */
internal fun Frame.text(s: String, x: Int, y: Int, v: Int = 255, scale: Int = 1) {
    val tmp = IntArray(profile.cellCount)
    com.dotforge.device.DotText.draw(tmp, profile.size, s, x, y, v, scale)
    for (cell in profile.ledCells) if (tmp[cell] > 0) put(cell % profile.size, cell / profile.size, tmp[cell])
}

internal fun Frame.textCentered(s: String, v: Int = 255, scale: Int = 1, dy: Int = 0) =
    text(s, (profile.size - com.dotforge.device.DotText.width(s, scale)) / 2, (profile.size - com.dotforge.device.DotText.height(scale)) / 2 + dy, v, scale)

internal fun Frame.digits(s: String, x: Int, y: Int, scale: Int = 1, v: Int = 255) = com.dotforge.device.BigDigits.draw(this, s, x, y, scale, v)

internal fun Frame.fadeBy(k: Double): Frame { for (i in pixels.indices) pixels[i] = (pixels[i] * k).roundToInt(); return this }

/** A looped pulse 0..1 with a sharp attack and slow decay, [beats] per loop. */
internal fun thump(t: Double, beats: Int = 1): Double { val f = (t * beats) % 1.0; return if (f < 0.12) f / 0.12 else (1 - (f - 0.12) / 0.88).pow2() }
private fun Double.pow2() = this * this

internal object Art {
    val HEART = listOf(".##...##.", "####.####", "#########", "#########", ".#######.", "..#####..", "...###...", "....#....")
    val BELL = listOf("....#....", "...###...", "..#####..", "..#####..", ".#######.", ".#######.", "#########", "#########", "...###...")
    val ENVELOPE = listOf("###########", "##.#####.##", "###.###.###", "####...####", "###########", "###########")
    val PHONE = listOf("#######", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#######", "###.###")
    val BATTERY = listOf("##########.", "#........##", "#........##", "#........##", "##########.")
    val BOLT = listOf("...##", "..##.", ".##..", "#####", "..##.", ".##..", ".#...")
    val CALENDAR = listOf("#.#####.#", "#########", "#.......#", "#.......#", "#.......#", "#.......#", "#.......#", "#########")
    val TRUCK = listOf("..#######..", "..#.....##..", "..#.....####", "############", "############", ".##..##..##.", ".##..##..##.")
    val SUN = listOf("...#...", ".#.#.#.", "..###..", "#######", "..###..", ".#.#.#.", "...#...")
    val LEAF = listOf("...##", "..###", ".####", "#####", "####.", "###..", "#....")
    val NOTE = listOf("..####", "..#..#", "..#..#", "..#..#", "###..#", "###.###", ".....##")
    val THUMB = listOf("....#..", "...##..", "...##..", "######.", "#######", "#######", "#######", "#######", ".######")
    val ROSE = listOf("..###..", ".#####.", "#######", "#######", ".#####.", "..###..", "...#...", "...##..", "...#...")
    val HOURGLASS = listOf("#########", ".#######.", "..#####..", "...###...", "....#....", "...###...", "..#####..", ".#######.", "#########")
    val TREE = listOf("....#....", "...###...", "..#####..", "...###...", "..#####..", ".#######.", "..#####..", ".#########.", "....#....", "....#....")
    val GHOST = listOf("..#####..", ".#######.", "#########", "##..#..##", "##..#..##", "#########", "#########", "#########", "##.###.##", "#..#.#..#")
    val PUMPKIN = listOf("....#....", "..#####..", ".#######.", "#########", "#.##.##.#", "#########", "#.#####.#", "#########", ".#######.")
    val EGG = listOf("...###...", "..#####..", ".#######.", ".#######.", "#########", "#########", "#########", ".#######.", "..#####..")
    val ARROW_DOWN = listOf("..###..", "..###..", "..###..", "#######", ".#####.", "..###..", "...#...")
    val STAR4 = listOf("..#..", "..#..", "#####", "..#..", "..#..")
    val ZZZ = listOf("####", "..#.", ".#..", "####")
}
