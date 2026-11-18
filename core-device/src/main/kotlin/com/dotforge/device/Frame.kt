package com.dotforge.device

/**
 * One image on the matrix: [DeviceProfile.cellCount] brightness values 0-255 on the full square grid,
 * row-major. Cells without an LED are kept at 0. Conversion to the hardware range happens in the driver.
 */
class Frame(val profile: DeviceProfile, val pixels: IntArray = IntArray(profile.cellCount)) {
    init {
        require(pixels.size == profile.cellCount) { "frame must have ${profile.cellCount} cells, got ${pixels.size}" }
    }

    operator fun set(x: Int, y: Int, value: Int) {
        if (profile.isActive(x, y)) pixels[profile.index(x, y)] = value.coerceIn(0, 255)
    }

    fun copy() = Frame(profile, pixels.copyOf())

    /** Sets every LED to [value]; returns this frame. */
    fun fill(value: Int): Frame {
        val v = value.coerceIn(0, 255)
        for (cell in profile.ledCells) pixels[cell] = v
        return this
    }

    /** Lights every cell that is lit in [other] at [value]; returns this frame. */
    fun overlay(other: Frame, value: Int = 255): Frame {
        val v = value.coerceIn(0, 255)
        for (cell in profile.ledCells) if (other.pixels[cell] > 0) pixels[cell] = v
        return this
    }

    fun scaled(factor: Float) = Frame(profile, IntArray(profile.cellCount) { (pixels[it] * factor).toInt().coerceIn(0, 255) })

    fun inverted() = Frame(profile, IntArray(profile.cellCount) { if (profile.mask[it]) 255 - pixels[it] else 0 })

    companion object {
        fun blank(profile: DeviceProfile) = Frame(profile)

        /** Wraps a full-grid array, zeroing cells without an LED and clamping to 0-255. */
        fun of(profile: DeviceProfile, cells: IntArray) =
            Frame(profile, IntArray(profile.cellCount) { if (profile.mask[it]) cells[it].coerceIn(0, 255) else 0 })
    }
}

data class AnimFrame(val frame: Frame, val durationMs: Int)

/** A sequence of frames, each shown for its own duration. Stored and shared as Dot Hub / Glyph Museum JSON. */
class Animation(val profile: DeviceProfile, val frames: List<AnimFrame>) {
    init {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
    }

    val totalMs: Int get() = frames.sumOf { it.durationMs }

    fun toMuseumJson(): String =
        GlyphMuseumJson.export(profile, frames.map { it.frame.pixels }, frames.map { it.durationMs })

    companion object {
        /** 50 fps cap. */
        const val MIN_FRAME_MS = 20
        const val MAX_FRAME_MS = 60_000

        fun single(frame: Frame, durationMs: Int = 1000) = Animation(frame.profile, listOf(AnimFrame(frame, durationMs)))

        /** Parses Dot Hub / Glyph Museum JSON; throws IllegalArgumentException (or a serialization error) on bad input. */
        fun fromMuseumJson(profile: DeviceProfile, text: String): Animation =
            Animation(profile, GlyphMuseumJson.import(profile, text).map { (cells, ms) ->
                AnimFrame(Frame.of(profile, cells), ms.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS))
            })
    }
}
