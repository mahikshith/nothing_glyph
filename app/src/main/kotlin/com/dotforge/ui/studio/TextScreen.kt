package com.dotforge.ui.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.BackgroundEffect
import com.dotforge.device.BackgroundLayer
import com.dotforge.device.BehindText
import com.dotforge.device.Compositor
import com.dotforge.device.DotFont
import com.dotforge.device.DotFonts
import com.dotforge.device.Frame
import com.dotforge.device.Layers
import com.dotforge.device.PhotoDots
import com.dotforge.device.PhotoSettings
import com.dotforge.device.TextBand
import com.dotforge.device.TextDirection
import com.dotforge.device.TextLayer
import com.dotforge.device.TextRender
import com.dotforge.device.TextSpec
import com.dotforge.device.TextStyle
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.design.capped
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DISABLED_ALPHA
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.DotSlider
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.design.timingSummary
import com.dotforge.ui.editor.Drafts
import com.dotforge.ui.editor.PickDesignSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the Text screen opens with (tests open its sheets this way). */
enum class TextPanel { NONE, SYMBOLS, TIMING, BACKGROUND, MENU, RENAME }

/** The Text screen's working copy: words and look, timing, an optional background. */
class TextDraft(val id: String, val isNew: Boolean, spec: TextSpec, timing: Timing, background: BackgroundLayer?, behind: BehindText, name: String) {
    var spec by mutableStateOf(spec)
    var timing by mutableStateOf(timing)
    var background by mutableStateOf(background)
    var behind by mutableStateOf(behind)
    var name by mutableStateOf(name)
    private val start = listOf(spec, timing, background, behind, name)
    val changed: Boolean get() = listOf(spec, timing, background, behind, name) != start

    /** The design's layers when there is a background; plain text has none. */
    fun layers(): Layers? = background?.let { Layers(background = it, text = TextLayer(spec.clamped()), behind = behind) }

    /** What plays: the text alone, or text over its background. */
    fun animation(): Animation {
        val p = AppGraph.profile
        val l = layers() ?: return TextRender.render(p, spec.clamped())
        return Compositor.render(p, l, Animation.single(Frame.blank(p), 1000))
    }

    fun design(): Design {
        val p = AppGraph.profile
        val a = animation()
        return Design(
            id = id, name = name, animated = a.frames.size > 1, frames = a.toMuseumJson(), updatedAt = System.currentTimeMillis(),
            timing = timing.takeIf { it != Timing() }, kind = DesignKind.TEXT, text = spec.clamped(), layers = layers(),
            drawing = layers()?.let { Animation.single(Frame.blank(p), 1000).toMuseumJson() },
        )
    }

    companion object {
        fun open(designId: String?, text: String?): TextDraft {
            val d = AppGraph.designs.get(designId)
            if (d != null) return TextDraft(
                d.id, false, d.text ?: TextSpec(text = d.name), d.timing ?: Timing(), d.layers?.background, d.layers?.behind ?: BehindText.CUT_OUT, d.name,
            )
            val words = text?.take(TextSpec.MAX_CHARS) ?: "HELLO"
            return TextDraft(UUID.randomUUID().toString(), true, TextSpec(text = words), Timing(), null, BehindText.CUT_OUT, nameFor(words))
        }

        /** A new text design is named after its words. */
        fun nameFor(words: String) = words.trim().take(30).ifEmpty { "Text" }
    }
}

/**
 * Text (row 34, production-design "Text studio"): words and symbols, a live matrix, 17 styles with live
 * previews, direction (always shown; dimmed with "Plays in place" for styles that don't move), fonts, look,
 * timing and an optional background. Saved as text, so it stays editable; ⋮ > Edit as dots makes a dot copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextScreen(designId: String?, text: String?, nav: Nav, startPanel: TextPanel = TextPanel.NONE) {
    val t = remember { TextDraft.open(designId, text) }
    var panel by remember { mutableStateOf(startPanel) }
    var field by remember { mutableStateOf(TextFieldValue(t.spec.text, TextRange(t.spec.text.length))) }
    var named by remember { mutableStateOf(!t.isNew) }
    val p = AppGraph.profile

    // the live matrix, re-rendered off the main thread
    val anim by produceState(TextRender.render(p, t.spec.clamped()), t.spec, t.background, t.behind) {
        value = withContext(Dispatchers.Default) { t.animation() }
    }
    val live = remember(anim, t.timing) { Timings.plan(anim, t.timing.copy(startDelayMs = 0)).animation }
    // on the Glyph too, while this screen is open
    LaunchedEffect(live) {
        delay(100)
        AppGraph.arbiter.post(DisplayRequest(PHONE_ID, Priority.STUDIO_PREVIEW, live, loop = true, label = "Text: ${t.name}"))
    }
    DisposableEffect(Unit) { onDispose { AppGraph.arbiter.remove(PHONE_ID) } }

    fun save(): Boolean {
        if (t.spec.text.isBlank()) return false
        AppGraph.designs.update(t.design())
        return true
    }
    fun close() {
        // like the editor, Close never asks: a changed design is kept; an untouched new one is dropped
        if (t.changed && (!t.isNew || t.spec.text.isNotBlank())) save()
        nav.pop()
    }
    fun done() {
        val ok = save()
        nav.pop()
        if (ok) nav.studioRequest = if (t.isNew) "usefor:${t.id}" else "saved:${t.id}"
    }
    BackHandler(onBack = ::close)

    UndoSnackbarHost {
        val snacks = rememberSnacks()
        Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            com.dotforge.ui.design.TopBar(t.name, onBack = ::close, close = true) {
                Box {
                    IconButton(onClick = { panel = TextPanel.MENU }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White) }
                    DropdownMenu(expanded = panel == TextPanel.MENU, onDismissRequest = { panel = TextPanel.NONE }, containerColor = Tokens.Raised) {
                        DropdownMenuItem(text = { Text("Edit as dots") }, modifier = Modifier.heightIn(min = Tokens.Target), onClick = {
                            panel = TextPanel.NONE
                            val bg = t.background
                            val key = if (bg == null) Drafts.put(t.name, anim)
                            else Drafts.put(t.name, Animation.single(Frame.blank(p), 1000), layers = t.layers())
                            nav.pop()
                            nav.push(Page.Editor(null, anim.frames.size > 1, key))
                        })
                        DropdownMenuItem(text = { Text("Rename") }, modifier = Modifier.heightIn(min = Tokens.Target), onClick = { panel = TextPanel.RENAME })
                    }
                }
                TextButton(onClick = ::done, enabled = t.spec.text.isNotBlank(), modifier = Modifier.heightIn(min = Tokens.Target)) {
                    Text("Done", color = if (t.spec.text.isNotBlank()) Color.White else Color.White.copy(alpha = DISABLED_ALPHA), style = MaterialTheme.typography.labelLarge)
                }
            }
            // the matrix stays pinned while the controls scroll under it
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = Tokens.Small), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth * 0.5f, 200.dp)
                GlyphPreview(live, t.spec.text.ifBlank { "No text" }, Modifier.size(side), userPlay = true)
            }
            HorizontalDivider(color = Tokens.Hairline)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Gutter).padding(bottom = Tokens.Section)) {
                OutlinedTextField(
                    field, { v ->
                        val clipped = if (v.text.length > TextSpec.MAX_CHARS) v.copy(text = v.text.take(TextSpec.MAX_CHARS)) else v
                        field = clipped
                        t.spec = t.spec.copy(text = clipped.text)
                        if (!named) t.name = TextDraft.nameFor(clipped.text)
                    },
                    Modifier.fillMaxWidth().padding(top = Tokens.Gap), singleLine = true,
                    placeholder = { Text("Type something", color = Tokens.Secondary) },
                    supportingText = { Text("${field.text.length} / ${TextSpec.MAX_CHARS}", color = Tokens.Secondary) },
                    trailingIcon = {
                        TextButton(onClick = { panel = if (panel == TextPanel.SYMBOLS) TextPanel.NONE else TextPanel.SYMBOLS }) { Text("♥ ★", color = Color.White) }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                )
                if (panel == TextPanel.SYMBOLS) SymbolGrid { c ->
                    if (field.text.length < TextSpec.MAX_CHARS) {
                        val at = field.selection.start.coerceIn(0, field.text.length)
                        val s = field.text.substring(0, at) + c + field.text.substring(at)
                        field = TextFieldValue(s, TextRange(at + 1))
                        t.spec = t.spec.copy(text = s)
                        if (!named) t.name = TextDraft.nameFor(s)
                    }
                }

                SectionLabel("Style")
                StyleStrip(t.spec) { t.spec = t.spec.copy(style = it) }
                Text(t.spec.style.hint, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)

                SectionLabel("Direction")
                val moves = t.spec.style.moves
                FlowRow(Modifier.alpha(if (moves) 1f else DISABLED_ALPHA), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    TextDirection.entries.forEach { d ->
                        Chip(d.arrow + " " + d.label, t.spec.direction == d, Modifier.semantics { if (!moves) stateDescription = "Plays in place" }) {
                            if (moves) t.spec = t.spec.copy(direction = d)
                        }
                    }
                }
                if (!moves) Hint("${t.spec.style.label} plays in place, so direction doesn't apply.")

                SectionLabel("Font")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    DotFont.entries.forEach { f -> Chip(f.label, t.spec.font == f) { t.spec = t.spec.copy(font = f) } }
                }

                SectionLabel("Look")
                RowGroup {
                    SwitchRow("Outline", "A dim edge round each letter", t.spec.outline) { t.spec = t.spec.copy(outline = it) }
                    RowDivider()
                    SwitchRow("Invert", "Dark letters on lit dots", t.spec.invert) { t.spec = t.spec.copy(invert = it) }
                    RowDivider()
                    SwitchRow("Border", "The outer ring lit as a frame", t.spec.border) { t.spec = t.spec.copy(border = it) }
                }
                Text("Letter spacing: ${t.spec.spacing}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
                DotSlider("Letter spacing", t.spec.spacing.toFloat(), 0f..3f, steps = 2, readout = "${t.spec.spacing} dots") { t.spec = t.spec.copy(spacing = it.toInt()) }
                if (moves || t.spec.direction == TextDirection.BOUNCE) {
                    Text("Pause at the ends: ${StudioText.seconds(t.spec.pauseMs)}", style = MaterialTheme.typography.bodyLarge)
                    DotSlider("Pause at the ends", t.spec.pauseMs.toFloat(), 0f..3000f, readout = StudioText.seconds(t.spec.pauseMs)) {
                        t.spec = t.spec.copy(pauseMs = (it / 100).toInt() * 100)
                    }
                }

                SectionLabel("Timing")
                RowGroup {
                    ListRow("Timing", subtitle = timingSummary(t.timing, TimingKind.TEXT, anim.frames.size > 1), trailing = { com.dotforge.ui.design.Chevron() }) { panel = TextPanel.TIMING }
                }

                SectionLabel("Background")
                val bg = t.background
                RowGroup {
                    if (bg == null) ListRow("None", subtitle = "Add a glyph, one of your designs or a photo behind the words", trailing = { com.dotforge.ui.design.Chevron() }) { panel = TextPanel.BACKGROUND }
                    else {
                        ListRow(bg.name, subtitle = "Change background", trailing = { com.dotforge.ui.design.Chevron() }) { panel = TextPanel.BACKGROUND }
                        RowDivider()
                        ListRow("Remove background") {
                            t.background = null
                            t.spec = t.spec.copy(band = TextBand.FULL)
                            snacks?.show("Removed the background") { t.background = bg }
                        }
                    }
                }
                if (bg != null) {
                    Text("Background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        BackgroundEffect.entries.forEach { e -> Chip(e.label, bg.effect == e) { t.background = bg.copy(effect = e) } }
                    }
                    Text("Words", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        TextBand.entries.forEach { b -> Chip(b.label, t.spec.band == b) { t.spec = t.spec.copy(band = b) } }
                    }
                    Text("Behind the words", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        BehindText.entries.forEach { b -> Chip(b.label, t.behind == b) { t.behind = b } }
                    }
                    Hint(t.behind.hint)
                }
            }
        }

        when (panel) {
            TextPanel.TIMING -> TimingSheet(t.timing, TimingKind.TEXT, anim, t.name, onDismiss = { panel = TextPanel.NONE }) { t.timing = it }
            TextPanel.BACKGROUND -> TextBackgroundSheet(onDismiss = { panel = TextPanel.NONE }) { name, a ->
                t.background = BackgroundLayer(a.toMuseumJson(), name, if (a.frames.size > 1) BackgroundEffect.PLAY else BackgroundEffect.STILL)
                if (t.spec.band == TextBand.FULL) t.spec = t.spec.copy(band = TextBand.BOTTOM)
                panel = TextPanel.NONE
            }
            TextPanel.RENAME -> RenameDialog(t.name, onDismiss = { panel = TextPanel.NONE }) { t.name = it.take(30); named = true; panel = TextPanel.NONE }
            else -> Unit
        }
    }
}

private const val PHONE_ID = "text"

private val TextDirection.arrow: String
    get() = when (this) {
        TextDirection.LEFT -> "←"; TextDirection.RIGHT -> "→"; TextDirection.UP -> "↑"; TextDirection.DOWN -> "↓"
        TextDirection.BOUNCE -> "↔"; TextDirection.RING -> "◯"
    }

/** The 30 dot symbols, each drawn as it shows on the Glyph, with its name for TalkBack. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolGrid(onPick: (Char) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().background(Tokens.Card, Tokens.CardShape).padding(Tokens.Small),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny), verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        DotFonts.SYMBOLS.forEach { s ->
            Box(
                Modifier.size(Tokens.Target).background(Tokens.Raised, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "Insert ${s.name}") { onPick(s.char) }
                    .semantics { contentDescription = s.name },
                contentAlignment = Alignment.Center,
            ) { Text(s.char.toString(), color = Color.White, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** 17 style tiles, each a live preview of these words in that style (posters under Remove animations). */
@Composable
private fun StyleStrip(spec: TextSpec, onPick: (TextStyle) -> Unit) {
    val p = AppGraph.profile
    val words = spec.text.ifBlank { "Aa" }
    val previews by produceState<Map<TextStyle, Animation>>(emptyMap(), words, spec.font) {
        delay(250)
        value = withContext(Dispatchers.Default) {
            TextStyle.entries.associateWith { st -> TextRender.render(p, spec.copy(text = words, style = st, band = TextBand.FULL).clamped()) }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
        TextStyle.entries.forEach { st ->
            val on = spec.style == st
            Column(
                Modifier.width(76.dp).clickable(role = Role.RadioButton, onClickLabel = st.label) { onPick(st) }
                    .semantics { stateDescription = if (on) "Selected" else "" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(68.dp).border(if (on) 2.dp else 1.dp, if (on) Color.White else Tokens.Hairline, RoundedCornerShape(14.dp)).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val a = previews[st]
                    if (a != null) GlyphPreview(a, st.label, Modifier.fillMaxSize(), playing = true)
                    else DotMatrix(null, p, Modifier.fillMaxSize())
                }
                Text(
                    st.label, style = MaterialTheme.typography.labelSmall.capped(1.15f), color = if (on) Color.White else Tokens.Secondary,
                    maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A background for the words: the library, your designs or a photo. */
@Composable
private fun TextBackgroundSheet(onDismiss: () -> Unit, onPick: (String, Animation) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snacks = rememberSnacks()
    var busy by remember { mutableStateOf(false) }
    val photo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launchCatching(
            work = {
                val pic = MediaLoader.picture(ctx, uri)
                MediaLoader.name(ctx, uri) to Animation.single(PhotoDots.frame(AppGraph.profile, pic, PhotoSettings()), 1000)
            },
            done = { busy = false; it.onSuccess { (n, a) -> onPick(n, a) }.onFailure { e -> snacks?.show(e.message ?: "This photo can't be read. Try another one.") } },
        )
    }
    PickDesignSheet("Background", onDismiss, top = {
        com.dotforge.ui.design.OptionRow(if (busy) "Reading the photo…" else "From a photo", "Turned into dots, 25×25", preview = { Icon(com.dotforge.ui.design.DfIcons.Photo, null, tint = Color.White) }) {
            if (!busy) photo.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }, onPick = onPick)
}

/** Runs [work] off the main thread and hands its result back on it. */
fun <T> kotlinx.coroutines.CoroutineScope.launchCatching(work: suspend () -> T, done: (Result<T>) -> Unit) = launch {
    val r = withContext(Dispatchers.Default) { runCatching { work() } }
    done(r)
}
