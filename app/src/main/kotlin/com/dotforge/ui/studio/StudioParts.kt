package com.dotforge.ui.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Gif
import com.dotforge.device.Picture
import com.dotforge.device.Posters
import com.dotforge.rules.GlyphChoice
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.Rule
import com.dotforge.rules.SimpleRules
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.ui.design.Snacks
import java.io.File

/** What the Photo screen opens: picked pictures (several become frames), a GIF, a video, or pictures in memory. */
sealed interface MediaSource {
    data class Images(val uris: List<Uri>) : MediaSource
    data class GifFile(val uri: Uri) : MediaSource
    data class Video(val uri: Uri) : MediaSource
    /** Already decoded (a camera photo, tests): one picture per frame. */
    class Pictures(val pictures: List<Picture>, val name: String = "Photo") : MediaSource
}

/** Picked media waiting for the Photo screen, by key. In memory only. */
object MediaDrafts {
    private val map = HashMap<String, MediaSource>()
    private var next = 0
    fun put(s: MediaSource): String = synchronized(map) { "m${next++}".also { map[it] = s } }
    fun get(key: String?): MediaSource? = key?.let { synchronized(map) { map[it] } }
    fun drop(key: String?) { synchronized(map) { map.remove(key) } }
}

object StudioText {
    /** "12 frames · 2.4 s", "1 frame", "Text" or "Photo" (row 43). */
    fun subtitle(d: Design, a: Animation?): String = when {
        d.kind == DesignKind.TEXT -> "Text"
        a == null -> "Can't be read"
        a.frames.size == 1 -> if (d.kind == DesignKind.PHOTO) "Photo" else "1 frame"
        else -> "${a.frames.size} frames · ${seconds(a.totalMs)}"
    }

    /** 2400 → "2.4 s", 12000 → "12 s". */
    fun seconds(ms: Int): String {
        val tenths = (ms + 50) / 100
        return if (tenths % 10 == 0 || ms >= 10_000) "${(ms + 500) / 1000} s" else "${tenths / 10}.${tenths % 10} s"
    }

    /** "0:30" */
    fun clock(ms: Long): String {
        val s = (ms + 500) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** "2 min ago", "just now", "3 h ago", "yesterday". */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((now - at) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    fun list(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/** Who uses a design: alert rules and people that show it, the DF Player toy, the Gallery. */
object DesignUse {
    fun rules(id: String): List<Rule> = AppGraph.rules.rules.value.filter { it.glyph == GlyphStyle.DESIGN && it.designId == id }

    fun names(id: String): List<String> = buildList {
        rules(id).forEach { add(it.name) }
        if (AppGraph.toys.playerDesignId == id) add("DF Player")
        if (id in AppGraph.toys.galleryIds) add("Gallery")
    }

    /**
     * Deletes [d] with no question (row 41): every place that used it goes back to its default glyph, and the
     * snackbar says so with Undo, which puts the design and every place back.
     */
    fun delete(d: Design, snacks: Snacks?) {
        val index = AppGraph.designs.designs.value.indexOfFirst { it.id == d.id }
        val users = rules(d.id)
        val player = AppGraph.toys.playerDesignId == d.id
        val gallery = d.id in AppGraph.toys.galleryIds
        val names = names(d.id)
        users.forEach { r -> AppGraph.rules.upsert(SimpleRules.withGlyph(r, defaultGlyph(r))) }
        if (player) AppGraph.toys.playerDesignId = null
        if (gallery) AppGraph.toys.galleryIds = AppGraph.toys.galleryIds - d.id
        // the photo stays on disk: Undo needs it
        AppGraph.designs.delete(d.id, keepMedia = true)
        val text = if (names.isEmpty()) "Deleted ${d.name}" else "Deleted ${d.name}. ${StudioText.list(names)} now ${if (names.size == 1) "uses its" else "use their"} default glyph"
        snacks?.show(text) {
            AppGraph.designs.restore(d, index)
            users.forEach { AppGraph.rules.upsert(it) }
            if (player) AppGraph.toys.playerDesignId = d.id
            if (gallery) AppGraph.toys.galleryIds = AppGraph.toys.galleryIds + d.id
        }
    }

    /** What a rule shows once its design is gone: a person their heart, an app its icon. */
    fun defaultGlyph(r: Rule): GlyphChoice = if (SimpleRules.isPersonRule(r)) GlyphChoice.icon("heart") else GlyphChoice(GlyphStyle.APP_ICON)
}

/** Share… (row 38): an animated GIF, a DotForge file that Glyph Museum and Dot Hub open too, or a picture. */
object DesignShare {
    enum class Kind(val title: String, val description: String) {
        GIF("Animated GIF", "For chats"),
        JSON("DotForge file (.json)", "Opens in DotForge, Glyph Museum and Dot Hub"),
        PNG("Image (PNG)", "A still picture"),
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Design" }

    /** Writes the file into the cache's share folder; throws on failure. */
    fun write(ctx: Context, d: Design, a: Animation, kind: Kind): File {
        val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }
        return when (kind) {
            Kind.GIF -> File(dir, "${safe(d.name)}.gif").also { it.writeBytes(Gif.encode(a)) }
            Kind.JSON -> File(dir, "${safe(d.name)}.json").also { it.writeText(exportJson(d, a)) }
            Kind.PNG -> File(dir, "${safe(d.name)}.png").also { f ->
                val still = Posters.of(a)
                val bytes = Gif.encode(Animation.single(still))
                // the GIF's single frame, re-encoded as PNG
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** Glyph Museum JSON, with the title and the creator's credit carried on. */
    fun exportJson(d: Design, a: Animation): String {
        val base = kotlinx.serialization.json.Json.parseToJsonElement(a.toMuseumJson()) as kotlinx.serialization.json.JsonObject
        val extra = buildMap {
            put("title", kotlinx.serialization.json.JsonPrimitive(d.name))
            d.author?.let { put("author", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        return kotlinx.serialization.json.JsonObject(base + extra).toString()
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun send(ctx: Context, f: File, name: String) {
        val type = when (f.extension) { "gif" -> "image/gif"; "png" -> "image/png"; else -> "application/json" }
        val send = Intent(Intent.ACTION_SEND).setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves to Downloads through MediaStore (no permission needed). */
    fun download(ctx: Context, f: File): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, when (f.extension) { "gif" -> "image/gif"; "png" -> "image/png"; else -> "application/json" })
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)
}
