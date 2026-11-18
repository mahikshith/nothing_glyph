package com.dotforge.studio

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A saved pixel design. [frames] is Dot Hub / Glyph Museum JSON; a static design has one frame. */
@Serializable
data class Design(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val animated: Boolean,
    val frames: String,
    val updatedAt: Long = 0,
    /** The design's own timing; places that use it may override it. Null = the defaults. */
    val timing: com.dotforge.device.Timing? = null,
)

/**
 * The user's designs from the Create tab, newest first, in one JSON file. The DF Player toy and the
 * "My design" rule effect play them. Call from one thread (the main thread).
 */
class DesignsRepository(private val profile: DeviceProfile, private val file: File, legacyFile: File? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Design.serializer())

    private val _designs = MutableStateFlow(load(legacyFile))
    val designs: StateFlow<List<Design>> = _designs.asStateFlow()

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

    fun delete(id: String) = save(_designs.value.filterNot { it.id == id })

    /** A name not used yet: "Design 1", "Design 2", ... */
    fun nextName(): String {
        val used = _designs.value.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "Design $it" }.first { it !in used }
    }
}
