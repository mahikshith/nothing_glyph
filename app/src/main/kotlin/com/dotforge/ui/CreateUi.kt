package com.dotforge.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.DesignEffect
import com.dotforge.device.Draw
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.studio.Design
import java.util.UUID
import kotlin.math.roundToInt

private const val DEFAULT_FRAME_MS = 150
private const val MAX_FRAME_MS = 1_000
private const val LIVE_ID = "create"

/** Create tab: the saved designs, and the way into the editor. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CreateScreen(nav: Nav, scroll: ScrollState) {
    val repo = AppGraph.designs
    val designs by repo.designs.collectAsStateWithLifecycle()
    var choose by rememberSaveable { mutableStateOf(false) }
    val import = rememberDesignImport(nav)
    var deleting by remember { mutableStateOf<Design?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DotTitle("CREATE", Modifier.weight(1f).padding(top = 12.dp))
            IconButton(onClick = { choose = true }, Modifier.padding(top = 4.dp)) { Icon(Icons.Filled.Add, "New design") }
        }
        if (designs.isEmpty()) {
            Spacer(Modifier.height(80.dp))
            EmptyState("Nothing here yet", "Draw on the Glyph Matrix, dot by dot.", "New design") { choose = true }
        } else {
            Text("Saved designs play in the DotForge player toy.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
            designs.chunked(2).forEach { pair ->
                TileRow {
                    pair.forEach { d ->
                        val first = remember(d) { repo.animation(d) }
                        Surface(color = Surface1, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                            Column(
                                Modifier
                                    .combinedClickable(
                                        onClick = { nav.push(Page.DesignEditor(d.id, d.animated)) },
                                        onLongClick = { deleting = d },
                                    )
                                    .padding(16.dp),
                            ) {
                                MatrixView(first?.frames?.first()?.frame?.pixels, AppGraph.profile, Modifier.fillMaxWidth())
                                Text(d.name, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp), maxLines = 1)
                                Text(
                                    if (d.animated) "Animated · ${first?.frames?.size ?: 0} frames" else "Static",
                                    color = Muted, fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Hold a design to delete it.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            containerColor = Surface2,
            title = { Text("New design") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceCard("Static", "One picture on the matrix", false, Modifier.fillMaxWidth()) {
                        choose = false
                        nav.push(Page.DesignEditor(null, animated = false))
                    }
                    ChoiceCard("Animated", "Frames that play in a loop", false, Modifier.fillMaxWidth()) {
                        choose = false
                        nav.push(Page.DesignEditor(null, animated = true))
                    }
                    ChoiceCard("From the library", "${com.dotforge.device.library.AnimationLibrary.all.size} glyphs, texts and animations", false, Modifier.fillMaxWidth()) {
                        choose = false
                        nav.push(Page.Library)
                    }
                    ChoiceCard("Import", "A picture, or a Dot Hub or Glyph Museum file", false, Modifier.fillMaxWidth()) {
                        choose = false
                        import.file()
                    }
                    ChoiceCard("Photo", "Take one and turn it into dots", false, Modifier.fillMaxWidth()) {
                        choose = false
                        import.camera()
                    }
                    ChoiceCard("Text", "Words in dots", false, Modifier.fillMaxWidth()) {
                        choose = false
                        import.text()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = false }) { Text("Cancel", color = Color.White) } },
        )
    }
    deleting?.let { d ->
        ConfirmDialog("Delete \"${d.name}\"?", "It can't be brought back.", "Delete", onConfirm = {
            delete(d)
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

private fun delete(d: Design) {
    AppGraph.designs.delete(d.id)
    if (AppGraph.toys.playerDesignId == d.id) AppGraph.toys.playerDesignId = null
}

/**
 * Pixel editor. While open, the drawing is mirrored live on the matrix ("Show on matrix", on by default);
 * a corner preview plays it. Save stores it for the DotForge player toy and the "My design" rule effect.
 */
@Composable
fun DesignEditorPage(designId: String?, animated: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val profile = AppGraph.profile
    val repo = AppGraph.designs
    val existing = remember { repo.get(designId) }
    val startAnimation = remember { existing?.let(repo::animation) ?: Animation.single(Frame.blank(profile), DEFAULT_FRAME_MS) }
    val frames = remember { startAnimation.frames.map { it.frame.copy() }.toMutableStateList() }
    val durations = remember { startAnimation.frames.map { it.durationMs }.toMutableStateList() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var index by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var erase by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf(Tool.PEN) }
    var onion by rememberSaveable { mutableStateOf(false) }
    // a shape being drawn: where it started and the frame before it
    var anchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var before by remember { mutableStateOf<IntArray?>(null) }
    var level by rememberSaveable { mutableFloatStateOf(255f) }
    var live by rememberSaveable { mutableStateOf(true) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var effects by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Animation?>(null) }
    var hasFrames by remember { mutableStateOf(animated || startAnimation.frames.size > 1) }

    fun current() = frames[index.coerceIn(0, frames.lastIndex)]
    fun animation() = Animation(profile, frames.indices.map { AnimFrame(frames[it].copy(), durations[it]) })
    fun setAll(a: Animation) {
        frames.clear(); frames.addAll(a.frames.map { it.frame.copy() })
        durations.clear(); durations.addAll(a.frames.map { it.durationMs })
        index = index.coerceIn(0, frames.lastIndex)
        if (frames.size > 1) hasFrames = true
        version++
    }
    val savedJson = remember { startAnimation.toMuseumJson() }
    fun dirty() = animation().toMuseumJson() != savedJson || name != existing?.name.orEmpty()

    val preview = remember(version, frames.size) { animation() }
    val corner = rememberLoopingFrame(preview)

    LaunchedEffect(version, frames.size, live) {
        if (live) {
            AppGraph.arbiter.post(DisplayRequest(LIVE_ID, Priority.STUDIO_PREVIEW, preview, loop = true, label = "Create"))
        } else {
            AppGraph.arbiter.remove(LIVE_ID)
        }
    }
    DisposableEffect(Unit) { onDispose { AppGraph.arbiter.remove(LIVE_ID) } }

    fun save() {
        val d = Design(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { repo.nextName() },
            animated = hasFrames,
            frames = animation().toMuseumJson(),
            updatedAt = System.currentTimeMillis(),
        )
        repo.save(d)
        Toast.makeText(ctx, "Saved. DotForge player can play it.", Toast.LENGTH_SHORT).show()
        onClose()
    }
    fun close() = if (dirty()) confirmLeave = true else onClose()

    PageScaffold(
        if (existing == null) "NEW DESIGN" else "EDIT DESIGN",
        onBack = ::close,
        actions = {
            if (existing != null) {
                IconButton(onClick = { shareDesign(ctx, existing.copy(frames = animation().toMuseumJson())) }) { Icon(Icons.Filled.Share, "Share as JSON") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete design") }
            }
        },
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { live = !live },
                    modifier = Modifier.weight(1f).height(52.dp),
                    border = BorderStroke(1.dp, if (live) NothingRed else Color(0xFF555555)),
                ) { Text(if (live) "On the matrix" else "Show on matrix", color = Color.White) }
                PrimaryButton("Save", Modifier.weight(1f), onClick = ::save)
            }
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            val shown = if (onion && hasFrames && index > 0) Draw.onion(current(), frames[index - 1]) else current().pixels
            MatrixView(
                shown, profile, Modifier.fillMaxWidth().padding(top = 8.dp), version = version,
                onCell = { x, y ->
                    val v = if (erase) 0 else level.roundToInt()
                    val f = current()
                    when (tool) {
                        Tool.PEN -> f[x, y] = v
                        Tool.FILL -> Draw.fill(f, x, y, v)
                        else -> {
                            val start = anchor ?: (x to y).also { anchor = it; before = f.pixels.copyOf() }
                            before?.copyInto(f.pixels)
                            when (tool) {
                                Tool.LINE -> Draw.line(f, start.first, start.second, x, y, v)
                                Tool.BOX -> Draw.rect(f, start.first, start.second, x, y, v)
                                else -> Draw.circle(f, start.first, start.second, x, y, v)
                            }
                        }
                    }
                    version++
                },
                onRelease = { anchor = null; before = null },
            )
            MatrixView(corner?.pixels, profile, Modifier.size(72.dp).align(Alignment.TopEnd))
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Tool.entries.forEachIndexed { i, t ->
                SegmentedButton(selected = tool == t, onClick = { tool = t }, shape = SegmentedButtonDefaults.itemShape(i, Tool.entries.size)) {
                    Text(t.label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(selected = !erase, onClick = { erase = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Light") }
                SegmentedButton(selected = erase, onClick = { erase = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Erase") }
            }
            TextButton(onClick = { current().fill(0); version++ }, Modifier.padding(start = 8.dp)) { Text("Clear", color = Color.White) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { effects = true }) { Text("Effects", color = NothingRed) }
            undo?.let { before ->
                TextButton(onClick = {
                    setAll(before)
                    undo = null
                }) { Text("Undo effect", color = Color.White) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dot brightness", Modifier.padding(end = 12.dp))
            Slider(level, { level = it }, valueRange = 20f..255f, colors = sliderColors())
        }

        if (hasFrames) {
            GroupTitle("FRAMES")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(frames) { i, f ->
                    MatrixView(
                        f.pixels, profile, version = version,
                        modifier = Modifier.size(56.dp)
                            .border(2.dp, if (i == index) NothingRed else Color.Transparent, CircleShape)
                            .clickable { index = i },
                    )
                }
                item {
                    IconButton(onClick = {
                        frames.add(index + 1, current().copy())
                        durations.add(index + 1, durations[index])
                        index++
                        version++
                    }) { Icon(Icons.Filled.Add, "Add frame (copy of this one)") }
                }
            }
            SwitchRow("Onion skin", "The previous frame shows faintly while you draw", onion) { onion = it }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Frame ${index + 1} of ${frames.size}", color = Muted, modifier = Modifier.weight(1f))
                if (frames.size > 1) {
                    TextButton(onClick = {
                        frames.removeAt(index)
                        durations.removeAt(index)
                        index = index.coerceAtMost(frames.lastIndex)
                        version++
                    }) { Text("Delete frame", color = Color.White) }
                }
            }
            var thisFrame by rememberSaveable { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { thisFrame = !thisFrame }) { Text(if (thisFrame) "This frame" else "All frames", color = NothingRed) }
                Slider(
                    value = durations[index].coerceAtMost(MAX_FRAME_MS).toFloat(),
                    onValueChange = { v ->
                        if (thisFrame) durations[index] = v.roundToInt() else durations.indices.forEach { durations[it] = v.roundToInt() }
                        version++
                    },
                    valueRange = Animation.MIN_FRAME_MS.toFloat()..MAX_FRAME_MS.toFloat(),
                    modifier = Modifier.weight(1f),
                    colors = sliderColors(),
                )
                Text("${durations[index]} ms", color = Muted, modifier = Modifier.padding(start = 12.dp))
            }
        }

        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            placeholder = { Text(existing?.name ?: repo.nextName(), color = Muted) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }

    if (effects) {
        AlertDialog(
            onDismissRequest = { effects = false },
            containerColor = Surface2,
            title = { Text("Effects") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesignEffect.entries.forEach { e ->
                        ChoiceCard(e.label, e.hint, false, Modifier.fillMaxWidth()) {
                            val before = animation()
                            undo = before
                            setAll(e.apply(before))
                            effects = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { effects = false }) { Text("Cancel", color = Color.White) } },
        )
    }
    if (confirmLeave) {
        ConfirmDialog("Discard changes?", "Your drawing hasn't been saved.", "Discard", onConfirm = onClose, onDismiss = { confirmLeave = false })
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog("Delete \"${existing.name}\"?", "It can't be brought back.", "Delete", onConfirm = {
            delete(existing)
            onClose()
        }, onDismiss = { confirmDelete = false })
    }
}

/** The editor's drawing tools; shapes are dragged from one corner (or the centre) to the other. */
private enum class Tool(val label: String) { PEN("Pen"), FILL("Fill"), LINE("Line"), BOX("Box"), CIRCLE("Circle") }

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Surface2)
