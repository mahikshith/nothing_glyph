package com.dotforge.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val PREVIEW_MS = 15_000L
private const val DEFAULT_FRAME_MS = 100
private const val MAX_EDIT_FRAME_MS = 2_000

private fun readClipboard(ctx: Context): String? =
    ctx.getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()

private fun readUri(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }

/** Pixel designer and animator for the round matrix. Saves and shares Dot Hub / Glyph Museum JSON. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen() {
    val ctx = LocalContext.current
    val profile = AppGraph.profile

    val initial = remember { AppGraph.studio.load() ?: Animation.single(Frame.blank(profile), DEFAULT_FRAME_MS) }
    val frames = remember { initial.frames.map { it.frame.copy() }.toMutableStateList() }
    val durations = remember { initial.frames.map { it.durationMs }.toMutableStateList() }
    var index by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var brush by remember { mutableFloatStateOf(255f) }
    var erase by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    fun load(a: Animation) {
        frames.clear(); durations.clear()
        a.frames.forEach { frames += it.frame.copy(); durations += it.durationMs }
        index = 0
        version++
    }
    fun current() = frames[index.coerceIn(0, frames.lastIndex)]
    fun animation() = Animation(profile, frames.indices.map { AnimFrame(frames[it].copy(), durations[it]) })
    fun importJson(json: String?) {
        runCatching { Animation.fromMuseumJson(profile, json.orEmpty()) }
            .onSuccess(::load)
            .onFailure { Toast.makeText(ctx, "Not a ${profile.ledCount}-LED animation: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importJson(runCatching { readUri(ctx, uri) }.getOrNull())
    }

    LaunchedEffect(playing) {
        while (playing) {
            delay(durations[index].coerceAtLeast(Animation.MIN_FRAME_MS).toLong())
            index = (index + 1) % frames.size
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Studio  ·  frame ${index + 1}/${frames.size}")

        MatrixView(
            pixels = current().pixels, profile = profile, version = version,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            onCell = { x, y ->
                if (!playing) {
                    current()[x, y] = if (erase) 0 else brush.roundToInt()
                    version++
                }
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness ${brush.roundToInt()}", Modifier.padding(end = 8.dp), fontFamily = FontFamily.Monospace)
            Slider(brush, { brush = it }, valueRange = 10f..255f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Frame ${durations[index]} ms", Modifier.padding(end = 8.dp), fontFamily = FontFamily.Monospace)
            Slider(
                value = durations[index].coerceAtMost(MAX_EDIT_FRAME_MS).toFloat(),
                onValueChange = { durations[index] = it.roundToInt() },
                valueRange = Animation.MIN_FRAME_MS.toFloat()..MAX_EDIT_FRAME_MS.toFloat(),
            )
        }
        OutlinedButton(onClick = { durations.indices.forEach { durations[it] = durations[index] } }) {
            Text("Use this timing for all frames")
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !erase, onClick = { erase = false }, label = { Text("Pen") })
            FilterChip(selected = erase, onClick = { erase = true }, label = { Text("Eraser") })
            OutlinedButton(onClick = { current().fill(0); version++ }) { Text("Clear") }
            OutlinedButton(onClick = { current().fill(brush.roundToInt()); version++ }) { Text("Fill") }
            OutlinedButton(onClick = { frames[index] = current().inverted(); version++ }) { Text("Invert") }
        }

        SectionTitle("Frames")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { index = (index - 1 + frames.size) % frames.size }) { Text("◀") }
            OutlinedButton(onClick = { index = (index + 1) % frames.size }) { Text("▶") }
            OutlinedButton(onClick = {
                frames.add(index + 1, Frame.blank(profile)); durations.add(index + 1, durations[index]); index++
            }) { Text("+ New") }
            OutlinedButton(onClick = {
                frames.add(index + 1, current().copy()); durations.add(index + 1, durations[index]); index++
            }) { Text("Duplicate") }
            OutlinedButton(onClick = {
                if (frames.size > 1) {
                    frames.removeAt(index); durations.removeAt(index)
                    index = index.coerceAtMost(frames.lastIndex)
                }
            }) { Text("Delete") }
            Button(onClick = { playing = !playing }) { Text(if (playing) "Stop" else "Play") }
        }

        SectionTitle("Start from")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { load(Effects.pulse(profile)) }) { Text("Pulse") }
            OutlinedButton(onClick = { load(Effects.ringChase(profile)) }) { Text("Ring") }
            OutlinedButton(onClick = { load(Effects.sparkle(profile)) }) { Text("Sparkle") }
            OutlinedButton(onClick = { load(Effects.flash(profile)) }) { Text("Flash") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, label = { Text("Scrolling text") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (text.isNotBlank()) load(Effects.marquee(profile, text)) }, Modifier.padding(start = 8.dp)) { Text("Make") }
        }

        SectionTitle("Use it")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                AppGraph.arbiter.post(
                    DisplayRequest("studio", Priority.STUDIO_PREVIEW, animation(), loop = true, timeoutMs = PREVIEW_MS, label = "Studio preview"),
                )
            }) { Text("Show on Glyphs (15 s)") }
            OutlinedButton(onClick = {
                AppGraph.studio.save(animation())
                Toast.makeText(ctx, "Saved. Used by the DF Player toy and the 'My Studio animation' effect.", Toast.LENGTH_LONG).show()
            }) { Text("Save") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, animation().toMuseumJson())
                ctx.startActivity(Intent.createChooser(send, "Export animation"))
            }) { Text("Export") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("Import file")
            }
            OutlinedButton(onClick = { importJson(readClipboard(ctx)) }) { Text("Import from clipboard") }
        }
        Text(
            "Format: {\"version\":1,\"frames\":[{\"duration\":ms,\"values\":[${profile.ledCount} values 0-255]}]} " +
                "(Dot Hub / Glyph Museum)",
            color = Color(0xFF7A7A7A), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        )
    }
}
