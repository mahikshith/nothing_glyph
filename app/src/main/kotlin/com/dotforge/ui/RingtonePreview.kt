package com.dotforge.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dotforge.AppGraph
import com.dotforge.composition.GlyphAuthor
import com.dotforge.composition.PeakEnvelope
import com.dotforge.composition.SongWindow
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.ringtones.RingtoneImporter
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private var previews = 0

/** Playback of a ringtone with its lights: [posMs] counts from [offsetMs] in the file, over [lengthMs]. */
@Stable
class RingtonePreview(internal val player: MediaPlayer?) {
    var playing by mutableStateOf(false)
    var posMs by mutableLongStateOf(0L)
    var offsetMs = 0L
        internal set
    var lengthMs by mutableLongStateOf(1L)
        internal set

    /** The 0-255 frame at [posMs] (set while composing, read by the caller right after). */
    var pixels: IntArray? = null
        internal set

    fun toggle() { playing = !playing }

    fun seek(ms: Long) {
        posMs = ms.coerceIn(0L, lengthMs)
        runCatching { player?.seekTo(offsetMs + posMs, MediaPlayer.SEEK_CLOSEST) }
    }
}

/**
 * Plays [file] from [offsetMs] for [lengthMs] (null: to its end) and shows the matching row of [rows] (60 fps)
 * on the real matrix, at most ~30 times a second. The last [fadeMs] are faded, as the saved sound will be.
 */
@Composable
fun rememberRingtonePreview(file: File, offsetMs: Long, lengthMs: Long?, rows: List<IntArray>, fadeMs: Int = 0): RingtonePreview {
    val player = remember(file) {
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(file.path)
                prepare()
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    val preview = remember(player) { RingtonePreview(player) }
    val length = (lengthMs ?: player?.duration?.toLong()?.takeIf { it > 0 } ?: (rows.size * 1000L / GlyphAuthor.FPS)).coerceAtLeast(1L)
    preview.lengthMs = length
    if (preview.offsetMs != offsetMs) {
        preview.offsetMs = offsetMs
        preview.posMs = 0L
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener { preview.playing = false; preview.posMs = 0L }
        onDispose { }
    }
    LaunchedEffect(preview.playing, player, offsetMs, length) {
        val p = player ?: return@LaunchedEffect
        if (!preview.playing) {
            if (p.isPlaying) p.pause()
            p.setVolume(1f, 1f)
            return@LaunchedEffect
        }
        if (preview.posMs >= length) preview.posMs = 0L
        p.seekTo(offsetMs + preview.posMs, MediaPlayer.SEEK_CLOSEST)
        p.start()
        while (isActive && preview.playing) {
            val pos = p.currentPosition - offsetMs
            if (pos >= length) {
                preview.playing = false
                preview.posMs = 0L
                break
            }
            preview.posMs = pos.coerceAtLeast(0L)
            val left = length - pos
            val v = if (fadeMs > 0 && left < fadeMs) (left.toFloat() / fadeMs).coerceIn(0f, 1f) else 1f
            p.setVolume(v, v)
            delay(15)
        }
    }
    LifecycleResumeEffect(preview) { onPauseOrDispose { preview.playing = false } }

    val index = (preview.posMs * GlyphAuthor.FPS / 1000).toInt()
    val pixels = remember(rows, index) { rows.getOrNull(index)?.let(RingtoneImporter::toLevels) }
    val lastPost = remember { longArrayOf(0L) }
    // each preview has its own request, so a page leaving does not clear the next page's frame
    val requestId = remember { "ringtone-${previews++}" }
    val profile = AppGraph.profile
    LaunchedEffect(pixels) {
        val now = SystemClock.uptimeMillis()
        if (preview.playing && now - lastPost[0] < 33) return@LaunchedEffect
        lastPost[0] = now
        val frame = Frame.of(profile, pixels ?: IntArray(profile.cellCount))
        AppGraph.arbiter.post(DisplayRequest(requestId, Priority.STUDIO_PREVIEW, Animation.single(frame, 1_000), loop = true, label = "Ringtone preview"))
    }
    DisposableEffect(requestId) { onDispose { AppGraph.arbiter.remove(requestId) } }
    preview.pixels = pixels
    return preview
}

/** Play / pause, scrubber and time. */
@Composable
fun PreviewTransport(preview: RingtonePreview) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = preview::toggle, enabled = preview.player != null) {
            Icon(if (preview.playing) PauseIcon else Icons.Filled.PlayArrow, if (preview.playing) "Pause" else "Play")
        }
        Slider(
            value = preview.posMs.coerceAtMost(preview.lengthMs).toFloat(),
            onValueChange = { preview.seek(it.toLong()) },
            valueRange = 0f..preview.lengthMs.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Surface2),
        )
        Text(clock(preview.posMs), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

val PauseIcon: ImageVector = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
    for (x in listOf(6f, 14f)) {
        addPath(PathData { moveTo(x, 5f); lineTo(x + 4f, 5f); lineTo(x + 4f, 19f); lineTo(x, 19f); close() }, fill = SolidColor(Color.Black))
    }
}.build()

/**
 * The song's waveform with the chosen part highlighted. Drag the part (or its left edge) to move it, drag its
 * right edge to change the length (up to 40 s), tap elsewhere to start the part there.
 */
@Composable
fun SongWindowBar(envelope: FloatArray, window: SongWindow, onChange: (SongWindow) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(window)
    val change by rememberUpdatedState(onChange)
    val bars = remember(envelope) { PeakEnvelope.bars(envelope, 120) }
    Canvas(
        modifier.fillMaxWidth().height(72.dp)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val w = current
                    val at = (o.x / size.width * w.songMs).toLong()
                    change(w.movedTo(at - w.lengthMs / 2))
                }
            }
            .pointerInput(Unit) {
                var resizing = false
                var grabMs = 0L
                detectDragGestures(
                    onDragStart = { o ->
                        val w = current
                        val endX = w.endMs.toFloat() / w.songMs * size.width
                        resizing = abs(o.x - endX) < 28.dp.toPx()
                        grabMs = (o.x / size.width * w.songMs).toLong() - w.startMs
                    },
                ) { change0, _ ->
                    val w = current
                    val at = (change0.position.x / size.width * w.songMs).toLong()
                    change(if (resizing) w.withEnd(at) else w.movedTo(at - grabMs))
                }
            },
    ) {
        val w = current
        val n = bars.size.coerceAtLeast(1)
        val bw = size.width / n
        val x0 = w.startMs.toFloat() / w.songMs * size.width
        val x1 = w.endMs.toFloat() / w.songMs * size.width
        drawRoundRect(Surface2, Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(8.dp.toPx()))
        bars.forEachIndexed { i, v ->
            val h = (0.08f + 0.92f * v) * size.height * 0.9f
            val cx = (i + 0.5f) * bw
            val inside = cx in x0..x1
            drawLine(
                if (inside) Color.White else Color(0xFF444444),
                Offset(cx, (size.height - h) / 2), Offset(cx, (size.height + h) / 2),
                strokeWidth = (bw * 0.55f).coerceAtLeast(1f),
            )
        }
        drawRoundRect(NothingRed, Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
        drawLine(NothingRed, Offset(x1, 0f), Offset(x1, size.height), strokeWidth = 5.dp.toPx())
    }
}
