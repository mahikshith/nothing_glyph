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

    fun ringChase(profile: DeviceProfile, tail: Int = 8, stepMs: Int = 30): Animation =
        Animation(profile, profile.ring.indices.map { AnimFrame(comet(profile, it, tail), stepMs) })

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

    fun sparkle(profile: DeviceProfile, frames: Int = 12, stepMs: Int = 60, random: Random = Random.Default): Animation =
        Animation(profile, (0 until frames).map {
            val f = Frame.blank(profile)
            repeat(profile.ledCount / 10) { f.pixels[profile.ledCells[random.nextInt(profile.ledCount)]] = 255 }
            AnimFrame(f, stepMs)
        })
}
