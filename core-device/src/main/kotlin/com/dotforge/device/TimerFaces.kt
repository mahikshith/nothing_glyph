package com.dotforge.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** How a timer looks on the Glyph. */
enum class TimerStyle(val label: String, val hint: String) {
    RING_DRAIN("Ring drain", "The ring empties around the time left"),
    HOURGLASS("Hourglass", "Sand streams down and piles up"),
    BIG_DIGITS("Big digits", "Minutes over seconds"),
    PIE("Pie", "A slice shrinks behind a bright edge"),
    DOTS("Dots", "25 dots pop out one by one"),
    ORBIT("Orbit", "A comet laps once a minute over the draining ring"),
    ;

    companion object {
        fun of(name: String?): TimerStyle = entries.firstOrNull { it.name == name } ?: RING_DRAIN
    }
}

/** How the stopwatch looks on the Glyph: it counts up, so it has its own looks (and always shows m:ss). */
enum class StopwatchStyle(val label: String, val hint: String) {
    BIG_DIGITS("Big digits", "Minutes over seconds, both always shown"),
    RING_FILLS("Ring fills", "The ring fills once a minute around m:ss"),
    PIE("Pie", "A slice sweeps round once a minute behind m:ss"),
    COMET("Comet", "A comet laps once a minute around m:ss"),
    ;

    companion object {
        fun of(name: String?): StopwatchStyle = entries.firstOrNull { it.name == name } ?: BIG_DIGITS
    }
}

/**
 * What a timer and the stopwatch draw, as pure functions of the time (unit tested).
 *
 * Digits never leave a doubt: from a minute on the middle shows m:ss in the small font ("4:12"); in the last
 * minute it shows big seconds and the ring becomes a seconds ring that ticks once a second; the stopwatch always
 * shows m:ss (h:mm from an hour on). A running countdown is drawn one second at a time ([second]); the last 10 s
 * tick off ten ring segments with each number scaling in ([lastSeconds]); a timer that has ended plays a burst
 * of rays, ripples and fireworks ([finished]); paused, a slow "II" blinks over the frozen face ([paused]).
 */
object TimerFaces {
    /** The last seconds count down big instead of the style. */
    const val PULSE_FROM_MS = 10_000L
    /** Frames in one second of a running timer (the comet, the sand stream and the sparkles move within it). */
    const val STEPS = 4
    const val GRID = 5
    private val PLUS = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
    /** The dim track an empty ring keeps. */
    private const val TRACK = 20

    private fun c(p: DeviceProfile) = (p.size - 1) / 2.0

    /** "4:05", "1:02:03"; under a minute "0:09". Whole seconds, rounded up while counting down. */
    fun clock(ms: Long, roundUp: Boolean = true): String {
        val s = if (roundUp) ceil(ms.coerceAtLeast(0) / 1000.0).toLong() else ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** A lap or split: "0:42.1", "12:03.5". */
    fun lap(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return clock(t, roundUp = false) + "." + (t % 1000) / 100
    }

    /** The status-bar chip: whole minutes left (rounded up) from a minute on, seconds under a minute. */
    fun middle(leftMs: Long): String {
        val s = ceil(leftMs.coerceAtLeast(0) / 1000.0).toLong()
        return if (s > 60) "${ceil(s / 60.0).toLong()}" else "$s"
    }

    /** What the middle of the Glyph reads: "4:12" (h:mm from an hour on), or the bare seconds under a minute. */
    fun readout(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 3600 -> "%d:%02d".format(s / 3600, (s % 3600) / 60)
            s >= 60 -> "%d:%02d".format(s / 60, s % 60)
            else -> "$s"
        }
    }

    /** The stopwatch's reading: always m:ss ("0:05", "12:30"), h:mm from an hour on. */
    fun watchReadout(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d".format(s / 3600, (s % 3600) / 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- text

    /** Width of [text] in the small font: 3-dot digits, a 1-dot colon, a dot between characters. */
    fun smallWidth(text: String): Int = text.sumOf { if (it == ':') 1 else BigDigits.GLYPH_WIDTH } + (text.length - 1).coerceAtLeast(0)

    /** [text] ("4:12") in the small 3x5 font, centred; [value] its brightness. */
    fun small(p: DeviceProfile, text: String, value: Int = 255, dy: Int = 0): Frame {
        val f = Frame.blank(p)
        var x = (p.size - smallWidth(text)) / 2
        val y = (p.size - BigDigits.GLYPH_HEIGHT) / 2 + dy
        for (ch in text) {
            if (ch == ':') {
                f[x, y + 1] = value
                f[x, y + 3] = value
                x += 2
            } else {
                BigDigits.draw(f, ch.toString(), x, y, 1, value)
                x += BigDigits.GLYPH_WIDTH + 1
            }
        }
        return f
    }

    /** A number in the middle, at 2x when it fits inside the ring, else 1x. */
    fun digits(p: DeviceProfile, text: String): Frame {
        val t = text.takeLast(3)
        val scale = if (BigDigits.width(t, 2) <= p.size - 10) 2 else 1
        return Effects.centredDigits(p, listOf(t), scale = scale)
    }

    /** The middle for [seconds] left: m:ss small from a minute on, big seconds under it. */
    private fun middleFrame(p: DeviceProfile, seconds: Long): Frame =
        if (seconds >= 60) small(p, readout(seconds)) else digits(p, "$seconds")

    // ---------------------------------------------------------------- one frame

    /**
     * A countdown with [leftMs] of [totalMs] left, [sub] 0..1 through the current second (the comet, the sand
     * stream, the seconds tick and the dot sparkles move within a second).
     */
    fun countdown(p: DeviceProfile, style: TimerStyle, leftMs: Long, totalMs: Long, sub: Float = 0f): Frame {
        val total = totalMs.coerceAtLeast(1)
        val left = leftMs.coerceIn(0, total)
        val fraction = left.toFloat() / total
        val s = ceil(left / 1000.0).toLong()
        return when (style) {
            TimerStyle.RING_DRAIN -> {
                val ring = if (s >= 60) drain(p, fraction) else secondsRing(p, s, sub)
                ring.overlay(middleFrame(p, s))
            }
            TimerStyle.ORBIT -> {
                // the comet laps once a minute, clockwise as the seconds pass, over the whole timer's draining ring
                val inMinute = ((60_000 - left % 60_000) % 60_000) / 60_000f + sub / 60f
                val base = if (s >= 60) Effects.ring(p, fraction, on = 70, off = TRACK) else Effects.ring(p, s / 60f, on = 70, off = TRACK)
                comet(base, inMinute).overlay(middleFrame(p, s))
            }
            TimerStyle.HOURGLASS -> hourglass(p, fraction, sub)
            TimerStyle.BIG_DIGITS -> bigDigits(p, s)
            TimerStyle.PIE -> pie(p, fraction)
            TimerStyle.DOTS -> {
                val gone = dotsLit(fraction) < dotsLit(((left + 1000).coerceAtMost(total)).toFloat() / total)
                dots(p, fraction, blink = sub < 0.5f, sparkle = if (gone) sub else null)
            }
        }
    }

    /** The stopwatch at [elapsedMs]: the ring, slice or comet follows the current minute; m:ss in the middle. */
    fun stopwatch(p: DeviceProfile, style: StopwatchStyle, elapsedMs: Long, sub: Float = 0f): Frame {
        val e = elapsedMs.coerceAtLeast(0)
        val minute = (e % 60_000) / 60_000f
        val text = watchReadout(e / 1000)
        return when (style) {
            StopwatchStyle.BIG_DIGITS -> {
                val s = e / 1000
                if (s >= 3600) Effects.centredDigits(p, listOf("${s / 3600}", "%02d".format((s % 3600) / 60)))
                else Effects.centredDigits(p, listOf("%02d".format(s / 60), "%02d".format(s % 60)))
            }
            StopwatchStyle.RING_FILLS -> headed(p, minute, 255, 150).overlay(small(p, text))
            StopwatchStyle.PIE -> {
                val f = pie(p, minute)
                val t = small(p, text)
                // the digits sit on a dark plate (one dot round them), so the slice never runs into them
                val lit = p.ledCells.filter { t.pixels[it] > 0 }
                val n = p.size
                val x0 = lit.minOf { it % n } - 1
                val x1 = lit.maxOf { it % n } + 1
                val y0 = lit.minOf { it / n } - 1
                val y1 = lit.maxOf { it / n } + 1
                for (cell in p.ledCells) if (cell % n in x0..x1 && cell / n in y0..y1) f.pixels[cell] = 0
                for (cell in lit) f.pixels[cell] = 255
                f
            }
            StopwatchStyle.COMET -> comet(Effects.ring(p, 0f, off = TRACK), minute + sub / 60f).overlay(small(p, text))
        }
    }

    /** Big digits: minutes over seconds (hours over minutes from an hour on), or the seconds alone in the last minute. */
    fun bigDigits(p: DeviceProfile, seconds: Long): Frame {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 3600 -> Effects.centredDigits(p, listOf("${s / 3600}", "%02d".format((s % 3600) / 60)))
            s >= 60 -> Effects.centredDigits(p, listOf("%02d".format(s / 60), "%02d".format(s % 60)))
            else -> Effects.centredDigits(p, listOf("$s"))
        }
    }

    /**
     * The ring lit for [fraction], clockwise from 12 o'clock, its edge a bright head with a fading tail behind it
     * ([head], then down to [body]) over a dim track.
     */
    fun headed(p: DeviceProfile, fraction: Float, head: Int = 255, body: Int = 150): Frame {
        val f = Frame.blank(p)
        val ring = p.ring
        val n = (ring.size * fraction.coerceIn(0f, 1f)).roundToInt()
        val tail = listOf(head, 225, 195, 170)
        ring.forEachIndexed { i, cell ->
            f.pixels[cell] = when {
                i >= n -> TRACK
                n - 1 - i < tail.size -> maxOf(tail[n - 1 - i], body)
                else -> body
            }
        }
        return f
    }

    /** The ring lit for what is left: it drains back to 12 o'clock, a bright head at the draining edge. */
    fun drain(p: DeviceProfile, fraction: Float): Frame = headed(p, fraction)

    /**
     * The last minute's seconds ring: lit for [seconds] of 60. Each second starts with a tick: the dot that just
     * went out flashes and fades over the first half of the second.
     */
    fun secondsRing(p: DeviceProfile, seconds: Long, sub: Float): Frame {
        val f = headed(p, seconds / 60f)
        val ring = p.ring
        val n = (ring.size * seconds / 60f).roundToInt()
        val gone = (ring.size * (seconds + 1) / 60f).roundToInt()
        if (sub < 0.5f) {
            val v = if (sub < 0.25f) 255 else 120
            for (i in n until gone.coerceAtMost(ring.size)) f.pixels[ring[i]] = v
        }
        return f
    }

    /** A comet on [base]'s ring: a full-brightness head at [position] (0..1 clockwise from 12) and a 5-dot fading tail. */
    fun comet(base: Frame, position: Float): Frame {
        val f = base.copy()
        val ring = f.profile.ring
        val n = ring.size
        val head = Math.floorMod((((position % 1f) + 1f) % 1f * n).roundToInt(), n)
        val levels = listOf(255, 255, 200, 145, 95, 55)
        for ((t, v) in levels.withIndex()) {
            val cell = ring[Math.floorMod(head - t, n)]
            f.pixels[cell] = maxOf(f.pixels[cell], v)
        }
        return f
    }

    /** One bright comet on a dim track, [position] 0..1 clockwise from 12. */
    fun orbit(p: DeviceProfile, position: Float): Frame = comet(Effects.ring(p, 0f, off = TRACK), position)

    /** A slice from 12 o'clock, clockwise, as large as [fraction]: a bright leading edge, a softer inside, a faint outline. */
    fun pie(p: DeviceProfile, fraction: Float): Frame {
        val f = Frame.blank(p)
        val c = c(p)
        val k = fraction.coerceIn(0f, 1f)
        val turn = k * 2 * PI
        for (cell in p.ledCells) {
            val dx = cell % p.size - c
            val dy = cell / p.size - c
            var a = atan2(dx, -dy)
            if (a < 0) a += 2 * PI
            if (a >= turn - 1e-9) continue
            val r = hypot(dx, dy)
            // the edge: within about a dot of the slice's moving side
            val edge = k < 0.999f && (turn - a) * r < 1.2
            f.pixels[cell] = when {
                edge -> 255
                r > c - 1.5 -> 210
                else -> 120
            }
        }
        for (cell in p.ring) if (f.pixels[cell] == 0) f.pixels[cell] = 28
        return f
    }

    private fun dotsLit(fraction: Float): Int = ceil(fraction.coerceIn(0f, 1f) * GRID * GRID - 1e-4).toInt()

    /**
     * [GRID]x[GRID] round dots, each 4% of the time. Counting down they go out from the last; the next to go
     * blinks ([blink]); the one that has just gone pops with a little sparkle ([sparkle], 0..1 through it).
     */
    fun dots(p: DeviceProfile, fraction: Float, blink: Boolean, sparkle: Float? = null): Frame {
        val f = Frame.blank(p)
        val n = GRID * GRID
        val lit = dotsLit(fraction)
        val pitch = 4
        val first = (p.size - 1) / 2 - pitch * (GRID - 1) / 2
        fun centre(i: Int) = (first + (i % GRID) * pitch) to (first + (i / GRID) * pitch)
        for (i in 0 until n) {
            val (cx, cy) = centre(i)
            val level = when {
                i == lit - 1 && blink -> 90
                i < lit -> 255
                else -> 26
            }
            for ((dx, dy) in PLUS) f[cx + dx, cy + dy] = level
        }
        if (sparkle != null && lit < n) {
            val (cx, cy) = centre(lit)
            val step = (sparkle.coerceIn(0f, 0.999f) * 4).toInt()
            if (step == 0) for ((dx, dy) in PLUS) f[cx + dx, cy + dy] = 255
            if (step < 3) {
                val d = step + 1
                val v = listOf(255, 190, 110)[step]
                for ((sx, sy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val x = cx + sx * d
                    val y = cy + sy * d
                    if (p.isActive(x, y) && f.pixels[p.index(x, y)] < 100) f[x, y] = v
                }
            }
        }
        return f
    }

    /**
     * An hourglass: [fraction] of the sand still in the top bulb (resting on the neck), the rest piled up in the
     * bottom as a mound; while sand is left, a stream of grains falls from the neck to the pile, moving with [sub].
     */
    fun hourglass(p: DeviceProfile, fraction: Float, sub: Float): Frame {
        val f = Frame.blank(p)
        val c = c(p).toInt()
        val half = 9 // rows from the neck to a bulb's end
        val top = c - half
        val bottom = c + half
        fun width(y: Int) = (abs(y - c) * 7 / half).coerceAtLeast(1)
        val inside = ArrayList<Pair<Int, Int>>()
        for (y in top..bottom) {
            val w = width(y)
            f[c - w - 1, y] = 70
            f[c + w + 1, y] = 70
            if (y == top || y == bottom) for (x in c - w - 1..c + w + 1) f[x, y] = 70
            else if (y != c) for (x in c - w..c + w) inside += x to y
        }
        val upper = inside.filter { it.second < c }.sortedByDescending { it.second } // nearest the neck first
        // the pile: the floor first, and on each row the middle first, so it grows as a mound
        val lower = inside.filter { it.second > c }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second * 3 - abs(it.first - c) })
        val k = fraction.coerceIn(0f, 1f)
        val inTop = (upper.size * k).roundToInt()
        val inBottom = (lower.size * (1 - k)).roundToInt()
        upper.take(inTop).forEach { (x, y) -> f[x, y] = 220 }
        val pile = lower.take(inBottom)
        pile.forEach { (x, y) -> f[x, y] = 220 }
        if (k > 0f && k < 1f) {
            // the stream: from the neck down to the top of the pile, grains every other dot, falling
            val pileTop = pile.filter { it.first == c }.minOfOrNull { it.second } ?: bottom
            val phase = (sub.coerceIn(0f, 0.999f) * 4).toInt()
            f[c, c] = 255
            for (y in c + 1 until pileTop) f[c, y] = if ((y - c - phase) % 2 == 0) 255 else 90
        }
        return f
    }

    // ---------------------------------------------------------------- animations

    /**
     * One second of a countdown starting at [leftMs]: [STEPS] frames; with [lastTen] the last 10 s count down
     * big ([lastSeconds]) instead.
     */
    fun second(p: DeviceProfile, style: TimerStyle, leftMs: Long, totalMs: Long, brightness: Float = 1f, lastTen: Boolean = true): Animation {
        if (lastTen && leftMs in 1..PULSE_FROM_MS) return lastSeconds(p, leftMs, brightness)
        return Animation(p, List(STEPS) { i ->
            AnimFrame(countdown(p, style, leftMs, totalMs, i / STEPS.toFloat()).dim(brightness), 1000 / STEPS)
        })
    }

    /** One second of the stopwatch from [elapsedMs]. */
    fun stopwatchSecond(p: DeviceProfile, style: StopwatchStyle, elapsedMs: Long, brightness: Float = 1f): Animation =
        Animation(p, List(STEPS) { i -> AnimFrame(stopwatch(p, style, elapsedMs + i * 1000L / STEPS, i / STEPS.toFloat()).dim(brightness), 1000 / STEPS) })

    /** The ring cells of segment [i] of [segments], clockwise from 12. */
    private fun segment(p: DeviceProfile, i: Int, segments: Int): List<Int> {
        val n = p.ring.size
        return (i * n / segments until (i + 1) * n / segments).map { p.ring[it] }
    }

    /**
     * The last 10 s: the ring is ten segments, one ticking off each second with a flash; the number scales in
     * (small, then full size) and settles.
     */
    fun lastSeconds(p: DeviceProfile, leftMs: Long, brightness: Float = 1f): Animation {
        val s = ceil(leftMs / 1000.0).toLong().coerceIn(0, 10).toInt()
        val full = if (s >= 10) 2 else 3
        val numberSmall = Effects.centredDigits(p, listOf("$s"), scale = full - 1)
        val numberFull = Effects.centredDigits(p, listOf("$s"), scale = full)
        val frames = ArrayList<AnimFrame>()
        for (step in 0 until STEPS) {
            val f = Frame.blank(p)
            for (cell in p.ring) f.pixels[cell] = TRACK
            for (i in 0 until s) {
                // a short gap between segments so they read as ten
                val cells = segment(p, i, 10)
                cells.dropLast(1).forEach { f.pixels[it] = if (step == 0) 255 else 200 }
            }
            // the segment that has just gone: a flash that fades
            if (s < 10) segment(p, s, 10).dropLast(1).forEach { f.pixels[it] = listOf(255, 140, 60, TRACK)[step] }
            val number = if (step == 0) numberSmall else numberFull
            f.overlay(number, listOf(255, 255, 235, 215)[step])
            frames += AnimFrame(f.dim(brightness), 250)
        }
        return Animation(p, frames)
    }

    /**
     * A timer that has ended: a burst of rays from the middle, two ripples, then fireworks going off around the
     * Glyph, and a short rest. It loops until dismissed (or for as long as the finish is set to run).
     */
    fun finished(p: DeviceProfile, brightness: Float = 1f): Animation {
        val c = c(p)
        val frames = ArrayList<AnimFrame>()
        // 1: rays burst out from the middle
        val rays = 12
        for (i in 0 until 6) {
            val f = Frame.blank(p)
            val reach = 2.5 + i * 2.2
            for (cell in p.ledCells) {
                val dx = cell % p.size - c
                val dy = cell / p.size - c
                val d = hypot(dx, dy)
                if (d > reach) continue
                val a = atan2(dy, dx)
                val k = (a / (2 * PI) * rays) % 1.0
                val off = minOf(abs(k), abs(1 - abs(k))) * 2 * PI / rays * d
                if (off < 0.6 || d < 1.5) f.pixels[cell] = if (d > reach - 2.5) 255 else 110
            }
            frames += AnimFrame(f.dim(brightness), 50)
        }
        // 2: two ripples go out, the ring flashing as the first reaches it
        for (i in 0 until 9) {
            val f = Frame.blank(p)
            for (wave in 0 until 2) {
                val r = (i - wave * 3) * 1.6
                if (r < 0) continue
                for (cell in p.ledCells) {
                    val d = hypot(cell % p.size - c, cell / p.size - c)
                    val v = (255 * (1 - abs(d - r) / 1.1)).roundToInt()
                    if (v > 70 && v > f.pixels[cell]) f.pixels[cell] = v
                }
            }
            if (i == 7) for (cell in p.ring) f.pixels[cell] = 255
            frames += AnimFrame(f.dim(brightness), 60)
        }
        // 3: fireworks: five shells burst in turn, ten sparks each flying out with a trail, then falling
        val shells = listOf(Triple(c - 5, c - 4, 0), Triple(c + 5, c - 3, 2), Triple(c - 1, c + 4, 4), Triple(c + 4, c + 5, 6), Triple(c - 6, c + 3, 8))
        val sparks = 10
        for (t in 0 until 15) {
            val f = Frame.blank(p)
            for ((sx, sy, start) in shells) {
                val age = t - start
                if (age < 0 || age > 6) continue
                if (age == 0) {
                    for ((dx, dy) in PLUS) f[sx.roundToInt() + dx, sy.roundToInt() + dy] = 255
                    continue
                }
                val r = age * 1.5
                val v = listOf(255, 255, 230, 180, 120, 70)[(age - 1).coerceAtMost(5)]
                val fall = (age - 2).coerceAtLeast(0) * 0.7
                for (k in 0 until sparks) {
                    val a = k * 2 * PI / sparks + start
                    for ((back, level) in listOf(0.0 to v, 1.3 to v / 2, 2.6 to v / 5)) {
                        if (r - back < 0.8) continue
                        val x = (sx + cos(a) * (r - back)).roundToInt()
                        val y = (sy + sin(a) * (r - back) + fall).roundToInt()
                        if (p.isActive(x, y) && f.pixels[p.index(x, y)] < level) f[x, y] = level
                    }
                }
            }
            frames += AnimFrame(f.dim(brightness), 70)
        }
        frames += AnimFrame(Frame.blank(p), 250)
        return Animation(p, frames)
    }

    /** "II": two bars in the middle. */
    fun pauseBars(p: DeviceProfile): Frame {
        val f = Frame.blank(p)
        val c = c(p).toInt()
        for (y in c - 4..c + 4) for (x in listOf(c - 3, c - 2, c + 2, c + 3)) f[x, y] = 255
        return f
    }

    /** Paused: a slow blink between a bright "II" over the face dimmed right down, and the face itself. */
    fun paused(p: DeviceProfile, face: Frame, brightness: Float = 1f): Animation {
        val behind = face.scaled(0.22f)
        val bars = pauseBars(p)
        for (cell in p.ledCells) if (bars.pixels[cell] > 0) behind.pixels[cell] = 255
        // a dark edge round the bars so they stand clear of the face
        return Animation(p, listOf(AnimFrame(behind.dim(brightness), 1000), AnimFrame(face.dim(brightness), 1000)))
    }

    /**
     * A style's demo: a 1:30 timer run through quickly (m:ss, then big seconds), its last three seconds at real
     * speed, then the finish. The page dims it and calls it a preview.
     */
    fun preview(p: DeviceProfile, style: TimerStyle): Animation {
        val total = 90_000L
        val frames = ArrayList<AnimFrame>()
        var i = 0
        for (left in listOf(90_000L, 82_000, 74_000, 66_000, 58_000, 46_000, 34_000, 22_000, 14_000)) {
            for (step in 0 until 2) frames += AnimFrame(countdown(p, style, left, total, (i++ % STEPS) / STEPS.toFloat()), 160)
        }
        for (s in 3 downTo 1) frames += lastSeconds(p, s * 1000L).frames
        frames += finished(p).frames
        return Animation(p, frames)
    }

    /** The stopwatch style's demo: 0:00 to about 1:20 quickly. */
    fun preview(p: DeviceProfile, style: StopwatchStyle): Animation =
        Animation(p, (0 until 24).map { i -> AnimFrame(stopwatch(p, style, i * 3_500L, (i % STEPS) / STEPS.toFloat()), 160) })

    private fun Frame.dim(k: Float): Frame = if (k >= 0.999f) this else scaled(k.coerceIn(0.05f, 1f))
}

/** Keeps [Frame.scaled]'s rounding; exposed for tests that compare brightness. */
internal fun ringCellsLit(p: DeviceProfile, f: Frame, above: Int = 100): Int = p.ring.count { f.pixels[it] > above }

/** The angle of a cell, clockwise from 12, 0..1. */
internal fun turnOf(p: DeviceProfile, cell: Int): Double {
    val c = (p.size - 1) / 2.0
    var a = atan2(cell % p.size - c, -(cell / p.size - c))
    if (a < 0) a += 2 * PI
    return a / (2 * PI)
}
