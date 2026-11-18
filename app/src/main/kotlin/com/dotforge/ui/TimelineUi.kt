package com.dotforge.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.AppGraph
import com.dotforge.composition.GlyphAuthor
import com.dotforge.composition.Lane
import com.dotforge.composition.PeakEnvelope
import com.dotforge.composition.Region
import com.dotforge.composition.Shape
import com.dotforge.composition.TimelineCodec
import com.dotforge.composition.TimelineRenderer
import com.dotforge.ringtones.AudioCodec
import com.dotforge.ringtones.ImportMode
import com.dotforge.ringtones.ImportSession
import com.dotforge.ringtones.ImportSessions
import com.dotforge.ringtones.TimelineEditorState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val LANE_HEIGHT = 56.dp
private val LABEL_WIDTH = 84.dp
private val FRAME_WIDTH = 1.dp
/** Envelope buckets for the waveform strip. */
private const val WAVE_BUCKET_MS = 50

/**
 * The timeline editor: one screen. Waveform on top, then a lane per region. Tap a lane to add a key at that
 * time and height, drag a key to move it (sideways for time, up and down for brightness), tap a key for its
 * shape and brightness. Undo, Add lane, and Save (AUTHOR rendered at 60 fps, the timeline kept in CUSTOM1).
 */
@Composable
fun TimelineEditorPage(nav: Nav) {
    val ctx = LocalContext.current
    val session = ImportSessions.current
    val src = session?.source
    if (session == null || src == null) {
        PageScaffold("EDIT LIGHTS", onBack = nav::pop) {
            EmptyState("Nothing to edit", "Open the ringtone again.", "Go back") { nav.pop() }
        }
        return
    }
    val editor = remember(session) { session.editor() }
    val timeline = editor.timeline
    var rows by remember { mutableStateOf(emptyList<IntArray>()) }
    LaunchedEffect(timeline) {
        delay(60)
        rows = withContext(Dispatchers.Default) { TimelineRenderer.render(timeline, editor.imported, AppGraph.profile) }
    }
    val part = session.part
    val wave by produceWave(session)
    LaunchedEffect(session.error) { session.error?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); session.error = null } }
    val preview = rememberRingtonePreview(
        src.playFile,
        offsetMs = part?.window?.startMs ?: 0L,
        lengthMs = part?.window?.lengthMs ?: (timeline.frames * 1000L / GlyphAuthor.FPS),
        rows = rows,
        fadeMs = if (part?.window?.cutsEnd == true) 1_500 else 0,
    )

    fun save() {
        val rendered = TimelineRenderer.render(timeline, editor.imported, AppGraph.profile)
        session.save(
            AppGraph.ringtones,
            override = rendered,
            custom1 = TimelineCodec.encode(editor.forSaving()),
            replace = session.savedUri,
        ) {
            session.applyEdit(rendered)
            Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            nav.pop()
        }
    }

    PageScaffold(
        "EDIT LIGHTS",
        onBack = nav::pop,
        actions = { TextButton(onClick = editor::undo, enabled = editor.canUndo) { Text("Undo", color = if (editor.canUndo) Color.White else Muted) } },
        bottomBar = {
            PrimaryButton(if (session.saving) "Saving..." else "Save", Modifier.fillMaxWidth(), enabled = !session.saving && rows.isNotEmpty(), onClick = ::save)
        },
    ) {
        MatrixView(preview.pixels, AppGraph.profile, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally))
        PreviewTransport(preview)
        Timeline(editor, wave, preview)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tap a lane to add a key. Drag keys to move them.", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { editor.addLaneSheet = true }) { Text("Add lane", color = NothingRed) }
        }
        if (session.mode == ImportMode.SAVED || session.savedUri != null) {
            Text("Save replaces the saved ringtone.", color = Muted, fontSize = 13.sp)
        }
    }
    EditorSheets(editor)
}

/** The waveform peaks of what the preview plays: the song part, or the glyph file's audio. */
@Composable
private fun produceWave(session: ImportSession): androidx.compose.runtime.State<FloatArray> {
    val state = remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(session.part, session.source) {
        val src = session.source ?: return@LaunchedEffect
        state.value = withContext(Dispatchers.IO) {
            runCatching {
                val pcm = session.part?.pcm
                if (pcm != null) {
                    PeakEnvelope(pcm.sampleRate, pcm.channels, WAVE_BUCKET_MS).apply { add(pcm.samples) }.result()
                } else {
                    src.ogg?.let { AudioCodec.scan(it).envelope } ?: FloatArray(0)
                }
            }.getOrDefault(FloatArray(0))
        }
    }
    return state
}

@Composable
private fun Timeline(editor: TimelineEditorState, wave: FloatArray, preview: RingtonePreview) {
    val density = LocalDensity.current
    val t = editor.timeline
    val scroll = rememberScrollState()
    val fpx = with(density) { FRAME_WIDTH.toPx() }
    val playFrame = (preview.posMs * GlyphAuthor.FPS / 1000).toInt()
    // keep the playhead in view while playing
    LaunchedEffect(playFrame / 30, preview.playing) {
        if (!preview.playing) return@LaunchedEffect
        val x = (playFrame * fpx).toInt()
        val view = scroll.viewportSize
        if (view > 0 && (x < scroll.value || x > scroll.value + view - 48)) scroll.scrollTo((x - 48).coerceAtLeast(0))
    }
    val contentWidth = FRAME_WIDTH * t.frames
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.width(LABEL_WIDTH)) {
            Box(Modifier.height(44.dp), contentAlignment = Alignment.CenterStart) { Text("Sound", color = Muted, fontSize = 13.sp) }
            t.lanes.forEach { lane ->
                Box(
                    Modifier.height(LANE_HEIGHT).padding(vertical = 3.dp).fillMaxWidth().clickable {
                        editor.checkpoint()
                        editor.laneSheet = lane.id
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(lane.label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
                }
            }
        }
        Column(Modifier.weight(1f).horizontalScroll(scroll)) {
            Canvas(
                Modifier.width(contentWidth).height(44.dp).pointerInput(t.frames) {
                    detectTapGestures { o -> preview.seek((o.x / fpx).toLong() * 1000 / GlyphAuthor.FPS) }
                },
            ) {
                val perBucket = WAVE_BUCKET_MS * GlyphAuthor.FPS / 1000f
                wave.forEachIndexed { i, v ->
                    val x = i * perBucket * fpx
                    val h = (0.06f + 0.94f * v) * size.height
                    drawLine(Color(0xFF666666), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = (perBucket * fpx * 0.6f).coerceAtLeast(1f))
                }
                drawLine(NothingRed, Offset(playFrame * fpx, 0f), Offset(playFrame * fpx, size.height), strokeWidth = 2f)
            }
            t.lanes.forEach { lane -> LaneCanvas(editor, lane, contentWidth, fpx, playFrame) }
        }
    }
}

@Composable
private fun LaneCanvas(editor: TimelineEditorState, lane: Lane, width: androidx.compose.ui.unit.Dp, fpx: Float, playFrame: Int) {
    val current by rememberUpdatedState(lane)
    val frames = editor.timeline.frames
    val energy = remember(editor.imported, lane.region) {
        if (lane.region != Region.IMPORTED) null
        else editor.imported?.let { rows -> FloatArray(rows.size) { f -> rows[f].sum().toFloat() / (489 * GlyphAuthor.MAX) } }
    }
    val selected = editor.selected
    Canvas(
        Modifier.width(width).height(LANE_HEIGHT).padding(vertical = 3.dp)
            .background(Surface1, RoundedCornerShape(10.dp))
            .pointerInput(lane.id, frames) {
                val touchX = 18.dp.toPx()
                val touchY = 26.dp.toPx()
                fun frameAt(x: Float) = (x / fpx).roundToInt().coerceIn(0, (frames - 1).coerceAtLeast(0))
                fun levelAt(y: Float) = ((1f - y / size.height) * 100 / 5).roundToInt().coerceIn(0, 20) * 5
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val l = current
                    if (l.region == Region.IMPORTED) {
                        if (waitForUpOrCancellation() != null) {
                            editor.checkpoint()
                            editor.laneSheet = l.id
                        }
                        return@awaitEachGesture
                    }
                    val hit = l.keys.minByOrNull { abs(it.frame * fpx - down.position.x) }?.takeIf {
                        abs(it.frame * fpx - down.position.x) < touchX &&
                            abs((1f - it.level / 100f) * size.height - down.position.y) < touchY
                    }
                    if (hit == null) {
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        editor.addKey(l.id, frameAt(up.position.x), levelAt(up.position.y))
                        return@awaitEachGesture
                    }
                    down.consume()
                    editor.checkpoint()
                    editor.selected = l.id to hit.id
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (!moved && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) editor.moveKey(l.id, hit.id, frameAt(c.position.x), levelAt(c.position.y))
                        c.consume()
                    }
                    if (!moved) editor.keySheet = l.id to hit.id
                }
            },
    ) {
        val h = size.height
        if (energy != null) {
            val end = if (lane.trimEnd < 0) frames else lane.trimEnd
            var f = 0
            while (f < energy.size && f < frames) {
                val inside = f in lane.trimStart until end
                val v = (energy[f] * lane.scale).coerceIn(0f, 1f)
                drawLine(if (inside) Color(0xFFBBBBBB) else Color(0xFF333333), Offset(f * fpx, h), Offset(f * fpx, h - v * h), strokeWidth = fpx * 2)
                f += 2
            }
        } else if (lane.keys.isNotEmpty()) {
            val keys = lane.keys
            val path = Path()
            var f = 0
            while (f < frames) {
                val y = (1f - TimelineRenderer.level(keys, f)) * h
                if (f == 0) path.moveTo(0f, y) else path.lineTo(f * fpx, y)
                f += 2
            }
            drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx()))
            for (k in keys) {
                val sel = selected == lane.id to k.id
                drawCircle(if (sel) NothingRed else Color.White, if (sel) 7.dp.toPx() else 5.dp.toPx(), Offset(k.frame * fpx, (1f - k.level / 100f) * h))
            }
        }
        drawLine(NothingRed, Offset(playFrame * fpx, 0f), Offset(playFrame * fpx, h), strokeWidth = 2f)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorSheets(editor: TimelineEditorState) {
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Surface2)
    editor.keySheet?.let { (laneId, keyId) ->
        val lane = editor.timeline.lane(laneId)
        val key = lane?.keys?.firstOrNull { it.id == keyId }
        if (key == null) {
            editor.keySheet = null
            return@let
        }
        EditorSheet(onDismiss = { editor.keySheet = null }) {
            Text("Key at ${clock(key.frame * 1000L / GlyphAuthor.FPS)} · ${lane.label}", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Shape.entries.forEachIndexed { i, s ->
                    SegmentedButton(selected = key.shape == s, onClick = { editor.checkpoint(); editor.editKey(laneId, keyId, shape = s) }, shape = SegmentedButtonDefaults.itemShape(i, Shape.entries.size)) {
                        Text(s.label)
                    }
                }
            }
            Text(key.shape.hint, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", Modifier.padding(end = 12.dp))
                Slider(
                    key.level.toFloat(), { editor.editKey(laneId, keyId, level = it.roundToInt()) },
                    valueRange = 0f..100f, modifier = Modifier.weight(1f), colors = sliderColors,
                    onValueChangeFinished = {},
                )
                Text("${key.level}%", color = Muted, modifier = Modifier.padding(start = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { editor.deleteKey(laneId, keyId) }) { Text("Delete key", color = NothingRed) }
                TextButton(onClick = { editor.keySheet = null }) { Text("Done", color = Color.White) }
            }
        }
    }
    editor.laneSheet?.let { laneId ->
        val lane = editor.timeline.lane(laneId)
        if (lane == null) {
            editor.laneSheet = null
            return@let
        }
        val frames = editor.timeline.frames
        EditorSheet(onDismiss = { editor.laneSheet = null }) {
            Text(lane.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (lane.region == Region.IMPORTED) {
                val end = if (lane.trimEnd < 0) frames else lane.trimEnd
                Text("Plays ${clock(lane.trimStart * 1000L / GlyphAuthor.FPS)} - ${clock(end * 1000L / GlyphAuthor.FPS)}", color = Muted, modifier = Modifier.padding(top = 12.dp))
                RangeSlider(
                    value = lane.trimStart.toFloat()..end.toFloat(),
                    onValueChange = { r -> editor.editImported(laneId, r.start.roundToInt(), r.endInclusive.roundToInt(), lane.scale) },
                    valueRange = 0f..frames.toFloat().coerceAtLeast(1f),
                    colors = sliderColors,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scale", Modifier.padding(end = 12.dp))
                    Slider(lane.scale, { editor.editImported(laneId, lane.trimStart, end, it) }, valueRange = 0f..2f, modifier = Modifier.weight(1f), colors = sliderColors)
                    Text("${(lane.scale * 100).roundToInt()}%", color = Muted, modifier = Modifier.padding(start = 12.dp))
                }
            } else {
                Text("${lane.keys.size} keys. Tap the lane to add more.", color = Muted, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { editor.removeLane(laneId) }) { Text("Remove lane", color = NothingRed) }
                TextButton(onClick = { editor.laneSheet = null }) { Text("Done", color = Color.White) }
            }
        }
    }
    if (editor.addLaneSheet) {
        EditorSheet(onDismiss = { editor.addLaneSheet = false }) {
            Text("Add a lane", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("Rings go inside out, arcs clockwise from the top, bars left to right.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Region.entries.filter { it != Region.IMPORTED }.forEach { r ->
                    (0 until r.parts).forEach { p ->
                        FilterChip(
                            selected = false,
                            onClick = { editor.addLane(r, p); editor.addLaneSheet = false },
                            label = { Text(Lane(0, r, p).label) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = Surface2),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) { content() }
    }
}
