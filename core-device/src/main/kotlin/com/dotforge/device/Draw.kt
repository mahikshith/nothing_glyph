package com.dotforge.device

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Drawing tools for the pixel editor; each changes the frame in place. */
object Draw {
    /** Fills the area of dots with the same value as (x, y), 4-connected, with [value]. */
    fun fill(f: Frame, x: Int, y: Int, value: Int) {
        val p = f.profile
        if (!p.isActive(x, y)) return
        val target = f.pixels[p.index(x, y)]
        val v = value.coerceIn(0, 255)
        if (target == v) return
        val stack = ArrayDeque(listOf(x to y))
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeLast()
            if (!p.isActive(cx, cy) || f.pixels[p.index(cx, cy)] != target) continue
            f.pixels[p.index(cx, cy)] = v
            stack += listOf(cx + 1 to cy, cx - 1 to cy, cx to cy + 1, cx to cy - 1)
        }
    }

    /** A straight line of dots (Bresenham). */
    fun line(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            f[x, y] = value
            if (x == x1 && y == y1) return
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** The outline of the box with corners (x0, y0) and (x1, y1). */
    fun rect(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        line(f, x0, y0, x1, y0, value)
        line(f, x1, y0, x1, y1, value)
        line(f, x1, y1, x0, y1, value)
        line(f, x0, y1, x0, y0, value)
    }

    /** A filled box with corners (x0, y0) and (x1, y1). */
    fun box(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        for (y in minOf(y0, y1)..maxOf(y0, y1)) for (x in minOf(x0, x1)..maxOf(x0, x1)) f[x, y] = value
    }

    /** A filled circle centred on (cx, cy) through (x, y). */
    fun disc(f: Frame, cx: Int, cy: Int, x: Int, y: Int, value: Int) {
        val r = hypot((x - cx).toDouble(), (y - cy).toDouble()) + 0.5
        val p = f.profile
        for (py in 0 until p.size) for (px in 0 until p.size) {
            if (hypot((px - cx).toDouble(), (py - cy).toDouble()) < r) f[px, py] = value
        }
    }

    /** A circle outline centred on (cx, cy) through (x, y). */
    fun circle(f: Frame, cx: Int, cy: Int, x: Int, y: Int, value: Int) {
        val r = hypot((x - cx).toDouble(), (y - cy).toDouble())
        val p = f.profile
        for (py in 0 until p.size) for (px in 0 until p.size) {
            if (abs(hypot((px - cx).toDouble(), (py - cy).toDouble()) - r) < 0.5) f[px, py] = value
        }
        if (r.roundToInt() == 0) f[cx, cy] = value
    }

    /** What the editor shows with onion skin: [current] over a faint [previous]. */
    fun onion(current: Frame, previous: Frame?): IntArray {
        previous ?: return current.pixels
        return IntArray(current.pixels.size) { i -> if (current.pixels[i] > 0) current.pixels[i] else previous.pixels[i] / 5 }
    }
}
