package com.dotforge.ui.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.Gif
import com.dotforge.device.PhotoBackground
import com.dotforge.device.PhotoCrop
import com.dotforge.device.PhotoDots
import com.dotforge.device.PhotoMotion
import com.dotforge.device.PhotoSettings
import com.dotforge.device.PhotoStyle
import com.dotforge.device.Picture
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DISABLED_ALPHA
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.DotSlider
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.Segmented
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.capped
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.editor.Drafts
import com.dotforge.ui.editor.OptionChip
import com.dotforge.ui.studio.MediaLoader.toPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * One photo (or several, a GIF or a video) on its way to dots: the pictures, their frame times and the settings,
 * with an undo stack of its own. Settings changes from one slider drag count as one step.
 */
class PhotoSession(
    val profile: DeviceProfile,
    val name: String,
    val pictures: List<Picture>,
    /** Each picture's time as a frame (several photos: 500 ms; a GIF or video: its own). */
    val delays: List<Int>,
    start: PhotoSettings = PhotoSettings(),
    /** The saved photo design being re-tuned, or null. */
    val existing: Design? = null,
) {
    var settings by mutableStateOf(start)
        private set
    private val undos = ArrayList<PhotoSettings>()
    private val redos = ArrayList<PhotoSettings>()
    private var lastKey: String? = null
    var version by mutableIntStateOf(0)
        private set

    val frames: Boolean get() = pictures.size > 1
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()
    val changed: Boolean get() = undos.isNotEmpty()

    /** A change; [key] names the control so one drag of one slider is one undo step. */
    fun set(s: PhotoSettings, key: String? = null) {
        val next = s.clamped()
        if (next == settings) return
        if (key == null || key != lastKey) { undos += settings; if (undos.size > 100) undos.removeAt(0) }
        redos.clear()
        lastKey = key
        settings = next
        version++
    }

    fun undo() {
        val prev = undos.removeLastOrNull() ?: return
        redos += settings
        settings = prev
        lastKey = null
        version++
    }

    fun redo() {
        val next = redos.removeLastOrNull() ?: return
        undos += settings
        settings = next
        lastKey = null
        version++
    }

    // samples depend only on the crop: kept per picture so style and adjust changes are instant
    private val samples = HashMap<Int, Pair<PhotoCrop, DoubleArray>>()

    private fun samplesOf(i: Int, crop: PhotoCrop): DoubleArray = synchronized(samples) {
        samples[i]?.takeIf { it.first == crop }?.second ?: PhotoDots.sample(profile, pictures[i], crop).also { samples[i] = crop to it }
    }

    fun frame(i: Int = 0, s: PhotoSettings = settings): Frame = PhotoDots.render(profile, samplesOf(i, s.crop), s)

    /** What it becomes: one frame, or a frame per picture. */
    fun render(s: PhotoSettings = settings): Animation =
        if (!frames) Animation.single(frame(0, s), STILL_MS)
        else Animation(profile, pictures.indices.map { i -> AnimFrame(frame(i, s), delays[i]) })

    fun motion(m: PhotoMotion, count: Int, frameMs: Int): Animation = PhotoDots.motion(profile, pictures[0], settings, m, count, frameMs)

    companion object {
        const val STILL_MS = 1000
        const val PHOTO_FRAME_MS = 500
    }
}

/** The Photo screen's states while a file is read. */
sealed interface PhotoLoad {
    data class Loading(val text: String, val fraction: Float? = null) : PhotoLoad
    data class Failed(val text: String) : PhotoLoad
    class Ready(val session: PhotoSession) : PhotoLoad
}

/** The Photo screen's tabs, and what a test opens it on. */
enum class PhotoTab(val label: String) { CROP("Crop"), STYLE("Style"), ADJUST("Adjust") }

/** The Adjust tab's controls: one is shown at a time so the screen never scrolls. */
enum class PhotoControl(val label: String) {
    BRIGHTNESS("Brightness"), CONTRAST("Contrast"), MIDTONES("Midtones"), DETAIL("Detail"), SHADES("Shades"),
    THRESHOLD("Threshold"), BACKGROUND("Background"), INVERT("Invert"),
}

object PhotoLoader {
    /** Reads [source]; [progress] gets "Converting… 12 of 48 frames" as it goes. */
    suspend fun load(ctx: Context, source: MediaSource, progress: (PhotoLoad.Loading) -> Unit): PhotoSession {
        val p = AppGraph.profile
        return when (source) {
            is MediaSource.Pictures -> PhotoSession(p, source.name, source.pictures, List(source.pictures.size) { PhotoSession.PHOTO_FRAME_MS })
            is MediaSource.Images -> {
                val n = source.uris.size
                val pics = source.uris.mapIndexed { i, u ->
                    coroutineContext.ensureActive()
                    if (n > 1) progress(PhotoLoad.Loading("Reading ${i + 1} of $n photos…", i / n.toFloat()))
                    MediaLoader.picture(ctx, u)
                }
                PhotoSession(p, MediaLoader.name(ctx, source.uris[0]), pics, List(n) { PhotoSession.PHOTO_FRAME_MS })
            }
            is MediaSource.GifFile -> {
                val bytes = ctx.contentResolver.openInputStream(source.uri)?.use { it.readBytes() } ?: throw MediaLoader.CantRead("This GIF can't be read. Try another file.")
                val total = Gif.count(bytes).coerceIn(1, 300)
                val ctxJob = coroutineContext
                val frames = try {
                    Gif.decode(bytes) { i -> progress(PhotoLoad.Loading("Converting… $i of $total frames", i / total.toFloat())); ctxJob.isActiveCompat() }
                } catch (e: Gif.GifException) {
                    throw MediaLoader.CantRead("This GIF can't be read. Try another file.")
                }
                coroutineContext.ensureActive()
                if (frames.isEmpty()) throw MediaLoader.CantRead("This GIF can't be read. Try another file.")
                PhotoSession(p, MediaLoader.name(ctx, source.uri, "GIF"), frames.map { it.picture.shrunk() }, frames.map { it.delayMs.coerceIn(20, 10_000) })
            }
            is MediaSource.Video -> {
                val ctxJob = coroutineContext
                val frames = MediaLoader.video(ctx, source.uri) { i, n -> progress(PhotoLoad.Loading("Converting… $i of $n frames", i / n.toFloat())); ctxJob.isActiveCompat() }
                coroutineContext.ensureActive()
                PhotoSession(p, MediaLoader.name(ctx, source.uri, "Video"), frames.map { it.picture }, frames.map { it.delayMs })
            }
        }
    }

    /** A saved photo design, to re-tune: its kept photo and settings. */
    fun reopen(d: Design): PhotoSession {
        val file = d.photoFile?.let { File(AppGraph.designs.media, it) }
        val bmp = file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } ?: throw MediaLoader.CantRead("The photo for ${d.name} is missing. Open it in the editor instead.")
        return PhotoSession(AppGraph.profile, d.name, listOf(bmp.toPicture()), listOf(PhotoSession.STILL_MS), d.photo ?: PhotoSettings(), d)
    }

    /** Keeps [pic] in the designs' media folder; returns its file name. */
    fun keep(pic: Picture, id: String): String {
        val name = "$id.png"
        AppGraph.designs.media.mkdirs()
        val bmp = Bitmap.createBitmap(pic.argb, pic.width, pic.height, Bitmap.Config.ARGB_8888)
        File(AppGraph.designs.media, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveCompat() = this[kotlinx.coroutines.Job]?.isActive != false

    /** GIF frames can be big: kept at most 400 px on the long side. */
    private fun Picture.shrunk(max: Int = 400): Picture {
        if (width <= max && height <= max) return this
        val k = max.toFloat() / maxOf(width, height)
        val w = (width * k).roundToInt().coerceAtLeast(1)
        val h = (height * k).roundToInt().coerceAtLeast(1)
        return Picture(w, h, IntArray(w * h) { i -> argb[((i / w) / k).toInt().coerceAtMost(height - 1) * width + ((i % w) / k).toInt().coerceAtMost(width - 1)] })
    }
}

/**
 * Photo to dots opened from the editor ("Add as frame"): the result goes into the open design after frame
 * [after] (0-based, the current frame) instead of becoming a new design. [onAdd] gets the frames; [onClose] leaves.
 */
class PhotoTarget(val after: Int, val onAdd: (Animation) -> Unit, val onClose: () -> Unit)

/**
 * Photo to dots (production-design): full screen, no scroll. A big preview with the round LED mask (pinch and
 * drag to crop), tabs Crop · Style · Adjust, and Cancel / Animate / Edit / Save. Undo, Redo and Preview on Glyph
 * sit in the top bar. Several photos, a GIF or a video become frames and use the same panel.
 */
@Composable
fun PhotoScreen(
    key: String?, designId: String?, nav: Nav, startTab: PhotoTab = PhotoTab.STYLE, startLoad: PhotoLoad? = null, startAnimate: Boolean = false,
    target: PhotoTarget? = null,
) {
    val ctx = LocalContext.current
    val state by produceState(startLoad ?: PhotoLoad.Loading("Reading the photo…")) {
        if (startLoad != null) return@produceState
        value = try {
            val d = AppGraph.designs.get(designId)
            val s = if (d != null) withContext(Dispatchers.IO) { PhotoLoader.reopen(d) }
            else {
                val src = MediaDrafts.get(key) ?: throw MediaLoader.CantRead(Picked.CANT_READ)
                withContext(Dispatchers.IO) { PhotoLoader.load(ctx, src) { value = it } }
            }
            PhotoLoad.Ready(s)
        } catch (e: MediaLoader.CantRead) {
            PhotoLoad.Failed(e.message ?: Picked.CANT_READ)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            PhotoLoad.Failed(Picked.CANT_READ)
        }
    }
    fun leave() { MediaDrafts.drop(key); if (target != null) target.onClose() else nav.pop() }
    BackHandler(onBack = ::leave)
    UndoSnackbarHost {
        Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val st = state) {
                is PhotoLoad.Loading -> {
                    PhotoTopBar(null, onClose = ::leave)
                    Column(Modifier.weight(1f).fillMaxWidth().padding(Tokens.Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(st.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (st.fraction != null) LinearProgressIndicator(progress = { st.fraction }, Modifier.fillMaxWidth().padding(top = Tokens.Gap), color = Color.White, trackColor = Tokens.Raised)
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Tokens.Gap), color = Color.White, trackColor = Tokens.Raised)
                        SecondaryButton("Cancel", Modifier.padding(top = Tokens.Section), onClick = ::leave)
                    }
                }
                is PhotoLoad.Failed -> {
                    PhotoTopBar(null, onClose = ::leave)
                    Column(Modifier.weight(1f).fillMaxWidth().padding(Tokens.Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(DfIcons.Photo, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(48.dp))
                        Text(st.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Gap))
                        SecondaryButton("Close", Modifier.padding(top = Tokens.Section), onClick = ::leave)
                    }
                }
                is PhotoLoad.Ready -> PhotoEditor(st.session, nav, startTab, startAnimate, target, onLeave = ::leave, onDone = { MediaDrafts.drop(key) })
            }
        }
    }
}

@Composable
private fun PhotoTopBar(s: PhotoSession?, onClose: () -> Unit, glyph: Boolean = false, onGlyph: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
        Text(s?.name ?: "Photo", style = MaterialTheme.typography.titleMedium.capped(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = Tokens.Tiny))
        if (s != null) {
            @Suppress("UNUSED_EXPRESSION") s.version
            BarIcon(DfIcons.Undo, "Undo", s.canUndo) { s.undo() }
            BarIcon(DfIcons.Redo, "Redo", s.canRedo) { s.redo() }
            IconButton(onClick = onGlyph, modifier = Modifier.semantics { contentDescription = "Preview on Glyph"; stateDescription = if (glyph) "On" else "Off" }) {
                Box(Modifier.size(36.dp).background(if (glyph) Color.White else Color.Transparent, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Icon(DfIcons.OnPhone, contentDescription = null, tint = if (glyph) Color.Black else Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) =
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = if (enabled) label else "$label, unavailable" }) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else DISABLED_ALPHA))
    }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PhotoEditor(
    s: PhotoSession, nav: Nav, startTab: PhotoTab, startAnimate: Boolean, target: PhotoTarget?, onLeave: () -> Unit, onDone: () -> Unit,
) {
    var tab by remember { mutableStateOf(startTab) }
    var control by remember { mutableStateOf(PhotoControl.BRIGHTNESS) }
    var glyph by remember { mutableStateOf(true) }
    var animate by remember { mutableStateOf(startAnimate) }
    val p = s.profile
    val result by produceState(remember { s.render() }, s.settings) {
        delay(16)
        value = withContext(Dispatchers.Default) { s.render() }
    }
    LaunchedEffect(result, glyph) {
        if (!glyph) { AppGraph.arbiter.remove(PHONE_ID); return@LaunchedEffect }
        delay(100)
        AppGraph.arbiter.post(DisplayRequest(PHONE_ID, Priority.STUDIO_PREVIEW, result, loop = true, label = "Photo: ${s.name}"))
    }
    DisposableEffect(Unit) { onDispose { AppGraph.arbiter.remove(PHONE_ID) } }

    fun save() {
        val id = s.existing?.id ?: UUID.randomUUID().toString()
        val a = result
        val d = if (!s.frames) Design(
            id = id, name = s.name, animated = false, frames = a.toMuseumJson(), updatedAt = System.currentTimeMillis(), kind = DesignKind.PHOTO,
            photo = s.settings, photoFile = s.existing?.photoFile ?: runCatching { PhotoLoader.keep(s.pictures[0], id) }.getOrNull(),
            timing = s.existing?.timing,
        ) else Design(id = id, name = s.name, animated = true, frames = a.toMuseumJson(), updatedAt = System.currentTimeMillis())
        AppGraph.designs.update(d)
        onDone()
        nav.pop()
        nav.studioRequest = if (s.existing == null) "usefor:$id" else "saved:$id"
    }
    fun edit(a: Animation) {
        onDone()
        if (target != null) { target.onAdd(a); return }
        nav.pop()
        nav.push(Page.Editor(null, a.frames.size > 1, Drafts.put(s.name, a)))
    }

    PhotoTopBar(s, onClose = onLeave, glyph = glyph) { glyph = !glyph }
    // the preview takes what the pinned rows leave
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - Tokens.Gutter * 2, maxHeight - 24.dp, 360.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val cropping = tab == PhotoTab.CROP
            val sidePx = with(LocalDensity.current) { side.toPx() }
            Box(
                Modifier.size(side).then(
                    if (cropping) Modifier.pointerInput(s) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val c = s.settings.crop
                            val z = (c.zoom * zoom).coerceIn(1f, 8f)
                            s.set(s.settings.copy(crop = c.copy(zoom = z, cx = c.cx - pan.x / sidePx / z, cy = c.cy - pan.y / sidePx / z)), key = "crop")
                        }
                    } else Modifier,
                ),
            ) {
                if (s.frames) GlyphPreview(result, s.name, Modifier.fillMaxSize(), userPlay = true)
                else DotMatrix(result.frames[0].frame.pixels, p, Modifier.fillMaxSize().semantics { contentDescription = "${s.name} as dots" })
            }
            Text(
                when {
                    cropping && !s.frames -> "Pinch and drag to frame it"
                    target != null -> if (s.frames) "${result.frames.size} frames go in after frame ${target.after + 1}" else "Goes in after frame ${target.after + 1}"
                    s.frames -> "${result.frames.size} frames · ${StudioText.seconds(result.totalMs)}"
                    else -> s.settings.style.label
                },
                style = MaterialTheme.typography.bodySmall.capped(), color = Tokens.Secondary, maxLines = 1, modifier = Modifier.height(24.dp).padding(top = 4.dp),
            )
        }
    }
    Box(Modifier.padding(horizontal = Tokens.Gutter)) {
        Segmented(PhotoTab.entries.map { it.label }, tab.ordinal) { tab = PhotoTab.entries[it] }
    }
    Box(Modifier.fillMaxWidth().height(PANEL)) {
        when (tab) {
            PhotoTab.CROP -> CropPanel(s)
            PhotoTab.STYLE -> StylePanel(s)
            PhotoTab.ADJUST -> AdjustPanel(s, control) { control = it }
        }
    }
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = Tokens.Small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
        BarButton("Cancel", false, onClick = onLeave)
        if (!s.frames) BarButton("Animate", false) { animate = true }
        if (target != null) BarButton(if (s.frames) "Add ${result.frames.size} frames" else "Add as frame", true) { edit(result) }
        else {
            BarButton("Edit", false) { edit(result) }
            BarButton("Save", true) { save() }
        }
    }
    if (animate) AnimateSheet(s, onDismiss = { animate = false }, adding = target != null, onEdit = { a -> animate = false; edit(a) })
}

private const val PHONE_ID = "photo"
private val PANEL = 132.dp

/** A bottom-bar button: widths follow the labels (so "Animate" fits at 360 dp), labels capped at 1.3x font. */
@Composable
private fun RowScope.BarButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val fs = LocalDensity.current.fontScale
    val style = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = it.fontSize * (minOf(fs, 1.3f) / fs)) }
    Button(
        onClick = onClick, modifier = Modifier.weight(label.length + 3f).heightIn(min = Tokens.Target),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Tokens.Accent else if (label == "Cancel") Color.Transparent else Tokens.Raised, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) { Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun CropPanel(s: PhotoSession) {
    val c = s.settings.crop
    fun crop(n: PhotoCrop) = s.set(s.settings.copy(crop = n))
    Column(Modifier.fillMaxSize().padding(top = Tokens.Small)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            OptionChip("Rotate", "Rotate 90 degrees", icon = DfIcons.Rotate) { crop(c.copy(turns = c.turns + 1)) }
            OptionChip(null, "Flip left-right", icon = DfIcons.FlipH, selected = c.flipH) { crop(c.copy(flipH = !c.flipH)) }
            OptionChip(null, "Flip top-bottom", icon = DfIcons.FlipV, selected = c.flipV) { crop(c.copy(flipV = !c.flipV)) }
            OptionChip("Fill", "Fill the circle", selected = !c.fit) { crop(c.copy(fit = false)) }
            OptionChip("Fit", "Fit the whole photo", selected = c.fit) { crop(c.copy(fit = true)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom ${"%.1f".format(c.zoom)}x", style = MaterialTheme.typography.bodyMedium.capped(), maxLines = 1, modifier = Modifier.width(96.dp))
            Box(Modifier.weight(1f)) { DotSlider("Zoom", c.zoom, 1f..8f, readout = "${"%.1f".format(c.zoom)}x") { s.set(s.settings.copy(crop = c.copy(zoom = it)), key = "zoom") } }
            androidx.compose.material3.TextButton(onClick = { crop(PhotoCrop()) }, enabled = c != PhotoCrop()) { Text("Reset", style = LocalTextStyle.current.capped(), color = if (c != PhotoCrop()) Color.White else Color.White.copy(alpha = DISABLED_ALPHA)) }
        }
    }
}

/** 13 live thumbnails: this photo in each style. */
@Composable
private fun StylePanel(s: PhotoSession) {
    val thumbs by produceState<Map<PhotoStyle, Frame>>(emptyMap(), s.settings.crop, s.settings.copy(style = PhotoStyle.SHADES)) {
        value = withContext(Dispatchers.Default) { PhotoStyle.entries.associateWith { st -> s.frame(0, s.settings.copy(style = st)) } }
    }
    LazyRow(
        Modifier.fillMaxSize().padding(top = Tokens.Small), horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Tokens.Gutter),
    ) {
        items(PhotoStyle.entries.toList(), key = { it.name }) { st ->
            val on = s.settings.style == st
            Column(
                Modifier.width(80.dp).clickable(role = Role.RadioButton, onClickLabel = st.label) { s.set(s.settings.copy(style = st)) }
                    .semantics { stateDescription = if (on) "Selected" else "" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(76.dp).border(if (on) 2.dp else 1.dp, if (on) Color.White else Tokens.Hairline, RoundedCornerShape(14.dp)).padding(6.dp)) {
                    DotMatrix(thumbs[st]?.pixels, s.profile, Modifier.fillMaxSize())
                }
                val fs = LocalDensity.current.fontScale
                val style = MaterialTheme.typography.labelSmall.let { it.copy(fontSize = it.fontSize * (minOf(fs, 1.3f) / fs)) }
                Text(st.label, style = style, color = if (on) Color.White else Tokens.Secondary, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** One control at a time: pick it from the chips, then its slider (or chips) and Reset. */
@Composable
private fun AdjustPanel(s: PhotoSession, control: PhotoControl, onControl: (PhotoControl) -> Unit) {
    val st = s.settings
    val d = PhotoSettings()
    Column(Modifier.fillMaxSize().padding(top = Tokens.Small)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            PhotoControl.entries.forEach { c ->
                val changed = when (c) {
                    PhotoControl.BRIGHTNESS -> st.brightness != d.brightness; PhotoControl.CONTRAST -> st.contrast != d.contrast
                    PhotoControl.MIDTONES -> st.midtones != d.midtones; PhotoControl.DETAIL -> st.detail != d.detail
                    PhotoControl.SHADES -> st.shades != d.shades; PhotoControl.THRESHOLD -> st.threshold != d.threshold
                    PhotoControl.BACKGROUND -> st.background != d.background; PhotoControl.INVERT -> st.invert != d.invert
                }
                OptionChip(if (changed) "${c.label} •" else c.label, if (changed) "${c.label}, changed" else c.label, selected = control == c) { onControl(c) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
            when (control) {
                PhotoControl.BACKGROUND -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    PhotoBackground.entries.forEach { b -> Chip(b.label, st.background == b) { s.set(st.copy(background = b)) } }
                }
                PhotoControl.INVERT -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Chip("Off", !st.invert) { s.set(st.copy(invert = false)) }
                    Chip("On", st.invert) { s.set(st.copy(invert = true)) }
                }
                else -> {
                    val (value, range) = when (control) {
                        PhotoControl.BRIGHTNESS -> st.brightness to -100..100; PhotoControl.CONTRAST -> st.contrast to -100..100
                        PhotoControl.MIDTONES -> st.midtones to -100..100; PhotoControl.DETAIL -> st.detail to 0..100
                        PhotoControl.SHADES -> st.shades to 2..16; else -> st.threshold to 0..100
                    }
                    val usable = when (control) {
                        PhotoControl.SHADES -> st.style == PhotoStyle.SOFT_SHADES
                        PhotoControl.THRESHOLD -> st.style in setOf(PhotoStyle.TONES2, PhotoStyle.SILHOUETTE, PhotoStyle.OUTLINE_FILL, PhotoStyle.POP)
                        else -> true
                    }
                    Text(if (value > 0 && range.first < 0) "+$value" else "$value", style = MaterialTheme.typography.bodyLarge.capped(), maxLines = 1, modifier = Modifier.width(48.dp))
                    Box(Modifier.weight(1f).then(if (usable) Modifier else Modifier.background(Color.Transparent))) {
                        DotSlider(control.label, value.toFloat(), range.first.toFloat()..range.last.toFloat(), readout = "$value", mark = if (range.first < 0) 0f else null) { v ->
                            val n = v.roundToInt()
                            s.set(
                                when (control) {
                                    PhotoControl.BRIGHTNESS -> st.copy(brightness = n); PhotoControl.CONTRAST -> st.copy(contrast = n)
                                    PhotoControl.MIDTONES -> st.copy(midtones = n); PhotoControl.DETAIL -> st.copy(detail = n)
                                    PhotoControl.SHADES -> st.copy(shades = n); else -> st.copy(threshold = n)
                                },
                                key = control.name,
                            )
                        }
                    }
                }
            }
            val reset = when (control) {
                PhotoControl.BRIGHTNESS -> st.copy(brightness = d.brightness); PhotoControl.CONTRAST -> st.copy(contrast = d.contrast)
                PhotoControl.MIDTONES -> st.copy(midtones = d.midtones); PhotoControl.DETAIL -> st.copy(detail = d.detail)
                PhotoControl.SHADES -> st.copy(shades = d.shades); PhotoControl.THRESHOLD -> st.copy(threshold = d.threshold)
                PhotoControl.BACKGROUND -> st.copy(background = d.background); PhotoControl.INVERT -> st.copy(invert = d.invert)
            }
            androidx.compose.material3.TextButton(onClick = { s.set(reset) }, enabled = reset != st, modifier = Modifier.semantics { contentDescription = "Reset ${control.label.lowercase()}" }) {
                Text("Reset", style = LocalTextStyle.current.capped(), color = if (reset != st) Color.White else Color.White.copy(alpha = DISABLED_ALPHA))
            }
        }
        val note = when (control) {
            PhotoControl.SHADES -> if (st.style != PhotoStyle.SOFT_SHADES) "Used by Soft shades" else null
            PhotoControl.THRESHOLD -> if (st.style !in setOf(PhotoStyle.TONES2, PhotoStyle.SILHOUETTE, PhotoStyle.OUTLINE_FILL, PhotoStyle.POP)) "Used by 2 tones, Silhouette, Outline + fill and Pop" else null
            PhotoControl.DETAIL -> "Sharpens edges and small shapes"
            PhotoControl.BACKGROUND -> "Darkens or removes what's round the subject"
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, modifier = Modifier.padding(horizontal = Tokens.Gutter)) }
    }
}

/** Animate: the photo moving (zoom, pan, fade in, reveal, pulse, glitch) as frames. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimateSheet(s: PhotoSession, onDismiss: () -> Unit, adding: Boolean = false, onEdit: (Animation) -> Unit) {
    var motion by remember { mutableStateOf(PhotoMotion.ZOOM) }
    var count by remember { mutableFloatStateOf(16f) }
    var ms by remember { mutableFloatStateOf(90f) }
    val a by produceState<Animation?>(null, motion, count.roundToInt(), ms.roundToInt()) {
        delay(120)
        value = withContext(Dispatchers.Default) { s.motion(motion, count.roundToInt(), ms.roundToInt()) }
    }
    FullSheet(onDismiss, "Animate", subtitle = a?.let { "${it.frames.size} frames · ${StudioText.seconds(it.totalMs)}" }, confirm = null, pinned = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GlyphPreview(a, motion.label, size = PreviewSize.MEDIUM, userPlay = true) }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            PhotoMotion.entries.forEach { m -> Chip(m.label, motion == m) { motion = m } }
        }
        Text("Frames: ${count.roundToInt()}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
        DotSlider("Frames", count, 8f..32f, readout = "${count.roundToInt()} frames") { count = it }
        Text("Each frame: ${ms.roundToInt()} ms", style = MaterialTheme.typography.bodyLarge)
        DotSlider("Frame time", ms, 40f..300f, readout = "${ms.roundToInt()} milliseconds") { ms = (it / 10).roundToInt() * 10f }
        if (motion == PhotoMotion.GLITCH && ms < 80) Hint("Flashes quickly")
        PrimaryButton(if (adding) "Add ${count.roundToInt()} frames" else "Open in editor", Modifier.fillMaxWidth().padding(top = Tokens.Gap), enabled = a != null) { a?.let(onEdit) }
    }
}
