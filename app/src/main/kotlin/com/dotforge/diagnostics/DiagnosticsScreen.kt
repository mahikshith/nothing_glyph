package com.dotforge.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dotforge.AppGraph
import com.dotforge.driver.DriverState
import com.dotforge.driver.GlyphDriver
import com.dotforge.ui.DotTitle
import com.dotforge.ui.MatrixView
import com.dotforge.ui.MonoSmall
import com.dotforge.ui.NothingRed

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(title.uppercase(), style = MaterialTheme.typography.titleMedium, color = NothingRed)
        content()
    }
}

@Composable
private fun Btn(label: String, accent: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (accent) NothingRed else Color.White),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (accent) NothingRed else Color.White),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(label) }
}

/**
 * The four on-phone checks: driver, Nothing settings, ringtone reader and toy event log. While open it holds
 * the driver and suspends the display arbiter so test frames are not overwritten.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(onClose: () -> Unit, vm: DiagnosticsViewModel = viewModel()) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        GlyphDriver.acquire(ctx)
        AppGraph.arbiter.setSuspended(true)
        onDispose {
            vm.stop()
            GlyphDriver.closeApp()
            GlyphDriver.release()
            AppGraph.arbiter.setSuspended(false)
        }
    }
    val state by GlyphDriver.state.collectAsStateWithLifecycle()
    val fps by GlyphDriver.fps.collectAsStateWithLifecycle()
    val stats by GlyphDriver.stats.collectAsStateWithLifecycle()
    val calls by GlyphDriver.calls.collectAsStateWithLifecycle()
    val frame by GlyphDriver.lastFrame.collectAsStateWithLifecycle()
    val brightness by GlyphDriver.brightness.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val stress by vm.stress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ringtone by vm.ringtone.collectAsStateWithLifecycle()
    val events by EventLog.events.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("HELLO DOTFORGE") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                // best effort: keep read access while the app is open
                vm.loadRingtone(uri)
            } catch (_: Throwable) {}
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotTitle("DIAGNOSTICS", dot = 3.dp)
                Spacer(Modifier.weight(1f))
                Btn("Close", onClick = onClose)
            }
            Text("Talks to the Glyph driver directly; rules and torch pause while this is open.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            MatrixView(frame, AppGraph.profile, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }

        item {
            Section("1 / Driver") {
                val (label, color) = when (val s = state) {
                    DriverState.Ready -> "READY" to Color.White
                    DriverState.Connecting -> "CONNECTING" to Color.Gray
                    DriverState.Disconnected -> "DISCONNECTED" to Color.Gray
                    is DriverState.Error -> "ERROR: ${s.message}" to NothingRed
                }
                Text(label, color = color, style = MaterialTheme.typography.titleMedium)
                Text("activity: $status   |   %.0f fps (last 1 s)".format(fps))
                Text("frames ok ${stats.framesSent}  failed ${stats.framesFailed}  last error: ${stats.lastError ?: "-"}", style = MonoSmall)
                Text("The SDK gives no confirmation that LEDs lit: 'ok' only means the call returned.", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("All on") { vm.allOn() }
                    Btn("Checkerboard") { vm.checkerboard() }
                    Btn("Ring sweep") { vm.ringSweep() }
                    Btn("Clear") { vm.clear() }
                    Btn("Close app matrix") { vm.closeApp() }
                    Btn("Stop") { vm.stop() }
                }
                Text("Brightness $brightness / 255")
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { vm.setBrightness(it.toInt()) },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(thumbColor = NothingRed, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF333333)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("scroll text") })
                    Btn("Scroll") { vm.scrollText(text) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("FPS stress 5 s", accent = true) { vm.stressTest() }
                }
                stress?.let { Text(it) }
                if (calls.isNotEmpty()) {
                    Text("recent SDK calls", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    calls.takeLast(8).reversed().forEach {
                        Text((if (it.ok) "ok   " else "FAIL ") + it.name + (it.message?.let { m -> "  $m" } ?: ""),
                            style = MonoSmall, color = if (it.ok) Color.White else NothingRed)
                    }
                }
            }
        }

        item {
            Section("2 / Nothing settings") {
                Text("Global / Secure / System", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                settings.forEach { r ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text(r.key, style = MonoSmall)
                        Text("  G: ${r.global}\n  Sec: ${r.secure}\n  Sys: ${r.system}", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Btn("Refresh") { vm.refreshSettings() }
            }
        }

        item {
            Section("3 / Ringtone reader") {
                Btn("Pick .ogg") { picker.launch(arrayOf("audio/ogg", "application/ogg", "audio/*", "application/octet-stream")) }
                ringtone.message?.let { Text(it, color = NothingRed) }
                ringtone.name?.let { Text("$it  (${ringtone.sizeBytes} bytes)", style = MonoSmall) }
                ringtone.info?.let { i ->
                    Text("COMPOSER: ${i.composer ?: "-"}\nCUSTOM1: ${i.custom1 ?: "-"}\nCUSTOM2: ${i.custom2 ?: "-"}", style = MonoSmall)
                    Text("vendor: ${i.tags?.vendor ?: "-"}   tags: ${i.tags?.comments?.joinToString { it.first } ?: "none"}", style = MonoSmall)
                    if (i.rows != null) {
                        Text("AUTHOR: ${i.columnCount} columns x ${i.rowCount} rows = %.2f s @ 60 fps".format(i.durationSeconds))
                    } else {
                        Text("AUTHOR: ${i.authorError ?: "not present"}", color = NothingRed)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Play lights 60 fps", accent = true) { vm.playRingtone() }
                        Btn("Stop") { vm.stop() }
                    }
                }
            }
        }

        item {
            Section("4 / Toy event log (last 50)") {
                Text("Add any DF toy in Glyph Toys, then press/hold the Glyph Button. Every event a toy receives is logged.", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (events.isEmpty()) Text("no events yet")
                events.reversed().forEach { Text(it.format(), style = MonoSmall) }
                Btn("Clear log") { EventLog.clear(ctx) }
            }
        }
    }
}
