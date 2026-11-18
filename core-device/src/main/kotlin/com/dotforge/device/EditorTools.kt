package com.dotforge.device

import kotlin.math.roundToInt

/** Mirror drawing: off, left-right, top-bottom or both (axis lines show on the matrix while it is on). */
enum class Mirror(val label: String) { OFF("Off"), H("↔"), V("↕"), BOTH("Both") }

/** What a Draw stroke does to each dot it passes. */
enum class BrushMode(val label: String) { PAINT("Paint"), BRIGHTEN("Brighten"), DIM("Dim") }

/** The Draw tool's options: dot size 1-3, the shade (0-255) and mirror. */
data class Brush(val size: Int = 1, val value: Int = 255, val mirror: Mirror = Mirror.OFF, val mode: BrushMode = BrushMode.PAINT) {
    /** The dots one touch at ([x], [y]) covers, mirrored. */
    fun cells(p: DeviceProfile, x: Int, y: Int): Set<Int> {
        val out = HashSet<Int>()
        val n = p.size
        val pts = buildList {
            add(x to y)
            if (mirror == Mirror.H || mirror == Mirror.BOTH) add(n - 1 - x to y)
            if (mirror == Mirror.V || mirror == Mirror.BOTH) add(x to n - 1 - y)
            if (mirror == Mirror.BOTH) add(n - 1 - x to n - 1 - y)
        }
        for ((px, py) in pts) for (dy in offsets(size)) for (dx in offsets(size)) {
            if (size == 3 && dx != 0 && dy != 0) continue // a plus at size 3 reads rounder than a 3x3 block
            if (p.isActive(px + dx, py + dy)) out += p.index(px + dx, py + dy)
        }
        return out
    }

    private fun offsets(s: Int) = when (s) { 1 -> 0..0; 2 -> 0..1; else -> -1..1 }

    companion object {
        /** Brighten and Dim step per stroke. */
        const val STEP = 64
        /** The 8 shade swatches. */
        val SHADES = listOf(255, 219, 183, 147, 111, 75, 48, 24)
    }
}

/** A rectangle of dots, corners included. */
data class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val left get() = minOf(x0, x1)
    val right get() = maxOf(x0, x1)
    val top get() = minOf(y0, y1)
    val bottom get() = maxOf(y0, y1)
    val width get() = right - left + 1
    val height get() = bottom - top + 1
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
    fun moved(dx: Int, dy: Int) = Region(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun all(p: DeviceProfile) = Region(0, 0, p.size - 1, p.size - 1)
    }
}

/** Copied dots: [width] x [height] values from a [Region]. */
class Clip(val width: Int, val height: Int, val values: IntArray)

object Regions {
    fun copy(f: IntArray, p: DeviceProfile, r: Region): Clip = Clip(r.width, r.height, IntArray(r.width * r.height) { i ->
        val x = r.left + i % r.width
        val y = r.top + i / r.width
        if (p.isActive(x, y)) f[p.index(x, y)] else 0
    })

    fun erase(f: Frame, r: Region) {
        for (y in r.top..r.bottom) for (x in r.left..r.right) f[x, y] = 0
    }

    /** Puts [c]'s lit dots with their top left at ([x], [y]); dark dots in the clip leave what's under them. */
    fun stamp(f: Frame, c: Clip, x: Int, y: Int, opaque: Boolean = true) {
        for (i in c.values.indices) {
            val v = c.values[i]
            if (v == 0 && !opaque) continue
            f[x + i % c.width, y + i / c.width] = v
        }
    }

    fun flipped(c: Clip, horizontal: Boolean) = Clip(c.width, c.height, IntArray(c.values.size) { i ->
        val x = i % c.width
        val y = i / c.width
        if (horizontal) c.values[y * c.width + (c.width - 1 - x)] else c.values[(c.height - 1 - y) * c.width + x]
    })
}

/** The Effects sheet's "Change it" section: each turns one frame into another. */
enum class FrameOp(val label: String) {
    ROTATE("Rotate 90°"), FLIP_H("Flip ↔"), FLIP_V("Flip ↕"), INVERT("Invert"),
    SHIFT_LEFT("Shift ←"), SHIFT_RIGHT("Shift →"), SHIFT_UP("Shift ↑"), SHIFT_DOWN("Shift ↓"),
    BRIGHTER("Brighter"), DIMMER("Dimmer"), OUTLINE("Outline"), FILL_IN("Fill in"), LEVELS("Levels");

    fun apply(f: Frame): Frame = FrameOps.apply(this, f)
}

object FrameOps {
    fun apply(op: FrameOp, f: Frame): Frame {
        val p = f.profile
        val n = p.size
        return when (op) {
            FrameOp.ROTATE -> Frame.of(p, IntArray(p.cellCount) { i -> val x = i % n; val y = i / n; f.pixels[(n - 1 - x) * n + y] })
            FrameOp.FLIP_H -> Frame.of(p, IntArray(p.cellCount) { i -> f.pixels[(i / n) * n + (n - 1 - i % n)] })
            FrameOp.FLIP_V -> Frame.of(p, IntArray(p.cellCount) { i -> f.pixels[(n - 1 - i / n) * n + i % n] })
            FrameOp.INVERT -> f.inverted()
            FrameOp.SHIFT_LEFT -> AnimatePresets.shift(f, -1, 0)
            FrameOp.SHIFT_RIGHT -> AnimatePresets.shift(f, 1, 0)
            FrameOp.SHIFT_UP -> AnimatePresets.shift(f, 0, -1)
            FrameOp.SHIFT_DOWN -> AnimatePresets.shift(f, 0, 1)
            FrameOp.BRIGHTER -> Frame.of(p, IntArray(p.cellCount) { if (f.pixels[it] > 0) (f.pixels[it] * 4 / 3).coerceAtLeast(f.pixels[it] + 16) else 0 })
            FrameOp.DIMMER -> Frame.of(p, IntArray(p.cellCount) { if (f.pixels[it] > 0) (f.pixels[it] * 2 / 3).coerceAtLeast(8) else 0 })
            FrameOp.OUTLINE -> outline(f)
            FrameOp.FILL_IN -> fillIn(f)
            FrameOp.LEVELS -> levels(f)
        }
    }

    /** Keeps lit dots that touch a dark dot or the edge: a filled shape becomes its outline. */
    fun outline(f: Frame): Frame {
        val p = f.profile
        val n = p.size
        fun lit(x: Int, y: Int) = p.isActive(x, y) && f.pixels[y * n + x] > 0
        return Frame.of(p, IntArray(p.cellCount) { i ->
            val x = i % n
            val y = i / n
            if (lit(x, y) && !(lit(x + 1, y) && lit(x - 1, y) && lit(x, y + 1) && lit(x, y - 1))) f.pixels[i] else 0
        })
    }

    /** Lights the dark dots a shape encloses (those the outside can't reach): an outline becomes a sticker. */
    fun fillIn(f: Frame): Frame {
        val p = f.profile
        val n = p.size
        val outside = BooleanArray(p.cellCount)
        val stack = ArrayDeque<Int>()
        for (c in 0 until p.cellCount) {
            val x = c % n
            val y = c / n
            val edge = !p.mask[c] || x == 0 || y == 0 || x == n - 1 || y == n - 1
            if (edge && f.pixels[c] == 0) { outside[c] = true; stack += c }
        }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            val x = c % n
            val y = c / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val xx = x + dx
                val yy = y + dy
                if (xx !in 0 until n || yy !in 0 until n) continue
                val nc = yy * n + xx
                if (!outside[nc] && f.pixels[nc] == 0) { outside[nc] = true; stack += nc }
            }
        }
        val fillValue = f.pixels.maxOrNull()?.takeIf { it > 0 } ?: 255
        return Frame.of(p, IntArray(p.cellCount) { c -> if (f.pixels[c] == 0 && !outside[c] && p.mask[c]) fillValue else f.pixels[c] })
    }

    /** Stretches the lit dots so the brightest is full and the dimmest is a quarter: fixes faint designs. */
    fun levels(f: Frame): Frame {
        val lit = f.pixels.filter { it > 0 }
        if (lit.isEmpty()) return f
        val lo = lit.min()
        val hi = lit.max()
        if (hi == lo) return Frame.of(f.profile, IntArray(f.pixels.size) { if (f.pixels[it] > 0) 255 else 0 })
        return Frame.of(f.profile, IntArray(f.pixels.size) { i ->
            val v = f.pixels[i]
            if (v == 0) 0 else (64 + (v - lo) * (255 - 64) / (hi - lo).toFloat()).roundToInt()
        })
    }
}
