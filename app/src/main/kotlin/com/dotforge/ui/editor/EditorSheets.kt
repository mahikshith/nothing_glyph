package com.dotforge.ui.editor

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import androidx.compose.ui.draw.clip
import com.dotforge.studio.TracePhoto
import com.dotforge.device.Animation
import com.dotforge.device.BackgroundEffect
import com.dotforge.device.BackgroundLayer
import com.dotforge.device.BehindText
import com.dotforge.device.Brush
import com.dotforge.device.BrushMode
import com.dotforge.device.DotFont
import com.dotforge.device.Frame
import com.dotforge.device.FrameOp
import com.dotforge.device.LayerKind
import com.dotforge.device.Layers
import com.dotforge.device.MoveEffect
import com.dotforge.device.PhotoDots
import com.dotforge.device.PhotoSettings
import com.dotforge.device.Posters
import com.dotforge.device.TextBand
import com.dotforge.device.TextLayer
import com.dotforge.device.TextSpec
import com.dotforge.device.TextStyle
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.ui.Nav
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.DotSlider
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.LocalReduceMotion
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.Segmented
import com.dotforge.ui.design.Snacks
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.capped
import com.dotforge.ui.design.edgeFade
import androidx.compose.ui.semantics.heading
import com.dotforge.ui.studio.MediaLoader
import com.dotforge.ui.studio.StudioText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private fun pct(v: Int) = (v * 100 + 127) / 255

// ---------------------------------------------------------------- Effects

/**
 * Effects (row 12): "Change it" (rotate, flip, invert, shift, brightness, outline, fill in, levels) on this frame
 * or all frames, Replace shade, and "Make it move" with Strength and Length and a live preview. Every change is
 * one undo step.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectsSheet(s: EditorSession, onDismiss: () -> Unit, snacks: Snacks?) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    var all by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf<MoveEffect?>(null) }
    var strength by remember { mutableFloatStateOf(1f) }
    var length by remember { mutableFloatStateOf(1f) }
    val p = AppGraph.profile
    val frame = Frame.of(p, s.doc.current)
    val moving = remember(move, strength, length, s.tick) { move?.make(frame, strength, length) }
    FullSheet(
        onDismiss, "Effects",
        pinned = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter)) {
                if (moving != null) GlyphPreview(moving, move!!.label, size = PreviewSize.MEDIUM, userPlay = true)
                else DotMatrix(s.doc.current, p, Modifier.size(Tokens.PreviewMedium).semantics { contentDescription = "Frame ${s.doc.cur + 1}" })
                Column(Modifier.weight(1f)) {
                    Text(if (moving != null) move!!.label else "Frame ${s.doc.cur + 1}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (moving != null) "${moving.frames.size} frames · ${StudioText.seconds(moving.totalMs)}" else if (s.doc.size > 1) "of ${s.doc.size}" else "Changes show here",
                        style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary,
                    )
                }
            }
        },
    ) {
        // one scope for the whole sheet, at the top: Change it and Replace shade both follow it
        if (s.doc.size > 1) {
            SectionLabel("Apply to")
            Segmented(listOf("This frame", "All frames"), if (all) 1 else 0) { all = it == 1 }
            Hint("For Change it and Replace shade. Make it move works on this frame.")
        }
        SectionLabel("Change it")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            FrameOp.entries.forEach { op ->
                Chip(op.label, false) {
                    s.edit { change(op, all) }
                    snacks?.show(if (all) "${op.label}: all frames" else "${op.label}: frame ${s.doc.cur + 1}") { s.edit { undo() } }
                }
            }
        }
        ReplaceShade(s, all)

        SectionLabel("Make it move", Modifier.padding(top = Tokens.Section))
        Hint("Turns frame ${s.doc.cur + 1} into moving frames. Undo takes it back.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            MoveEffect.entries.forEach { m -> Chip(m.label, move == m) { move = if (move == m) null else m } }
        }
        if (move != null) {
            // labels above their sliders, as everywhere else
            Text("Strength ${(strength * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Small))
            DotSlider("Strength", strength, 0.2f..1f, readout = "${(strength * 100).roundToInt()}%") { strength = it }
            Text("Length ${"%.1f".format(length)}x", style = MaterialTheme.typography.bodyLarge)
            DotSlider("Length", length, 0.5f..2f, readout = "${"%.1f".format(length)}x", mark = 1f) { length = it }
            if (move == MoveEffect.BLINK && length < 1f) Hint("Flashes quickly")
            val a = moving
            PrimaryButton(
                if (a == null) "Apply" else "Apply · ${a.frames.size} frames",
                Modifier.fillMaxWidth().padding(top = Tokens.Gap), enabled = a != null,
            ) {
                val made = a ?: return@PrimaryButton
                s.animatedView = true
                s.edit { replaceCurrentWith(made) }
                onDismiss()
                snacks?.show("${move!!.label}: added ${made.frames.size} frames") { s.edit { undo() } }
            }
        }
    }
}

/**
 * Replace shade, all in this sheet: pick the shade to replace (from those in the frame, or in every frame with
 * All frames), pick the new one, and apply to This frame or All frames (the sheet's one scope, at the top).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplaceShade(s: EditorSession, all: Boolean) {
    val present = remember(s.tick, all) {
        (if (all) s.doc.frames.flatMap { f -> f.filter { it > 0 } } else s.doc.current.filter { it > 0 }).distinct().sortedDescending().take(12)
    }
    if (present.isEmpty()) return
    var from by remember { mutableIntStateOf(present.firstOrNull { it != s.brush.value } ?: present.first()) }
    if (from !in present) from = present.first()
    var to by remember { mutableIntStateOf(s.brush.value) }
    SectionLabel("Replace shade", Modifier.padding(top = Tokens.Section))
    Text("Replace", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
        present.forEach { v -> ShadeSwatch(v, from == v) { from = v } }
    }
    Text("With", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
        (Brush.SHADES + 0).forEach { v -> ShadeSwatch(v, to == v, off = v == 0) { to = v } }
    }
    val where = if (all && s.doc.size > 1) "all frames" else "this frame"
    SecondaryButton(
        if (from != to) "Replace ${pct(from)}% with ${if (to == 0) "off" else "${pct(to)}%"} · $where" else "Pick a different shade to replace it with",
        Modifier.fillMaxWidth().padding(top = Tokens.Small), enabled = from != to,
    ) { s.edit { replaceShade(from, to, all) } }
}

@Composable
private fun ShadeSwatch(value: Int, selected: Boolean, off: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(56.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (off) "Off" else "Shade ${pct(value)}%"; stateDescription = if (selected) "Selected" else "" }
            .padding(vertical = Tokens.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape).padding(5.dp)
                .background(if (off) Tokens.DotOff else Color.White.copy(alpha = 0.25f + 0.75f * value / 255f), CircleShape),
        )
        Text(if (off) "Off" else "${pct(value)}%", style = MaterialTheme.typography.labelSmall.capped(), maxLines = 1, color = if (selected) Color.White else Tokens.Secondary)
    }
}

// ---------------------------------------------------------------- shade

/** The brush's shade: 8 swatches, a fine slider and Pick (row 20). Dots show at matching greys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShadeSheet(s: EditorSession, onDismiss: () -> Unit) {
    val b = s.brush
    FullSheet(onDismiss, "Shade", subtitle = "${pct(b.value)}%", half = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
            Brush.SHADES.forEach { v -> ShadeSwatch(v, b.value == v) { s.brush = b.copy(value = v, mode = BrushMode.PAINT) } }
        }
        DotSlider("Shade", b.value.toFloat(), 8f..255f, readout = "${pct(b.value)}%") { s.brush = b.copy(value = it.roundToInt(), mode = BrushMode.PAINT) }
        OptionRow("Pick from the matrix", "Tap a dot to use its shade", preview = { Icon(com.dotforge.ui.design.DfIcons.Pick, null, tint = Color.White) }) {
            s.picking = true
            onDismiss()
        }
    }
}

// ---------------------------------------------------------------- Frame menu

/** The current frame: duration (typed or slider, 20 ms-10 s), Duplicate, Move left/right, Delete, and Set all frames. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrameMenuSheet(s: EditorSession, onDismiss: () -> Unit, snacks: Snacks?) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val i = s.doc.cur
    var ms by remember(i) { mutableIntStateOf(s.doc.durations[i]) }
    var typed by remember(i) { mutableStateOf(ms.toString()) }
    // the duration is one undo step, written when the sheet closes or another action runs
    fun commit() { if (s.doc.durations.getOrNull(s.doc.cur) != ms) s.edit { setDuration(cur, ms) } }
    FullSheet({ commit(); onDismiss() }, "Frame ${i + 1}", subtitle = "of ${s.doc.size}") {
        SectionLabel("Show this frame for")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                typed, { t -> typed = t.filter(Char::isDigit).take(5); typed.toIntOrNull()?.let { ms = it.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS) } },
                Modifier.width(120.dp), singleLine = true, suffix = { Text("ms") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            )
            if (ms >= 1000) Text(StudioText.seconds(ms), color = Tokens.Secondary, modifier = Modifier.padding(start = Tokens.Gap))
        }
        // a log slider: fine steps at short durations, 20 ms to 10 s
        val lo = ln(Animation.MIN_FRAME_MS.toFloat())
        val hi = ln(10_000f)
        DotSlider("Duration", ln(ms.coerceIn(20, 10_000).toFloat()), lo..hi, readout = "$ms milliseconds") { v ->
            ms = snap(exp(v).roundToInt())
            typed = ms.toString()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            listOf(50, 100, 200, 500, 1000).forEach { v -> Chip(if (v < 1000) "$v ms" else "1 s", ms == v) { ms = v; typed = v.toString() } }
        }
        if (s.doc.size > 1) TextButton(onClick = { commit(); s.edit { setAllDurations(ms) }; snacks?.show("All frames: $ms ms") { s.edit { undo() } } }, modifier = Modifier.heightIn(min = Tokens.Target)) {
            Text("Set all ${s.doc.size} frames to $ms ms", color = Color.White)
        }
        RowDivider()
        OptionRow("Duplicate", if (s.doc.canAddFrame) "A copy goes after this frame" else "Up to ${com.dotforge.device.EditorDoc.MAX_FRAMES} frames", chevron = false) {
            commit()
            if (s.doc.canAddFrame) { s.edit { addCopy() }; onDismiss() }
        }
        if (s.doc.size > 1) {
            if (i > 0) OptionRow("Move left", "Becomes frame $i", chevron = false) { commit(); s.edit { moveFrame(i, i - 1) } }
            if (i < s.doc.size - 1) OptionRow("Move right", "Becomes frame ${i + 2}", chevron = false) { commit(); s.edit { moveFrame(i, i + 1) } }
            OptionRow("Delete frame", null, destructive = true, chevron = false) {
                commit()
                s.edit { deleteFrame() }
                onDismiss()
                snacks?.show("Deleted frame ${i + 1}") { s.edit { undo() } }
            }
        }
    }
}

/** Round a slider duration to a tidy step: 5 ms under 100, 10 under 1 s, 100 above. */
private fun snap(ms: Int): Int = when {
    ms < 100 -> (ms / 5) * 5
    ms < 1000 -> (ms / 10) * 10
    else -> (ms / 100) * 100
}.coerceIn(Animation.MIN_FRAME_MS, 10_000)

// ---------------------------------------------------------------- guide

/** "How animation works" (row 32): plays once on the first animation, Skip any time, replay from ⋮. */
object Guide {
    private const val KEY = "editor_guide_seen"
    fun seen(ctx: Context) = ctx.getSharedPreferences("dotforge", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markSeen(ctx: Context) = ctx.getSharedPreferences("dotforge", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private class GuideStep(val title: String, val text: String, val demo: (com.dotforge.device.DeviceProfile) -> Animation)

private val GUIDE = listOf(
    GuideStep("Each frame is one picture", "Draw a picture, then add the next one. Played in order, they move.") { p ->
        Animation.single(com.dotforge.device.GlyphLibrary.all.first().still(p), 1000)
    },
    GuideStep("+ Copy starts from this frame", "Change a few dots in each copy. Small changes look smooth.") { p ->
        AnimationLibrary.all.firstOrNull { it.id.contains("heart") }?.load(p) ?: AnimationLibrary.all.first().load(p)
    },
    GuideStep("Play to watch it", "Timing sets the speed. The faint rings show the frame before.") { p ->
        AnimationLibrary.all.first().load(p)
    },
)

@Composable
fun GuideSheet(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val g = GUIDE[step]
    val reduce = LocalReduceMotion.current
    val demo = remember(step) { g.demo(AppGraph.profile) }
    FullSheet(onDone, "How animation works", subtitle = "${step + 1} of ${GUIDE.size}", confirm = "Skip") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // under Remove animations the guide shows a still picture
            if (reduce) GlyphPreview(Posters.of(demo), g.title, size = PreviewSize.LARGE)
            else GlyphPreview(demo, g.title, size = PreviewSize.LARGE, userPlay = true)
        }
        Text(g.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Tokens.Gutter))
        Text(g.text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Tiny))
        PrimaryButton(if (step < GUIDE.size - 1) "Next" else "Start drawing", Modifier.fillMaxWidth().padding(top = Tokens.Section)) {
            if (step < GUIDE.size - 1) step++ else onDone()
        }
    }
}

// ---------------------------------------------------------------- pick a design (library or yours)

/**
 * Picks a library item (the shared Library browser: search, Favourites, Recently used, categories) or one of your
 * designs (Insert, a layer's background, the Text screen's background). [libraryFilter] limits the library.
 */
@Composable
fun PickDesignSheet(
    title: String,
    onDismiss: () -> Unit,
    exclude: String? = null,
    top: (@Composable () -> Unit)? = null,
    libraryFilter: (com.dotforge.device.library.LibraryItem) -> Boolean = { true },
    onPick: (String, Animation) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val designs by AppGraph.designs.designs.collectAsState()
    // full height, the switch on the title line and the actions as the grid's first line: three rows of tiles at 360 dp
    FullSheet(onDismiss, title, confirm = null, scrolls = false, fill = true, titleEnd = { com.dotforge.ui.library.LibraryYoursSwitch(tab) { tab = it } }) {
        if (tab == 0) {
            com.dotforge.ui.library.LibraryBrowser(
                Modifier.fillMaxWidth().weight(1f), filter = libraryFilter, actionLabel = "Use", picker = true,
                header = top?.let { t -> { com.dotforge.ui.library.PickerActions(t) } },
            ) { item ->
                com.dotforge.ui.library.LibraryPrefs.used(item.id)
                onPick(item.name, item.load(AppGraph.profile))
            }
            return@FullSheet
        }
        val items = designs.filter { it.id != exclude }
        top?.let { com.dotforge.ui.library.PickerActions(it) }
        if (items.isEmpty()) Hint("Nothing saved yet. Designs you make show here.", Modifier.padding(top = Tokens.Gutter))
        LazyVerticalGrid(
            GridCells.Adaptive(com.dotforge.ui.library.PICKER_TILE_MIN), Modifier.fillMaxWidth().weight(1f).padding(top = Tokens.Small),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            items(items, key = { it.id }) { d ->
                val thumb = remember(d.id) { AppGraph.designs.animation(d)?.let(Posters::of) }
                Column(
                    Modifier.clickable(onClickLabel = "Use ${d.name}") { AppGraph.designs.animation(d)?.let { onPick(d.name, it) } }.padding(Tokens.Tiny),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        DotMatrix(thumb?.pixels, AppGraph.profile, Modifier.fillMaxSize().semantics { contentDescription = d.name })
                        if (d.animated) com.dotforge.ui.library.PlayBadge(Modifier.align(Alignment.BottomStart))
                    }
                    com.dotforge.ui.library.TileName(d.name, Tokens.Secondary)
                }
            }
        }
    }
}

/** Library items that make sense as a picture behind words: glyphs and animations, not ready text. */
val backgroundFilter: (com.dotforge.device.library.LibraryItem) -> Boolean = { it.form == com.dotforge.device.library.ItemForm.STILL || it.form == com.dotforge.device.library.ItemForm.ANIMATION }

/**
 * ⋮ > Insert: a photo (Photo to dots, added as frames after this one), or a library design or one of yours (a still
 * goes on top of this frame, an animation's frames go in after it).
 */
@Composable
fun InsertSheet(s: EditorSession, onDismiss: () -> Unit, snacks: Snacks?, onPhoto: () -> Unit = {}) {
    PickDesignSheet(
        "Insert", onDismiss, exclude = s.designId,
        top = {
            com.dotforge.ui.library.PickerAction("Photo, GIF or video", com.dotforge.ui.design.DfIcons.Photo, Modifier.semantics { contentDescription = "A photo, GIF or video, turned into dots and added after frame ${s.doc.cur + 1}" }) { onPhoto() }
        },
    ) { name, a ->
        onDismiss()
        if (a.frames.size == 1) {
            s.edit { stamp(a.frames[0].frame) }
            snacks?.show("Added $name to frame ${s.doc.cur + 1}") { s.edit { undo() } }
        } else {
            s.animatedView = true
            val ok = s.doc.canAddFrame
            s.edit { insertAfter(a) }
            snacks?.show(if (ok) "Added $name's ${a.frames.size} frames" else "Up to ${com.dotforge.device.EditorDoc.MAX_FRAMES} frames") { if (ok) s.edit { undo() } }
        }
    }
}

// ---------------------------------------------------------------- layers

/** ⋮ > Layers: a Background (library, yours or a photo) and Text under your drawing, cut out behind the letters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayersSheet(s: EditorSession, nav: Nav, onDismiss: () -> Unit, onPickBackground: () -> Unit, onEditText: () -> Unit, snacks: Snacks?) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val l = s.doc.layers ?: Layers()
    FullSheet(onDismiss, "Layers", subtitle = "Your drawing is always on top") {
        SectionLabel("Background")
        val bg = l.background
        if (bg == null) OptionRow("Add a background", "A library design, one of yours, or a photo") { onPickBackground() }
        else {
            OptionRow(bg.name, "${bg.effect.label} · change it from the Background tab") { s.layer = LayerKind.BACKGROUND; s.edit { }; onDismiss() }
            OptionRow("Change background…", null) { onPickBackground() }
            OptionRow("Remove background", null, destructive = true, chevron = false) {
                s.edit { setLayers(l.copy(background = null).takeIf { it.text != null }) }
                if (s.layer == LayerKind.BACKGROUND) s.layer = LayerKind.DRAWING
                snacks?.show("Removed the background") { s.edit { undo() } }
            }
        }
        SectionLabel("Text", Modifier.padding(top = Tokens.Gutter))
        val tl = l.text
        if (tl == null) OptionRow("Add text", "Words in a band, in any text style") {
            s.edit { setLayers(l.copy(text = TextLayer())) }
            s.layer = LayerKind.TEXT
            onEditText()
        } else {
            OptionRow("“${tl.spec.text}”", "${tl.spec.style.label} · ${tl.spec.band.label}") { onEditText() }
            OptionRow("Remove text", null, destructive = true, chevron = false) {
                s.edit { setLayers(l.copy(text = null).takeIf { it.background != null }) }
                if (s.layer == LayerKind.TEXT) s.layer = LayerKind.DRAWING
                snacks?.show("Removed the text") { s.edit { undo() } }
            }
        }
        if (l.text != null && l.background != null) {
            SectionLabel("Behind the text", Modifier.padding(top = Tokens.Gutter))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                BehindText.entries.forEach { b -> Chip(b.label, l.behind == b) { s.edit { setLayers(l.copy(behind = b)) } } }
            }
            Hint(l.behind.hint)
        }
        if (s.doc.layers != null) {
            RowDivider()
            OptionRow("Flatten to frames", "Bakes every layer into plain frames you can draw on") {
                s.edit { flatten() }
                s.layer = LayerKind.DRAWING
                onDismiss()
                snacks?.show("Flattened to frames") { s.edit { undo() } }
            }
        }
    }
}

/** A background from the library, your designs or a photo. */
@Composable
fun BackgroundPickSheet(s: EditorSession, onDismiss: () -> Unit, snacks: Snacks?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun use(name: String, a: Animation) {
        val l = s.doc.layers ?: Layers()
        val bg = BackgroundLayer(a.toMuseumJson(), name, if (a.frames.size > 1) BackgroundEffect.PLAY else BackgroundEffect.STILL)
        s.edit { setLayers(l.copy(background = bg)) }
        s.layer = LayerKind.BACKGROUND
        onDismiss()
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val pic = MediaLoader.picture(ctx, uri)
                    MediaLoader.name(ctx, uri) to Animation.single(PhotoDots.frame(AppGraph.profile, pic, PhotoSettings()), 1000)
                }
            }
            busy = false
            r.onSuccess { (n, a) -> use(n, a) }.onFailure { snacks?.show(it.message ?: "This photo can't be read. Try another one.") }
        }
    }
    PickDesignSheet(
        "Background", onDismiss, exclude = s.designId, libraryFilter = backgroundFilter,
        top = {
            val current = s.doc.layers?.background
            if (current != null) com.dotforge.ui.library.PickerAction("None", Icons.Filled.Close, Modifier.semantics { contentDescription = "No background" }) {
                s.edit { setLayers(s.doc.layers!!.copy(background = null)) }
                onDismiss()
                snacks?.show("Removed the background") { s.edit { setLayers((s.doc.layers ?: Layers()).copy(background = current)) } }
            }
            com.dotforge.ui.library.PickerAction(if (busy) "Reading the photo…" else "From a photo", com.dotforge.ui.design.DfIcons.Photo) {
                if (!busy) photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        },
    ) { name, a -> use(name, a) }
}

/** The text layer's words and look: the Text screen's styles and fonts, in a band. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextLayerSheet(s: EditorSession, onDismiss: () -> Unit) {
    val l = s.doc.layers ?: return
    val t = l.text ?: return
    var spec by remember { mutableStateOf(t.spec) }
    var speed by remember { mutableFloatStateOf(t.speed) }
    fun save() { s.edit { setLayers(l.copy(text = t.copy(spec = spec.clamped(), speed = speed))) } }
    val preview = remember(spec, speed) { com.dotforge.device.Compositor.text(AppGraph.profile, t.copy(spec = spec.clamped(), speed = speed)) }
    FullSheet({ save(); onDismiss() }, "Text", onConfirm = { save(); onDismiss() }, pinned = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GlyphPreview(preview, spec.text, size = PreviewSize.MEDIUM, userPlay = true) }
    }) {
        OutlinedTextField(
            spec.text, { spec = spec.copy(text = it.take(TextSpec.MAX_CHARS)) }, Modifier.fillMaxWidth(), singleLine = true,
            supportingText = { Text("${spec.text.length} / ${TextSpec.MAX_CHARS}", color = Tokens.Secondary) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        )
        SectionLabel("Text position")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            TextBand.entries.forEach { b -> Chip(b.label, spec.band == b) { spec = spec.copy(band = b) } }
        }
        SectionLabel("Style", Modifier.padding(top = Tokens.Small))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            TextStyle.entries.forEach { st -> Chip(st.label, spec.style == st) { spec = spec.copy(style = st) } }
        }
        SectionLabel("Font", Modifier.padding(top = Tokens.Small))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            DotFont.entries.forEach { f -> Chip(f.label, spec.font == f) { spec = spec.copy(font = f) } }
        }
        SectionLabel("Speed", Modifier.padding(top = Tokens.Small))
        DotSlider("Text speed", speed, 0.25f..4f, readout = "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x", mark = 1f) { speed = (it * 4).roundToInt() / 4f }
        Text(com.dotforge.ui.design.speedText(speed), style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary)
    }
}

/**
 * Takes the place of the tool options and tools rows while the Background or Text layer is selected: those
 * layers are changed here, not drawn on. Labelled rows: the background's Effect; the text's "Behind the text"
 * and "Text position"; then Change…/Edit text… and the layer's brightness.
 */
@Composable
fun LayerPanel(s: EditorSession, onEditText: () -> Unit, onChangeBackground: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val l = s.doc.layers ?: return
    Column(Modifier.fillMaxWidth()) {
        when (s.layer) {
            LayerKind.BACKGROUND -> l.background?.let { bg ->
                LabelledChips("Effect") {
                    BackgroundEffect.entries.forEach { e ->
                        OptionChip(e.label, "Effect: ${e.label}", selected = bg.effect == e) { s.edit { setLayers(l.copy(background = bg.copy(effect = e))) } }
                    }
                }
            }
            LayerKind.TEXT -> {
                LabelledChips("Behind the text") {
                    BehindText.entries.forEach { b ->
                        OptionChip(b.label, "Behind the text: ${b.label}", selected = l.behind == b) { s.edit { setLayers(l.copy(behind = b)) } }
                    }
                }
                l.text?.let { t ->
                    LabelledChips("Text position") {
                        TextBand.entries.forEach { b ->
                            OptionChip(b.label, "Text position: ${b.label}", selected = t.spec.band == b) { s.edit { setLayers(l.copy(text = t.copy(spec = t.spec.copy(band = b)))) } }
                        }
                    }
                }
            }
            LayerKind.DRAWING -> Unit
        }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
            val (label, value) = when (s.layer) {
                LayerKind.BACKGROUND -> "Change…" to (l.background?.brightness ?: 100)
                else -> "Edit text…" to (l.text?.brightness ?: 100)
            }
            OptionChip(label, if (s.layer == LayerKind.BACKGROUND) "Change background" else "Edit text") {
                if (s.layer == LayerKind.BACKGROUND) onChangeBackground() else onEditText()
            }
            Box(Modifier.weight(1f).padding(start = Tokens.Gap)) {
                DotSlider("${s.layer.label} brightness", value.toFloat(), 10f..100f, readout = "$value%") { v ->
                    val b = v.roundToInt()
                    s.edit {
                        setLayers(
                            if (s.layer == LayerKind.BACKGROUND) l.copy(background = l.background?.copy(brightness = b))
                            else l.copy(text = l.text?.copy(brightness = b)),
                        )
                    }
                }
            }
            Text("$value%", color = Tokens.Secondary, style = MaterialTheme.typography.bodySmall.capped(), maxLines = 1, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        }
    }
}

/** One labelled row of option chips: the label first, then the chips, scrolling sideways with faded edges. */
@Composable
private fun LabelledChips(label: String, chips: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().height(48.dp).edgeFade(scroll).horizontalScroll(scroll).padding(horizontal = Tokens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.capped(), color = Tokens.Secondary, maxLines = 1, modifier = Modifier.semantics { heading() })
        chips()
    }
}

// ---------------------------------------------------------------- trace photo

/**
 * ⋮ > Trace photo: a photo faintly under the grid to draw over. Only the editor shows it (never the Glyph, the
 * frames or an export); it stays with the design. Opacity 10-60%, show or hide, change or remove.
 */
@Composable
fun TraceSheet(s: EditorSession, onDismiss: () -> Unit, snacks: Snacks?, onPick: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val t = s.trace
    var opacity by remember(t?.file) { mutableFloatStateOf((t?.opacity ?: TracePhoto.DEFAULT_OPACITY).toFloat()) }
    FullSheet(onDismiss, "Trace photo", subtitle = "Only you see it") {
        Hint("A photo under the grid to draw over. It never goes to the Glyph or into your frames.")
        if (t == null) {
            PrimaryButton("Choose a photo", Modifier.fillMaxWidth().padding(top = Tokens.Section), onClick = onPick)
            return@FullSheet
        }
        val img = rememberTraceImage(t.file)
        Box(
            Modifier.padding(top = Tokens.Gap).size(96.dp).background(Tokens.Raised, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) androidx.compose.foundation.Image(
                img, contentDescription = "The trace photo", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            )
        }
        com.dotforge.ui.design.SwitchRow("Show under the grid", null, t.visible, inset = 0.dp) { s.changeTrace(t.copy(visible = it)) }
        SectionLabel("Opacity · ${opacity.roundToInt()}%")
        DotSlider("Opacity", opacity, TracePhoto.MIN_OPACITY.toFloat()..TracePhoto.MAX_OPACITY.toFloat(), steps = 9, readout = "${opacity.roundToInt()}%") {
            opacity = it
            s.changeTrace(t.copy(opacity = it.roundToInt()))
        }
        OptionRow("Change photo…", null, Modifier.padding(top = Tokens.Gap)) { onPick() }
        OptionRow("Remove trace photo", null, destructive = true, chevron = false) {
            s.changeTrace(null)
            onDismiss()
            snacks?.show("Removed the trace photo") { s.changeTrace(t) }
        }
    }
}
