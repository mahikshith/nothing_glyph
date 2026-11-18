package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** How another phone's zones are laid out on the round matrix. */
enum class ConvertLayout(val label: String, val hint: String) {
    RINGS("Rings", "Each zone is a ring, first in the middle"),
    ARCS("Arcs", "Each zone is a slice, clockwise from the top"),
    BARS("Bars", "Each zone is a bar, left to right"),
    BURST("Radial burst", "All zones together as one growing ring"),
}

/**
 * Converts a glyph ringtone made for another phone (one brightness track per zone) to Phone (3) matrix
 * frames. Timing is kept: one output row per input row, at the same 60 fps. Values stay in 0-4095 and cells
 * without an LED are always 0.
 */
object RingtoneConverter {

    /** Converted rows (cellCount values each). [intensity] scales brightness (1 = as made). */
    fun convert(
        rows: List<IntArray>,
        layout: ConvertLayout,
        profile: DeviceProfile = DeviceProfile.Phone3,
        intensity: Float = 1f,
    ): List<IntArray> {
        val cols = rows.firstOrNull()?.size ?: return emptyList()
        if (layout == ConvertLayout.BURST) return rows.map { burst(it, profile, intensity) }
        val zones = zoneMap(cols, layout, profile)
        return rows.map { r ->
            val out = IntArray(profile.cellCount)
            for (cell in profile.ledCells) {
                var v = 0
                for (z in zones[cell]) v = maxOf(v, r.getOrElse(z) { 0 })
                out[cell] = scale(v, intensity)
            }
            out
        }
    }

    /** Phone (3) rows kept as they are, apart from [intensity] and the LED mask. */
    fun keep(rows: List<IntArray>, profile: DeviceProfile = DeviceProfile.Phone3, intensity: Float = 1f): List<IntArray> =
        rows.map { r -> IntArray(profile.cellCount) { if (profile.mask[it]) scale(r.getOrElse(it) { 0 }, intensity) else 0 } }

    fun scale(v: Int, intensity: Float): Int =
        if (intensity == 1f) v.coerceIn(0, GlyphAuthor.MAX) else (v * intensity).toInt().coerceIn(0, GlyphAuthor.MAX)

    /** For each cell, the zones (input columns) whose brightness it shows; empty for cells without an LED. */
    fun zoneMap(columns: Int, layout: ConvertLayout, profile: DeviceProfile = DeviceProfile.Phone3): Array<IntArray> {
        require(columns > 0) { "no columns" }
        val map = Array(profile.cellCount) { IntArray(0) }
        val c = (profile.size - 1) / 2.0
        when (layout) {
            ConvertLayout.RINGS -> {
                val ringOf = ringZones(columns, profile)
                for (cell in profile.ledCells) map[cell] = intArrayOf(ringOf.getValue(radius2(cell, profile)))
            }
            ConvertLayout.ARCS -> for (cell in profile.ledCells) {
                val dx = cell % profile.size - c
                val dy = cell / profile.size - c
                // clockwise from 12 o'clock; the centre cell counts as the first slice
                var a = if (dx == 0.0 && dy == 0.0) 0.0 else atan2(dx, -dy)
                if (a < 0) a += 2 * Math.PI
                map[cell] = intArrayOf(floor(a / (2 * Math.PI) * columns).toInt().coerceIn(0, columns - 1))
            }
            ConvertLayout.BARS -> {
                val n = profile.size
                val perColumn = Array(n) { x ->
                    val from = x * columns / n
                    val to = maxOf(from + 1, (x + 1) * columns / n)
                    IntArray(to - from) { from + it }
                }
                for (cell in profile.ledCells) map[cell] = perColumn[cell % n]
            }
            ConvertLayout.BURST -> for (cell in profile.ledCells) map[cell] = IntArray(columns) { it }
        }
        return map
    }

    private fun radius2(cell: Int, p: DeviceProfile): Int {
        val c = (p.size - 1) / 2
        val dx = cell % p.size - c
        val dy = cell / p.size - c
        return dx * dx + dy * dy
    }

    /**
     * Zone for each distinct squared radius, inside out, so the rings hold about the same number of LEDs.
     * No zone is skipped while there are at least as many distinct radii as zones.
     */
    private fun ringZones(columns: Int, p: DeviceProfile): Map<Int, Int> {
        val counts = p.ledCells.toList().groupingBy { radius2(it, p) }.eachCount().toSortedMap()
        val total = p.ledCount
        val d = counts.size
        val out = HashMap<Int, Int>()
        var seen = 0
        var prev = -1
        for ((j, entry) in counts.entries.withIndex()) {
            var z = (seen.toLong() * columns / total).toInt()
            if (columns <= d) {
                z = z.coerceAtLeast(columns - (d - j)) // leave enough radii for the remaining zones
                z = z.coerceAtMost(prev + 1) // never skip a zone
            }
            z = z.coerceIn(0, columns - 1).coerceAtLeast(maxOf(prev, 0))
            out[entry.key] = z
            prev = z
            seen += entry.value
        }
        return out
    }

    /** Overall energy (half the loudest zone, half the average) as a ring whose radius follows it, filled faintly. */
    private fun burst(r: IntArray, p: DeviceProfile, intensity: Float): IntArray {
        val max = r.maxOrNull() ?: 0
        val mean = if (r.isEmpty()) 0.0 else r.average()
        val e = ((0.5 * max + 0.5 * mean) / GlyphAuthor.MAX).coerceIn(0.0, 1.0)
        val out = IntArray(p.cellCount)
        if (e <= 0.0) return out
        val maxR = sqrt(p.radiusSquared.toDouble())
        val ring = e * (maxR + 1)
        for (cell in p.ledCells) {
            val rr = sqrt(radius2(cell, p).toDouble())
            val edge = (1 - abs(rr - ring) / 1.5).coerceAtLeast(0.0)
            val fill = if (rr < ring) 0.3 else 0.0
            val v = maxOf(edge, fill) * (0.4 + 0.6 * e) * GlyphAuthor.MAX
            out[cell] = scale(v.toInt(), intensity)
        }
        return out
    }
}
