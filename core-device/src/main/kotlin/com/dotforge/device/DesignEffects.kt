package com.dotforge.device

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Effects for a whole design (the Create editor's Effects button), each a pure function of the animation.
 * Effects that need motion (rotate, breathe, shake) turn a single picture into frames.
 */
enum class DesignEffect(val label: String, val hint: String) {
    ROTATE("Rotate", "Spins once round the middle"),
    INVERT("Invert", "Lit dots go dark and dark dots light"),
    BRIGHTER("Brighter", "Every dot a third brighter"),
    DIMMER("Dimmer", "Every dot a third dimmer"),
    CONTRAST("Contrast", "Bright dots brighter, dim dots dimmer"),
    BREATHE("Breathe", "Fades in and out"),
    FASTER("Faster", "Plays twice as fast"),
    SLOWER("Slower", "Plays half as fast"),
    SHAKE("Shake", "A short wobble"),
    FLASH("Flash", "Starts with a full white flash"),
    ;

    fun apply(a: Animation): Animation = when (this) {
        ROTATE -> DesignEffects.rotate(a)
        INVERT -> DesignEffects.map(a) { it.inverted() }
        BRIGHTER -> DesignEffects.map(a) { DesignEffects.brightness(it, 4f / 3) }
        DIMMER -> DesignEffects.map(a) { DesignEffects.brightness(it, 2f / 3) }
        CONTRAST -> DesignEffects.map(a) { DesignEffects.contrast(it, 1.6f) }
        BREATHE -> DesignEffects.breathe(a)
        FASTER -> DesignEffects.speed(a, 0.5f)
        SLOWER -> DesignEffects.speed(a, 2f)
        SHAKE -> DesignEffects.shake(a)
        FLASH -> DesignEffects.flash(a)
    }
}

object DesignEffects {
    /** Frames longer than this make no sense in a design; Glyph Museum uses up to 60 s. */
    private const val MOTION_FRAME_MS = 60
    private const val STEPS = 24

    fun map(a: Animation, f: (Frame) -> Frame) = Animation(a.profile, a.frames.map { AnimFrame(f(it.frame), it.durationMs) })

    fun brightness(f: Frame, factor: Float) =
        Frame.of(f.profile, IntArray(f.profile.cellCount) { (f.pixels[it] * factor).roundToInt() })

    /** Spreads values away from the middle grey; dark dots stay dark. */
    fun contrast(f: Frame, k: Float) = Frame.of(f.profile, IntArray(f.profile.cellCount) { i ->
        val v = f.pixels[i]
        if (v == 0) 0 else ((v - 128) * k + 128).roundToInt().coerceAtLeast(1)
    })

    /** [f] turned [degrees] clockwise round the matrix centre (nearest dot). */
    fun rotated(f: Frame, degrees: Double): Frame {
        val p = f.profile
        val c = (p.size - 1) / 2.0
        val r = -degrees * PI / 180
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) {
            val dx = cell % p.size - c
            val dy = cell / p.size - c
            val sx = (c + dx * cos(r) - dy * sin(r)).roundToInt()
            val sy = (c + dx * sin(r) + dy * cos(r)).roundToInt()
            if (p.isActive(sx, sy)) out[cell] = f.pixels[p.index(sx, sy)]
        }
        return Frame(p, out)
    }

    /** [f] moved by whole dots; what leaves the matrix is lost. */
    fun shifted(f: Frame, dx: Int, dy: Int): Frame {
        val p = f.profile
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) {
            val sx = cell % p.size - dx
            val sy = cell / p.size - dy
            if (p.isActive(sx, sy)) out[cell] = f.pixels[p.index(sx, sy)]
        }
        return Frame(p, out)
    }

    /** One picture: a full turn in [STEPS]; frames: each turned a little further, one turn over the loop. */
    fun rotate(a: Animation): Animation {
        val src = if (a.frames.size == 1) List(STEPS) { AnimFrame(a.frames[0].frame, MOTION_FRAME_MS) } else a.frames
        return Animation(a.profile, src.mapIndexed { i, af -> AnimFrame(rotated(af.frame, 360.0 * i / src.size), af.durationMs) })
    }

    /** Brightness follows one slow breath over the loop (a picture gets [STEPS] frames over 1.6 s). */
    fun breathe(a: Animation): Animation {
        val src = if (a.frames.size == 1) List(STEPS) { AnimFrame(a.frames[0].frame, 1600 / STEPS) } else a.frames
        return Animation(a.profile, src.mapIndexed { i, af ->
            val level = 0.15f + 0.85f * (0.5f + 0.5f * sin(2 * PI * i / src.size - PI / 2)).toFloat()
            AnimFrame(brightness(af.frame, level), af.durationMs)
        })
    }

    fun speed(a: Animation, factor: Float) = Animation(a.profile, a.frames.map {
        AnimFrame(it.frame, (it.durationMs * factor).roundToInt().coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS))
    })

    private val WOBBLE = listOf(1 to 0, -1 to 0, 1 to 1, -1 to -1, 0 to 1, 0 to -1, 1 to 0, 0 to 0)

    /** A short wobble first, then the design as it was. */
    fun shake(a: Animation): Animation {
        val first = a.frames.first().frame
        return Animation(a.profile, WOBBLE.map { (dx, dy) -> AnimFrame(shifted(first, dx, dy), 40) } + a.frames)
    }

    fun flash(a: Animation) = Animation(
        a.profile,
        listOf(AnimFrame(Frame.blank(a.profile).fill(255), 120), AnimFrame(Frame.blank(a.profile), 80)) + a.frames,
    )
}
