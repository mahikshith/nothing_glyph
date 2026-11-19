package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.Posters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DotForge's glyph art: original pictures drawn dot by dot for the 25x25 matrix, kept as text in
 * `library/glyphs.txt` (see the notes at the top of that file). Some are animated frame by frame; others
 * get a [Motion] (a bell swings, a car drives in, a heart beats).
 */
internal object GlyphArt {
    class Entry(
        val id: String, val name: String, val category: String, val tags: List<String>,
        /** Frames of rows for a drawn picture, or empty for a motion. */
        val frames: List<List<String>>,
        val durations: List<Int>,
        /** The motion and its arguments for a "!" entry. */
        val motion: List<String>,
    ) {
        val still: Boolean get() = motion.isEmpty() && frames.size == 1
    }

    val entries: List<Entry> by lazy {
        val text = GlyphArt::class.java.getResourceAsStream("/library/glyphs.txt")?.bufferedReader()?.use { it.readText() }
            ?: error("missing library asset glyphs.txt")
        parse(text)
    }

    private val byId by lazy { entries.associateBy { it.id } }

    fun entry(id: String): Entry? = byId[id]

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var head: List<String>? = null
        var motionLine = false
        val frames = ArrayList<List<String>>()
        var rows = ArrayList<String>()
        fun close() {
            val h = head ?: return
            if (rows.isNotEmpty()) frames += rows
            val extra = h.getOrNull(4).orEmpty()
            val ms = if (!motionLine && extra.startsWith("ms=")) extra.removePrefix("ms=").split(',').map { it.trim().toInt() } else emptyList()
            out += Entry(
                h[0], h[1], h[2], h[3].split(' ').filter { it.isNotBlank() },
                if (motionLine) emptyList() else frames.toList(), ms,
                if (motionLine) extra.split(' ').filter { it.isNotBlank() } else emptyList(),
            )
            head = null; frames.clear(); rows = ArrayList()
        }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("#!")) continue
            if (line.startsWith("@ ") || line.startsWith("! ")) {
                close()
                motionLine = line[0] == '!'
                head = line.substring(2).split('|').map { it.trim() }
                continue
            }
            if (head == null) continue
            when {
                line.trim() == "~" -> { frames += rows; rows = ArrayList() }
                line.isBlank() -> if (rows.isNotEmpty()) { frames += rows; rows = ArrayList() }
                !motionLine -> rows += line
            }
        }
        close()
        return out
    }

    /** The New Year picture's year, drawn when it is shown: rows 14-20 of its art hold four 4 x 7 digits. */
    private const val NEW_YEAR = "g-newyear"
    private const val YEAR_ROW = 14

    /** The 4x7 digits of the calendar's year (tests check the 8). */
    internal fun digit(ch: Char): List<String>? = DIGITS[ch]

    private val DIGITS = mapOf(
        '0' to listOf("####", "#..#", "#..#", "#..#", "#..#", "#..#", "####"),
        '1' to listOf("..#.", ".##.", "..#.", "..#.", "..#.", "..#.", ".###"),
        '2' to listOf("####", "...#", "...#", "####", "#...", "#...", "####"),
        '3' to listOf("####", "...#", "...#", ".###", "...#", "...#", "####"),
        '4' to listOf("#..#", "#..#", "#..#", "####", "...#", "...#", "...#"),
        '5' to listOf("####", "#...", "#...", "####", "...#", "...#", "####"),
        '6' to listOf("####", "#...", "#...", "####", "#..#", "#..#", "####"),
        '7' to listOf("####", "...#", "...#", "..#.", "..#.", ".#..", ".#.."),
        '8' to listOf(".##.", "#..#", "#..#", ".##.", "#..#", "#..#", ".##."),
        '9' to listOf("####", "#..#", "#..#", "####", "...#", "...#", "####"),
    )

    /**
     * The year New Year fireworks celebrate: next year from November on (the run-up to the 31st), else this year.
     * [today] is read when the picture is drawn, so it never shows a stale year.
     */
    internal fun newYear(today: java.time.LocalDate = java.time.LocalDate.now()): Int =
        if (today.monthValue >= 11) today.year + 1 else today.year

    /** [e]'s rows for frame [i], with the year written in for the New Year picture. */
    private fun rows(e: Entry, i: Int): List<String> {
        val rows = e.frames[i]
        if (e.id != NEW_YEAR) return rows
        val year = newYear().toString().takeLast(4)
        return rows.mapIndexed { y, r ->
            if (y !in YEAR_ROW until YEAR_ROW + 7) r
            else "." + year.map { DIGITS.getValue(it)[y - YEAR_ROW] }.joinToString(".") + "."
        }
    }

    private fun value(c: Char) = when (c) { '#' -> 255; 'o' -> 150; '+' -> 70; else -> 0 }

    /** [rows] centred on the matrix of [p] and clipped to its round mask. */
    fun picture(p: DeviceProfile, rows: List<String>): Frame {
        val w = rows.maxOf { it.length }
        val x0 = (p.size - w) / 2
        val y0 = (p.size - rows.size) / 2
        val out = IntArray(p.cellCount)
        for ((ry, row) in rows.withIndex()) for ((rx, c) in row.withIndex()) {
            val v = value(c)
            val x = x0 + rx
            val y = y0 + ry
            if (v > 0 && p.isActive(x, y)) out[p.index(x, y)] = v
        }
        return Frame(p, out)
    }

    /** Lit dots of [rows] that fall outside the Phone (3) mask (a drawing check). */
    fun outside(rows: List<String>, p: DeviceProfile = DeviceProfile.Phone3): Int {
        val w = rows.maxOf { it.length }
        val x0 = (p.size - w) / 2
        val y0 = (p.size - rows.size) / 2
        var n = 0
        for ((ry, row) in rows.withIndex()) for ((rx, c) in row.withIndex()) if (value(c) > 0 && !p.isActive(x0 + rx, y0 + ry)) n++
        return n
    }

    fun still(id: String, p: DeviceProfile): Frame {
        val e = requireNotNull(byId[id]) { "unknown glyph $id" }
        return if (e.frames.isNotEmpty()) picture(p, rows(e, 0)) else Posters.of(load(e, p))
    }

    /**
     * [frame]'s picture shrunk into rows [top]..[bottom] (at most [maxWidth] wide), centred across and resting on
     * [bottom]: the glyph above the words of a glyph + text design. Each new dot averages the dots it covers, so
     * thin strokes turn dim rather than vanish; the picture shrinks a little more until it fits the circle.
     */
    fun shrink(frame: Frame, p: DeviceProfile, top: Int = 1, bottom: Int = 13, maxWidth: Int = 19): Frame {
        val n = p.size
        val src = frame.pixels
        val lit = p.ledCells.filter { src[it] > 0 }
        if (lit.isEmpty()) return Frame.blank(p)
        val x0 = lit.minOf { it % n }
        val x1 = lit.maxOf { it % n }
        val y0 = lit.minOf { it / n }
        val y1 = lit.maxOf { it / n }
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        var s = minOf(1.0, (bottom - top + 1).toDouble() / bh, maxWidth.toDouble() / bw)
        while (true) {
            val tw = maxOf(1, Math.round(bw * s).toInt())
            val th = maxOf(1, Math.round(bh * s).toInt())
            val ox = (n - tw) / 2
            val oy = bottom - th + 1
            val out = Frame.blank(p)
            var outside = 0
            for (ty in 0 until th) for (tx in 0 until tw) {
                val sx0 = x0 + tx * bw.toDouble() / tw
                val sx1 = x0 + (tx + 1) * bw.toDouble() / tw
                val sy0 = y0 + ty * bh.toDouble() / th
                val sy1 = y0 + (ty + 1) * bh.toDouble() / th
                var sum = 0.0
                var area = 0.0
                for (sy in sy0.toInt() until kotlin.math.ceil(sy1).toInt()) for (sx in sx0.toInt() until kotlin.math.ceil(sx1).toInt()) {
                    val ax = minOf(sx + 1.0, sx1) - maxOf(sx.toDouble(), sx0)
                    val ay = minOf(sy + 1.0, sy1) - maxOf(sy.toDouble(), sy0)
                    if (ax <= 0 || ay <= 0) continue
                    sum += src[sy * n + sx] * ax * ay
                    area += ax * ay
                }
                val v = if (area > 0) sum / area / 255 else 0.0
                val q = when {
                    v >= 0.5 -> 255
                    v >= 0.3 -> 150
                    v >= 0.15 -> 70
                    else -> 0
                }
                if (q == 0) continue
                val x = ox + tx
                val y = oy + ty
                if (p.isActive(x, y)) out.pixels[p.index(x, y)] = q else outside++
            }
            if (outside == 0 || s < 0.3) return out
            s *= 0.93
        }
    }

    fun load(e: Entry, p: DeviceProfile): Animation {
        if (e.motion.isNotEmpty()) return Motion.make(p, e.motion)
        val ms = e.durations
        return Animation(p, e.frames.indices.map { i -> AnimFrame(picture(p, rows(e, i)), ms.getOrElse(i) { ms.lastOrNull() ?: 1000 }) })
    }

    /** Motions for still pictures. Arguments name pictures by id, then optional frame times. */
    object Motion {
        val names = listOf("beat", "bounce", "swing", "rock", "shake", "wiggle", "float", "drive", "rise", "drop", "glow", "flash", "sparkle", "zoom", "circle", "swap", "seq")

        fun make(p: DeviceProfile, args: List<String>): Animation {
            val name = args[0]
            val ids = args.drop(1).filter { !it.first().isDigit() }
            val times = args.drop(1).filter { it.first().isDigit() }.map { it.toInt() }
            val f = still(ids[0], p)
            fun of(vararg steps: Pair<Frame, Int>) = Animation(p, steps.map { AnimFrame(it.first, it.second) })
            fun moves(list: List<Pair<Int, Int>>, ms: Int, hold: Int) =
                Animation(p, list.mapIndexed { i, (dx, dy) -> AnimFrame(shift(f, dx, dy), if (i == list.size - 1) hold else ms) })
            return when (name) {
                "beat" -> of(f to 520, scale(f, 1.14) to 110, f to 110, scale(f, 1.09) to 110, f to 700)
                // big enough to see at a glance on 25 x 25: two dots side to side, a seven-row hop
                "bounce" -> moves(listOf(0 to 0, 0 to -2, 0 to -4, 0 to -6, 0 to -7, 0 to -6, 0 to -4, 0 to -2, 0 to 0, 0 to 1, 0 to 0, 0 to -2, 0 to 0), 65, 500)
                "swing" -> moves(List(2) { listOf(1 to 0, 2 to 0, 2 to 0, 1 to 0, 0 to 0, -1 to 0, -2 to 0, -2 to 0, -1 to 0, 0 to 0) }.flatten(), 60, 800)
                "rock" -> moves(listOf(0 to 0, 1 to 0, 2 to 1, 1 to 1, 0 to 1, -1 to 1, -2 to 1, -1 to 0), 150, 150)
                "shake" -> moves(List(5) { listOf(2 to 0, -2 to 0) }.flatten() + listOf(1 to 0, 0 to 0), 45, 650)
                "wiggle" -> moves(listOf(2 to 0, -2 to 0, 2 to -1, 0 to -2, -2 to -1, 2 to 0, -1 to 0, 0 to -3, 0 to 0), 70, 800)
                "float" -> Animation(p, List(16) { i -> AnimFrame(shift(f, 0, (-1.5 + 1.5 * cos(2 * PI * i / 16)).roundToInt()), 110) })
                "drive", "rise" -> {
                    val path = listOf(-24, -18, -13, -9, -6, -3, -1, 0) + List(8) { 1 + it * it / 2 + it }
                    val list = path.map { if (name == "drive") it to 0 else 0 to -it }
                    Animation(p, list.mapIndexed { i, (dx, dy) -> AnimFrame(shift(f, dx, dy), if (i == 7) 1100 else 60) } + AnimFrame(Frame.blank(p), 300))
                }
                "drop" -> moves(listOf(0 to -24, 0 to -20, 0 to -15, 0 to -10, 0 to -5, 0 to 0, 0 to -2, 0 to -1, 0 to 0), 55, 1400)
                "glow" -> Animation(p, List(20) { i -> AnimFrame(f.scaled((0.55 - 0.45 * cos(2 * PI * i / 20)).toFloat()), 90) })
                "flash" -> of(f to 110, Frame.blank(p) to 70, f to 110, Frame.blank(p) to 70, f to 1200, f.scaled(0.4f) to 300)
                "sparkle" -> sparkle(f)
                "zoom" -> of(scale(f, 0.25) to 70, scale(f, 0.5) to 70, scale(f, 0.8) to 70, scale(f, 1.12) to 90, f to 1500)
                "circle" -> Animation(p, List(12) { i -> AnimFrame(shift(f, (2 * cos(2 * PI * i / 12)).roundToInt(), (2 * sin(2 * PI * i / 12)).roundToInt()), 90) })
                "swap" -> of(f to times.getOrElse(0) { 600 }, still(ids[1], p) to times.getOrElse(1) { 600 })
                "seq" -> Animation(p, ids.mapIndexed { i, id -> AnimFrame(still(id, p), times.getOrElse(i) { 500 }) })
                else -> error("unknown motion $name")
            }
        }

        private fun shift(f: Frame, dx: Int, dy: Int): Frame {
            val p = f.profile
            val out = IntArray(p.cellCount)
            for (cell in p.ledCells) {
                val v = f.pixels[cell]
                if (v == 0) continue
                val x = cell % p.size + dx
                val y = cell / p.size + dy
                if (p.isActive(x, y)) out[p.index(x, y)] = v
            }
            return Frame(p, out)
        }

        /** [f] grown or shrunk about the middle (nearest dot). */
        private fun scale(f: Frame, k: Double): Frame {
            val p = f.profile
            val c = (p.size - 1) / 2.0
            val out = IntArray(p.cellCount)
            for (cell in p.ledCells) {
                val sx = (c + (cell % p.size - c) / k).roundToInt()
                val sy = (c + (cell / p.size - c) / k).roundToInt()
                if (sx in 0 until p.size && sy in 0 until p.size) out[cell] = f.pixels[sy * p.size + sx]
            }
            return Frame(p, out)
        }

        /** The picture with small four-point twinkles coming and going in the dark around it. */
        private fun sparkle(f: Frame): Animation {
            val p = f.profile
            val spots = p.ledCells.filter { cell ->
                val x = cell % p.size
                val y = cell / p.size
                (-2..2).all { d -> listOf(x + d to y, x to y + d).all { (xx, yy) -> !p.isActive(xx, yy) || f.pixels[p.index(xx, yy)] == 0 } } &&
                    listOf(x - 2 to y, x + 2 to y, x to y - 2, x to y + 2).all { (xx, yy) -> p.isActive(xx, yy) }
            }
            val picks = (0 until 5).mapNotNull { k -> spots.getOrNull((hash(k, 7, 3) * spots.size).toInt()) }
            return Animation(p, List(12) { i ->
                val out = f.pixels.copyOf()
                for ((k, cell) in picks.withIndex()) {
                    val phase = (i + k * 5) % 12
                    val v = when (phase) { 0 -> 150; 1 -> 255; 2 -> 150; else -> 0 }
                    if (v == 0) continue
                    val x = cell % p.size
                    val y = cell / p.size
                    out[cell] = v
                    if (phase == 1) for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) if (p.isActive(x + dx, y + dy)) out[p.index(x + dx, y + dy)] = 110
                }
                AnimFrame(Frame(p, out), 110)
            })
        }
    }
}
