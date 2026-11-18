package com.dotforge.device

import kotlin.math.atan2

/**
 * Geometry of a Glyph Matrix: a square grid in which only the cells inside a round mask have a
 * physical LED. Frames are row-major arrays of [cellCount] ints (0-255 brightness).
 */
class DeviceProfile(
    val name: String,
    val size: Int,
    /** Cell is active when (x-c)^2 + (y-c)^2 <= [radiusSquared], c = (size-1)/2. */
    val radiusSquared: Int,
) {
    val cellCount: Int = size * size

    /** Row-major mask: true when the cell has a real LED. */
    val mask: BooleanArray = BooleanArray(cellCount).also { m ->
        val c = (size - 1) / 2
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - c
            val dy = y - c
            m[y * size + x] = dx * dx + dy * dy <= radiusSquared
        }
    }

    /** Number of real LEDs. */
    val ledCount: Int = mask.count { it }

    /** Cell indices (row-major) of the real LEDs, in row-major order. LED i of a "real LED array" is cell [ledCells]\[i]. */
    val ledCells: IntArray = IntArray(ledCount).also { out ->
        var i = 0
        for (cell in 0 until cellCount) if (mask[cell]) out[i++] = cell
    }

    fun index(x: Int, y: Int): Int = y * size + x
    fun isActive(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && mask[y * size + x]

    /**
     * Outer ring: active cells that have at least one 4-neighbour outside the mask, ordered
     * clockwise starting at 12 o'clock (the top-centre cell). Cell indices.
     */
    val ring: List<Int> by lazy {
        val c = (size - 1) / 2.0
        val edge = ledCells.filter { cell ->
            val x = cell % size
            val y = cell / size
            !isActive(x + 1, y) || !isActive(x - 1, y) || !isActive(x, y + 1) || !isActive(x, y - 1)
        }
        edge.sortedBy { cell ->
            val dx = cell % size - c
            val dy = cell / size - c
            // angle clockwise from straight up, in [0, 2pi)
            val a = atan2(dx, -dy)
            if (a < 0) a + 2 * Math.PI else a
        }
    }

    /** Full frame (cellCount) to real-LED array (ledCount). */
    fun toRealLeds(frame: IntArray): IntArray {
        require(frame.size == cellCount) { "frame must have $cellCount cells, got ${frame.size}" }
        return IntArray(ledCount) { frame[ledCells[it]] }
    }

    /** Real-LED array (ledCount) to full frame (cellCount); cells without an LED are 0. */
    fun fromRealLeds(values: IntArray): IntArray {
        require(values.size == ledCount) { "expected $ledCount LED values, got ${values.size}" }
        val frame = IntArray(cellCount)
        for (i in values.indices) frame[ledCells[i]] = values[i]
        return frame
    }

    companion object {
        /** Nothing Phone (3): 25x25 grid, Glyph.DEVICE_23112, 489 LEDs (radius^2 = 153). */
        val Phone3 = DeviceProfile("Nothing Phone (3)", 25, 153)
    }
}
