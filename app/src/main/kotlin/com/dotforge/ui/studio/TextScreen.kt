package com.dotforge.ui.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Close
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
enum class TextPanel { NONE, SYMBOLS, TIMING, BACKGROUND, MENU, RENAME, SHARE }

/** The Text screen's working copy: words and look, timing, an optional background. */
/** Ready text designs (a library pick) handed to the Text screen by key, like the editor's Drafts. In memory only. */
object TextStarts {
    class Start(val spec: TextSpec, val name: String)

    private val starts = HashMap<String, Start>()
    private var next = 0

    fun put(spec: TextSpec, name: String): String = synchronized(starts) { "t${next++}".also { starts[it] = Start(spec, name) } }

    fun take(key: String?): Start? = key?.let { synchronized(starts) { starts.remove(it) } }
}

class TextDraft(val id: String, val isNew: Boolean, spec: TextSpec, timing: Timing, background: BackgroundLayer?, behind: BehindText, name: String) {
    var spec by mutableStateOf(spec)
    var timing by mutableStateOf(timing)
    var background by mutableStateOf(background)
    var behind by mutableStateOf(behind)
    var name by mutableStateOf(name)
    private val start = listOf(spec, timing, background, behind, name)
    val changed: Boolean get() = listOf(spec, timing, background, behind, name) != start

    /** Everything Undo brings back. */
    data class State(val spec: TextSpec, val timing: Timing, val background: BackgroundLayer?, val behind: BehindText, val name: String)

    fun state() = State(spec, timing, background, behind, name)

    private var current = state()
    private val undos = ArrayList<State>()
    private val redos = ArrayList<State>()
    private var lastAt = Long.MIN_VALUE / 2
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /**
     * Records a change to [next] (the screen calls this for every new state). Changes less than [MERGE_MS]
     * apart are one step, so typing a word or one slider drag is undone at once.
     */
    fun changed(next: State, now: Long) {
        if (next == current) return
        if (now - lastAt > MERGE_MS || undos.isEmpty()) { undos += current; if (undos.size > 100) undos.removeAt(0) }
        redos.clear()
        current = next
        lastAt = now
        flags()
    }

    fun undo() {
        val prev = undos.removeLastOrNull() ?: return
        redos += current
        restore(prev)
    }

    fun redo() {
        val next = redos.removeLastOrNull() ?: return
        undos += current
        restore(next)
    }

    private fun restore(st: State) {
        spec = st.spec; timing = st.timing; background = st.background; behind = st.behind; name = st.name
        current = st
        lastAt = Long.MIN_VALUE / 2
        flags()
    }

    private fun flags() { canUndo = undos.isNotEmpty(); canRedo = redos.isNotEmpty() }

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
        const val MERGE_MS = 700L

        fun open(designId: String?, text: String?, start: TextStarts.Start? = null): TextDraft {
            if (start != null) return TextDraft(UUID.randomUUID().toString(), true, start.spec.clamped(), Timing(), null, BehindText.CUT_OUT, start.name)
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
fun TextScreen(designId: String?, text: String?, nav: Nav, startPanel: TextPanel = TextPanel.NONE, start: String? = null) {
    val t = remember { TextDraft.open(designId, text, TextStarts.take(start)) }
    var panel by remember { mutableStateOf(startPanel) }
    var field by remember { mutableStateOf(TextFieldValue(t.spec.text, TextRange(t.spec.text.length))) }
    var named by remember { mutableStateOf(!t.isNew) }
    val p = AppGraph.profile

    // the live matrix, re-rendered off the main thread
    val anim by produceState(TextRender.render(p, t.spec.clamped()), t.spec, t.background, t.behind) {
        value = withContext(Dispatchers.Default) { t.animation() }
    }
    val live = remember(anim, t.timing) { Timings.plan(anim, t.timing.copy(startDelayMs = 0)).animation }
    // every change goes on the undo stack; after Undo the text field follows the words
    LaunchedEffect(t) { androidx.compose.runtime.snapshotFlow { t.state() }.collect { t.changed(it, System.currentTimeMillis()) } }
    LaunchedEffect(t.spec.text) { if (field.text != t.spec.text) field = TextFieldValue(t.spec.text, TextRange(t.spec.text.length)) }
    // on the Glyph too, while this screen is open and Show on Glyph is on
    var glyph by remember { mutableStateOf(true) }
    val glyphState = com.dotforge.ui.editor.rememberGlyphState(glyph)
    LaunchedEffect(live, glyph) {
        if (!glyph) { AppGraph.arbiter.remove(PHONE_ID); return@LaunchedEffect }
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
            // the editor's top bar: Close, then Glyph, Undo, Redo, ⋮ and Done (the name sits under the preview).
            // When the bar is tight (narrow screen, large text), Redo moves into ⋮ so nothing is squeezed or cut.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tight = maxWidth < 400.dp
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                com.dotforge.ui.design.GlyphToggle(
                    on = glyph && glyphState != com.dotforge.ui.editor.PhoneState.NOT_PHONE3, live = glyphState == com.dotforge.ui.editor.PhoneState.ON,
                    state = glyphState.text, enabled = glyphState != com.dotforge.ui.editor.PhoneState.NOT_PHONE3,
                ) { glyph = !glyph }
                TextBarIcon(com.dotforge.ui.design.DfIcons.Undo, "Undo", t.canUndo) { t.undo() }
                if (!tight) TextBarIcon(com.dotforge.ui.design.DfIcons.Redo, "Redo", t.canRedo) { t.redo() }
                Box {
                    IconButton(onClick = { panel = TextPanel.MENU }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White) }
                    DropdownMenu(expanded = panel == TextPanel.MENU, onDismissRequest = { panel = TextPanel.NONE }, containerColor = Tokens.Raised) {
                        if (tight) DropdownMenuItem(
                            text = { Text("Redo") }, enabled = t.canRedo, modifier = Modifier.heightIn(min = Tokens.Target),
                            leadingIcon = { Icon(com.dotforge.ui.design.DfIcons.Redo, contentDescription = null, tint = Color.White.copy(alpha = if (t.canRedo) 1f else DISABLED_ALPHA)) },
                            onClick = { panel = TextPanel.NONE; t.redo() },
                        )
                        DropdownMenuItem(text = { Text("Edit as dots") }, modifier = Modifier.heightIn(min = Tokens.Target), onClick = {
                            panel = TextPanel.NONE
                            val bg = t.background
                            val key = if (bg == null) Drafts.put(t.name, anim)
                            else Drafts.put(t.name, Animation.single(Frame.blank(p), 1000), layers = t.layers())
                            nav.pop()
                            nav.push(Page.Editor(null, anim.frames.size > 1, key))
                        })
                        DropdownMenuItem(text = { Text("Rename") }, modifier = Modifier.heightIn(min = Tokens.Target), onClick = { panel = TextPanel.RENAME })
                        val words = t.spec.text.isNotBlank()
                        DropdownMenuItem(text = { Text("Share…") }, enabled = words, modifier = Modifier.heightIn(min = Tokens.Target), onClick = { save(); panel = TextPanel.SHARE })
                        DropdownMenuItem(text = { Text("Duplicate design") }, enabled = words, modifier = Modifier.heightIn(min = Tokens.Target), onClick = {
                            panel = TextPanel.NONE
                            save()
                            AppGraph.designs.duplicate(t.id)?.let { snacks?.show("Made ${it.name}") }
                        })
                        DropdownMenuItem(text = { Text("Delete design", color = Tokens.AccentText) }, modifier = Modifier.heightIn(min = Tokens.Target), onClick = {
                            panel = TextPanel.NONE
                            val d = AppGraph.designs.get(t.id)
                            nav.pop()
                            if (d != null) DesignUse.delete(d, snacks)
                        })
                    }
                }
                TextButton(onClick = ::done, enabled = t.spec.text.isNotBlank(), modifier = Modifier.heightIn(min = Tokens.Target)) {
                    Text("Done", color = if (t.spec.text.isNotBlank()) Color.White else Color.White.copy(alpha = DISABLED_ALPHA), style = MaterialTheme.typography.labelLarge.capped(), maxLines = 1, softWrap = false)
                }
            }
            }
            // the matrix stays pinned while the controls scroll under it
            BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = Tokens.Small), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth * 0.5f, 200.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlyphPreview(live, t.spec.text.ifBlank { "No text" }, Modifier.size(side), userPlay = true)
                    // the design's name: a tap renames it
                    Row(
                        Modifier.heightIn(min = 32.dp).clickable(onClickLabel = "Rename") { panel = TextPanel.RENAME }.padding(horizontal = Tokens.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall.capped(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                        Icon(com.dotforge.ui.design.DfIcons.Pencil, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    }
                }
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
                        // one 48 dp button that says what it adds
                        TextButton(
                            onClick = { panel = if (panel == TextPanel.SYMBOLS) TextPanel.NONE else TextPanel.SYMBOLS },
                            modifier = Modifier.heightIn(min = Tokens.Target).padding(end = 4.dp)
                                .semantics { stateDescription = if (panel == TextPanel.SYMBOLS) "Open" else "Closed" },
                        ) {
                            Icon(com.dotforge.ui.design.DfIcons.Face, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Text("Symbols", color = Color.White, style = MaterialTheme.typography.labelLarge.capped(), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
                        }
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
                    // what the background does: the editor's word for it too
                    Text("Effect", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        BackgroundEffect.entries.forEach { e -> Chip(e.label, bg.effect == e) { t.background = bg.copy(effect = e) } }
                    }
                    Text("Text position", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        TextBand.entries.forEach { b -> Chip(b.label, t.spec.band == b) { t.spec = t.spec.copy(band = b) } }
                    }
                    Text("Behind the text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Tokens.Gap))
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
            TextPanel.SHARE -> AppGraph.designs.get(t.id)?.let { d -> ShareSheet(d, onDismiss = { panel = TextPanel.NONE }) } ?: run { panel = TextPanel.NONE }
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
            ) { SymbolDots(s.rows, Modifier.size(26.dp)) }
        }
    }
}

/** A symbol as the Glyph shows it: its 5x5 dots in white, never the phone's colour emoji. */
@Composable
private fun SymbolDots(rows: List<String>, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val n = maxOf(rows.size, rows.maxOf { it.length })
        val cell = size.minDimension / n
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, c ->
                val o = androidx.compose.ui.geometry.Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
                drawCircle(if (c == '#') Color.White else Tokens.DotOff, cell * 0.38f, o)
            }
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
                    // a still that shows something (the poster), never a blank moment of a scroll
                    if (a != null) GlyphPreview(remember(a) { com.dotforge.device.Posters.of(a) }, st.label, Modifier.fillMaxSize())
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
internal fun TextBackgroundSheet(onDismiss: () -> Unit, onPick: (String, Animation) -> Unit) {
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
    }, libraryFilter = com.dotforge.ui.editor.backgroundFilter, onPick = onPick)
}

/** Runs [work] off the main thread and hands its result back on it. */
fun <T> kotlinx.coroutines.CoroutineScope.launchCatching(work: suspend () -> T, done: (Result<T>) -> Unit) = launch {
    val r = withContext(Dispatchers.Default) { runCatching { work() } }
    done(r)
}

@Composable
private fun TextBarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) =
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = if (enabled) label else "$label, unavailable" }) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else DISABLED_ALPHA))
    }
