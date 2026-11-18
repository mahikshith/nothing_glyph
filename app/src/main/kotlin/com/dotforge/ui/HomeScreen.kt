package com.dotforge.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Effects
import com.dotforge.driver.DriverState
import com.dotforge.driver.GlyphDriver
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import com.dotforge.tile.TorchTileService

fun hasNotificationAccess(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

@Composable
private fun deviceStatus(): String {
    if (!AppGraph.device.isHardware) return "Preview mode: not a Nothing phone, the matrix is only drawn here"
    val state by GlyphDriver.state.collectAsStateWithLifecycle()
    return when (val s = state) {
        DriverState.Ready -> "Glyph Matrix connected"
        DriverState.Connecting -> "Glyph Matrix connecting"
        DriverState.Disconnected -> "Glyph Matrix idle (connects when something is shown)"
        is DriverState.Error -> "Glyph Matrix error: ${s.message}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val arbiter = AppGraph.arbiter
    val profile = AppGraph.profile
    val frame by arbiter.frame.collectAsStateWithLifecycle()
    val active by arbiter.active.collectAsStateWithLifecycle()
    val enabled by arbiter.enabled.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        DotTitle("DOTFORGE")
        Text(deviceStatus(), color = Muted, modifier = Modifier.padding(top = 8.dp))

        MatrixView(frame?.pixels, profile, Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 32.dp))

        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glyph lights on")
                    Text("Master switch for rules, progress, Studio preview and torch", color = Muted)
                }
                Switch(checked = enabled, onCheckedChange = { AppGraph.setLightsOn(ctx, it) })
            }
        }

        if (!hasNotificationAccess(ctx)) {
            InfoCard {
                Text("Notification access needed")
                Text("Rules, the spam filter and progress rings read notifications on your phone only.", color = Muted)
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("Grant access")
                }
            }
        }

        SectionTitle("Try it")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val info = NotificationInfo("test", "test.app", "Test", "Alex", "Are we still on for tonight?", System.currentTimeMillis())
                val rule = AppGraph.rules.rules.value.firstOrNull { it.enabled && !it.suppress } ?: return@OutlinedButton
                arbiter.post(
                    DisplayRequest(
                        "n:test", Priority.NOTIFICATION, RuleEngine.animationFor(rule, info, profile, AppGraph.studio.load()),
                        loop = true, timeoutMs = 5_000, label = "Test message",
                    ),
                )
            }) { Text("Message") }
            OutlinedButton(onClick = {
                arbiter.post(DisplayRequest("p:test", Priority.PROGRESS, Effects.progress(profile, 0.62f), loop = true, timeoutMs = 5_000, label = "Test progress"))
            }) { Text("Progress") }
            OutlinedButton(onClick = { TorchTileService.toggle() }) { Text("Torch") }
            OutlinedButton(onClick = { arbiter.clearAll() }) { Text("Clear matrix") }
        }

        SectionTitle("Now showing")
        if (active.isEmpty()) Text("Nothing. The matrix is free.", color = Muted)
        active.forEachIndexed { i, r ->
            Text((if (i == 0) "▶ " else "   ") + "${r.label}  (priority ${r.priority})", fontFamily = FontFamily.Monospace)
        }
        Text(
            "System lights (ringtone, active Glyph Toy, Nothing's notification glyphs) always cover these; " +
                "DotForge re-sends its frame every few seconds and when the screen turns on. " +
                "Notifications and progress also follow Nothing's Glyph switch and bedtime when the phone lets apps read them.",
            color = Muted, modifier = Modifier.padding(top = 12.dp),
        )
    }
}
