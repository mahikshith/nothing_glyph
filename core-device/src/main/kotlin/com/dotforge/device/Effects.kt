package com.dotforge.device

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Ready-made frames and animations used by notification rules, toys, the Studio and Diagnostics. */
object Effects {
    private const val BIG_SCALE = 2

    /** Lines of [BigDigits] centred on the matrix, one row gap between lines. */
    fun centredDigits(profile: DeviceProfile, lines: List<String>, scale: Int = BIG_SCALE): Frame {
        val f = Frame.blank(profile)
        val lineH = BigDigits.GLYPH_HEIGHT * scale
        val gap = (scale / 2).coerceAtLeast(1)
        val totalH = lines.size * lineH + (lines.size - 1) * gap
        var y = (profile.size - totalH) / 2
        for (line in lines) {
            BigDigits.draw(f, line, (profile.size - BigDigits.width(line, scale)) / 2, y, scale)
            y += lineH + gap
        }
        return f
    }

    /** Two-line clock "HH" over "MM" (also used for "dd" over "MM"). */
    fun clock(profile: DeviceProfile, top: Int, bottom: Int): Frame =
        centredDigits(profile, listOf(top.toString().padStart(2, '0'), bottom.toString().padStart(2, '0')))

    /** Progress on the outer ring, [fraction] 0..1, clockwise from 12 o'clock over a dim track. */
    fun ring(profile: DeviceProfile, fraction: Float, on: Int = 255, off: Int = 20): Frame {
        val f = Frame.blank(profile)
        val n = (profile.ring.size * fraction.coerceIn(0f, 1f)).roundToInt()
        profile.ring.forEachIndexed { i, cell -> f.pixels[cell] = if (i < n) on else off }
        return f
    }

    /** A comet of [tail] cells on the outer ring with its head at ring position [head] (wraps). */
    fun comet(profile: DeviceProfile, head: Int, tail: Int = 8): Frame {
        val f = Frame.blank(profile)
        val ring = profile.ring
        val n = ring.size
        val h = Math.floorMod(head, n)
        for (t in 0 until tail) f.pixels[ring[Math.floorMod(h - t, n)]] = 255 * (tail - t) / tail
        return f
    }

    /** Alternate LEDs lit; [phase] 0 or 1 swaps which. */
    fun checkerboard(profile: DeviceProfile, phase: Int = 0, level: Int = 255): Frame {
        val f = Frame.blank(profile)
        for (cell in profile.ledCells) if ((cell % profile.size + cell / profile.size + phase) % 2 == 0) f.pixels[cell] = level
        return f
    }

    private fun disc(profile: DeviceProfile, cx: Float, cy: Float, radius: Float, value: Int = 255): Frame {
        val f = Frame.blank(profile)
        for (cell in profile.ledCells) {
            val dx = cell % profile.size - cx
            val dy = cell / profile.size - cy
            if (dx * dx + dy * dy <= radius * radius) f.pixels[cell] = value
        }
        return f
    }

    /** A single character of the 5x5 font at 3x, centred (sender / app initial). */
    fun letter(profile: DeviceProfile, ch: Char): Frame {
        val text = ch.uppercaseChar().toString()
        return Frame.of(profile, DotText.centered(profile.size, text, scale = 3))
    }

    // ---- animations -------------------------------------------------------------------------

    fun pulse(profile: DeviceProfile, periodMs: Int = 1600, steps: Int = 24): Animation =
        Animation(profile, (0 until steps).map { i ->
            val v = (40 + 215 * (0.5 + 0.5 * sin(2 * PI * i / steps - PI / 2))).toInt()
            AnimFrame(Frame.blank(profile).fill(v), periodMs / steps)
        })

    /** One lap of [heads] evenly spaced comets, each with a [tail], round the outer ring. */
    fun ringChase(profile: DeviceProfile, tail: Int = 8, stepMs: Int = 30, heads: Int = 1): Animation {
        val n = profile.ring.size
        return Animation(profile, profile.ring.indices.map { step ->
            val f = Frame.blank(profile)
            repeat(heads) { h ->
                val c = comet(profile, step + h * n / heads, tail)
                for (cell in profile.ring) f.pixels[cell] = maxOf(f.pixels[cell], c.pixels[cell])
            }
            AnimFrame(f, stepMs)
        })
    }

    /** Ring chase for notifications: three short comets chasing each other. */
    fun chase(profile: DeviceProfile): Animation = ringChase(profile, tail = 4, stepMs = 30, heads = 3)

    /** A single bright comet with a long tail, two fast laps. */
    fun cometLaps(profile: DeviceProfile): Animation {
        val lap = ringChase(profile, tail = 20, stepMs = 15).frames
        return Animation(profile, lap + lap)
    }

    fun flash(profile: DeviceProfile, times: Int = 3): Animation {
        val on = Frame.blank(profile).fill(255)
        val off = Frame.blank(profile)
        val frames = (0 until times).flatMap { listOf(AnimFrame(on, 120), AnimFrame(off, 180)) }
        return Animation(profile, frames + AnimFrame(off, 600))
    }

    fun blinkLetter(profile: DeviceProfile, ch: Char): Animation {
        val on = letter(profile, ch)
        return Animation(profile, listOf(AnimFrame(on, 900), AnimFrame(on.scaled(0.25f), 400)))
    }

    /** [text] scrolling right to left through the middle of the matrix with the 5x5 font at 2x. */
    fun marquee(profile: DeviceProfile, text: String, stepMs: Int = 60): Animation {
        val t = text.uppercase()
        val scale = 2
        return Animation(profile, (0..DotText.scrollLength(profile.size, t, scale)).map { offset ->
            AnimFrame(Frame.of(profile, DotText.scrollFrame(profile.size, t, offset, scale = scale)), stepMs)
        })
    }

    /** Ring filled to [fraction] with the percentage in the middle when it fits. */
    fun progress(profile: DeviceProfile, fraction: Float): Animation {
        val base = ring(profile, fraction)
        val pct = (fraction * 100).roundToInt().coerceIn(0, 100).toString()
        if (BigDigits.width(pct, BIG_SCALE) < profile.size - 4) base.overlay(centredDigits(profile, listOf(pct)), 200)
        return Animation.single(base, 1000)
    }

    /** Die face 1..6 as round pips. */
    fun dice(profile: DeviceProfile, value: Int): Frame {
        require(value in 1..6) { "die value must be 1..6, got $value" }
        val f = Frame.blank(profile)
        val c = (profile.size - 1) / 2f
        val o = profile.size * 0.22f
        val r = profile.size * 0.088f
        val pips = when (value) {
            1 -> listOf(0f to 0f)
            2 -> listOf(-o to -o, o to o)
            3 -> listOf(-o to -o, 0f to 0f, o to o)
            4 -> listOf(-o to -o, o to -o, -o to o, o to o)
            5 -> listOf(-o to -o, o to -o, 0f to 0f, -o to o, o to o)
            else -> listOf(-o to -o, o to -o, -o to 0f, o to 0f, -o to o, o to o)
        }
        for ((dx, dy) in pips) f.overlay(disc(profile, c + dx, c + dy, r))
        return f
    }

    /** A straight line, [width] cells thick to the right; cells without an LED are skipped. */
    private fun line(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, width: Int = 2, value: Int = 255) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * i / steps
            for (w in 0 until width) f[x + w, y] = value
        }
    }

    private fun withRing(profile: DeviceProfile, f: Frame, level: Int): Frame {
        for (cell in profile.ring) f.pixels[cell] = maxOf(f.pixels[cell], level)
        return f
    }

    /** A call DotForge rejected: an X inside a dim ring, blinking twice. */
    fun callBlocked(profile: DeviceProfile): Animation {
        val n = profile.size
        val m = n / 4
        val x = Frame.blank(profile)
        line(x, m, m, n - 2 - m, n - 1 - m)
        line(x, n - 2 - m, m, m, n - 1 - m)
        val on = withRing(profile, x, 90)
        val off = Frame.blank(profile)
        return Animation(profile, listOf(AnimFrame(on, 450), AnimFrame(off, 200), AnimFrame(on, 450), AnimFrame(off, 700)))
    }

    /** A call DotForge silenced: a bar across a ring (muted), shown twice and fading. */
    fun callSilenced(profile: DeviceProfile): Animation {
        val n = profile.size
        val c = (n - 1) / 2
        val bar = Frame.blank(profile)
        for (dy in -1..1) line(bar, n / 5, c + dy, n - 1 - n / 5, c + dy, width = 1)
        val on = withRing(profile, bar, 255)
        val fade = (5 downTo 0).map { AnimFrame(on.scaled(it / 5f), 90) }
        return Animation(profile, listOf(AnimFrame(on, 400)) + fade + listOf(AnimFrame(on, 400)) + fade + AnimFrame(Frame.blank(profile), 500))
    }

    /** Spam: a "!" in a ring, three quick blinks. */
    fun spam(profile: DeviceProfile): Animation {
        val n = profile.size
        val c = (n - 1) / 2
        val f = Frame.blank(profile)
        for (y in n / 4..c + 2) for (x in c - 1..c + 1) f[x, y] = 255
        for (y in c + 5..c + 7) for (x in c - 1..c + 1) f[x, y] = 255
        val on = withRing(profile, f, 160)
        val off = Frame.blank(profile)
        return Animation(profile, (0 until 3).flatMap { listOf(AnimFrame(on, 220), AnimFrame(off, 160)) } + AnimFrame(off, 500))
    }

    /** A missed call: the missed-call arrow (pointing down-left), blinking slowly. */
    fun missedCall(profile: DeviceProfile): Animation {
        val n = profile.size
        val m = n / 4 + 1
        val lo = n - 1 - m
        val k = n / 4
        val f = Frame.blank(profile)
        line(f, lo - 1, m, m, lo - 1)
        line(f, m, lo - 1, m + k, lo - 1)
        line(f, m, lo - 1 - k, m, lo - 1)
        return Animation(profile, listOf(AnimFrame(f, 800), AnimFrame(f.scaled(0.2f), 600)))
    }

    fun sparkle(profile: DeviceProfile, frames: Int = 12, stepMs: Int = 60, random: Random = Random.Default): Animation =
        Animation(profile, (0 until frames).map {
            val f = Frame.blank(profile)
            repeat(profile.ledCount / 10) { f.pixels[profile.ledCells[random.nextInt(profile.ledCount)]] = 255 }
            AnimFrame(f, stepMs)
        })
}
