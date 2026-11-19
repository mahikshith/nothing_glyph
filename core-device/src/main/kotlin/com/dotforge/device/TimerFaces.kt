package com.dotforge.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How a timer or the stopwatch looks on the Glyph. */
enum class TimerStyle(val label: String, val hint: String) {
    RING_DRAIN("Ring drain", "The ring empties, minutes in the middle"),
    HOURGLASS("Hourglass", "Sand falls from the top to the bottom"),
    BIG_DIGITS("Big digits", "Minutes over seconds"),
    PIE("Pie", "A slice that shrinks"),
    DOTS("Dots", "25 dots go out one by one"),
    ORBIT("Orbit", "A dot circles once a minute"),
    ;

    companion object {
        fun of(name: String?): TimerStyle = entries.firstOrNull { it.name == name } ?: RING_DRAIN
    }
}

/**
 * What a timer and the stopwatch draw, as pure functions of the time (unit tested). A running countdown is
 * drawn one second at a time ([second]); its last 10 s pulse with the seconds big ([lastSeconds]); a timer that
 * has ended bursts until it is dismissed ([finished]).
 */
object TimerFaces {
    /** The last seconds pulse instead of the style. */
    const val PULSE_FROM_MS = 10_000L
    /** Frames in one second of a running timer (the orbit and the falling sand move within the second). */
    const val STEPS = 4
    const val GRID = 5
    private val PLUS = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)

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

    /** The number in the middle: whole minutes left (rounded up) from a minute on, seconds under a minute. */
    fun middle(leftMs: Long): String {
        val s = ceil(leftMs.coerceAtLeast(0) / 1000.0).toLong()
        return if (s > 60) "${ceil(s / 60.0).toLong()}" else "$s"
    }

    // ---------------------------------------------------------------- one frame

    /**
     * A countdown with [leftMs] of [totalMs] left, [sub] 0..1 through the current second (only the orbit and the
     * hourglass's stream move within a second).
     */
    fun countdown(p: DeviceProfile, style: TimerStyle, leftMs: Long, totalMs: Long, sub: Float = 0f): Frame {
        val total = totalMs.coerceAtLeast(1)
        val left = leftMs.coerceIn(0, total)
        val fraction = left.toFloat() / total
        return when (style) {
            TimerStyle.RING_DRAIN -> drain(p, fraction).overlay(digits(p, middle(left)))
            TimerStyle.HOURGLASS -> hourglass(p, fraction, sub)
            TimerStyle.BIG_DIGITS -> bigDigits(p, ceil(left / 1000.0).toLong())
            TimerStyle.PIE -> pie(p, fraction)
            TimerStyle.DOTS -> dots(p, fraction, blink = sub < 0.5f)
            TimerStyle.ORBIT -> orbit(p, ((left % 60_000) / 60_000f) - sub / 60f).overlay(digits(p, middle(left)))
        }
    }

    /** The stopwatch at [elapsedMs]: the styles follow the current minute; the middle shows minutes (seconds in the first). */
    fun stopwatch(p: DeviceProfile, style: TimerStyle, elapsedMs: Long, sub: Float = 0f): Frame {
        val e = elapsedMs.coerceAtLeast(0)
        val minute = (e % 60_000) / 60_000f
        val mid = if (e < 60_000) "${e / 1000}" else "${e / 60_000}"
        return when (style) {
            TimerStyle.RING_DRAIN -> Effects.ring(p, minute, off = 20).overlay(digits(p, mid))
            // the glass turns over every minute
            TimerStyle.HOURGLASS -> hourglass(p, 1f - minute, sub)
            TimerStyle.BIG_DIGITS -> bigDigits(p, e / 1000)
            TimerStyle.PIE -> pie(p, minute)
            TimerStyle.DOTS -> dots(p, minute, blink = false, filling = true)
            TimerStyle.ORBIT -> orbit(p, minute + sub / 60f).overlay(digits(p, mid))
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

    /** A number in the middle, at 2x when it fits inside the ring, else 1x. */
    fun digits(p: DeviceProfile, text: String): Frame {
        val t = text.takeLast(3)
        val scale = if (BigDigits.width(t, 2) <= p.size - 10) 2 else 1
        return Effects.centredDigits(p, listOf(t), scale = scale)
    }

    /** The ring lit for what is left: it drains anticlockwise back to 12 o'clock, over a dim track. */
    fun drain(p: DeviceProfile, fraction: Float): Frame = Effects.ring(p, fraction, on = 255, off = 20)

    /** A slice from 12 o'clock, clockwise, as large as [fraction], over a faint outline. */
    fun pie(p: DeviceProfile, fraction: Float): Frame {
        val f = Frame.blank(p)
        val c = c(p)
        val turn = fraction.coerceIn(0f, 1f) * 2 * PI
        for (cell in p.ledCells) {
            val dx = cell % p.size - c
            val dy = cell / p.size - c
            var a = atan2(dx, -dy)
            if (a < 0) a += 2 * PI
            if (a < turn - 1e-9) f.pixels[cell] = if (hypot(dx, dy) > c - 1.5) 255 else 170
        }
        for (cell in p.ring) if (f.pixels[cell] == 0) f.pixels[cell] = 28
        return f
    }

    /**
     * [GRID]x[GRID] round dots, each 4% of the time. Counting down they go out from the last; the next to go
     * blinks ([blink]). The stopwatch fills them instead ([filling]).
     */
    fun dots(p: DeviceProfile, fraction: Float, blink: Boolean, filling: Boolean = false): Frame {
        val f = Frame.blank(p)
        val n = GRID * GRID
        val lit = if (filling) (fraction.coerceIn(0f, 1f) * n).toInt() else ceil(fraction.coerceIn(0f, 1f) * n - 1e-4).toInt()
        // round dots (a plus of 5 cells) every 4 cells, centred: the corner dots stay inside the circle
        val pitch = 4
        val first = (p.size - 1) / 2 - pitch * (GRID - 1) / 2
        for (i in 0 until n) {
            val cx = first + (i % GRID) * pitch
            val cy = first + (i / GRID) * pitch
            val on = i < lit
            val next = !filling && i == lit - 1 && blink
            val level = when {
                next -> 90
                on -> 255
                else -> 26
            }
            for ((dx, dy) in PLUS) f[cx + dx, cy + dy] = level
        }
        return f
    }

    /** One bright dot on the ring with a short tail, [position] 0..1 clockwise from 12, over a dim track. */
    fun orbit(p: DeviceProfile, position: Float): Frame {
        val f = Frame.blank(p)
        val ring = p.ring
        val n = ring.size
        for (cell in ring) f.pixels[cell] = 22
        val head = Math.floorMod((((position % 1f) + 1f) % 1f * n).roundToInt(), n)
        // a bright head two cells long and a fading tail
        for (t in 0 until 9) f.pixels[ring[Math.floorMod(head - t, n)]] = if (t < 2) 255 else 230 - (t - 2) * 28
        return f
    }

    /**
     * An hourglass: [fraction] of the sand still in the top bulb (resting on the neck), the rest piled in the
     * bottom; a grain falls through the neck while [fraction] is between 0 and 1.
     */
    fun hourglass(p: DeviceProfile, fraction: Float, sub: Float): Frame {
        val f = Frame.blank(p)
        val c = c(p).toInt()
        val half = 9 // rows from the neck to a bulb's end
        val top = c - half
        val bottom = c + half
        // the glass: two triangles meeting at the neck
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
        val lower = inside.filter { it.second > c }.sortedByDescending { it.second } // the floor first
        val k = fraction.coerceIn(0f, 1f)
        val inTop = (upper.size * k).roundToInt()
        val inBottom = (lower.size * (1 - k)).roundToInt()
        // the top's sand rests on the neck: fill its lowest cells first
        upper.take(inTop).forEach { (x, y) -> f[x, y] = 220 }
        lower.take(inBottom).forEach { (x, y) -> f[x, y] = 220 }
        if (k > 0f && k < 1f) {
            f[c, c] = 255
            val y = c + 1 + (sub.coerceIn(0f, 0.999f) * 4).toInt()
            f[c, y] = 255
        }
        return f
    }

    // ---------------------------------------------------------------- animations

    /** One second of a countdown starting at [leftMs]: [STEPS] frames, the last 10 s pulsing instead. */
    fun second(p: DeviceProfile, style: TimerStyle, leftMs: Long, totalMs: Long, brightness: Float = 1f): Animation {
        if (leftMs in 1..PULSE_FROM_MS) return lastSeconds(p, leftMs, brightness)
        return Animation(p, List(STEPS) { i ->
            AnimFrame(countdown(p, style, leftMs, totalMs, i / STEPS.toFloat()).dim(brightness), 1000 / STEPS)
        })
    }

    /** One second of the stopwatch from [elapsedMs]. */
    fun stopwatchSecond(p: DeviceProfile, style: TimerStyle, elapsedMs: Long, brightness: Float = 1f): Animation =
        Animation(p, List(STEPS) { i -> AnimFrame(stopwatch(p, style, elapsedMs + i * 1000L / STEPS, i / STEPS.toFloat()).dim(brightness), 1000 / STEPS) })

    /** The last 10 s: the seconds big in the middle, the ring flashing full and fading once a second. */
    fun lastSeconds(p: DeviceProfile, leftMs: Long, brightness: Float = 1f): Animation {
        val s = ceil(leftMs / 1000.0).toLong().coerceIn(0, 99)
        val number = Effects.centredDigits(p, listOf("$s"), scale = if (s >= 10) 2 else 3)
        val levels = listOf(1f, 0.7f, 0.45f, 0.25f)
        return Animation(p, levels.map { k ->
            val f = Frame.blank(p)
            for (cell in p.ring) f.pixels[cell] = (255 * k).roundToInt()
            AnimFrame(f.overlay(number, (255 * (0.55f + 0.45f * k)).roundToInt()).dim(brightness), 250)
        })
    }

    /** A timer that has ended: rings burst out from the middle, then a flash; [finished] loops until dismissed. */
    fun finished(p: DeviceProfile, brightness: Float = 1f): Animation {
        val c = c(p)
        val frames = ArrayList<AnimFrame>()
        val steps = 10
        for (i in 0 until steps) {
            val f = Frame.blank(p)
            for (wave in 0 until 2) {
                val r = (i - wave * 4) * 1.5
                if (r < 0) continue
                for (cell in p.ledCells) {
                    val d = hypot(cell % p.size - c, cell / p.size - c)
                    val v = (255 * (1 - abs(d - r) / 1.3)).roundToInt()
                    if (v > f.pixels[cell]) f.pixels[cell] = v
                }
            }
            frames += AnimFrame(f.dim(brightness), 60)
        }
        val full = Frame.blank(p).fill(255).dim(brightness)
        frames += AnimFrame(full, 120)
        frames += AnimFrame(Frame.blank(p), 120)
        frames += AnimFrame(full, 120)
        frames += AnimFrame(Frame.blank(p), 300)
        return Animation(p, frames)
    }

    /** Paused: the face held, breathing slowly between full and dim. */
    fun paused(p: DeviceProfile, face: Frame, brightness: Float = 1f): Animation =
        Animation(p, listOf(AnimFrame(face.dim(brightness), 900), AnimFrame(face.scaled(0.3f).dim(brightness), 600)))

    /**
     * A style's live preview: a 20 s timer counting down in about 4 s (then its last seconds and the burst), so
     * each option in the style picker shows how it moves.
     */
    fun preview(p: DeviceProfile, style: TimerStyle): Animation {
        val total = 60_000L
        val frames = ArrayList<AnimFrame>()
        var left = total
        while (left > PULSE_FROM_MS) {
            frames += AnimFrame(countdown(p, style, left, total, (frames.size % STEPS) / STEPS.toFloat()), 120)
            left -= 2_000
        }
        frames += lastSeconds(p, 3_000).frames.map { AnimFrame(it.frame, 120) }
        frames += lastSeconds(p, 2_000).frames.map { AnimFrame(it.frame, 120) }
        frames += lastSeconds(p, 1_000).frames.map { AnimFrame(it.frame, 120) }
        frames += finished(p).frames
        return Animation(p, frames)
    }

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
