package com.dotforge.live

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What sits inside a Live progress ring. */
sealed interface Centre {
    data class Minutes(val n: Int) : Centre
    data class Percent(val n: Int) : Centre
    /** A library glyph (already a frame), shrunk to fit inside the ring. */
    data class Icon(val frame: Frame) : Centre
    data object None : Centre
}

/**
 * The Live progress ring: the ring is the progress, the middle the minutes left (or the stage's icon); about to
 * arrive, the ring pulses; done, it plays [done] once.
 */
object LiveRing {
    /** The box an icon is shrunk into, inside the ring. */
    const val ICON_BOX = 13

    fun frame(p: DeviceProfile, fraction: Float, centre: Centre, ring: Int = 255): Frame {
        val f = Effects.ring(p, fraction, on = ring, off = 22)
        when (centre) {
            is Centre.Minutes -> f.overlay(com.dotforge.device.TimerFaces.digits(p, centre.n.coerceIn(0, 999).toString()))
            is Centre.Percent -> f.overlay(com.dotforge.device.TimerFaces.digits(p, centre.n.coerceIn(0, 100).toString()))
            is Centre.Icon -> {
                val icon = fit(p, centre.frame)
                for (cell in p.ledCells) if (icon.pixels[cell] > 0) f.pixels[cell] = icon.pixels[cell]
            }
            Centre.None -> Unit
        }
        return f
    }

    /** Still while it travels; about to arrive, the ring breathes from dim to full and back every 1.2 s. */
    fun animation(p: DeviceProfile, fraction: Float, centre: Centre, arriving: Boolean): Animation {
        if (!arriving) return Animation.single(frame(p, fraction, centre), 1000)
        val steps = 12
        return Animation(p, List(steps) { i ->
            val k = 0.5f + 0.5f * cos(2 * PI * i / steps).toFloat()
            AnimFrame(frame(p, fraction, centre, ring = (60 + 195 * k).roundToInt()), 100)
        })
    }

    /** Done: the ring sweeps to full, the icon (a tick by default) holds, a ripple goes out twice, then it rests. */
    fun done(p: DeviceProfile, icon: Frame?): Animation {
        val frames = ArrayList<AnimFrame>()
        val mark = icon?.let { fit(p, it) } ?: tick(p)
        for (i in 1..8) frames += AnimFrame(Effects.ring(p, i / 8f, off = 22).also { f -> overlayInto(f, mark) }, 50)
        val c = (p.size - 1) / 2.0
        repeat(2) {
            for (r in 0..6) {
                val f = Effects.ring(p, 1f)
                overlayInto(f, mark)
                val rad = 6.0 + r
                for (cell in p.ledCells) {
                    val d = hypot(cell % p.size - c, cell / p.size - c)
                    if (kotlin.math.abs(d - rad) < 0.7 && f.pixels[cell] == 0) f.pixels[cell] = 150
                }
                frames += AnimFrame(f, 60)
            }
        }
        val rest = Effects.ring(p, 1f).also { overlayInto(it, mark) }
        frames += AnimFrame(rest, 1200)
        return Animation(p, frames)
    }

    private fun overlayInto(f: Frame, icon: Frame) {
        for (cell in f.profile.ledCells) if (icon.pixels[cell] > 0) f.pixels[cell] = icon.pixels[cell]
    }

    /**
     * The icon for [id] at ring size: a drawing made for the ring where the library's picture is too big to sit
     * inside it at 1:1 (the scooter, the bike taxi, the auto, the food bag, the apple, the download arrow), and the
     * ride stages and done marks drawn for it (pickup, trip, the delivered box, the chequered flag); else
     * the library glyph's poster (an animated glyph's first frame may be blank), fitted. Null when unknown.
     */
    fun icon(p: DeviceProfile, id: String?): Frame? {
        RING_ICONS[id]?.let { rows -> return fit(p, draw(p, rows)) }
        return com.dotforge.device.GlyphLibrary.get(id)?.animation(p)?.let(com.dotforge.device.Posters::of)?.let { fit(p, it) }
    }

    /** Ids drawn for the ring (tests check each fits). */
    val drawn: Set<String> get() = RING_ICONS.keys

    /** True when the ring drawing [id] shows every one of its dots (it fits at 1:1, not shrunk). */
    fun fullSize(p: DeviceProfile, id: String): Boolean {
        val rows = RING_ICONS[id] ?: return false
        val dots = rows.sumOf { r -> r.count { it != '.' } }
        return icon(p, id)?.pixels?.count { it > 0 } == dots
    }

    private fun draw(p: DeviceProfile, rows: List<String>): Frame {
        val f = Frame.blank(p)
        val w = rows.maxOf { it.length }
        val x0 = (p.size - w) / 2
        val y0 = (p.size - rows.size) / 2
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val v = when (ch) { '#' -> 255; 'o' -> 150; '+' -> 70; else -> 0 }
                if (v > 0) f[x0 + x, y0 + y] = v
            }
        }
        return f
    }

    /** True when every lit cell of [icon] keeps a dark dot between it and the ring. */
    fun clearOfRing(p: DeviceProfile, icon: Frame): Boolean {
        val n = p.size
        val near = HashSet<Int>()
        for (cell in p.ring) {
            val x = cell % n
            val y = cell / n
            for (dy in -1..1) for (dx in -1..1) if (x + dx in 0 until n && y + dy in 0 until n) near += (y + dy) * n + x + dx
        }
        return p.ledCells.none { icon.pixels[it] > 0 && it in near } && icon.pixels.indices.none { icon.pixels[it] > 0 && !p.mask[it] }
    }

    /**
     * [icon] in the middle of the ring: at 1:1, centred, when it fits clear of the ring (so its lines stay
     * sharp); otherwise shrunk ([shrink]).
     */
    fun fit(p: DeviceProfile, icon: Frame): Frame {
        val n = p.size
        val lit = icon.pixels.indices.filter { icon.pixels[it] > 0 }
        if (lit.isEmpty()) return Frame.blank(p)
        val x0 = lit.minOf { it % n }
        val x1 = lit.maxOf { it % n }
        val y0 = lit.minOf { it / n }
        val y1 = lit.maxOf { it / n }
        val ox = (n - (x1 - x0 + 1)) / 2 - x0
        val oy = (n - (y1 - y0 + 1)) / 2 - y0
        val moved = Frame.blank(p)
        var lost = false
        for (cell in lit) {
            val x = cell % n + ox
            val y = cell / n + oy
            if (!p.isActive(x, y)) { lost = true; continue }
            moved.pixels[p.index(x, y)] = icon.pixels[cell]
        }
        return if (!lost && clearOfRing(p, moved)) moved else shrink(p, icon)
    }

    /** Ring-size drawings: at most 18 dots wide and 13 high, so they sit inside the ring at 1:1 (tests check). */
    private val RING_ICONS: Map<String, List<String>> = mapOf(
        "g-scooter" to listOf(
            ".######..........",
            ".#oooo#.....####.",
            ".#oooo#.......#..",
            ".######.......#..",
            "...##.........#..",
            ".##########..##..",
            ".###########.#...",
            "..###########....",
            "..###.......###..",
            ".#...#.....#...#.",
            ".#.#.#.....#.#.#.",
            ".#...#.....#...#.",
            "..###.......###..",
        ),
        "g-bike-taxi" to listOf(
            "......##........",
            "......##........",
            ".....###........",
            ".....####.##....",
            "......#####.....",
            "......##..#.....",
            "...#######.#....",
            "..##########....",
            ".###.......###..",
            "#...#.....#...#.",
            "#.#.#.....#.#.#.",
            "#...#.....#...#.",
            ".###.......###..",
        ),
        "g-auto" to listOf(
            "..###########....",
            ".##.....#....#...",
            ".#......#.....#..",
            ".#......#.....##.",
            "################.",
            "################.",
            "################.",
            ".###.........###.",
            "#...#.......#...#",
            "#.#.#.......#.#.#",
            "#...#.......#...#",
            ".###.........###.",
        ),
        "g-apple" to listOf(
            "......##.oo..",
            ".......#oooo.",
            "..####.#.oo..",
            ".##########..",
            "#############",
            "#############",
            "#############",
            "#############",
            ".###########.",
            ".###########.",
            "..#########..",
            "...###.###...",
        ),
        "g-food-bag" to listOf(
            "...###...###...",
            "..#...#.#...#..",
            "..#...#.#...#..",
            "###############",
            "#ooooooooooooo#",
            "#ooo#.#.#ooooo#",
            "#ooo#####ooooo#",
            "#oooo###oooooo#",
            "#ooooo#ooooooo#",
            "#ooooo#ooooooo#",
            ".#ooooooooooo#.",
            ".#############.",
        ),
        // a ride's driver coming to you: the car, a dotted route, the pin where you wait
        "ride-pickup" to listOf(
            "..............###.",
            ".............#####",
            ".............##.##",
            ".............#####",
            "..............###.",
            "..#####........#..",
            ".#..#..#.......#..",
            "#...#...#......#..",
            "#########......#..",
            "#########.#.#..#..",
            ".##...##..........",
        ),
        // on the trip: motion lines, the same car, and a chequered flag ahead
        "ride-trip" to listOf(
            ".............###++",
            ".............###++",
            ".............#++##",
            ".............#++##",
            ".............#....",
            ".....#####...#....",
            "....#..#..#..#....",
            "##.#...#...#.#....",
            "...#########.#....",
            "##.#########.#....",
            "....##...##..#....",
        ),
        // delivered: a box with a bold tick, no lid line (it must not read as an envelope)
        "g-parcel-done" to listOf(
            "#############",
            "#...........#",
            "#........##.#",
            "#.......##..#",
            "#..##..##...#",
            "#...####....#",
            "#....##.....#",
            "#...........#",
            "#############",
        ),
        // arrived: a chequered flag, 2x2 checks (the dark checks faint, so the flag keeps its shape)
        "g-finish-flag" to listOf(
            "###++##++##",
            "###++##++##",
            "#++##++##++",
            "#++##++##++",
            "###++##++##",
            "###++##++##",
            "#..........",
            "#..........",
            "#..........",
            "#..........",
            "#..........",
        ),
        "download" to listOf(
            ".....###.....",
            ".....###.....",
            ".....###.....",
            ".....###.....",
            "..#########..",
            "...#######...",
            "....#####....",
            ".....###.....",
            "......#......",
            ".............",
            "#...........#",
            "#...........#",
            "#############",
        ),
    )

    /** A tick for "done" when there is no icon. */
    fun tick(p: DeviceProfile): Frame {
        val f = Frame.blank(p)
        val c = (p.size - 1) / 2
        for (i in 0..2) { f[c - 4 + i, c + i] = 255; f[c - 4 + i, c + i - 1] = 255 }
        for (i in 0..6) { f[c - 1 + i, c + 2 - i] = 255; f[c - 1 + i, c + 1 - i] = 255 }
        return f
    }

    /**
     * [icon] shrunk into the middle: its lit area is scaled to fit [box]x[box] cells (each target cell takes the
     * brightest of the cells it covers, so thin lines survive) and centred, clear of the ring.
     */
    fun shrink(p: DeviceProfile, icon: Frame, box: Int = ICON_BOX): Frame {
        val n = p.size
        val lit = p.ledCells.filter { icon.pixels[it] > 0 }
        if (lit.isEmpty()) return Frame.blank(p)
        val x0 = lit.minOf { it % n }
        val x1 = lit.maxOf { it % n }
        val y0 = lit.minOf { it / n }
        val y1 = lit.maxOf { it / n }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val side = maxOf(w, h)
        val target = minOf(box, side)
        val tw = (w * target + side - 1) / side
        val th = (h * target + side - 1) / side
        val ox = (n - tw) / 2
        val oy = (n - th) / 2
        val out = Frame.blank(p)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val sx0 = x0 + tx * side / target
            val sx1 = (x0 + (tx + 1) * side / target).coerceAtLeast(sx0 + 1)
            val sy0 = y0 + ty * side / target
            val sy1 = (y0 + (ty + 1) * side / target).coerceAtLeast(sy0 + 1)
            var v = 0
            for (sy in sy0 until minOf(sy1, n)) for (sx in sx0 until minOf(sx1, n)) v = maxOf(v, icon.pixels[sy * n + sx])
            if (v > 0) out[ox + tx, oy + ty] = v
        }
        return out
    }

    /**
     * The approaching ring before a calendar event or an alarm: filled over the lead time, minutes in the middle,
     * breathing in the last minute.
     */
    fun approaching(p: DeviceProfile, fraction: Float, minutesLeft: Int, icon: Frame?): Animation {
        val centre = if (minutesLeft <= 1 && icon != null) Centre.Icon(icon) else Centre.Minutes(minutesLeft.coerceAtLeast(1))
        return animation(p, fraction, centre, arriving = minutesLeft <= 1)
    }
}
