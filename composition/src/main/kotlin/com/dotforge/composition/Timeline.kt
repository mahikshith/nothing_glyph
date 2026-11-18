package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a lane's light goes on the matrix. Rings, arcs and bars come in [parts]. */
@Serializable
enum class Region(val label: String, val parts: Int) {
    WHOLE("Whole matrix", 1),
    RING("Ring", 4),
    ARC("Arc", 4),
    BAR("Bar", 5),
    OUTLINE("Outline", 1),

    /** The lights the file came with (or that were composed): trimmed and scaled, no keys. */
    IMPORTED("Imported lights", 1),
}

/** How brightness goes from a key to the next one. */
@Serializable
enum class Shape(val label: String, val hint: String) {
    HOLD("Hold", "Stays until the next key"),
    FADE("Fade", "Slides to the next key"),
    PULSE("Pulse", "Flashes, then fades out"),
}

/** A keyframe: at [frame] (60 fps) the lane is at [level] percent, then follows [shape]. */
@Serializable
data class Key(val id: Int, val frame: Int, val level: Int, val shape: Shape = Shape.HOLD)

/** One lane of the timeline. Imported lanes use [trimStart], [trimEnd] (exclusive, -1 = to the end) and [scale]. */
@Serializable
data class Lane(
    val id: Int,
    val region: Region,
    val part: Int = 0,
    val keys: List<Key> = emptyList(),
    val trimStart: Int = 0,
    val trimEnd: Int = -1,
    val scale: Float = 1f,
) {
    val label: String get() = if (region.parts > 1) "${region.label} ${part + 1}" else region.label
}

/**
 * An editable light track: lanes of keyframes, plus the imported lights (AUTHOR form in [imported]). Stored in
 * CUSTOM1 by [TimelineCodec] so a saved ringtone opens again with its keys; AUTHOR holds the render.
 */
@Serializable
data class Timeline(
    val frames: Int,
    val lanes: List<Lane>,
    val imported: String? = null,
    val app: String = APP,
    val version: Int = 1,
) {
    fun lane(id: Int): Lane? = lanes.firstOrNull { it.id == id }

    fun nextLaneId(): Int = (lanes.maxOfOrNull { it.id } ?: -1) + 1

    companion object {
        const val APP = "DotForge"

        /** A timeline holding [rows] as a single imported lane. */
        fun fromImported(rows: List<IntArray>, frames: Int = rows.size): Timeline =
            Timeline(frames, listOf(Lane(0, Region.IMPORTED)), GlyphAuthor.encodeComposer(rows, wrapLines = false))
    }
}

/** Renders a [Timeline] to AUTHOR rows: 60 fps, cellCount values 0-4095, the brightest lane wins per cell. */
object TimelineRenderer {
    /** How long a pulse takes to fade out: 0.4 s. */
    const val PULSE_FRAMES = 24

    /** A lane's brightness (0..1) at [frame]. */
    fun level(keys: List<Key>, frame: Int): Float {
        val sorted = keys.sortedBy { it.frame }
        val i = sorted.indexOfLast { it.frame <= frame }
        if (i < 0) return 0f
        val k = sorted[i]
        val next = sorted.getOrNull(i + 1)
        val a = k.level.coerceIn(0, 100) / 100f
        return when (k.shape) {
            Shape.HOLD -> a
            Shape.FADE -> if (next == null || next.frame == k.frame) a else {
                val t = (frame - k.frame).toFloat() / (next.frame - k.frame)
                a + (next.level.coerceIn(0, 100) / 100f - a) * t
            }
            Shape.PULSE -> a * (1f - (frame - k.frame).toFloat() / PULSE_FRAMES).coerceAtLeast(0f)
        }
    }

    /** The cells (with an LED) a region part lights. */
    fun cells(region: Region, part: Int, profile: DeviceProfile = DeviceProfile.Phone3): IntArray = when (region) {
        Region.WHOLE, Region.IMPORTED -> profile.ledCells
        Region.OUTLINE -> profile.ring.toIntArray()
        Region.RING, Region.ARC, Region.BAR -> {
            val layout = when (region) {
                Region.RING -> ConvertLayout.RINGS
                Region.ARC -> ConvertLayout.ARCS
                else -> ConvertLayout.BARS
            }
            val map = RingtoneConverter.zoneMap(region.parts, layout, profile)
            val p = part.coerceIn(0, region.parts - 1)
            profile.ledCells.filter { p in map[it] }.toIntArray()
        }
    }

    fun render(t: Timeline, imported: List<IntArray>?, profile: DeviceProfile = DeviceProfile.Phone3): List<IntArray> {
        val out = List(t.frames.coerceAtLeast(0)) { IntArray(profile.cellCount) }
        for (lane in t.lanes) {
            if (lane.region == Region.IMPORTED) {
                val rows = imported ?: continue
                val end = if (lane.trimEnd < 0) t.frames else minOf(lane.trimEnd, t.frames)
                for (f in lane.trimStart.coerceAtLeast(0) until minOf(end, rows.size)) {
                    val src = rows[f]
                    val dst = out[f]
                    for (cell in profile.ledCells) {
                        val v = (src.getOrElse(cell) { 0 } * lane.scale).toInt().coerceIn(0, GlyphAuthor.MAX)
                        if (v > dst[cell]) dst[cell] = v
                    }
                }
                continue
            }
            if (lane.keys.isEmpty()) continue
            val cells = cells(lane.region, lane.part, profile)
            val keys = lane.keys.sortedBy { it.frame }
            for (f in 0 until t.frames) {
                val v = (level(keys, f) * GlyphAuthor.MAX).toInt()
                if (v <= 0) continue
                val dst = out[f]
                for (cell in cells) if (v > dst[cell]) dst[cell] = v
            }
        }
        return out
    }
}

/** A [Timeline] as CUSTOM1 text: JSON, zlib, base64. */
object TimelineCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(t: Timeline): String = GlyphAuthor.packText(json.encodeToString(Timeline.serializer(), t))

    /** The timeline in [custom1], or null when it holds none (empty, Composer's own data, anything else). */
    fun decode(custom1: String?): Timeline? {
        if (custom1.isNullOrBlank()) return null
        return runCatching {
            val text = GlyphAuthor.unpackText(custom1).trim()
            if (!text.startsWith("{")) return null
            json.decodeFromString(Timeline.serializer(), text).takeIf { it.app == Timeline.APP }
        }.getOrNull()
    }

    /** The imported rows a timeline carries. */
    fun importedRows(t: Timeline): List<IntArray>? = t.imported?.let { runCatching { GlyphAuthor.decode(it) }.getOrNull() }
}
