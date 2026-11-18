package com.dotforge.ui.studio

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.ui.design.LibraryGlyph
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.PreviewSize
import com.dotforge.rules.GlyphChoice
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.SimpleRules
import com.dotforge.studio.Design
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Tab
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.IconCircle
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.editor.Drafts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a picked file becomes (row 8: one Import entry routes each file by type). */
object Picked {
    const val CANT_READ = "DotForge can't read this file. It opens images, GIFs, videos, Glyph Museum or Dot Hub designs and Glyph ringtones."

    /** What Photo to dots makes of picked media: several photos, one photo, a GIF or a video; null for anything else. */
    fun media(ctx: Context, uris: List<Uri>): MediaSource? {
        if (uris.isEmpty()) return null
        val types = uris.map { ctx.contentResolver.getType(it).orEmpty() }
        if (uris.size > 1) return if (types.all { it.startsWith("image/") && it != "image/gif" }) MediaSource.Images(uris) else null
        val type = types[0]
        return when {
            type == "image/gif" -> MediaSource.GifFile(uris[0])
            type.startsWith("image/") -> MediaSource.Images(uris)
            type.startsWith("video/") -> MediaSource.Video(uris[0])
            else -> null
        }
    }

    /** Opens the right screen for [uris], or returns the message to show. */
    fun route(ctx: Context, nav: Nav, uris: List<Uri>): String? {
        if (uris.isEmpty()) return null
        val types = uris.map { ctx.contentResolver.getType(it).orEmpty() }
        if (uris.size > 1) {
            if (types.all { it.startsWith("image/") && it != "image/gif" }) { nav.push(Page.Photo(MediaDrafts.put(MediaSource.Images(uris)))); return null }
            return "Pick several photos to make frames, or one GIF, video or file."
        }
        val uri = uris[0]
        val type = types[0]
        return when {
            type == "image/gif" -> { nav.push(Page.Photo(MediaDrafts.put(MediaSource.GifFile(uri)))); null }
            type.startsWith("image/") -> { nav.push(Page.Photo(MediaDrafts.put(MediaSource.Images(listOf(uri))))); null }
            type.startsWith("video/") -> { nav.push(Page.Photo(MediaDrafts.put(MediaSource.Video(uri)))); null }
            type.startsWith("audio/") || type == "application/ogg" -> { nav.push(Page.GlyphRingtone(uri.toString())); null }
            else -> {
                val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size < 4_000_000 }?.decodeToString() } }.getOrNull()
                val anim = text?.let { runCatching { Animation.fromMuseumJson(AppGraph.profile, it) }.getOrNull() }
                if (anim == null) return CANT_READ
                val meta = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject }.getOrNull()
                val title = (meta?.get("title") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.take(30) ?: "Imported"
                val author = (meta?.get("author") as? kotlinx.serialization.json.JsonPrimitive)?.content
                nav.push(Page.Editor(null, anim.frames.size > 1, Drafts.put(title, anim, author)))
                null
            }
        }
    }
}

/** + New (decisions file: Still design and Animation stay separate rows). */
@Composable
fun NewSheet(nav: Nav, onDismiss: () -> Unit, startOnFiles: Boolean = false) {
    val ctx = LocalContext.current
    val snacks = rememberSnacks()
    var files by remember { mutableStateOf(startOnFiles) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        onDismiss()
        Picked.route(ctx, nav, uris)?.let { snacks?.show(it) }
    }
    val song = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        onDismiss()
        nav.push(Page.GlyphRingtone(uri.toString()))
    }
    val shot = remember { CameraShot(ctx) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        onDismiss()
        nav.push(Page.Photo(MediaDrafts.put(MediaSource.Images(listOf(shot.uri)))))
    }
    fun go(p: Page) { onDismiss(); nav.push(p) }
    FullSheet(onDismiss, if (files) "From a photo, GIF or file" else "New", confirm = null) {
        if (!files) {
            // each row shows what it makes, live on a small Glyph
            val p = AppGraph.profile
            val text = remember { com.dotforge.device.TextRender.render(p, com.dotforge.device.TextSpec("Hi")) }
            val photo = remember {
                val pic = com.dotforge.device.PhotoDots.samplePicture()
                Animation.single(com.dotforge.device.PhotoDots.render(p, com.dotforge.device.PhotoDots.sample(p, pic, com.dotforge.device.PhotoCrop()), com.dotforge.device.PhotoSettings()), 1000)
            }
            OptionRow("Still design", "One picture to draw", preview = { LibraryGlyph("heart", still = true) }) { go(Page.Editor(null, false)) }
            OptionRow("Animation", "Frames that play one after another", preview = { LibraryGlyph("ripple") }) { go(Page.Editor(null, true)) }
            OptionRow("Text", "Words that scroll, type or flash", preview = { GlyphPreview(text, "Text", size = PreviewSize.SMALL) }) { go(Page.TextDesign(null)) }
            OptionRow("From a photo, GIF or file", "Photos, GIFs, videos, Glyph Museum and Dot Hub files", preview = { GlyphPreview(photo, "Photo", size = PreviewSize.SMALL) }) { files = true }
            OptionRow("From the library", "Hundreds of designs to use or change", preview = { LibraryGlyph("star") }) { go(Page.Library) }
            OptionRow("Glyph ringtone", "Lights that follow a song", preview = { LibraryGlyph("pulse") }) { song.launch(arrayOf("audio/*", "application/ogg")) }
        } else {
            OptionRow("Choose photos or a file", "Several photos become frames", preview = { IconCircle(DfIcons.Photo) }) {
                pick.launch(arrayOf("image/*", "video/*", "application/json", "text/plain", "application/octet-stream", "audio/*", "application/ogg"))
            }
            OptionRow("Take a photo", "Full size, straight from the camera", preview = { IconCircle(DfIcons.Camera) }) {
                runCatching { camera.launch(shot.fresh()) }.onFailure { snacks?.show("Couldn't open the camera.") }
            }
            TextButton(onClick = { files = false }, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Back", color = Color.White) }
        }
    }
}

/** A full-size camera photo goes to a cache file (not the low-quality thumbnail). */
class CameraShot(private val ctx: Context) {
    var uri: Uri = Uri.EMPTY
        private set

    fun fresh(): Uri {
        val dir = File(ctx.cacheDir, "camera").also { it.mkdirs() }
        val f = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        uri = DesignShare.uri(ctx, f)
        return uri
    }
}

/**
 * "Use Heart for…" (row 39): any of your apps and people, the DotForge player toy (and Always-on through it) and
 * the Gallery, several at once.
 */
@Composable
fun UseForSheet(d: Design, nav: Nav, fresh: Boolean, onDismiss: () -> Unit) {
    val snacks = rememberSnacks()
    val rules = remember { AppGraph.rules.rules.value }
    // every alert that can show a design: phone calls and reminders, each app, phone moments; then people
    val people = rules.filter { SimpleRules.isPersonRule(it) }
    val alerts = rules.filter { it.action == com.dotforge.rules.Action.SHOW && !SimpleRules.isPersonRule(it) }
        .sortedBy { when { it.id == SimpleRules.PHONE_CALLS -> 0; it.id == SimpleRules.REMINDERS -> 1; it.moment != null -> 3; else -> 2 } }
    val chosen = remember { mutableStateListOf<String>().also { list -> rules.filter { it.glyph == GlyphStyle.DESIGN && it.designId == d.id }.forEach { list += it.id } } }
    var player by remember { mutableStateOf(AppGraph.toys.playerDesignId == d.id) }
    var gallery by remember { mutableStateOf(d.id in AppGraph.toys.galleryIds) }
    FullSheet(
        onDismiss, "Use ${d.name} for…", subtitle = if (fresh) "Saved. Pick where it shows, or Not now." else null,
        confirm = "Done",
        onConfirm = {
            var n = 0
            rules.forEach { r ->
                val on = r.id in chosen
                val was = r.glyph == GlyphStyle.DESIGN && r.designId == d.id
                if (on && !was) { AppGraph.rules.upsert(SimpleRules.withGlyph(r, GlyphChoice(GlyphStyle.DESIGN, d.id))); n++ }
                if (!on && was) AppGraph.rules.upsert(SimpleRules.withGlyph(r, DesignUse.defaultGlyph(r)))
            }
            if (player != (AppGraph.toys.playerDesignId == d.id)) AppGraph.toys.playerDesignId = if (player) d.id else null
            if (gallery != (d.id in AppGraph.toys.galleryIds)) AppGraph.toys.galleryIds = if (gallery) AppGraph.toys.galleryIds + d.id else AppGraph.toys.galleryIds - d.id
            val count = n + (if (player) 1 else 0) + (if (gallery) 1 else 0)
            if (count > 0) snacks?.show("${d.name} is in use")
            onDismiss()
        },
    ) {
        val using = alerts.count { it.id in chosen } + people.count { it.id in chosen } + (if (player) 1 else 0) + (if (gallery) 1 else 0)
        Hint(if (using == 0) "Not used anywhere yet. Tick where it should show." else "Ticked: where it shows now ($using).")
        SectionLabel("Apps and alerts")
        if (alerts.isEmpty()) Hint("No apps light up yet. Add one in Alerts.")
        alerts.forEach { r -> CheckRow(r.name, r.id in chosen) { if (it) chosen += r.id else chosen -= r.id } }
        SectionLabel("People")
        if (people.isEmpty()) Hint("No people yet. Add one in Alerts > People.")
        people.forEach { r -> CheckRow(r.name, r.id in chosen) { if (it) chosen += r.id else chosen -= r.id } }
        SectionLabel("Toys")
        CheckRow("DotForge player and Always-on", player) { player = it }
        CheckRow("Gallery", gallery) { gallery = it }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = Tokens.Small).heightIn(min = Tokens.Target)) { Text("Not now", color = Color.White) }
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Target + Tokens.Small).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Tokens.Secondary))
    }
}

/** Share… (row 38): GIF, DotForge file or PNG, to the share sheet or Downloads. Making a GIF shows progress. */
@Composable
fun ShareSheet(d: Design, onDismiss: () -> Unit, making: DesignShare.Kind? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snacks = rememberSnacks()
    var downloads by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(making) }
    var job by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf<DesignShare.Kind?>(null) }
    fun start(kind: DesignShare.Kind) {
        failed = null
        busy = kind
        job = scope.launch {
            val f = runCatching {
                withContext(Dispatchers.Default) {
                    val a = AppGraph.designs.animation(d) ?: error("unreadable")
                    DesignShare.write(ctx, d, a, kind)
                }
            }.getOrNull()
            busy = null
            when {
                f == null -> failed = kind
                downloads -> { snacks?.show(if (DesignShare.download(ctx, f)) "Saved ${f.name} to Downloads" else "Couldn't save to Downloads."); onDismiss() }
                else -> { runCatching { DesignShare.send(ctx, f, d.name) }; onDismiss() }
            }
        }
    }
    FullSheet(onDismiss, "Share ${d.name}", confirm = null) {
        val b = busy
        if (b != null) {
            Text(if (b == DesignShare.Kind.GIF) "Making GIF…" else "Getting it ready…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Small))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Tokens.Gap), color = Color.White, trackColor = Tokens.Raised)
            TextButton(onClick = { job?.cancel(); busy = null }, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Cancel", color = Color.White) }
            return@FullSheet
        }
        failed?.let { k -> Text(if (k == DesignShare.Kind.GIF) "Couldn't make the GIF. Try again." else "Couldn't make the file. Try again.", color = Tokens.AccentText, style = MaterialTheme.typography.bodyMedium) }
        DesignShare.Kind.entries.forEach { k -> OptionRow(k.title, k.description) { start(k) } }
        SwitchRow("Save to Downloads instead", null, downloads, inset = 0.dp) { downloads = it }
    }
}
