package com.dotforge.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.driver.DriverState
import com.dotforge.driver.GlyphDriver
import com.dotforge.system.NothingSettings
import com.dotforge.tile.TorchTileService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")

private fun addTorchTile(ctx: Context) {
    ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(ctx, TorchTileService::class.java), ctx.getString(R.string.torch_tile),
        Icon.createWithResource(ctx, R.drawable.ic_toy), ctx.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Torch tile added"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Torch tile is already there"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile not added"
            else -> "Could not add the tile"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

/** Settings: the live permission checklist, what Nothing's own Glyph settings say, Advanced, About. */
@Composable
fun SettingsScreen(nav: Nav, setup: Setup, scroll: ScrollState) {
    val ctx = LocalContext.current
    val needsContacts by rememberNeedsContacts()
    val filters by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val driver by GlyphDriver.state.collectAsStateWithLifecycle()
    // re-read on every resume: the user may have changed these in Nothing's settings
    val lights = remember(setup.resumes) { NothingSettings.glyphLightsEnabled(ctx.contentResolver) }
    val bedtime = remember(setup.resumes) { NothingSettings.bedtime(ctx.contentResolver) }
    var advanced by rememberToggle()

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {
        DotTitle("SETTINGS", Modifier.padding(top = 20.dp))

        GroupTitle("SETUP")
        ValueRow(
            "Notification access", if (setup.notificationAccess) "Granted" else "Tap to grant",
            subtitle = "Notification glyphs, progress rings",
            attention = !setup.notificationAccess,
        ) {
            if (setup.notificationAccess) setup.manageAccess() else setup.needNotificationAccess()
        }
        ValueRow(
            "Contacts",
            when {
                setup.contacts -> "Granted"
                needsContacts -> "Tap to grant"
                else -> "Not needed"
            },
            subtitle = "People in rules and the spam filter",
            attention = needsContacts && !setup.contacts,
        ) { if (!setup.contacts) setup.needContacts() }
        ValueRow("Torch in Quick Settings", "Add", subtitle = "Turn the torch on from the shade") { addTorchTile(ctx) }

        GroupTitle("NOTHING GLYPH")
        ValueRow(
            "Glyph Matrix",
            when {
                !AppGraph.device.isHardware -> "Not found"
                driver == DriverState.Ready -> "Connected"
                driver is DriverState.Error -> "Error"
                else -> "Ready"
            },
            subtitle = if (AppGraph.device.isHardware) null else "Not a Nothing phone: lights only show in the app",
        )
        ValueRow(
            "Glyph switch",
            when (lights) {
                true -> "On"
                false -> "Off"
                null -> "Unknown"
            },
            subtitle = "When off, notification glyphs and progress pause",
            attention = lights == false,
        )
        ValueRow(
            "Bedtime",
            bedtime?.let {
                "${it.start.format(hm)}–${it.end.format(hm)}" + if (it.isActive(LocalDateTime.now())) " · now" else ""
            } ?: if (lights == null) "Unknown" else "Off",
            subtitle = if (filters.quiet.followBedtime) "Notification glyphs and progress pause at bedtime"
            else "Ignored: Quiet hours don't follow it",
        )
        ValueRow("Glyph Toys", "Open", subtitle = "Add or reorder toys") { openToysManager(ctx) }

        Collapsible("Advanced", advanced, { advanced = !advanced }) {
            ValueRow("Diagnostics", "Open", subtitle = "Driver test bench, settings keys, toy log") { nav.push(Page.Diagnostics) }
            ValueRow("Rule presets", "Restore", subtitle = "Add back Calls, Messages, OTP codes and Delivery") {
                AppGraph.rules.addPresets()
                Toast.makeText(ctx, "Presets restored", Toast.LENGTH_SHORT).show()
            }
        }

        GroupTitle("SKIN")
        Skin.entries.chunked(2).forEach { row ->
            TileRow {
                row.forEach { s ->
                    ChoiceCard(s.label, if (s == Skin.RED) "The default" else "Accent colour", s == Skins.current, Modifier.weight(1f)) { Skins.set(s) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        GroupTitle("ABOUT")
        ValueRow("DotForge", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty())
        Text("Everything runs on this phone. No account, no ads, no tracking.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
}
