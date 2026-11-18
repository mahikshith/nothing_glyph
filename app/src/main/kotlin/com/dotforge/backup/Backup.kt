package com.dotforge.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore: one ".dotforge" file (a zip) with designs, alert rules, people, spam rules and lists,
 * caller glyphs, filter settings and the app's settings, plus the designs' pictures (the photo a photo design
 * was made from, and trace photos) from the design_media folder. Glyph ringtones are not in it: they live in the phone's
 * Ringtones folder. Pure JVM so it is unit tested; the UI moves the bytes through the system file picker.
 */
object Backup {
    /** The zip's manifest: format version and what is inside. A zip without it is not a backup. */
    const val MANIFEST = "dotforge-backup.json"
    /** Raise when the layout changes; a newer backup than this app knows is refused. */
    const val FORMAT = 1
    const val EXTENSION = "dotforge"
    /** The app's settings (SharedPreferences), as JSON: file name -> key -> value. */
    const val SETTINGS = "settings.json"

    /** The data files in the app's files folder that a backup carries. */
    val DATA_FILES = listOf("designs.json", "rules.json", "calls.json", "filters.json")
    /** The designs' pictures: kept in this folder of the files folder, and under this path in the zip. */
    const val MEDIA = "design_media"
    private val MEDIA_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}""")
    private const val MAX_BYTES = 200L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What a backup holds, for the restore preview: "52 designs, 9 alert rules, 14 spam rules". */
    data class Summary(val designs: Int, val alertRules: Int, val people: Int, val spamRules: Int) {
        fun text(): String = listOf(
            designs to "design", alertRules to "alert rule", people to "person", spamRules to "spam rule",
        ).filter { it.first > 0 }.joinToString(", ") { (n, w) -> "$n ${if (n == 1) w else if (w == "person") "people" else w + "s"}" }
            .ifEmpty { "No designs or rules" }
    }

    /** A backup read into memory, checked, ready to preview and apply. */
    class Contents internal constructor(
        val format: Int, val files: Map<String, ByteArray>, val settings: JsonObject?,
        /** Design pictures by file name (photo designs' photos, trace photos). */
        val media: Map<String, ByteArray> = emptyMap(),
    ) {
        val summary: Summary by lazy {
            val designs = files["designs.json"]?.let { parse(it) as? JsonArray }?.size ?: 0
            val rules = (files["rules.json"]?.let { parse(it) as? JsonObject })?.get("rules") as? JsonArray ?: JsonArray(emptyList())
            var alert = 0; var people = 0; var spam = 0
            rules.forEach { r ->
                val o = r as? JsonObject ?: return@forEach
                val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when {
                    o["action"]?.jsonPrimitive?.contentOrNull == "SPAM" -> spam++
                    id.startsWith("person-") -> people++
                    else -> alert++
                }
            }
            Summary(designs, alert, people, spam)
        }
    }

    class NewerBackupException : IOException("Update DotForge to restore this backup")

    /** Writes a backup of [filesDir]'s data files and [settings] to [out]; returns its size in bytes. */
    fun write(filesDir: File, settings: JsonObject, out: OutputStream, appVersion: String = ""): Long {
        val counting = CountingStream(out)
        ZipOutputStream(counting).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            val manifest = buildJsonObject {
                put("format", FORMAT)
                put("app", appVersion)
                put("createdAt", System.currentTimeMillis())
            }
            put(MANIFEST, manifest.toString().toByteArray())
            DATA_FILES.map { File(filesDir, it) }.filter { it.isFile }.forEach { put(it.name, it.readBytes()) }
            // only the pictures a design still uses (photoFile, trace.file), not leftovers
            mediaUsed(File(filesDir, "designs.json")).map { File(File(filesDir, MEDIA), it) }.filter { it.isFile }
                .forEach { put("$MEDIA/${it.name}", it.readBytes()) }
            put(SETTINGS, settings.toString().toByteArray())
        }
        return counting.count
    }

    /** Reads and checks a backup. Throws [NewerBackupException] for a newer format, [IOException] for anything else. */
    fun read(input: InputStream): Contents {
        val files = HashMap<String, ByteArray>()
        val media = HashMap<String, ByteArray>()
        var manifest: JsonObject? = null
        var settings: JsonObject? = null
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zip.readBytes()
                total += bytes.size
                if (total > MAX_BYTES) throw IOException("Backup is too large")
                when (e.name) {
                    MANIFEST -> manifest = parse(bytes) as? JsonObject
                    SETTINGS -> settings = parse(bytes) as? JsonObject
                    in DATA_FILES -> files[e.name] = bytes
                    // a plain file name inside design_media/ only: never a path that leaves the folder
                    else -> if (e.name.startsWith("$MEDIA/") && MEDIA_NAME.matches(e.name.removePrefix("$MEDIA/"))) media[e.name.removePrefix("$MEDIA/")] = bytes // unknown entries (from a later version) are ignored, never written
                }
            }
        }
        val m = manifest ?: throw IOException("Not a DotForge backup")
        val format = m["format"]?.jsonPrimitive?.intOrNull ?: throw IOException("Not a DotForge backup")
        if (format > FORMAT) throw NewerBackupException()
        files.forEach { (name, bytes) -> parse(bytes) ?: throw IOException("Damaged file in backup: $name") }
        return Contents(format, files, settings, media)
    }

    /** The picture file names that the designs in [designsFile] use. */
    internal fun mediaUsed(designsFile: File): Set<String> {
        val designs = designsFile.takeIf { it.isFile }?.let { parse(it.readBytes()) as? JsonArray } ?: return emptySet()
        return designs.mapNotNull { it as? JsonObject }.flatMap { d ->
            listOfNotNull(d.str("photoFile"), (d["trace"] as? JsonObject)?.str("file"))
        }.filter { MEDIA_NAME.matches(it) }.toSet()
    }

    /**
     * Restores [c] into [filesDir]. [replace] = "Replace everything": every data file is overwritten.
     * Otherwise "Add to what's here": designs and rules from the backup are added (a clashing name becomes
     * "Heart (2)", a clashing id gets a new one), list entries are joined, and other files stay as they are.
     */
    fun restore(c: Contents, filesDir: File, replace: Boolean) {
        val out = LinkedHashMap<String, ByteArray>()
        for (name in DATA_FILES) {
            val incoming = c.files[name] ?: continue
            val current = File(filesDir, name).takeIf { it.isFile }?.readBytes()
            out[name] = when {
                replace || current == null -> incoming
                name == "designs.json" -> mergeDesigns(parse(current) as? JsonArray, parse(incoming) as JsonArray).toString().toByteArray()
                name == "rules.json" -> mergeRules(parse(current) as? JsonObject, parse(incoming) as JsonObject).toString().toByteArray()
                else -> continue
            }
        }
        // pictures first, so no restored design points at a missing file; "Add" never overwrites one that is here
        val media = File(filesDir, MEDIA)
        c.media.forEach { (name, bytes) ->
            val f = File(media, name)
            if (replace || !f.exists()) writeAtomically(f, bytes)
        }
        out.forEach { (name, bytes) -> writeAtomically(File(filesDir, name), bytes) }
    }

    internal fun mergeDesigns(current: JsonArray?, incoming: JsonArray): JsonArray {
        val cur = current?.mapNotNull { it as? JsonObject }.orEmpty()
        val ids = cur.mapNotNull { it.str("id") }.toMutableSet()
        val names = cur.mapNotNull { it.str("name") }.toMutableSet()
        val added = incoming.mapNotNull { it as? JsonObject }.mapNotNull { d ->
            // the same design (same id) is already here: the one on the phone wins
            if (d.str("id") in ids) return@mapNotNull null
            var o = d
            if (o.str("id") == null) o = o.with("id", JsonPrimitive(UUID.randomUUID().toString()))
            val name = o.str("name")
            if (name != null && name in names) o = o.with("name", JsonPrimitive(uniqueName(name, names)))
            o.str("id")?.let(ids::add); o.str("name")?.let(names::add)
            o
        }
        return JsonArray(cur + added)
    }

    internal fun mergeRules(current: JsonObject?, incoming: JsonObject): JsonObject {
        if (current == null) return incoming
        val cur = current["rules"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        val ids = cur.mapNotNull { it.str("id") }.toMutableSet()
        val names = cur.mapNotNull { it.str("name") }.toMutableSet()
        val added = incoming["rules"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { r ->
            // a rule that is already here (same id, presets included) stays as the user has it now
            if (r.str("id") in ids) return@mapNotNull null
            var o = r
            if (o.str("id") == null) o = o.with("id", JsonPrimitive(UUID.randomUUID().toString()))
            val name = o.str("name")
            if (name != null && name in names) o = o.with("name", JsonPrimitive(uniqueName(name, names)))
            o.str("id")?.let(ids::add); o.str("name")?.let(names::add)
            o
        }
        val lists = LinkedHashMap<String, MutableList<String>>()
        fun addLists(src: JsonObject) = src["lists"]?.jsonArray?.forEach { l ->
            val o = l as? JsonObject ?: return@forEach
            val name = o.str("name") ?: return@forEach
            val entries = lists.getOrPut(name) { mutableListOf() }
            o["entries"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { if (it !in entries) entries += it }
        }
        addLists(current); addLists(incoming)
        val version = maxOf(current["version"]?.jsonPrimitive?.int ?: 0, incoming["version"]?.jsonPrimitive?.int ?: 0)
        return JsonObject(
            current + mapOf(
                "version" to JsonPrimitive(version),
                "rules" to JsonArray(cur + added),
                "lists" to JsonArray(lists.map { (n, e) -> buildJsonObject { put("name", n); put("entries", JsonArray(e.map(::JsonPrimitive))) } }),
            ),
        )
    }

    /** "Heart" -> "Heart (2)", or the next free number. */
    fun uniqueName(name: String, taken: Set<String>): String {
        val stem = Regex("""^(.*) \((\d+)\)$""").find(name)?.groupValues?.get(1) ?: name
        var n = 2
        while ("$stem ($n)" in taken) n++
        return "$stem ($n)"
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.with(key: String, v: JsonElement) = JsonObject(this + (key to v))
    private fun parse(bytes: ByteArray): JsonElement? = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull()

    private fun writeAtomically(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.restore")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("Could not write ${f.name}") }
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
