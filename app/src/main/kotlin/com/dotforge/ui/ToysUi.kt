package com.dotforge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.toys.ToySettings
import kotlinx.coroutines.delay
import java.time.LocalTime

/** The four Glyph Toys as the app shows them. */
enum class Toy(val nameRes: Int, val summaryRes: Int, val short: String) {
    CLOCK(R.string.toy_clock_name, R.string.toy_clock_summary, "Big dot clock"),
    BATTERY(R.string.toy_battery_name, R.string.toy_battery_summary, "Battery ring"),
    DICE(R.string.toy_dice_name, R.string.toy_dice_summary, "Shake to roll"),
    PLAYER(R.string.toy_player_name, R.string.toy_player_summary, "Plays your design"),
    ;

    fun preview(profile: DeviceProfile): Frame = when (this) {
        CLOCK -> LocalTime.now().let { Effects.clock(profile, ToySettings.displayHour(it.hour, AppGraph.toys.clock24h), it.minute) }
        BATTERY -> Effects.ring(profile, 0.7f)
        DICE -> Effects.dice(profile, 5)
        PLAYER -> AppGraph.playerAnimation()?.frames?.first()?.frame ?: Effects.comet(profile, 10)
    }
}

/** Nothing's Glyph Toys manager (system version 20250829 or later), else the system settings. */
fun openToysManager(ctx: Context) {
    val manager = Intent().setComponent(
        ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"),
    )
    if (runCatching { ctx.startActivity(manager) }.isFailure) ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
}

@Composable
private fun howToSteps(toy: Toy) =
    "1.  Tap Open Glyph Toys.\n2.  Turn on ${stringResource(toy.nameRes)}.\n" +
        "3.  Press the Glyph Button on the back until it shows."

/** Tap on a toy: how to turn it on in Glyph Toys. */
@Composable
fun ToyHowToDialog(toy: Toy, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val preview = remember(toy) { toy.preview(AppGraph.profile) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { MatrixView(preview.pixels, AppGraph.profile, Modifier.size(96.dp)) },
        title = { Text(stringResource(toy.nameRes)) },
        text = {
            Column {
                Text(stringResource(toy.summaryRes), color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                Text(howToSteps(toy))
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); openToysManager(ctx) }) { Text("Open Glyph Toys", color = NothingRed) }
        },
        dismissButton = { TextButton(onClick = onSettings) { Text("Settings", color = Color.White) } },
    )
}

/** Long-press on a toy: its few options, with a live preview. */
@Composable
fun ToySettingsPage(toy: Toy, nav: Nav) {
    val ctx = LocalContext.current
    val profile = AppGraph.profile
    val prefs = AppGraph.toys
    var version by remember { mutableStateOf(0) }
    val preview = remember(toy, version) { toy.preview(profile) }
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    var playerDesign by remember { mutableStateOf(prefs.playerDesignId) }
    if (toy == Toy.CLOCK) LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            version++
        }
    }

    PageScaffold(stringResource(toy.nameRes), onBack = nav::pop, bottomBar = {
        PrimaryButton("Open Glyph Toys", Modifier.fillMaxWidth()) { openToysManager(ctx) }
    }) {
        MatrixView(preview.pixels, profile, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
        Text(stringResource(toy.summaryRes), color = Muted)

        when (toy) {
            Toy.CLOCK -> {
                var h24 by remember { mutableStateOf(prefs.clock24h) }
                GroupTitle("TIME FORMAT")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceCard("24-hour", "Shows 13:45", h24, Modifier.weight(1f)) { h24 = true; prefs.clock24h = true; version++ }
                    ChoiceCard("12-hour", "Shows 01:45", !h24, Modifier.weight(1f)) { h24 = false; prefs.clock24h = false; version++ }
                }
            }
            Toy.BATTERY -> {
                var anim by remember { mutableStateOf(prefs.batteryChargeAnimation) }
                GroupTitle("OPTIONS")
                SwitchRow("Charging animation", "A dot runs round the ring while charging", anim) { anim = it; prefs.batteryChargeAnimation = it }
            }
            Toy.DICE -> {
                var shake by remember { mutableStateOf(prefs.diceShake) }
                GroupTitle("OPTIONS")
                SwitchRow("Shake to roll", "Uses the motion sensor while the toy is on", shake) { shake = it; prefs.diceShake = it }
            }
            Toy.PLAYER -> {
                GroupTitle("PLAYS")
                if (designs.isEmpty()) {
                    EmptyState("No designs yet", "Make one in Create. Until then it plays a ring.", "Open Create") { nav.popTo(Tab.CREATE) }
                } else {
                    val chosen = AppGraph.designs.get(playerDesign)
                    ChoiceCard("Newest design", designs.first().name, chosen == null, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        playerDesign = null; prefs.playerDesignId = null; version++
                    }
                    designs.forEach { d ->
                        ChoiceCard(d.name, if (d.animated) "Animated" else "Static", chosen?.id == d.id, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            playerDesign = d.id; prefs.playerDesignId = d.id; version++
                        }
                    }
                }
            }
        }
        GroupTitle("HOW TO USE")
        Text(howToSteps(toy))
    }
}
