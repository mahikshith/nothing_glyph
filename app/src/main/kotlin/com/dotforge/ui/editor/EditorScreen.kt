package com.dotforge.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Brush
import com.dotforge.device.BrushMode
import com.dotforge.device.EditorTool
import com.dotforge.device.LayerKind
import com.dotforge.device.Mirror
import com.dotforge.device.Playback
import com.dotforge.device.Region
import com.dotforge.device.Regions
import com.dotforge.engine.Priority
import com.dotforge.ui.Nav
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.alerts.deviceState
import com.dotforge.ui.design.DISABLED_ALPHA
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.capped
import com.dotforge.ui.design.edgeFade
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.design.rememberHaptics
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.design.speedText
import com.dotforge.ui.studio.RenameDialog
import com.dotforge.ui.studio.ShareSheet
import com.dotforge.ui.studio.StudioText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.dotforge.studio.TracePhoto
import com.dotforge.ui.studio.MediaDrafts
import com.dotforge.ui.studio.MediaLoader
import com.dotforge.ui.studio.MediaSource
import com.dotforge.ui.studio.Picked
import com.dotforge.ui.studio.PhotoLoader
import com.dotforge.ui.studio.PhotoScreen
import com.dotforge.ui.studio.PhotoTarget

/** Sheets and menus over the editor (also how screenshots open them). */
enum class EditorPanel { NONE, CLEAR, FRAME, TIMING, EFFECTS, MORE, SHADE, RENAME, SHARE, GUIDE, LAYERS, LAYER_TEXT, LAYER_BG, INSERT, ADD_MENU, TRACE, BRUSH }

/** What a test or a link starts the editor with. */
class EditorStart(
    val panel: EditorPanel = EditorPanel.NONE,
    val tool: Tool = Tool.DRAW,
    val selection: Region? = null,
    val layer: LayerKind = LayerKind.DRAWING,
    val brush: Brush? = null,
    val playing: Boolean = false,
    /** Show on phone's state: null = the real one. */
    val phone: PhoneState? = null,
    /** Opens Photo to dots on this media in "Add as frame" mode, as if just picked. */
    val photo: MediaSource? = null,
    val prepare: (EditorSession) -> Unit = {},
)

/** Show on Glyph's state (row 29): the top bar's Glyph button, and a notice line when the Glyph can't show it. */
enum class PhoneState(val text: String) {
    ON("Live on Glyph"), OFF("Not on the Glyph"), TORCH("The Glyph torch is on, so your design waits"),
    BUSY("Another light is using the Glyph. Your design shows when it ends."),
    FACE_DOWN("Face down: the Glyph is off. Turn the phone over to see it."), NOT_PHONE3("Show on Glyph needs a Phone (3)");
}

/**
 * The editor (spec-review's one layout). Everything is pinned and the page never scrolls: top bar (with the
 * Glyph toggle), matrix (all the height the rest leaves, up to the width less the gutters; pinch zoom to 3x),
 * a timing line that opens Timing, the tool options bar, the tools row, the actions row, a playback row when
 * there is something to play, and the frame strip (numbered frames, the current one centred, a + tile at the
 * end). Autosaves 1 s after each change; Close and Back never ask.
 */
@Composable
fun EditorScreen(designId: String?, animated: Boolean, draft: String?, nav: Nav, start: EditorStart? = null) {
    val s = remember { EditorSession.open(designId, animated, Drafts.take(draft)) }
    var panel by remember { mutableStateOf(start?.panel ?: EditorPanel.NONE) }
    // Photo to dots over the editor ("Add as frame"): picked media by key. It is not a page, so the open
    // editor (and its undo) stays as it is underneath.
    var photoKey by remember { mutableStateOf(start?.photo?.let { MediaDrafts.put(it) }) }
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    remember {
        start?.let { st ->
            st.prepare(s)
            s.tool = st.tool
            s.selection = st.selection
            s.layer = st.layer
            st.brush?.let { s.brush = it }
            if (st.playing) s.togglePlay()
        }
        AppGraph.designs.beginEditing(s.designId)
        // first animation: the guide plays once
        if (start == null && s.animatedView && !Guide.seen(ctx)) panel = EditorPanel.GUIDE
        true
    }

    fun close() {
        s.close()
        nav.pop()
    }

    fun done() {
        val fresh = s.isNew
        s.close()
        nav.pop()
        if (s.everSaved || s.hasDots) {
            if (fresh) nav.studioRequest = "usefor:${s.designId}" else nav.studioRequest = "saved:${s.designId}"
        }
    }

    BackHandler(onBack = ::close)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { s.save(); s.release() }
            if (e == Lifecycle.Event.ON_START) s.mirror()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // autosave 1 s after the last change
    LaunchedEffect(s.tick) {
        if (!s.dirty) return@LaunchedEffect
        delay(1_000)
        s.save()
    }
    // Show on phone, debounced to 100 ms
    LaunchedEffect(s.tick, s.showOnPhone, s.playing) {
        delay(100)
        s.mirror()
    }
    LaunchedEffect(s.playing) {
        var last = System.nanoTime()
        while (s.playing) {
            delay(s.playback.msToNextFrame().coerceIn(16, 500))
            val now = System.nanoTime()
            s.tickPlayback((now - last) / 1_000_000)
            last = now
        }
    }

    val phone = start?.phone ?: rememberPhoneState(s)
    // a polite TalkBack announcement for frame changes ("Frame 4 added, copy of frame 3"), shown on the status line for 2 s
    var announce by remember { mutableStateOf("") }
    LaunchedEffect(announce) { if (announce.isNotEmpty()) { delay(2_000); announce = "" } }
    @Suppress("UNUSED_EXPRESSION") s.tick
    // one pattern for stills and animations: the strip is always there; playback only when something plays
    val playable = s.doc.size > 1 || s.doc.layers != null
    // the snackbar sits above the tool rows (options, tools, actions, playback, strip), so it never covers a tool
    var toolsHeight by remember { mutableStateOf(0.dp) }

    UndoSnackbarHost(above = toolsHeight) {
        val snacks = rememberSnacks()
        val scope = rememberCoroutineScope()
        val pickFrames = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val m = Picked.media(ctx, uris)
            if (m == null) snacks?.show("Pick photos, one GIF or one video") else photoKey = MediaDrafts.put(m)
        }
        fun addPhoto() = pickFrames.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        val pickTrace = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { PhotoLoader.keep(MediaLoader.picture(ctx, uri, 900), "${java.util.UUID.randomUUID()}-trace") }
                }
                r.onSuccess { file ->
                    val had = s.trace != null
                    s.changeTrace(TracePhoto(file, s.trace?.opacity ?: TracePhoto.DEFAULT_OPACITY, visible = true))
                    snacks?.show(if (had) "Changed the trace photo" else "Trace photo added. Only you see it.")
                }.onFailure { snacks?.show("This photo can't be read. Try another one.") }
            }
        }
        Column(
            Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)
                // under Photo to dots, the editor is hidden from TalkBack too
                .then(if (photoKey != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            TopBar(
                s, phone, onClose = ::close, onDone = ::done, onRename = { panel = EditorPanel.RENAME },
                onGlyph = { s.showOnPhone = !s.showOnPhone; if (!s.showOnPhone) s.release() },
            )
            GlyphNotice(s, phone)
            if (s.doc.layers != null) LayerStrip(s)
            // the matrix takes all the height the pinned rows leave, up to the width less the gutters
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth - Tokens.Gutter * 2, maxHeight - STATUS_LINE)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EditorCanvas(s, side, onPicked = { v -> s.brush = s.brush.copy(value = v, mode = BrushMode.PAINT); s.picking = false; s.edit { } })
                    StatusLine(announce.ifEmpty { statusLine(s) }, zoom = s.zoom, onFit = { s.zoom = 1f; s.pan = androidx.compose.ui.geometry.Offset.Zero }, onTiming = { panel = EditorPanel.TIMING })
                }
            }
            val density = LocalDensity.current
            Column(Modifier.fillMaxWidth().onSizeChanged { toolsHeight = with(density) { it.height.toDp() } }) {
            if (s.layer == LayerKind.DRAWING) {
                OptionsBar(s, onShade = { panel = EditorPanel.SHADE }, onBrush = { panel = EditorPanel.BRUSH }, onPaste = {
                    val clip = EditorClipboard.clip
                    if (clip == null) snacks?.show("Copy something first") else {
                        val at = EditorClipboard.from ?: Region(0, 0, clip.width - 1, clip.height - 1)
                        s.edit { paste(clip, at.left, at.top) }
                        s.selection = Region(at.left, at.top, at.left + clip.width - 1, at.top + clip.height - 1)
                        snacks?.show("Pasted")
                    }
                }, onCopy = {
                    val sel = s.selection ?: Region.all(AppGraph.profile)
                    EditorClipboard.clip = Regions.copy(s.doc.current, AppGraph.profile, sel)
                    EditorClipboard.from = sel
                    snacks?.show("Copied")
                })
                ToolsRow(s, brushPanel = panel == EditorPanel.BRUSH, onBrush = { panel = EditorPanel.BRUSH }, onBrushChoice = { m ->
                    panel = EditorPanel.NONE
                    s.brush = s.brush.copy(mode = m)
                    if (s.tool != Tool.DRAW) { s.tool = Tool.DRAW; s.selection = null }
                    s.picking = false
                    s.edit { }
                }, onDismiss = { panel = EditorPanel.NONE })
            } else {
                LayerPanel(s, onEditText = { panel = EditorPanel.LAYER_TEXT }, onChangeBackground = { panel = EditorPanel.LAYER_BG })
            }
            ActionsRow(
                s,
                onClear = {
                    if (s.doc.size > 1) panel = EditorPanel.CLEAR else {
                        s.edit { clearFrame() }
                        snacks?.show("Cleared frame 1") { s.edit { undo() } }
                    }
                },
                onTiming = { panel = EditorPanel.TIMING },
                onEffects = { panel = EditorPanel.EFFECTS },
                onMore = { panel = EditorPanel.MORE },
                morePanel = panel == EditorPanel.MORE,
                clearPanel = panel == EditorPanel.CLEAR,
                onPanelDismiss = { panel = EditorPanel.NONE },
                onClearChoice = { choice ->
                    panel = EditorPanel.NONE
                    val i = s.doc.cur + 1
                    when (choice) {
                        ClearItem.FRAME -> { s.edit { clearFrame() }; snacks?.show("Cleared frame $i") { s.edit { undo() } } }
                        ClearItem.ALL -> {
                            val n = s.doc.size
                            s.edit { clearAll() }
                            snacks?.show("Cleared the dots in all $n frames") { s.edit { undo() } }
                        }
                        ClearItem.DELETE -> if (s.doc.size > 1) { s.edit { deleteFrame() }; snacks?.show("Deleted frame $i") { s.edit { undo() } } }
                    }
                },
                onMoreChoice = { item ->
                    panel = EditorPanel.NONE
                    when (item) {
                        MoreItem.ONION -> s.onion = !s.onion
                        MoreItem.LAYERS -> panel = EditorPanel.LAYERS
                        MoreItem.TRACE -> panel = EditorPanel.TRACE
                        MoreItem.INSERT -> panel = EditorPanel.INSERT
                        MoreItem.SHARE -> { s.save(); panel = EditorPanel.SHARE }
                        MoreItem.DUPLICATE -> { s.save(); AppGraph.designs.duplicate(s.designId)?.let { snacks?.show("Made ${it.name}") } }
                        MoreItem.GUIDE -> panel = EditorPanel.GUIDE
                        MoreItem.FLATTEN -> { s.edit { flatten() }; s.layer = LayerKind.DRAWING; snacks?.show("Flattened to frames") { s.edit { undo() } } }
                        MoreItem.DELETE -> {
                            s.save()
                            val d = AppGraph.designs.get(s.designId)
                            s.release()
                            AppGraph.designs.endEditing()
                            nav.pop()
                            if (d != null) com.dotforge.ui.studio.DesignUse.delete(d, snacks) else AppGraph.designs.delete(s.designId)
                        }
                    }
                },
            )
            if (playable) PlaybackRow(s)
            FrameStrip(
                s, onFrameMenu = { panel = EditorPanel.FRAME }, addMenu = panel == EditorPanel.ADD_MENU,
                onAddMenu = { panel = EditorPanel.ADD_MENU }, onAddMenuDismiss = { panel = EditorPanel.NONE },
                onAddChoice = { item ->
                    panel = EditorPanel.NONE
                    val from = s.doc.cur + 1
                    when {
                        !s.doc.canAddFrame -> snacks?.show("Up to ${com.dotforge.device.EditorDoc.MAX_FRAMES} frames")
                        item == AddItem.BLANK -> { s.edit { addBlank() }; haptics.confirm(); announce = "Frame ${s.doc.cur + 1} added" }
                        item == AddItem.COPY -> { s.edit { addCopy() }; haptics.confirm(); announce = "Frame ${s.doc.cur + 1} added, copy of frame $from" }
                        item == AddItem.PHOTO -> addPhoto()
                        item == AddItem.LIBRARY -> panel = EditorPanel.INSERT
                    }
                    if (s.doc.size > 1) s.animatedView = true
                },
            )
            }
        }

        when (panel) {
            EditorPanel.TIMING -> TimingSheet(
                s.doc.timing, if (s.doc.rendered().frames.size > 1) TimingKind.ANIMATION else TimingKind.STILL, s.doc.rendered(), s.doc.name,
                onDismiss = { panel = EditorPanel.NONE },
            ) { t -> s.edit { setTiming(t) } }
            EditorPanel.EFFECTS -> EffectsSheet(s, onDismiss = { panel = EditorPanel.NONE }, snacks = snacks)
            EditorPanel.FRAME -> FrameMenuSheet(s, onDismiss = { panel = EditorPanel.NONE }, snacks = snacks)
            EditorPanel.SHADE -> ShadeSheet(s, onDismiss = { panel = EditorPanel.NONE })
            EditorPanel.RENAME -> RenameDialog(s.doc.name, onDismiss = { panel = EditorPanel.NONE }) { s.edit { rename(it) }; panel = EditorPanel.NONE }
            EditorPanel.SHARE -> AppGraph.designs.get(s.designId)?.let { d -> ShareSheet(d, onDismiss = { panel = EditorPanel.NONE }) } ?: run { panel = EditorPanel.NONE }
            EditorPanel.GUIDE -> GuideSheet { Guide.markSeen(ctx); panel = EditorPanel.NONE }
            EditorPanel.LAYERS -> LayersSheet(
                s, nav, onDismiss = { panel = EditorPanel.NONE }, onPickBackground = { panel = EditorPanel.LAYER_BG },
                onEditText = { panel = EditorPanel.LAYER_TEXT }, snacks = snacks,
            )
            EditorPanel.LAYER_TEXT -> TextLayerSheet(s) { panel = EditorPanel.NONE }
            EditorPanel.LAYER_BG -> BackgroundPickSheet(s, onDismiss = { panel = EditorPanel.NONE }, snacks = snacks)
            EditorPanel.INSERT -> InsertSheet(s, onDismiss = { panel = EditorPanel.NONE }, snacks = snacks, onPhoto = { panel = EditorPanel.NONE; addPhoto() })
            EditorPanel.TRACE -> TraceSheet(
                s, onDismiss = { panel = EditorPanel.NONE }, snacks = snacks,
                onPick = { pickTrace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            else -> Unit
        }
        photoKey?.let { key ->
            // takes every touch, so nothing reaches the editor underneath
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
            PhotoScreen(
                key, null, nav,
                target = PhotoTarget(
                    after = s.doc.cur,
                    onAdd = { a ->
                        photoKey = null
                        val from = s.doc.cur + 1
                        val n = s.addFrames(a)
                        if (n == 0) snacks?.show("Up to ${com.dotforge.device.EditorDoc.MAX_FRAMES} frames")
                        else {
                            haptics.confirm()
                            snacks?.show(if (n == 1) "Added the photo as frame ${s.doc.cur + 1}" else "Added $n frames after frame $from") { s.edit { undo() } }
                        }
                    },
                    onClose = { photoKey = null },
                ),
            )
            }
        }
    }
}

private val STATUS_LINE = 44.dp
private val PLAYBACK = 48.dp
private val STRIP = 96.dp

@Composable
private fun rememberPhoneState(s: EditorSession): PhoneState = rememberGlyphState(s.showOnPhone)

/** Screenshots render on a JVM with no Glyph: they set the state Text and Photo show (the editor has EditorStart.phone). */
object GlyphStateForTests {
    @Volatile var forced: PhoneState? = null
}

/** Where Show on Glyph stands for a screen with its Glyph toggle [on]: live, off, or why the Glyph can't show it. */
@Composable
fun rememberGlyphState(on: Boolean): PhoneState {
    val ctx = LocalContext.current
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val device = remember { deviceState(ctx) }
    return when {
        !on -> PhoneState.OFF
        GlyphStateForTests.forced != null -> GlyphStateForTests.forced!!
        device != DeviceState.PHONE3 -> PhoneState.NOT_PHONE3
        active.any { it.id == com.dotforge.tile.TorchTileService.ID } -> PhoneState.TORCH
        active.any { it.priority > Priority.STUDIO_PREVIEW } -> PhoneState.BUSY
        AppGraph.faceDown.faceDown == true -> PhoneState.FACE_DOWN
        else -> PhoneState.ON
    }
}

/** Under the matrix: the timing summary (row 26), or what a tap does now. */
private fun statusLine(s: EditorSession): String = when {
    s.picking -> "Tap a dot to pick its shade"
    s.layer == LayerKind.BACKGROUND -> "Background layer · change it below"
    s.layer == LayerKind.TEXT -> "Text layer · change it below"
    else -> timingChip(s)
}

/** "1x · 2.4 s · 100%" */
private fun timingChip(s: EditorSession): String {
    val t = s.doc.timing
    return "${speedText(t.speed)} · ${StudioText.seconds(s.doc.totalMs)} · ${t.brightness}%"
}

/**
 * The line under the matrix: the timing summary with a timer icon (a tap opens Timing). While zoomed, the Fit
 * chip leads it: its own place, outside the circle, so it never covers dots.
 */
@Composable
private fun StatusLine(text: String, zoom: Float, onFit: () -> Unit, onTiming: () -> Unit) {
    Row(Modifier.height(STATUS_LINE), verticalAlignment = Alignment.CenterVertically) {
        if (zoom > 1f) {
            val x = "%.1f".format(zoom).removeSuffix(".0")
            OptionChip("Fit · ${x}x", "Fit: zoomed ${x}x") { onFit() }
        }
        Row(
            Modifier.fillMaxHeight().clickable(onClickLabel = "Timing", role = Role.Button, onClick = onTiming)
                .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(DfIcons.Timer, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(14.dp))
            Text(
                text, style = MaterialTheme.typography.bodySmall.capped(), color = Tokens.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * When Show on Glyph is on but the Glyph can't show the design: why, and the way out only when there is one.
 * Face down has none (turning the phone over is the way). Busy offers Stop showing only when what holds the
 * Glyph is something DotForge is showing on request (a toy, a design shown from the library), which it stops.
 */
@Composable
private fun GlyphNotice(s: EditorSession, phone: PhoneState) {
    if (phone != PhoneState.TORCH && phone != PhoneState.BUSY && phone != PhoneState.FACE_DOWN) return
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val ours = active.filter { it.priority > Priority.STUDIO_PREVIEW && it.loop && !it.isAmbient && it.id != com.dotforge.tile.TorchTileService.ID }.maxByOrNull { it.priority }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = Tokens.Gutter).background(Tokens.Raised, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(phone.text, style = MaterialTheme.typography.bodySmall.capped(), color = Color.White, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        if (phone == PhoneState.TORCH) TextButton(onClick = { com.dotforge.tile.TorchTileService.toggle() }, modifier = Modifier.heightIn(min = Tokens.Target)) {
            Text("Turn off torch", color = Tokens.AccentText, style = MaterialTheme.typography.labelLarge.capped())
        }
        if (phone == PhoneState.BUSY && ours != null) TextButton(onClick = { AppGraph.arbiter.remove(ours.id) }, modifier = Modifier.heightIn(min = Tokens.Target)) {
            Text("Stop showing", color = Color.White, style = MaterialTheme.typography.labelLarge.capped())
        }
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun TopBar(s: EditorSession, phone: PhoneState, onClose: () -> Unit, onDone: () -> Unit, onRename: () -> Unit, onGlyph: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val saved = if (s.saving) "Saving…" else if (s.everSaved && !s.dirty) "Saved" else if (s.isNew && !s.hasDots) "Not saved yet" else "Saving…"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // with large text or a narrow screen the name would be squeezed by the pill and buttons: it moves to its
        // own line under the bar, so it is never cut to a letter
        val stacked = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) < NAME_ROOM
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                if (stacked) Spacer(Modifier.weight(1f))
                else Column(Modifier.weight(1f).clickable(onClickLabel = "Rename", onClick = onRename).padding(horizontal = Tokens.Tiny)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // the name gets the room up to the Glyph pill, on up to 2 lines
                        Text(s.doc.name, style = MaterialTheme.typography.titleMedium.capped(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(DfIcons.Pencil, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                    }
                    Text(saved, style = MaterialTheme.typography.bodySmall.capped(), color = Tokens.Secondary, maxLines = 1, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                com.dotforge.ui.design.GlyphToggle(
                    on = s.showOnPhone && phone != PhoneState.NOT_PHONE3, live = phone == PhoneState.ON, state = phone.text,
                    enabled = phone != PhoneState.NOT_PHONE3, onToggle = onGlyph,
                )
                BarIcon(DfIcons.Undo, "Undo", s.doc.canUndo) { s.edit { undo() } }
                BarIcon(DfIcons.Redo, "Redo", s.doc.canRedo) { s.edit { redo() } }
                TextButton(onClick = onDone, modifier = Modifier.heightIn(min = Tokens.Target)) {
                    Text("Done", color = Color.White, style = MaterialTheme.typography.labelLarge.capped(), maxLines = 1, softWrap = false)
                }
            }
            if (stacked) Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Rename", onClick = onRename).padding(horizontal = Tokens.Gutter).padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.doc.name, style = MaterialTheme.typography.titleMedium.capped(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(DfIcons.Pencil, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                Text(
                    saved, style = MaterialTheme.typography.bodySmall.capped(), color = Tokens.Secondary, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = Tokens.Small).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Below this width (in dp at 1x text) the editor's name moves under the top bar. */
private val NAME_ROOM = 400.dp

@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = if (enabled) label else "$label, unavailable" },
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else DISABLED_ALPHA))
    }
}

// ---------------------------------------------------------------- layers

@Composable
private fun LayerStrip(s: EditorSession) {
    val l = s.doc.layers ?: return
    // chips size to their names, each with its own eye (struck through when hidden); the strip scrolls
    // sideways when it can't fit, keeping the selected chip in view, with faded edges
    val items = buildList {
        if (l.background != null) add(LayerKind.BACKGROUND)
        if (l.text != null) add(LayerKind.TEXT)
        add(LayerKind.DRAWING)
    }
    val scroll = rememberScrollState()
    LaunchedEffect(s.layer, scroll.maxValue) {
        when (s.layer) { items.last() -> scroll.scrollTo(scroll.maxValue); items.first() -> scroll.scrollTo(0); else -> Unit }
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp).edgeFade(scroll).horizontalScroll(scroll).padding(horizontal = Tokens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { k ->
            val visible = when (k) {
                LayerKind.BACKGROUND -> l.background?.visible == true
                LayerKind.TEXT -> l.text?.visible == true
                LayerKind.DRAWING -> l.drawingVisible
            }
            val on = s.layer == k
            Row(
                Modifier.height(40.dp).background(if (on) Color.White else Tokens.Raised, RoundedCornerShape(20.dp))
                    .clickable(role = Role.Tab) { s.layer = k; s.edit { } }
                    .semantics { if (!visible) stateDescription = "Hidden" }
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    k.label, color = (if (on) Color.Black else Color.White).copy(alpha = if (visible) 1f else 0.6f),
                    style = MaterialTheme.typography.labelLarge.capped(1.15f), maxLines = 1,
                )
                IconButton(onClick = {
                    s.edit {
                        setLayers(when (k) {
                            LayerKind.BACKGROUND -> l.copy(background = l.background?.copy(visible = !visible))
                            LayerKind.TEXT -> l.copy(text = l.text?.copy(visible = !visible))
                            LayerKind.DRAWING -> l.copy(drawingVisible = !visible)
                        })
                    }
                }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (visible) DfIcons.Eye else DfIcons.EyeOff, contentDescription = if (visible) "Hide ${k.label.lowercase()}" else "Show ${k.label.lowercase()}",
                        tint = if (on) Color.Black else Color.White, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tool options

@Composable
private fun OptionsBar(s: EditorSession, onShade: () -> Unit, onPaste: () -> Unit, onCopy: () -> Unit, onBrush: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().height(48.dp).edgeFade(scroll).horizontalScroll(scroll).padding(horizontal = Tokens.Gap),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalAlignment = Alignment.CenterVertically,
    ) {
        val b = s.brush
        when (s.tool) {
            Tool.DRAW -> {
                OptionChip("${b.mode.label} ▾", "Brush: ${b.mode.label}", icon = DfIcons.Paintbrush) { onBrush() }
                OptionChip("Size ${b.size}", "Dot size ${b.size}") { s.brush = b.copy(size = b.size % 3 + 1) }
                ShadeChip(b.value) { onShade() }
                OptionChip(null, "Pick a shade", icon = DfIcons.Pick, selected = s.picking) { s.picking = !s.picking }
                MirrorChip(s)
            }
            Tool.ERASE -> {
                OptionChip("Size ${b.size}", "Eraser size ${b.size}") { s.brush = b.copy(size = b.size % 3 + 1) }
                MirrorChip(s)
            }
            Tool.FILL -> {
                ShadeChip(b.value) { onShade() }
                OptionChip(null, "Pick a shade", icon = DfIcons.Pick, selected = s.picking) { s.picking = !s.picking }
            }
            Tool.SHAPES -> {
                listOf(EditorTool.LINE, EditorTool.BOX, EditorTool.CIRCLE).forEach { t ->
                    OptionChip(t.label, t.label, selected = s.shape == t) { s.shape = t }
                }
                OptionChip("Filled", "Filled", selected = s.filled) { s.filled = !s.filled }
                MirrorChip(s)
            }
            Tool.SELECT -> {
                val sel = s.selection
                OptionChip("All", "Select all") { s.selection = Region.all(AppGraph.profile); s.edit { } }
                OptionChip(null, "Copy", icon = DfIcons.Copy, enabled = true) { onCopy() }
                OptionChip(null, "Paste", icon = DfIcons.Paste, enabled = EditorClipboard.clip != null) { onPaste() }
                OptionChip(null, "Flip left-right", icon = DfIcons.FlipH, enabled = sel != null) { sel?.let { r -> s.edit { flipRegion(r, true) } } }
                OptionChip(null, "Flip top-bottom", icon = DfIcons.FlipV, enabled = sel != null) { sel?.let { r -> s.edit { flipRegion(r, false) } } }
                OptionChip("Delete", "Delete the selected dots", enabled = sel != null) { sel?.let { r -> s.edit { deleteRegion(r) } } }
                for ((label, d) in listOf("←" to (-1 to 0), "↑" to (0 to -1), "↓" to (0 to 1), "→" to (1 to 0))) {
                    OptionChip(label, "Nudge ${when (label) { "←" -> "left"; "→" -> "right"; "↑" -> "up"; else -> "down" }}", enabled = sel != null) {
                        sel?.let { r -> s.edit { moveRegion(r, d.first, d.second) }; s.selection = r.moved(d.first, d.second) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorChip(s: EditorSession) {
    val m = s.brush.mirror
    OptionChip(if (m == Mirror.OFF) "Mirror" else "Mirror ${m.label}", "Mirror: ${m.label}", icon = DfIcons.Mirror, selected = m != Mirror.OFF) {
        s.brush = s.brush.copy(mirror = Mirror.entries[(m.ordinal + 1) % Mirror.entries.size])
        s.edit { }
    }
}

@Composable
private fun ShadeChip(value: Int, onClick: () -> Unit) {
    val pct = (value * 100 + 127) / 255
    Row(
        Modifier.heightIn(min = 36.dp).background(Tokens.Raised, RoundedCornerShape(18.dp)).clickable(onClickLabel = "Change shade", onClick = onClick)
            .semantics { contentDescription = "Shade $pct%" }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).background(Color.White.copy(alpha = 0.25f + 0.75f * value / 255f), CircleShape))
        Text("$pct%", color = Color.White, style = MaterialTheme.typography.bodyMedium.capped(), modifier = Modifier.padding(start = 6.dp))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(18.dp))
    }
}

/** A compact option: 36 dp pill, 48 dp touch. */
@Composable
fun OptionChip(text: String?, label: String, icon: ImageVector? = null, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp).widthIn(min = 40.dp)
            .background(if (selected) Color.White else Tokens.Raised, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (selected) stateDescription = "On" }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (selected) Color.Black else Color.White
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        if (text != null) Text(text, color = tint, style = MaterialTheme.typography.bodyMedium.capped(), maxLines = 1, modifier = Modifier.padding(start = if (icon != null) 4.dp else 0.dp))
    }
}

// ---------------------------------------------------------------- tools and actions rows

/**
 * The tools: Draw, Erase, Fill, Shapes, Select. One selected pill at a time; the Brush mode (Paint, Brighten,
 * Dim) is a mode of Draw, named on its button ("Draw · Dim") and chosen from its menu (a second tap on Draw, or
 * the mode chip in Draw's options).
 */
@Composable
private fun ToolsRow(s: EditorSession, brushPanel: Boolean, onBrush: () -> Unit, onBrushChoice: (BrushMode) -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).height(IntrinsicSize.Min).padding(horizontal = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
        for (t in Tool.entries) {
            val icon = when (t) { Tool.DRAW -> DfIcons.Brush; Tool.ERASE -> DfIcons.Eraser; Tool.FILL -> DfIcons.Fill; Tool.SHAPES -> DfIcons.Shapes; Tool.SELECT -> DfIcons.Select }
            val mode = s.brush.mode
            val label = if (t == Tool.DRAW && mode != BrushMode.PAINT) "${t.label} · ${mode.label}" else t.label
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxSize()) {
                    LabelledButton(icon, label, selected = s.tool == t, chip = if (t == Tool.DRAW) "Brush: ${mode.label}" else null) {
                        if (t == Tool.DRAW && s.tool == Tool.DRAW) { onBrush(); return@LabelledButton }
                        s.tool = t
                        s.picking = false
                        if (t != Tool.SELECT) s.selection = null
                        s.edit { }
                    }
                }
                if (t == Tool.DRAW) BrushMenu(mode, brushPanel, onBrushChoice, onDismiss)
            }
        }
    }
}

/** What the Draw tool does to the dots it touches: Paint, Brighten or Dim, each with a line of help. */
@Composable
private fun BrushMenu(mode: BrushMode, expanded: Boolean, onChoice: (BrushMode) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Tokens.Raised) {
        BrushHelp.entries.forEach { h ->
            DropdownMenuItem(
                text = {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(h.mode.label, color = Color.White)
                        Text(h.text, color = Tokens.Secondary, style = MaterialTheme.typography.bodySmall)
                    }
                },
                trailingIcon = { if (mode == h.mode) Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = "Selected", tint = Color.White) },
                onClick = { onChoice(h.mode) }, modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
}

/**
 * A 48 dp tool button with its label under the icon at every font size: the label grows with the font up to
 * 1.5x and wraps to 2 lines rather than being cut.
 */
@Composable
private fun RowScope.LabelledButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, chip: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = Tokens.Target).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = if (chip != null) "$label, $chip" else label; if (selected) stateDescription = "Selected" }
            .alpha(if (enabled) 1f else DISABLED_ALPHA).padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 30.dp).background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(22.dp)) }
        Text(
            label, style = MaterialTheme.typography.labelMedium.capped(LABEL_SCALE), color = Color.White, maxLines = 2, softWrap = true,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Tool labels grow with the font up to this, then wrap. */
private const val LABEL_SCALE = 1.5f

/** The Brush button's menu: what the Draw tool does to the dots it touches. */
enum class BrushHelp(val mode: BrushMode, val text: String) {
    PAINT(BrushMode.PAINT, "Sets dots to the shade"), BRIGHTEN(BrushMode.BRIGHTEN, "Makes lit dots brighter, one step a touch"),
    DIM(BrushMode.DIM, "Makes lit dots dimmer, one step a touch"),
}

enum class MoreItem(val label: String) {
    ONION("Onion skin"), LAYERS("Layers…"), TRACE("Trace photo…"), INSERT("Insert: a design or photo…"), SHARE("Share…"), DUPLICATE("Duplicate design"),
    GUIDE("How animation works"), FLATTEN("Flatten to frames"), DELETE("Delete design"),
}

/** The Clear menu with several frames: blank this frame, blank them all (frames and times stay), or delete this one. */
enum class ClearItem { FRAME, ALL, DELETE }

/** The frame strip's + tile menu (row 17); a long-press on + is Copy of this frame. */
enum class AddItem(val label: String) { BLANK("Blank frame"), COPY("Copy of this frame"), PHOTO("Photo as a frame…"), LIBRARY("From the library…") }

@Composable
private fun ActionsRow(
    s: EditorSession,
    onClear: () -> Unit, onTiming: () -> Unit, onEffects: () -> Unit, onMore: () -> Unit,
    morePanel: Boolean, clearPanel: Boolean, onPanelDismiss: () -> Unit, onClearChoice: (ClearItem) -> Unit, onMoreChoice: (MoreItem) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val t = s.doc.timing
    val chip = "${speedText(t.speed)} · ${StudioText.seconds(s.doc.totalMs)} · ${t.brightness}%"
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).height(IntrinsicSize.Min).padding(horizontal = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize()) {
                LabelledButton(DfIcons.Clear, if (s.doc.size > 1) "Clear ▾" else "Clear") { onClear() }
            }
            DropdownMenu(expanded = clearPanel, onDismissRequest = onPanelDismiss, containerColor = Tokens.Raised) {
                DropdownMenuItem(text = { Text("Clear this frame") }, onClick = { onClearChoice(ClearItem.FRAME) }, modifier = Modifier.heightIn(min = Tokens.Target))
                DropdownMenuItem(
                    text = { Text("Clear all ${s.doc.size} frames (keeps the frames)") },
                    onClick = { onClearChoice(ClearItem.ALL) }, modifier = Modifier.heightIn(min = Tokens.Target),
                )
                androidx.compose.material3.HorizontalDivider(color = Tokens.Hairline)
                DropdownMenuItem(
                    text = { Text("Delete this frame", color = Tokens.AccentText) },
                    onClick = { onClearChoice(ClearItem.DELETE) }, modifier = Modifier.heightIn(min = Tokens.Target),
                )
            }
        }
        LabelledButton(DfIcons.Timer, "Timing", chip = chip) { onTiming() }
        LabelledButton(DfIcons.Sparkle, "Effects") { onEffects() }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize()) { LabelledButton(Icons.Filled.MoreVert, "More") { onMore() } }
            DropdownMenu(expanded = morePanel, onDismissRequest = onPanelDismiss, containerColor = Tokens.Raised) {
                MoreItem.entries.forEach { item ->
                    val show = when (item) {
                        MoreItem.ONION -> s.doc.size > 1
                        MoreItem.FLATTEN -> s.doc.layers != null
                        else -> true
                    }
                    if (show) DropdownMenuItem(
                        text = {
                            Text(
                                if (item == MoreItem.ONION) "Onion skin: ${if (s.onion) "On" else "Off"}" else item.label,
                                color = if (item == MoreItem.DELETE) Tokens.AccentText else Color.White,
                            )
                        },
                        onClick = { onMoreChoice(item) }, modifier = Modifier.heightIn(min = Tokens.Target),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- playback and frames

@Composable
private fun PlaybackRow(s: EditorSession) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val total = s.doc.totalMs
    val i = if (s.playing) s.shown else s.doc.cur
    val at = s.doc.durations.take(i).sum()
    Row(Modifier.fillMaxWidth().height(PLAYBACK).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { s.step(false) }) { Icon(DfIcons.StepBack, contentDescription = "Step back", tint = Color.White) }
        IconButton(onClick = { s.togglePlay() }, modifier = Modifier.background(Color.White, CircleShape).size(40.dp)) {
            Icon(if (s.playing) DfIcons.Pause else DfIcons.Play, contentDescription = if (s.playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { s.step(true) }) { Icon(DfIcons.StepForward, contentDescription = "Step forward", tint = Color.White) }
        Column(Modifier.weight(1f).padding(horizontal = Tokens.Small)) {
            // "3/6 · 340 ms of 1 s"; when that doesn't fit, the total goes first, never a cut word
            val full = "${i + 1}/${s.doc.size} · ${StudioText.seconds(at)} of ${StudioText.seconds(total)}"
            var short by remember { mutableStateOf(false) }
            Text(
                if (short) "${i + 1}/${s.doc.size} · ${StudioText.seconds(at)}" else full,
                style = MaterialTheme.typography.bodySmall.capped(), color = Color.White, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                onTextLayout = { r -> if (r.hasVisualOverflow && !short) short = true },
                modifier = Modifier.semantics { contentDescription = "Frame ${i + 1} of ${s.doc.size}, ${StudioText.seconds(at)} of ${StudioText.seconds(total)}" },
            )
            Scrubber(at.toFloat() / total.coerceAtLeast(1)) { f -> s.scrub((f * total).toInt()) }
        }
        TextButton(onClick = { s.cycleSpeed() }, modifier = Modifier.heightIn(min = Tokens.Target).semantics { contentDescription = "Preview speed ${s.speed.label}" }) {
            Text(s.speed.label, color = Color.White, style = MaterialTheme.typography.labelLarge.capped())
        }
    }
}

@Composable
private fun Scrubber(fraction: Float, onSeek: (Float) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(16.dp).padding(vertical = 6.dp).background(com.dotforge.ui.design.SLIDER_TRACK, RoundedCornerShape(2.dp))
            .seekOnTap(onSeek),
    ) {
        Box(Modifier.fillMaxHeight().width(maxWidth * fraction.coerceIn(0f, 1f)).background(Color.White, RoundedCornerShape(2.dp)))
    }
}

private fun Modifier.seekOnTap(onSeek: (Float) -> Unit): Modifier = this.pointerInput(Unit) {
    detectTapGestures { o -> onSeek((o.x / size.width.toFloat()).coerceIn(0f, 1f)) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FrameStrip(
    s: EditorSession, onFrameMenu: () -> Unit, addMenu: Boolean, onAddMenu: () -> Unit, onAddMenuDismiss: () -> Unit,
    onAddChoice: (AddItem) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") s.tick
    val list = rememberLazyListState()
    val now = if (s.playing) s.shown else s.doc.cur
    val density = LocalDensity.current
    val tilePx = with(density) { TILE.toPx() }
    val stepPx = with(density) { (TILE + TILE_GAP).toPx() }
    // the current frame stays in the middle of the strip, so the frames before and after it both show
    LaunchedEffect(now, s.doc.size) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.viewportSize.width }.first { it > 0 }
        val vp = list.layoutInfo.viewportSize.width
        val offset = -((vp - tilePx) / 2).toInt()
        if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 && now == 0) return@LaunchedEffect
        list.animateScrollToItem(now, offset)
    }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDx by remember { mutableStateOf(0f) }
    Row(Modifier.fillMaxWidth().height(STRIP).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
        // the Frame menu, pinned left (row 16): a raised frame button over the frame's number, "3/6"
        Column(
            Modifier.width(60.dp).fillMaxHeight().clickable(role = Role.Button, onClickLabel = "Frame menu", onClick = onFrameMenu)
                .semantics { contentDescription = "Frame ${s.doc.cur + 1} of ${s.doc.size}, frame menu" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.background(Tokens.Raised, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Icon(DfIcons.Frame, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(
                "${s.doc.cur + 1}/${s.doc.size}", style = MaterialTheme.typography.labelMedium.capped(LABEL_SCALE), color = Color.White,
                maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 2.dp),
            )
        }
        // the frames read here, so the row's content (a remembered lambda) changes with every edit
        val frames = s.doc.frames
        LazyRow(
            Modifier.weight(1f).fillMaxHeight().edgeFade(list.canScrollBackward, list.canScrollForward), state = list,
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP), verticalAlignment = Alignment.CenterVertically,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(frames, key = { i, f -> System.identityHashCode(f) * 31 + i }) { i, f ->
                val on = i == now
                val next = i == (now + 1).takeIf { it < s.doc.size }
                Column(
                    Modifier.width(TILE)
                        .then(if (dragging == i) Modifier.graphicsLayer { translationX = dragDx; scaleX = 1.08f; scaleY = 1.08f } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(TILE)
                            .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) Color.White else Tokens.Hairline), RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "Frame ${i + 1}") { s.pause(); s.select(i) }
                            .reorder(i, stepPx, onStart = { dragging = i; dragDx = 0f }, onDrag = { dragDx += it }, onEnd = {
                                val to = (i + (dragDx / stepPx).let { kotlin.math.round(it).toInt() }).coerceIn(0, s.doc.size - 1)
                                dragging = null
                                if (to != i) s.edit { moveFrame(i, to) }
                            })
                            .semantics {
                                val blank = f.none { it > 0 }
                                contentDescription = "Frame ${i + 1}, ${StudioText.duration(s.doc.durations[i])}" + (if (blank) ", empty" else "") +
                                    when { on -> ", current"; next -> ", next"; else -> "" }
                            }
                            .padding(3.dp),
                    ) {
                        // with layers, the frame as it shows (background and text too), so a tile never looks empty
                        FrameThumb(if (s.doc.layers != null) s.composed(i) else f, s.tick, Modifier.fillMaxSize())
                        // the frame's number, top left
                        Text(
                            "${i + 1}", style = MaterialTheme.typography.labelSmall.capped(1f), color = if (on) Color.Black else Color.White,
                            modifier = Modifier.align(Alignment.TopStart).background(if (on) Color.White else Color.Black.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 4.dp),
                        )
                    }
                    // the frame's time under the tile (never over the dots), then what plays now and next
                    Text(
                        StudioText.duration(s.doc.durations[i]), style = MaterialTheme.typography.labelSmall.capped(1f),
                        color = if (on) Color.White else Tokens.Secondary, maxLines = 1, softWrap = false, modifier = Modifier.height(16.dp).padding(top = 2.dp),
                    )
                    Text(
                        if (next) "Next ›" else if (on) "Now" else "", style = MaterialTheme.typography.labelSmall.capped(1f),
                        color = if (on) Color.White else Tokens.Secondary, maxLines = 1, modifier = Modifier.height(16.dp),
                    )
                }
            }
            // + at the end of the strip: a menu; long-press copies this frame
            item(key = "add") {
                Column(Modifier.width(TILE), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        val enabled = s.doc.canAddFrame
                        Box(
                            Modifier.size(TILE).background(Tokens.Raised, RoundedCornerShape(10.dp)).border(1.dp, Tokens.Hairline, RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    enabled = enabled, role = Role.Button, onClickLabel = "Add a frame", onClick = onAddMenu,
                                    onLongClickLabel = "Copy this frame", onLongClick = { onAddChoice(AddItem.COPY) },
                                )
                                .semantics { contentDescription = if (enabled) "Add a frame" else "Add a frame, up to ${com.dotforge.device.EditorDoc.MAX_FRAMES} frames" }
                                .alpha(if (enabled) 1f else DISABLED_ALPHA),
                            contentAlignment = Alignment.Center,
                        ) { Icon(DfIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = onAddMenuDismiss, containerColor = Tokens.Raised) {
                            AddItem.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(item.label) }, onClick = { onAddChoice(item) }, modifier = Modifier.heightIn(min = Tokens.Target))
                            }
                        }
                    }
                    Text("Add", style = MaterialTheme.typography.labelSmall.capped(1f), color = Tokens.Secondary, maxLines = 1, modifier = Modifier.height(32.dp).padding(top = 2.dp))
                }
            }
        }
    }
}

private val TILE = 56.dp
private val TILE_GAP = 6.dp

/** A frame strip thumbnail: lit dots in white (brighter for brighter shades) on a #2A2A2A disc; off dots aren't drawn. */
@Composable
private fun FrameThumb(pixels: IntArray, tick: Int, modifier: Modifier) {
    val p = AppGraph.profile
    // frames change in place while drawing: [tick] (the session's edit count) redraws the thumbnail
    androidx.compose.foundation.Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick
        val n = p.size
        val cell = size.minDimension / n
        drawCircle(THUMB_DISC, radius = size.minDimension / 2)
        for (i in p.ledCells) {
            val v = pixels[i]
            if (v == 0) continue
            drawCircle(Color.White.copy(alpha = 0.6f + 0.4f * v / 255f), cell * 0.52f, androidx.compose.ui.geometry.Offset((i % n + 0.5f) * cell, (i / n + 0.5f) * cell))
        }
    }
}

private val THUMB_DISC = Color(0xFF2A2A2A)

/** Long-press a thumbnail, then drag it to a new place. */
private fun Modifier.reorder(index: Int, thumbPx: Float, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = this.pointerInput(index) {
    detectDragGesturesAfterLongPress(
        onDragStart = { onStart() },
        onDrag = { change, amount -> change.consume(); onDrag(amount.x) },
        onDragEnd = onEnd,
        onDragCancel = onEnd,
    )
}
