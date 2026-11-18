package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.MuseumAnimation
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Converted sprite packs (see tools/library/convert_sprites.py). `library/sprites.index.json` lists the sprites;
 * the pixels sit in one gzip file per category and are parsed the first time one of its sprites is loaded.
 * Each sprite is stored as Dot Hub / Glyph Museum animation JSON for the 25x25 Phone (3) mask.
 */
internal object SpriteStore {
    class Entry(
        val id: String, val src: String, val name: String, val category: String, val tags: List<String>,
        val file: String, val credit: String, /** frames in the source sprite, 0 for scrolling banners */ val sourceFrames: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val files = ConcurrentHashMap<String, Map<String, MuseumAnimation>>()
    private val source = DeviceProfile.Phone3

    private fun open(name: String): InputStream =
        SpriteStore::class.java.getResourceAsStream("/library/$name") ?: error("missing library asset $name")

    val entries: List<Entry> by lazy {
        val root = json.parseToJsonElement(open("sprites.index.json").bufferedReader().use { it.readText() }).jsonObject
        val credits = root.getValue("credits").jsonArray.map { it.jsonPrimitive.content }
        root.getValue("items").jsonArray.map { e ->
            val o: JsonObject = e.jsonObject
            Entry(
                id = o.getValue("id").jsonPrimitive.content, src = o.getValue("src").jsonPrimitive.content,
                name = o.getValue("name").jsonPrimitive.content, category = o.getValue("cat").jsonPrimitive.content,
                tags = (o.getValue("tags") as JsonArray).map { it.jsonPrimitive.content },
                file = o.getValue("file").jsonPrimitive.content,
                credit = credits[o.getValue("credit").jsonPrimitive.int], sourceFrames = o.getValue("n").jsonPrimitive.int,
            )
        }
    }

    private fun pack(file: String): Map<String, MuseumAnimation> = files.getOrPut(file) {
        val text = GZIPInputStream(open(file)).bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject.getValue("items")
        json.decodeFromJsonElement(MapSerializer(String.serializer(), MuseumAnimation.serializer()), root)
    }

    fun load(entry: Entry, profile: DeviceProfile): Animation {
        val raw = pack(entry.file).getValue(entry.src)
        val frames = raw.frames.map { f ->
            val phone = source.fromRealLeds(f.values.toIntArray())
            AnimFrame(if (sameGrid(profile)) Frame.of(profile, phone) else resample(profile, phone), f.duration)
        }
        return Animation(profile, frames)
    }

    private fun sameGrid(p: DeviceProfile) = p.size == source.size && p.radiusSquared == source.radiusSquared

    /** Nearest-neighbour resize of a Phone (3) frame onto another grid. */
    private fun resample(p: DeviceProfile, cells: IntArray): Frame {
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) {
            val x = (cell % p.size + 0.5) * source.size / p.size
            val y = (cell / p.size + 0.5) * source.size / p.size
            out[cell] = cells[source.index(x.toInt().coerceIn(0, source.size - 1), y.toInt().coerceIn(0, source.size - 1))]
        }
        return Frame.of(p, out)
    }
}
