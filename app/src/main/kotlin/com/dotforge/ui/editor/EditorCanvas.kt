package com.dotforge.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.dotforge.AppGraph
import com.dotforge.device.Draw
import com.dotforge.device.EditorDoc
import com.dotforge.device.EditorTool
import com.dotforge.device.FrameOp
import com.dotforge.device.LayerKind
import com.dotforge.device.Mirror
import com.dotforge.device.Region
import com.dotforge.ui.design.Tokens
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The matrix being edited: only the 489 real LEDs, as circles; off dots in 20% grey. The previous frame shows
 * as outlined rings (onion skin) so it can't be mistaken for dim dots. One finger draws; a second finger cancels
 * the stroke and pinches (1x-3x) or pans. While playing, a tap pauses and never draws.
 */
@Composable
fun EditorCanvas(s: EditorSession, side: Dp, modifier: Modifier = Modifier, onPicked: (Int) -> Unit = {}) {
    val p = AppGraph.profile
    val n = p.size
    @Suppress("UNUSED_EXPRESSION") s.tick
    val pixels = s.visible()
    val prev = if (s.onion && !s.playing && s.doc.size > 1 && s.layer == LayerKind.DRAWING && s.doc.cur > 0) s.doc.frames[s.doc.cur - 1] else null
    val lit = pixels.count { it > 0 }
    // the trace photo: drawn under the dots here only, never part of the frames or what the Glyph shows
    val trace = s.trace?.takeIf { it.visible }
    val traceImage = rememberTraceImage(trace?.file)
    val label = buildString {
        append("Dot editor")
        if (s.doc.size > 1) append(", frame ${s.doc.cur + 1} of ${s.doc.size}")
        append(", $lit of ${p.ledCount} dots lit")
        if (trace != null) append(", trace photo under the grid")
    }
    Box(modifier.size(side).clipToBounds(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(side)
                .graphicsLayer { scaleX = s.zoom; scaleY = s.zoom; translationX = s.pan.x; translationY = s.pan.y }
                .semantics {
                    contentDescription = label
                    customActions = listOf(
                        CustomAccessibilityAction("Clear frame") { s.edit { clearFrame() }; true },
                        CustomAccessibilityAction("Invert") { s.edit { change(FrameOp.INVERT, all = false) }; true },
                        CustomAccessibilityAction("Next frame") { s.step(true); true },
                        CustomAccessibilityAction(if (s.playing) "Pause" else "Play") { s.togglePlay(); true },
                    )
                }
                .pointerInput(s) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val cell = size.width / n.toFloat()
                        // the matrix is drawn scaled by graphicsLayer: touches arrive in its own space already
                        fun at(o: Offset): Pair<Int, Int> = (o.x / cell).toInt().coerceIn(0, n - 1) to (o.y / cell).toInt().coerceIn(0, n - 1)
                        if (s.playing) {
                            s.pause()
                            down.consume()
                            return@awaitEachGesture
                        }
                        if (s.layer != LayerKind.DRAWING) {
                            // the background and text layers are changed from their panel, not drawn on
                            down.consume()
                            return@awaitEachGesture
                        }
                        val start = at(down.position)
                        if (s.picking) {
                            val v = s.doc.current[p.index(start.first, start.second)]
                            if (v > 0) onPicked(v)
                            down.consume()
                            return@awaitEachGesture
                        }
                        val stroke = Stroke(s, start)
                        stroke.begin()
                        var multi = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (!multi) { multi = true; stroke.cancel() }
                                val a = pressed[0]
                                val b = pressed[1]
                                val before = hypot((a.previousPosition - b.previousPosition).x, (a.previousPosition - b.previousPosition).y)
                                val now = hypot((a.position - b.position).x, (a.position - b.position).y)
                                if (before > 1f) s.zoom = (s.zoom * now / before).coerceIn(1f, MAX_ZOOM)
                                val mid = (a.position + b.position) / 2f - (a.previousPosition + b.previousPosition) / 2f
                                s.pan = clampPan(s.pan + mid * s.zoom, s.zoom, size.width.toFloat())
                                if (s.zoom == 1f) s.pan = Offset.Zero
                                ev.changes.forEach(PointerInputChange::consume)
                                continue
                            }
                            if (multi) continue
                            val c = pressed[0]
                            if (c.positionChanged()) stroke.to(at(c.position))
                            c.consume()
                        }
                        if (!multi) stroke.end()
                    }
                },
        ) {
            val cell = size.width / n
            val r = cell * 0.4f
            val tracing = trace != null && traceImage != null
            if (tracing) {
                val img = traceImage!!
                val side = minOf(img.width, img.height)
                clipPath(Path().apply { addOval(Rect(Offset.Zero, size)) }) {
                    drawImage(
                        img, srcOffset = IntOffset((img.width - side) / 2, (img.height - side) / 2), srcSize = IntSize(side, side),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = trace!!.opacity / 100f,
                    )
                }
            }
            // off dots let the trace photo show through
            val off = if (tracing) Tokens.DotOff.copy(alpha = 0.45f) else Tokens.DotOff
            for (i in p.ledCells) {
                val v = pixels[i]
                val c = Offset((i % n + 0.5f) * cell, (i / n + 0.5f) * cell)
                drawCircle(if (v == 0) off else Color.White.copy(alpha = 0.25f + 0.75f * v / 255f), r, c)
                if (prev != null && v == 0 && prev[i] > 0) drawCircle(Color.White.copy(alpha = 0.3f), r * 0.85f, c, style = Stroke(width = cell * 0.09f))
            }
            if (s.layer == LayerKind.DRAWING && s.brush.mirror != Mirror.OFF && (s.tool == Tool.DRAW || s.tool == Tool.ERASE || s.tool == Tool.SHAPES)) {
                val axis = Color.White.copy(alpha = 0.22f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(cell * 0.4f, cell * 0.4f))
                if (s.brush.mirror == Mirror.H || s.brush.mirror == Mirror.BOTH) drawLine(axis, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), cell * 0.08f, pathEffect = dash)
                if (s.brush.mirror == Mirror.V || s.brush.mirror == Mirror.BOTH) drawLine(axis, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), cell * 0.08f, pathEffect = dash)
            }
            s.selection?.takeIf { s.tool == Tool.SELECT }?.let { sel ->
                drawRect(
                    Color.White, Offset(sel.left * cell, sel.top * cell), Size(sel.width * cell, sel.height * cell),
                    style = Stroke(width = cell * 0.1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(cell * 0.35f, cell * 0.25f))),
                )
            }
        }
        if (s.zoom > 1f) {
            TextButton(
                onClick = { s.zoom = 1f; s.pan = Offset.Zero },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
                    .semantics { contentDescription = "Fit: zoomed ${"%.1f".format(s.zoom)}x" },
            ) { Text("Fit · ${"%.1f".format(s.zoom).removeSuffix(".0")}x", color = Color.White) }
        }
    }
}

/** The trace photo [file] from the designs' media folder, decoded off the main thread; null while loading or if missing. */
@Composable
fun rememberTraceImage(file: String?): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(File(AppGraph.designs.media, file).path)?.asImageBitmap() }.getOrNull()
        }
    }
    return img
}

/** Pinch zoom stops at 3x: a dot is then about 40 dp, easy to hit with a finger. */
const val MAX_ZOOM = 3f

/** Keeps the zoomed matrix covering its box. */
fun clampPan(o: Offset, zoom: Float, w: Float): Offset {
    val m = (zoom - 1f) * w / 2f
    return Offset(o.x.coerceIn(-m, m), o.y.coerceIn(-m, m))
}

/** One finger's stroke with the current tool: every touched dot, joined by straight lines so fast drags leave no gaps. */
private class Stroke(val s: EditorSession, val start: Pair<Int, Int>) {
    private var last = start
    private val doc: EditorDoc get() = s.doc
    private var moving: Region? = null

    fun begin() {
        doc.beginStroke()
        when (s.tool) {
            Tool.DRAW -> dot(start)
            Tool.ERASE -> dot(start, erase = true)
            Tool.FILL -> doc.draw { Draw.fill(it, start.first, start.second, s.brush.value) }
            Tool.SHAPES -> EditorDoc.apply(doc, s.shape, start, start.first, start.second, s.brush.value, s.filled, s.brush.mirror)
            Tool.SELECT -> {
                val sel = s.selection
                if (sel != null && sel.contains(start.first, start.second)) moving = sel
                else s.selection = Region(start.first, start.second, start.first, start.second)
            }
        }
        s.edit { }
    }

    private fun dot(c: Pair<Int, Int>, erase: Boolean = false) = doc.brush(s.brush, c.first, c.second, erase)

    fun to(c: Pair<Int, Int>) {
        if (c == last) return
        when (s.tool) {
            Tool.DRAW, Tool.ERASE -> {
                // the dots on the line from the last touch, so a fast drag leaves no gaps
                val steps = max(abs(c.first - last.first), abs(c.second - last.second))
                for (i in 1..steps) {
                    val x = last.first + (c.first - last.first) * i / steps
                    val y = last.second + (c.second - last.second) * i / steps
                    dot(x to y, erase = s.tool == Tool.ERASE)
                }
            }
            Tool.FILL -> Unit
            Tool.SHAPES -> EditorDoc.apply(doc, s.shape, start, c.first, c.second, s.brush.value, s.filled, s.brush.mirror)
            Tool.SELECT -> {
                val m = moving
                if (m != null) doc.moveRegion(m, c.first - start.first, c.second - start.second)
                else s.selection = Region(start.first, start.second, c.first, c.second)
            }
        }
        last = c
        s.edit { }
    }

    fun end() {
        moving?.let { m -> s.selection = m.moved(last.first - start.first, last.second - start.second) }
        s.edit { endStroke() }
    }

    /** A second finger landed: the stroke so far is taken back. */
    fun cancel() {
        val before = doc.edits
        doc.endStroke()
        if (doc.edits > before) doc.undo()
        s.edit { }
    }
}
