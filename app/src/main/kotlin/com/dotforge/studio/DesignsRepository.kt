package com.dotforge.studio

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Layers
import com.dotforge.device.PhotoSettings
import com.dotforge.device.TextSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** How a design was made, so it reopens where it can still be changed (text as text, a photo's settings). */
@Serializable
enum class DesignKind { DRAW, TEXT, PHOTO }

/**
 * A saved design. [frames] is Dot Hub / Glyph Museum JSON of what plays (a still has one frame); everything
 * else is how to edit it again: the text, the photo's settings, or the layers with the drawing layer's own
 * frames in [drawing].
 */
@Serializable
data class Design(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val animated: Boolean,
    val frames: String,
    val updatedAt: Long = 0,
    /** The design's own timing; places that use it may override it. Null = the defaults. */
    val timing: com.dotforge.device.Timing? = null,
    val kind: DesignKind = DesignKind.DRAW,
    val text: TextSpec? = null,
    val photo: PhotoSettings? = null,
    /** The photo kept for re-tuning: a file name in the designs' media folder. */
    val photoFile: String? = null,
    val layers: Layers? = null,
    /** With [layers]: the drawing layer's frames (Glyph Museum JSON). */
    val drawing: String? = null,
    /** Who made it, kept from Glyph Museum files and carried on in exports. */
    val author: String? = null,
    /** The editor's trace photo: a guide under the grid, never in [frames] or on the Glyph. */
    val trace: TracePhoto? = null,
)

/**
 * A photo shown faintly under the editor's grid to draw over. Only the editor shows it: it is never sent to the
 * Glyph, never drawn into frames and never exported. [file] is in the designs' media folder.
 */
@Serializable
data class TracePhoto(val file: String, val opacity: Int = DEFAULT_OPACITY, val visible: Boolean = true) {
    fun clamped() = copy(opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY))

    companion object {
        const val MIN_OPACITY = 10
        const val MAX_OPACITY = 60
        const val DEFAULT_OPACITY = 30
    }
}

/**
 * The user's designs, newest first, in one JSON file. The DF Player toy, Always-on, alerts and people play
 * them. The editor autosaves into it; [editing] remembers the design open in the editor until it closes
 * cleanly, so a design left by a crash or a force-stop can be continued. Call from one thread (the main thread).
 */
class DesignsRepository(private val profile: DeviceProfile, private val file: File, legacyFile: File? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Design.serializer())
    private val editingFile = File(file.parentFile, "designs_editing.txt")

    /** Photos kept for re-tuning. */
    val media: File = File(file.parentFile, "design_media")

    private val _designs = MutableStateFlow(load(legacyFile))
    val designs: StateFlow<List<Design>> = _designs.asStateFlow()

    private val _editing = MutableStateFlow(runCatching { editingFile.readText().trim() }.getOrNull()?.ifEmpty { null })
    /** The design open in the editor, or left open by a crash. */
    val editing: StateFlow<String?> = _editing.asStateFlow()

    /** True while an editor is on screen in this process (a left-over [editing] from a crash is not). */
    var editorOpen = false

    private fun load(legacyFile: File?): List<Design> {
        if (file.exists()) return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        // The single Studio animation of earlier versions becomes the first design.
        val legacy = legacyFile?.takeIf { it.exists() }?.let { f ->
            runCatching { Animation.fromMuseumJson(profile, f.readText()) }.getOrNull()
        } ?: return emptyList()
        val list = listOf(Design(name = "My design", animated = legacy.frames.size > 1, frames = legacy.toMuseumJson(), updatedAt = legacyFile.lastModified()))
        file.writeText(json.encodeToString(serializer, list))
        legacyFile.delete()
        return list
    }

    private fun save(list: List<Design>) {
        _designs.value = list
        file.writeText(json.encodeToString(serializer, list))
    }

    fun get(id: String?): Design? = id?.let { i -> _designs.value.firstOrNull { it.id == i } }

    fun animation(design: Design): Animation? =
        runCatching { Animation.fromMuseumJson(profile, design.frames) }.getOrNull()

    /** Saves [design] and moves it to the top. */
    fun save(design: Design) = save(listOf(design) + _designs.value.filterNot { it.id == design.id })

    /** Saves [design] where it already is (autosave: the grid doesn't jump while you draw). */
    fun update(design: Design) {
        val list = _designs.value
        val i = list.indexOfFirst { it.id == design.id }
        if (i < 0) save(design) else save(list.toMutableList().also { it[i] = design })
    }

    /** Deletes a design; [keepMedia] keeps its photo while an Undo can still bring it back. */
    fun delete(id: String, keepMedia: Boolean = false) {
        if (!keepMedia) get(id)?.let { d -> listOfNotNull(d.photoFile, d.trace?.file).forEach { File(media, it).delete() } }
        save(_designs.value.filterNot { it.id == id })
    }

    /** Puts [design] back where it was (Undo after a delete). */
    fun restore(design: Design, index: Int) {
        val list = _designs.value.filterNot { it.id == design.id }.toMutableList()
        list.add(index.coerceIn(0, list.size), design)
        save(list)
    }

    fun rename(id: String, name: String) {
        val d = get(id) ?: return
        val v = name.trim().take(30)
        if (v.isEmpty()) return
        update(d.copy(name = v, updatedAt = System.currentTimeMillis()))
    }

    /** A copy named "Heart copy", right after the original. */
    fun duplicate(id: String): Design? {
        val d = get(id) ?: return null
        val photo = d.photoFile?.let { name ->
            val copyName = "${UUID.randomUUID()}.png"
            runCatching { File(media, name).copyTo(File(media, copyName)); copyName }.getOrNull()
        }
        val trace = d.trace?.let { t ->
            val copyName = "${UUID.randomUUID()}-trace.png"
            runCatching { File(media, t.file).copyTo(File(media, copyName)); t.copy(file = copyName) }.getOrNull()
        }
        val copy = d.copy(id = UUID.randomUUID().toString(), name = copyName(d.name), photoFile = photo, trace = trace, updatedAt = System.currentTimeMillis())
        val list = _designs.value.toMutableList()
        list.add(list.indexOfFirst { it.id == id } + 1, copy)
        save(list)
        return copy
    }

    private fun copyName(name: String): String {
        val used = _designs.value.map { it.name }.toSet()
        val base = "$name copy".take(30)
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it".take(30) }.first { it !in used }
    }

    /** A name not used yet: "Design 1", "Design 2", ... */
    fun nextName(): String {
        val used = _designs.value.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "Design $it" }.first { it !in used }
    }

    fun beginEditing(id: String) {
        editorOpen = true
        _editing.value = id
        runCatching { editingFile.writeText(id) }
    }

    /** The editor closed cleanly. */
    fun endEditing() {
        editorOpen = false
        _editing.value = null
        editingFile.delete()
    }

    /** A design the editor didn't close cleanly (shown as "Continue Design 14?"), unless it is open now. */
    fun recoverable(): Design? = if (editorOpen) null else get(_editing.value)
}
