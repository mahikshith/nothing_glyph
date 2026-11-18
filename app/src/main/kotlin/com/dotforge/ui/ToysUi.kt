package com.dotforge.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.composition.VisualStyle
import com.dotforge.composition.VisualiserRenderer
import com.dotforge.device.ClockStyle
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.SnakeGame
import com.dotforge.device.ToyFaces
import com.dotforge.toys.HubFace
import com.dotforge.toys.InstalledToys
import com.dotforge.toys.galleryAnimations
import java.time.LocalTime
import kotlinx.coroutines.delay

/** The four Glyph Toys as the app shows them. */
/** DotForge's toys; [cls] is the service's simple class name (its component). */
enum class Toy(val nameRes: Int, val summaryRes: Int, val short: String, val cls: String) {
    CLOCK(R.string.toy_clock_name, R.string.toy_clock_summary, "Big dot clock", "ClockToy"),
    BATTERY(R.string.toy_battery_name, R.string.toy_battery_summary, "Battery ring", "BatteryToy"),
    DICE(R.string.toy_dice_name, R.string.toy_dice_summary, "Shake to roll", "DiceToy"),
    PLAYER(R.string.toy_player_name, R.string.toy_player_summary, "Plays your design", "PlayerToy"),
    MUSIC(R.string.toy_music_name, R.string.toy_music_summary, "Lights to the music", "MusicToy"),
    HUB(R.string.toy_hub_name, R.string.toy_hub_summary, "Always-on faces", "HubToy"),
    GALLERY(R.string.toy_gallery_name, R.string.toy_gallery_summary, "Designs in turn", "GalleryToy"),
    NOW_PLAYING(R.string.toy_nowplaying_name, R.string.toy_nowplaying_summary, "Play, pause, skip", "NowPlayingToy"),
    TIMER(R.string.toy_timer_name, R.string.toy_timer_summary, "Stopwatch, countdown", "TimerToy"),
    COMPASS(R.string.toy_compass_name, R.string.toy_compass_summary, "Compass and level", "CompassToy"),
    SNAKE(R.string.toy_snake_name, R.string.toy_snake_summary, "The classic game", "SnakeToy"),
    BREATHE(R.string.toy_breathe_name, R.string.toy_breathe_summary, "Slow breathing", "BreatheToy"),
    MOON(R.string.toy_moon_name, R.string.toy_moon_summary, "Moon phase", "MoonToy"),
    SPEED(R.string.toy_speed_name, R.string.toy_speed_summary, "Download speed", "SpeedToy"),
    ;

    fun preview(profile: DeviceProfile): Frame = when (this) {
        CLOCK -> LocalTime.now().let { ToyFaces.clock(profile, AppGraph.toys.clockStyle, it.hour, it.minute, AppGraph.toys.clock24h) }
        HUB -> LocalTime.now().let { ToyFaces.clock(profile, AppGraph.toys.clockStyle, it.hour, it.minute, AppGraph.toys.clock24h) }
        GALLERY -> galleryAnimations().firstOrNull()?.frames?.first()?.frame ?: Effects.comet(profile, 30)
        NOW_PLAYING -> ToyFaces.nowPlaying(profile, true, 0.4f)
        TIMER -> ToyFaces.timer(profile, 0.75f, 300)
        COMPASS -> ToyFaces.compass(profile, 30.0)
        SNAKE -> SnakeGame(profile, 3).frame()
        BREATHE -> ToyFaces.breath(profile, 3.0)
        SPEED -> ToyFaces.speed(profile, 2_400_000)
        MOON -> ToyFaces.moon(profile, ToyFaces.moonAge(java.time.LocalDate.now().toEpochDay() + 0.5))
        BATTERY -> Effects.ring(profile, 0.7f)
        DICE -> Effects.dice(profile, 5)
        PLAYER -> AppGraph.playerAnimation()?.frames?.first()?.frame ?: Effects.comet(profile, 10)
        MUSIC -> Frame.of(profile, VisualiserRenderer.frame(floatArrayOf(0.9f, 0.8f, 0.7f, 0.75f, 0.6f, 0.5f, 0.55f, 0.4f, 0.35f, 0.3f, 0.2f, 0.15f), AppGraph.toys.visualStyle, profile))
    }
}

/** Nothing's Glyph Toys manager, else the Glyph settings, else system Settings. */
fun openToysManager(ctx: Context) = InstalledToys.openManager(ctx)

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
            Toy.CLOCK -> ClockOptions { version++ }
            Toy.HUB -> {
                var face by remember { mutableStateOf(prefs.hubFace) }
                var battery by remember { mutableStateOf(prefs.hubBattery) }
                GroupTitle("FACE")
                HubFace.entries.forEach { f ->
                    ChoiceCard(f.label, f.hint, f == face, Modifier.fillMaxWidth().padding(bottom = 8.dp)) { face = f; prefs.hubFace = f }
                }
                Text("The Always-on face tile in Quick Settings switches it too.", color = Muted, fontSize = 13.sp)
                SwitchRow("Battery ring", "The clock face shows the battery round the edge", battery) { battery = it; prefs.hubBattery = it }
                ClockOptions { version++ }
            }
            Toy.GALLERY -> {
                var chosen by remember { mutableStateOf(prefs.galleryIds) }
                GroupTitle("DESIGNS")
                if (designs.isEmpty()) EmptyState("No designs yet", "Make one in Create, or take one from the library.", "Open Create") { nav.popTo(Tab.STUDIO) }
                else Text(if (chosen.isEmpty()) "None picked: all designs play." else "${chosen.size} picked.", color = Muted, fontSize = 13.sp)
                designs.forEach { d ->
                    SwitchRow(d.name, if (d.animated) "Animated" else "Static", d.id in chosen) { on ->
                        chosen = if (on) chosen + d.id else chosen - d.id
                        prefs.galleryIds = chosen
                        version++
                    }
                }
            }
            Toy.TIMER -> {
                var minutes by remember { mutableStateOf(prefs.timerMinutes) }
                GroupTitle("MODE")
                listOf(0, 1, 3, 5, 10, 25).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { m ->
                            ChoiceCard(if (m == 0) "Stopwatch" else "$m min", if (m == 0) "Counts up" else "Counts down", m == minutes, Modifier.weight(1f)) {
                                minutes = m; prefs.timerMinutes = m
                            }
                        }
                    }
                }
            }
            Toy.NOW_PLAYING -> AccessNote()
            Toy.COMPASS, Toy.SNAKE, Toy.BREATHE, Toy.MOON, Toy.SPEED -> Unit
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
            Toy.MUSIC -> Unit
            Toy.PLAYER -> {
                GroupTitle("PLAYS")
                if (designs.isEmpty()) {
                    EmptyState("No designs yet", "Make one in Create. Until then it plays a ring.", "Open Create") { nav.popTo(Tab.STUDIO) }
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
        if (toy == Toy.MUSIC) {
            var style by remember { mutableStateOf(prefs.visualStyle) }
            GroupTitle("LOOK")
            VisualStyle.entries.forEach { s ->
                ChoiceCard(s.label, s.hint, s == style, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    style = s; prefs.visualStyle = s; version++
                }
            }
            MicAsk()
        }
        GroupTitle("HOW TO USE")
        Text(howToSteps(toy))
    }
}

/** The toy can't ask for the microphone itself: ask here, with Not now. */
@Composable
private fun MicAsk() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var notNow by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted || notNow) return
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Allow the microphone", fontWeight = FontWeight.Medium)
            Text("The toy reads the sound that is playing, which Android counts as microphone access. Nothing is recorded.", color = Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow", color = NothingRed) }
                TextButton(onClick = { notNow = true }) { Text("Not now", color = Color.White) }
            }
        }
    }
}

@Composable
private fun ClockOptions(changed: () -> Unit) {
    val prefs = AppGraph.toys
    var h24 by remember { mutableStateOf(prefs.clock24h) }
    var style by remember { mutableStateOf(prefs.clockStyle) }
    GroupTitle("CLOCK STYLE")
    ClockStyle.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            row.forEach { s -> ChoiceCard(s.label, s.hint, s == style, Modifier.weight(1f)) { style = s; prefs.clockStyle = s; changed() } }
        }
    }
    GroupTitle("TIME FORMAT")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceCard("24-hour", "Shows 13:45", h24, Modifier.weight(1f)) { h24 = true; prefs.clock24h = true; changed() }
        ChoiceCard("12-hour", "Shows 01:45", !h24, Modifier.weight(1f)) { h24 = false; prefs.clock24h = false; changed() }
    }
}

/** Now playing reads media sessions through notification access. */
@Composable
private fun AccessNote() {
    Text(
        "Reads what's playing through notification access (Settings > Notification access). Nothing else is read.",
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
    )
}

/** A switch per DF toy: on means it is in Nothing's toy list and the Glyph Button carousel. */
@Composable
fun DfToySwitches(onOpen: (Toy) -> Unit = {}) {
    val ctx = LocalContext.current
    Text("The Glyph Button cycles through the toys turned on here.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
    Toy.entries.forEach { toy ->
        var on by remember(toy) { mutableStateOf(com.dotforge.toys.ToyComponents.isOn(ctx, toy.cls)) }
        SwitchRow(stringResource(toy.nameRes), toy.short, on) {
            com.dotforge.toys.ToyComponents.set(ctx, toy.cls, it)
            on = it
        }
    }
}
