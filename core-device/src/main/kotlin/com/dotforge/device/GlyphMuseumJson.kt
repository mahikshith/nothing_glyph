package com.dotforge.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dot Hub / Glyph Museum animation JSON: `{version, frames:[{duration (ms), values (0-255 per real LED)}]}`.
 */
@Serializable
data class MuseumFrame(val duration: Int, val values: List<Int>)

@Serializable
data class MuseumAnimation(val version: Int = 1, val frames: List<MuseumFrame>)

object GlyphMuseumJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(animation: MuseumAnimation): String = json.encodeToString(MuseumAnimation.serializer(), animation)

    fun decode(text: String): MuseumAnimation = json.decodeFromString(MuseumAnimation.serializer(), text)

    /** Export full 625-cell frames, each shown for the matching duration in ms. */
    fun export(profile: DeviceProfile, frames: List<IntArray>, durationsMs: List<Int>): String {
        require(frames.size == durationsMs.size) { "one duration per frame" }
        val list = frames.mapIndexed { i, f ->
            MuseumFrame(durationsMs[i], profile.toRealLeds(f).map { it.coerceIn(0, 255) })
        }
        return encode(MuseumAnimation(1, list))
    }

    /** Import to full cell frames (with duration in ms). Frames with the wrong LED count are rejected. */
    fun import(profile: DeviceProfile, text: String): List<Pair<IntArray, Int>> =
        decode(text).frames.mapIndexed { i, f ->
            require(f.values.size == profile.ledCount) {
                "frame $i has ${f.values.size} values, expected ${profile.ledCount}"
            }
            profile.fromRealLeds(f.values.toIntArray()) to f.duration
        }
}
