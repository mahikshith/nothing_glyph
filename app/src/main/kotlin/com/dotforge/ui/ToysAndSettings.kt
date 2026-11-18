package com.dotforge.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.engine.MatrixDevices
import com.dotforge.system.NothingSettings
import com.dotforge.tile.TorchTileService
import java.time.LocalDateTime

private class ToyInfo(val name: Int, val summary: Int, val preview: (DeviceProfile) -> Frame)

private val TOYS = listOf(
    ToyInfo(R.string.toy_clock_name, R.string.toy_clock_summary) { Effects.clock(it, 10, 42) },
    ToyInfo(R.string.toy_battery_name, R.string.toy_battery_summary) { Effects.ring(it, 0.7f) },
    ToyInfo(R.string.toy_dice_name, R.string.toy_dice_summary) { Effects.dice(it, 5) },
    ToyInfo(R.string.toy_player_name, R.string.toy_player_summary) { Effects.comet(it, 10) },
)

/** Nothing's Glyph Toys manager (system version 20250829 or later), else the system settings. */
private fun openToysManager(ctx: Context) {
    val manager = Intent().setComponent(
        ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"),
    )
    if (runCatching { ctx.startActivity(manager) }.isFailure) ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
}

@Composable
fun ToysScreen() {
    val ctx = LocalContext.current
    val profile = AppGraph.profile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Glyph toys")
        Text(
            "Add them in Glyph Toys, then press the Glyph Button on the back to cycle and long-press to interact.",
            color = Muted,
        )
        Button(onClick = { openToysManager(ctx) }, Modifier.padding(vertical = 8.dp)) { Text("Manage Glyph Toys") }
        TOYS.forEach { t ->
            val frame = remember(profile) { t.preview(profile) }
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MatrixView(frame.pixels, profile, Modifier.size(84.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(t.name))
                        Text(stringResource(t.summary), color = Muted)
                    }
                }
            }
        }
    }
}

private data class SystemGlyphState(val lights: String, val bedtime: String)

private fun readSystemGlyphState(ctx: Context): SystemGlyphState {
    val cr = ctx.contentResolver
    val lights = when (NothingSettings.glyphLightsEnabled(cr)) {
        true -> "on"
        false -> "off (DotForge notifications and progress pause)"
        null -> "not readable"
    }
    val bedtime = NothingSettings.bedtime(cr)?.let {
        "${it.start}-${it.end}" + if (it.isActive(LocalDateTime.now())) " (active now)" else ""
    } ?: "off or not readable"
    return SystemGlyphState(lights, bedtime)
}

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit) {
    val ctx = LocalContext.current
    val profile = AppGraph.profile
    val system = remember { readSystemGlyphState(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Permissions")
        InfoCard {
            Text("Notification access: " + if (hasNotificationAccess(ctx)) "granted" else "not granted")
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open settings") }
        }

        SectionTitle("Quick Settings")
        InfoCard {
            Text("Add the Glyph torch tile to your Quick Settings panel.")
            OutlinedButton(onClick = {
                ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(ctx, TorchTileService::class.java), ctx.getString(R.string.torch_tile),
                    Icon.createWithResource(ctx, R.drawable.ic_toy), ctx.mainExecutor,
                ) { result ->
                    val msg = when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tile already added"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile not added"
                        else -> "Could not add the tile (code $result)"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }) { Text("Add tile") }
        }

        SectionTitle("Nothing Glyph settings")
        InfoCard {
            Text("Glyph lights: ${system.lights}", fontFamily = FontFamily.Monospace)
            Text("Bedtime: ${system.bedtime}", fontFamily = FontFamily.Monospace)
            Text("Notifications and progress follow these when readable. Torch and Studio preview always work.", color = Muted)
            OutlinedButton(onClick = { openToysManager(ctx) }) { Text("Manage Glyph Toys") }
        }

        SectionTitle("Diagnostics")
        InfoCard {
            Text("Driver test bench, Nothing settings, ringtone reader and toy event log.", color = Muted)
            OutlinedButton(onClick = onOpenDiagnostics) { Text("Open Diagnostics") }
        }

        SectionTitle("About")
        InfoCard {
            Text("DotForge ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}", fontFamily = FontFamily.Monospace)
            Text("Matrix: ${profile.size}x${profile.size}, ${profile.ledCount} LEDs, ring ${profile.ring.size} LEDs", fontFamily = FontFamily.Monospace)
            Text("Nothing phone: ${MatrixDevices.isNothingPhone}", fontFamily = FontFamily.Monospace)
            Text("Everything runs on the phone. No ads, no account, no tracking.", color = Muted)
        }
    }
}
