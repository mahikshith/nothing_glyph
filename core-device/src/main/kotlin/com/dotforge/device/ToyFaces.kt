package com.dotforge.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Clock faces for the clock toy and the always-on toy. */
enum class ClockStyle(val label: String, val hint: String) {
    DIGITAL("Digital", "Big hours over minutes"),
    ANALOG("Analog", "Hands and hour marks"),
    DOT_FONT("Dot font", "Small 5x5 letters, one line"),
    BINARY("Binary", "Hours and minutes as dot columns"),
}

/** What the toys draw, as pure functions of their state (unit tested). */
object ToyFaces {
    private fun c(p: DeviceProfile) = (p.size - 1) / 2.0

    /** A line of dots from the centre at [angle] (radians clockwise from 12) out to [length]. */
    private fun hand(f: Frame, angle: Double, length: Double, value: Int, from: Double = 0.0) {
        val c = c(f.profile)
        var r = from
        while (r <= length) {
            f[(c + r * sin(angle)).roundToInt(), (c - r * cos(angle)).roundToInt()] = value
            r += 0.5
        }
    }

    fun clock(p: DeviceProfile, style: ClockStyle, hour24: Int, minute: Int, use24h: Boolean = true): Frame {
        val shown = if (use24h) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        return when (style) {
            ClockStyle.DIGITAL -> Effects.clock(p, shown, minute)
            ClockStyle.ANALOG -> analog(p, hour24, minute)
            ClockStyle.DOT_FONT -> Frame.of(p, DotText.centered(p.size, "%02d:%02d".format(shown, minute)))
            ClockStyle.BINARY -> binary(p, shown, minute)
        }
    }

    fun analog(p: DeviceProfile, hour: Int, minute: Int): Frame {
        val f = Frame.blank(p)
        val r = p.size / 2.0 - 1
        for (h in 0 until 12) hand(f, 2 * PI * h / 12, r, if (h % 3 == 0) 200 else 90, from = r - if (h % 3 == 0) 1.5 else 0.5)
        hand(f, 2 * PI * ((hour % 12) + minute / 60.0) / 12, r * 0.5, 255)
        hand(f, 2 * PI * minute / 60, r * 0.85, 180)
        f[c(p).toInt(), c(p).toInt()] = 255
        return f
    }

    /** Four columns (tens and ones of hours and minutes), bits from the bottom, 2x2 dots each. */
    fun binary(p: DeviceProfile, hour: Int, minute: Int): Frame {
        val f = Frame.blank(p)
        val digits = listOf(hour / 10, hour % 10, minute / 10, minute % 10)
        val x0 = (p.size - (4 * 3 + 3)) / 2 // 4 columns of 3 dots with 1-dot gaps
        val y0 = (p.size + 4 * 3) / 2
        digits.forEachIndexed { col, d ->
            for (bit in 0 until 4) {
                val v = if (d shr bit and 1 == 1) 255 else 30
                for (dx in 0..1) for (dy in 0..1) f[x0 + col * 4 + dx, y0 - bit * 3 - dy] = v
            }
        }
        return f
    }

    /** The moon's age as a fraction of the synodic month, 0 = new, 0.5 = full ([epochDay]: days since 1970-01-01). */
    fun moonAge(epochDay: Double): Double {
        val sinceNew = epochDay - 10_962.76 // the new moon of 2000-01-06 18:14 UTC
        val month = 29.530588853
        return ((sinceNew / month) % 1 + 1) % 1
    }

    /** The lit part of the moon for [age] (0..1): waxing lights the right side first. */
    fun moon(p: DeviceProfile, age: Double): Frame {
        val f = Frame.blank(p)
        val c = c(p)
        val r = p.size / 2.0 - 2
        val k = cos(2 * PI * age) // 1 new, -1 full
        for (cell in p.ledCells) {
            val x = cell % p.size - c
            val y = cell / p.size - c
            if (hypot(x, y) > r) continue
            val edge = r * k * kotlin.math.sqrt((1 - (y / r) * (y / r)).coerceAtLeast(0.0))
            val lit = if (age < 0.5) x > edge else x < -edge
            f.pixels[cell] = if (lit) 230 else 18
        }
        return f
    }

    /** A needle pointing north for a phone heading [azimuth] degrees, with a dot ring. */
    fun compass(p: DeviceProfile, azimuth: Double): Frame {
        val f = Frame.blank(p)
        for ((i, cell) in p.ring.withIndex()) if (i % 4 == 0) f.pixels[cell] = 40
        val north = -azimuth * PI / 180
        hand(f, north, p.size / 2.0 - 2, 255, from = 1.0)
        hand(f, north + PI, p.size / 2.0 - 6, 70, from = 1.0)
        return f
    }

    /** A spirit-level bubble: [pitch] and [roll] in degrees move it from the centre (±45° reaches the edge). */
    fun level(p: DeviceProfile, pitch: Double, roll: Double): Frame {
        val f = Frame.blank(p)
        val c = c(p)
        val reach = p.size / 2.0 - 4
        val bx = c + (roll / 45).coerceIn(-1.0, 1.0) * reach
        val by = c + (pitch / 45).coerceIn(-1.0, 1.0) * reach
        val flat = abs(pitch) < 2 && abs(roll) < 2
        for (cell in p.ledCells) {
            val x = cell % p.size
            val y = cell / p.size
            if (abs(hypot(x - c, y - c) - 3.5) < 0.6) f.pixels[cell] = if (flat) 255 else 60
            if (hypot(x - bx, y - by) <= 2.4) f.pixels[cell] = 255
        }
        return f
    }

    /** A countdown or stopwatch: the outer ring for [fraction] and minutes (or seconds under a minute) inside. */
    fun timer(p: DeviceProfile, fraction: Float, seconds: Long): Frame {
        val f = Effects.ring(p, fraction)
        val text = if (seconds >= 60) "${seconds / 60}" else "$seconds"
        val digits = Effects.centredDigits(p, listOf(text.takeLast(3)), scale = if (text.length > 2) 1 else 2)
        return f.overlay(digits)
    }

    /** Play (a triangle) or pause (two bars) in the middle, the track position on the ring. */
    fun nowPlaying(p: DeviceProfile, playing: Boolean, position: Float?): Frame {
        val f = position?.let { Effects.ring(p, it, off = 25) } ?: Frame.blank(p)
        val c = c(p).toInt()
        if (playing) {
            for (x in 0..6) for (y in -(3 - x / 2)..(3 - x / 2)) f[c - 3 + x, c + y] = 255
        } else {
            for (y in -3..3) for (dx in 0..1) { f[c - 2 + dx, c + y] = 255; f[c + 1 + dx, c + y] = 255 }
        }
        return f
    }

    /** One breath for box breathing: in, hold, out, hold, [phaseSeconds] each; [t] seconds into the cycle. */
    fun breath(p: DeviceProfile, t: Double, phaseSeconds: Int = 4): Frame {
        val phase = floor(t / phaseSeconds).toInt() % 4
        val within = (t % phaseSeconds) / phaseSeconds
        val size = when (phase) {
            0 -> within
            1 -> 1.0
            2 -> 1 - within
            else -> 0.0
        }
        val r = 2 + size * (p.size / 2.0 - 3)
        val c = c(p)
        return Frame.of(p, IntArray(p.cellCount) { cell ->
            val d = hypot(cell % p.size - c, cell / p.size - c)
            if (d <= r) (90 + 165 * size).roundToInt() else 0
        })
    }
}

/** Snake on the matrix: the Glyph Button turns it. Pure state, one [step] per tick. */
class SnakeGame(private val p: DeviceProfile, seed: Int = 0) {
    enum class Dir(val dx: Int, val dy: Int) { UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0) }

    private val random = kotlin.random.Random(seed)
    val body = ArrayDeque<Int>()
    var dir = Dir.RIGHT
        private set
    var food = -1
        private set
    var over = false
        private set
    val score: Int get() = body.size - START

    init {
        val c = (p.size - 1) / 2
        for (i in 0 until START) body.addFirst(p.index(c - START + 1 + i, c))
        placeFood()
    }

    fun turnRight() { dir = Dir.entries[(dir.ordinal + 1) % 4] }
    fun turnLeft() { dir = Dir.entries[(dir.ordinal + 3) % 4] }

    /** Moves one dot; eats, grows, or ends the game on the edge or its own tail. */
    fun step() {
        if (over) return
        val head = body.first()
        val nx = head % p.size + dir.dx
        val ny = head / p.size + dir.dy
        val next = p.index(nx.coerceIn(0, p.size - 1), ny.coerceIn(0, p.size - 1))
        if (!p.isActive(nx, ny) || (next in body && next != body.last())) {
            over = true
            return
        }
        body.addFirst(next)
        if (next == food) placeFood() else body.removeLast()
    }

    /** Puts food on a free dot (for tests). */
    fun placeFood(at: Int? = null) {
        food = at ?: p.ledCells.filter { it !in body }.let { free -> free[random.nextInt(free.size)] }
    }

    fun frame(): Frame {
        val f = Frame.blank(p)
        body.forEachIndexed { i, cell -> f.pixels[cell] = if (i == 0) 255 else 150 }
        if (food >= 0) f.pixels[food] = 255
        return f
    }

    companion object {
        const val START = 3
    }
}
