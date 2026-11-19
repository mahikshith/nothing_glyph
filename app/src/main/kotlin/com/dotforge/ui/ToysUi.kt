package com.dotforge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.composition.VisualStyle
import com.dotforge.composition.VisualiserRenderer
import com.dotforge.device.ClockStyle
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.GlyphLibrary
import com.dotforge.device.Posters
import com.dotforge.device.SnakeGame
import com.dotforge.device.ToyFaces
import com.dotforge.toys.DayUsage
import com.dotforge.toys.HubFace
import com.dotforge.toys.InstalledToys
import com.dotforge.toys.ToyComponents
import com.dotforge.toys.ToyList
import com.dotforge.toys.ToyStatus
import com.dotforge.toys.Weather
import com.dotforge.toys.galleryAnimations
import com.dotforge.ui.design.ChoiceRows
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.Page as DesignPage
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.features.ChipChoices
import com.dotforge.ui.features.MIC_WHY
import com.dotforge.ui.features.MIC_OFF
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.dotforge.ui.design.PrimaryButton as MainButton
import com.dotforge.ui.design.SecondaryButton as GreyButton
import com.dotforge.ui.design.SwitchRow as DesignSwitchRow
import com.dotforge.ui.design.EmptyState as DesignEmptyState

/** DotForge's toys; [cls] is the service's simple class name (its component). [isNew] toys lead the list, tagged New. */
enum class Toy(val nameRes: Int, val summaryRes: Int, val short: String, val cls: String, val isNew: Boolean = false) {
    WEATHER(R.string.toy_weather_name, R.string.toy_weather_summary, "Sky and temperature", "WeatherToy", isNew = true),
    DAY_DOTS(R.string.toy_daydots_name, R.string.toy_daydots_summary, "Screen time today", "DayDotsToy", isNew = true),
    MAZE(R.string.toy_maze_name, R.string.toy_maze_summary, "Tilt to roll the ball", "TiltMazeToy", isNew = true),
    CLOCK(R.string.toy_clock_name, R.string.toy_clock_summary, "Big dot clock", "ClockToy"),
    BATTERY(R.string.toy_battery_name, R.string.toy_battery_summary, "Battery ring", "BatteryToy"),
    DICE(R.string.toy_dice_name, R.string.toy_dice_summary, "Shake to roll", "DiceToy"),
    PLAYER(R.string.toy_player_name, R.string.toy_player_summary, "Plays your design", "PlayerToy"),
    MUSIC(R.string.toy_music_name, R.string.toy_music_summary, "Lights to the music", "MusicToy"),
    HUB(R.string.toy_hub_name, R.string.toy_hub_summary, "Always-on faces", "HubToy"),
    GALLERY(R.string.toy_gallery_name, R.string.toy_gallery_summary, "Designs in turn", "GalleryToy"),
    NOW_PLAYING(R.string.toy_nowplaying_name, R.string.toy_nowplaying_summary, "Play, pause, skip", "NowPlayingToy"),
    TIMER(R.string.toy_timer_name, R.string.toy_timer_summary, "Your DotForge timer", "TimerToy"),
    COMPASS(R.string.toy_compass_name, R.string.toy_compass_summary, "Compass and level", "CompassToy"),
    SNAKE(R.string.toy_snake_name, R.string.toy_snake_summary, "The classic game", "SnakeToy"),
    BREATHE(R.string.toy_breathe_name, R.string.toy_breathe_summary, "Slow breathing", "BreatheToy"),
    MOON(R.string.toy_moon_name, R.string.toy_moon_summary, "Moon phase", "MoonToy"),
    SPEED(R.string.toy_speed_name, R.string.toy_speed_summary, "Download speed", "SpeedToy"),
    ;

    /**
     * What the toy shows now, from real data only: Weather shows "--" until a reading came in, Day dots shows no
     * screen time without Usage access ([ctx] reads it).
     */
    fun preview(profile: DeviceProfile, ctx: Context? = null): Frame = when (this) {
        CLOCK -> LocalTime.now().let { ToyFaces.clock(profile, AppGraph.toys.clockStyle, it.hour, it.minute, AppGraph.toys.clock24h) }
        HUB -> LocalTime.now().let { ToyFaces.clock(profile, AppGraph.toys.clockStyle, it.hour, it.minute, AppGraph.toys.clock24h) }
        GALLERY -> galleryAnimations().firstOrNull()?.let(Posters::of) ?: library("heart", profile)
        NOW_PLAYING -> ToyFaces.nowPlaying(profile, true, 0.4f)
        TIMER -> com.dotforge.device.TimerFaces.countdown(profile, com.dotforge.timer.GlyphTimers.style.value, 252_000, 300_000)
        COMPASS -> ToyFaces.compass(profile, 30.0)
        SNAKE -> snakeIcon(profile)
        BREATHE -> ToyFaces.breath(profile, 3.0)
        SPEED -> ToyFaces.speed(profile, 2_400_000)
        MOON -> ToyFaces.moon(profile, ToyFaces.moonAge(java.time.LocalDate.now().toEpochDay() + 0.5))
        BATTERY -> Effects.ring(profile, 0.7f)
        DICE -> Effects.dice(profile, 5)
        PLAYER -> AppGraph.playerAnimation()?.let(Posters::of) ?: playIcon(profile)
        WEATHER -> Weather.cached()?.let { com.dotforge.device.WeatherFace.frame(profile, it, AppGraph.toys.weatherFahrenheit) }
            ?: com.dotforge.device.WeatherFace.waiting(profile)
        DAY_DOTS -> com.dotforge.device.DayDots.frame(profile, ctx?.let { runCatching { DayUsage.minutesToday(it) }.getOrNull() } ?: 0, AppGraph.toys.dayGoalMinutes)
        MAZE -> com.dotforge.device.TiltMaze(AppGraph.toys.mazeLevel).frame(profile, blink = true)
        MUSIC -> Frame.of(profile, VisualiserRenderer.frame(floatArrayOf(0.9f, 0.8f, 0.7f, 0.75f, 0.6f, 0.5f, 0.55f, 0.4f, 0.35f, 0.3f, 0.2f, 0.15f), AppGraph.toys.visualStyle, profile))
    }

    /** The list's 40 dp icon: the preview with every lit dot at least [ICON_FLOOR], so it reads at that size. */
    fun icon(profile: DeviceProfile, ctx: Context? = null): Frame = brighten(
        runCatching {
            // a picture of the toy, not data: Day dots shows a day part-filled so its dots read at 40 dp
            if (this == DAY_DOTS) com.dotforge.device.DayDots.frame(profile, 150, 180) else preview(profile, ctx)
        }.getOrElse { Effects.ring(profile, 1f) },
    )

    companion object {
        /** New toys first (as declared), then the rest. */
        val listed: List<Toy> get() = entries.filter { it.isNew } + entries.filterNot { it.isNew }

        /** The dimmest a lit dot is drawn in a toy's list icon. */
        const val ICON_FLOOR = 150

        /** Every lit dot brought up to at least [ICON_FLOOR], the brightest to full. Pure; tested. */
        fun brighten(f: Frame): Frame {
            val n = Posters.normalised(f)
            return Frame(n.profile, IntArray(n.pixels.size) { i -> val v = n.pixels[i]; if (v <= 0) 0 else maxOf(v, ICON_FLOOR) })
        }

        private fun library(id: String, p: DeviceProfile): Frame = GlyphLibrary.get(id)?.still(p) ?: Effects.ring(p, 1f)

        /** A play triangle (DF Player with no design yet). */
        fun playIcon(p: DeviceProfile): Frame {
            val f = Frame.blank(p)
            val c = p.size / 2
            for (x in c - 4..c + 5) {
                val half = ((c + 5 - x) * 0.6).toInt()
                for (y in c - half..c + half) if (p.isActive(x, y)) f[x, y] = 255
            }
            return f
        }

        /** A snake winding towards its food, two dots thick so it reads small. */
        fun snakeIcon(p: DeviceProfile): Frame {
            val f = Frame.blank(p)
            val c = p.size / 2
            val body = (c - 8..c + 4).map { it to c + 4 } + (c - 4..c + 4).map { c + 4 to it } + (c - 2..c + 4).map { it to c - 4 }
            body.forEach { (x, y) -> for (dy in 0..1) for (dx in 0..1) if (p.isActive(x + dx, y + dy)) f[x + dx, y + dy] = 255 }
            for (dy in 0..1) for (dx in 0..1) if (p.isActive(c - 7 + dx, c - 5 + dy)) f[c - 7 + dx, c - 5 + dy] = 255
            return f
        }
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

/**
 * A toy's page, in the app's one design language: its picture, what it does, where it stands in Glyph Toys
 * (the same line as the list) and its options. The main button adds it to Glyph Toys; the menu can hide it from
 * Nothing's list.
 */
@Composable
fun ToySettingsPage(toy: Toy, nav: Nav) {
    val ctx = LocalContext.current
    val profile = AppGraph.profile
    var version by remember { mutableStateOf(0) }
    val preview = remember(toy, version) { toy.preview(profile, ctx) }
    var list by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var shown by remember { mutableStateOf(runCatching { ToyComponents.isOn(ctx, toy.cls) }.getOrDefault(true)) }
    var hidden by remember { mutableStateOf(runCatching { ToyComponents.hiddenByUser(ctx, toy.cls) }.getOrDefault(false)) }
    var menu by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    LaunchedEffect(version) { list = withContext(Dispatchers.IO) { ToyList.read(ctx) } }
    if (toy == Toy.CLOCK || toy == Toy.HUB) LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            version++
        }
    }
    val status = ToyStatus.of(list, toy.cls)
    val name = stringResource(toy.nameRes)

    DesignPage(
        name, onBack = nav::pop,
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Tokens.Raised) {
                    DropdownMenuItem(
                        text = { Text(if (shown) "Hide from Glyph Toys list" else "Show in Glyph Toys list") },
                        onClick = {
                            menu = false
                            runCatching { ToyComponents.set(ctx, toy.cls, !shown) }
                            shown = !shown
                            hidden = runCatching { ToyComponents.hiddenByUser(ctx, toy.cls) }.getOrDefault(!shown)
                            version++
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (status == ToyStatus.IN) GreyButton("Open Glyph Toys", Modifier.fillMaxWidth()) { InstalledToys.openManager(ctx) }
            else MainButton("Add to Glyph Toys", Modifier.fillMaxWidth()) { addToGlyphToys(ctx, toy) }
        },
    ) {
        item(key = "preview") {
            Box(Modifier.fillMaxWidth().padding(vertical = Tokens.Gutter), contentAlignment = Alignment.Center) {
                GlyphPreview(preview, "$name on the Glyph", size = PreviewSize.LARGE)
            }
            Hint(stringResource(toy.summaryRes))
        }
        item(key = "status") {
            RowGroup(Modifier.padding(top = Tokens.Small)) {
                ListRow(status.label, subtitle = statusHelp(status, hidden), subtitleLines = 8)
            }
        }
        item(key = "options") { ToyOptions(toy, nav) { version++ } }
    }
}

/** Under the status: what to do next, one instruction everywhere. */
internal fun statusHelp(status: ToyStatus, hidden: Boolean): String = when {
    hidden -> "Hidden from the Glyph Toys list. Add to Glyph Toys shows it again."
    status == ToyStatus.IN -> "Press the Glyph Button on the back until it shows."
    status == ToyStatus.UNKNOWN -> "This phone doesn't say if it's there. Add to Glyph Toys opens the list."
    else -> "Tap Add to Glyph Toys, then turn it on in the list."
}

/** Add: the toy shows in Nothing's list (its component on), then the list opens to turn it on. */
fun addToGlyphToys(ctx: Context, toy: Toy) {
    runCatching { if (!ToyComponents.isOn(ctx, toy.cls)) ToyComponents.set(ctx, toy.cls, true) }
    InstalledToys.openManager(ctx)
}

/** Each toy's few options, in the shared section labels, chips and check rows. */
@Composable
private fun ToyOptions(toy: Toy, nav: Nav, changed: () -> Unit) {
    val prefs = AppGraph.toys
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    Column {
        when (toy) {
            Toy.CLOCK -> ClockOptions(changed)
            Toy.HUB -> {
                var face by remember { mutableStateOf(prefs.hubFace) }
                var battery by remember { mutableStateOf(prefs.hubBattery) }
                SectionLabel("Face")
                ChoiceRows(HubFace.entries, face, { it.label }, { it.hint }) { face = it; prefs.hubFace = it; changed() }
                Hint("The Always-on face tile in Quick Settings switches it too.")
                RowGroup { DesignSwitchRow("Battery ring", "The clock face shows the battery round the edge", battery) { battery = it; prefs.hubBattery = it; changed() } }
                ClockOptions(changed)
            }
            Toy.GALLERY -> {
                var chosen by remember { mutableStateOf(prefs.galleryIds) }
                SectionLabel("Designs")
                if (designs.isEmpty()) DesignEmptyState("No designs yet. Make one in Create.", "Open Create") { nav.popTo(Tab.STUDIO) }
                else {
                    Hint(if (chosen.isEmpty()) "None picked: all designs play." else "${chosen.size} picked.")
                    RowGroup {
                        designs.forEachIndexed { i, d ->
                            if (i > 0) RowDivider()
                            DesignSwitchRow(d.name, if (d.animated) "Animated" else "Still", d.id in chosen) { on ->
                                chosen = if (on) chosen + d.id else chosen - d.id
                                prefs.galleryIds = chosen
                                changed()
                            }
                        }
                    }
                }
            }
            Toy.TIMER -> {
                SectionLabel("Timer")
                Hint("It shows the timer from Features > Timer & stopwatch, in the style you picked there. With nothing running, a press starts your first preset.")
                GreyButton("Open Timer & stopwatch", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { nav.push(Page.Feature("timer")) }
            }
            Toy.WEATHER -> WeatherOptions(changed)
            Toy.DAY_DOTS -> DayDotsOptions(changed)
            Toy.MAZE -> {
                var level by remember { mutableStateOf(prefs.mazeLevel) }
                SectionLabel("Difficulty")
                ChipChoices(com.dotforge.device.TiltMaze.Level.entries, level, { it.label }) { level = it; prefs.mazeLevel = it; changed() }
                PartNote(if (level == com.dotforge.device.TiltMaze.Level.EASY) "Wide paths." else "Twists and dead ends.")
            }
            Toy.NOW_PLAYING -> Hint("Reads what's playing through notification access (Settings > Notification access). Nothing else is read.")
            Toy.COMPASS, Toy.SNAKE, Toy.BREATHE, Toy.MOON, Toy.SPEED -> Unit
            Toy.BATTERY -> {
                var anim by remember { mutableStateOf(prefs.batteryChargeAnimation) }
                SectionLabel("Options")
                RowGroup { DesignSwitchRow("Charging animation", "A dot runs round the ring while charging", anim) { anim = it; prefs.batteryChargeAnimation = it } }
            }
            Toy.DICE -> {
                var shake by remember { mutableStateOf(prefs.diceShake) }
                SectionLabel("Options")
                RowGroup { DesignSwitchRow("Shake to roll", "Uses the motion sensor while the toy is on", shake) { shake = it; prefs.diceShake = it } }
            }
            Toy.MUSIC -> {
                var style by remember { mutableStateOf(prefs.visualStyle) }
                MicAsk()
                SectionLabel("Style")
                ChoiceRows(VisualStyle.entries, style, { it.label }, { it.hint }) { style = it; prefs.visualStyle = it; changed() }
            }
            Toy.PLAYER -> {
                var playerDesign by remember { mutableStateOf(prefs.playerDesignId) }
                SectionLabel("Plays")
                if (designs.isEmpty()) DesignEmptyState("No designs yet. Until then it plays a ring.", "Open Create") { nav.popTo(Tab.STUDIO) }
                else {
                    val chosen = AppGraph.designs.get(playerDesign)
                    val options = listOf<String?>(null) + designs.map { it.id }
                    ChoiceRows(
                        options, chosen?.id, { id -> if (id == null) "Newest design" else designs.first { it.id == id }.name },
                        { id -> if (id == null) designs.first().name else if (designs.first { it.id == id }.animated) "Animated" else "Still" },
                    ) { playerDesign = it; prefs.playerDesignId = it; changed() }
                }
            }
        }
    }
}

/** "Updated 12 min ago", or "No connection. Showing the last weather from 9:40". Pure; tested. */
internal fun weatherLine(now: Long, at: Long, offlineAt: Long, clock: (Long) -> String): String? = when {
    at > 0 && offlineAt > at -> "No connection. Showing the last weather from ${clock(at)}."
    at <= 0 && offlineAt > 0 -> "No connection. The weather shows once the phone is online."
    at <= 0 -> null
    else -> {
        val min = ((now - at) / 60_000).coerceAtLeast(0)
        when {
            min < 1 -> "Updated just now"
            min < 60 -> "Updated $min min ago"
            else -> "Updated at ${clock(at)}"
        }
    }
}

/**
 * DF Weather: where (here, with rough location, or a typed place), when it last updated (or that it's offline),
 * °C or °F, and the chance of rain on a press.
 */
@Composable
private fun WeatherOptions(changed: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = AppGraph.toys
    var place by remember { mutableStateOf(prefs.weatherPlace.orEmpty()) }
    var saved by remember { mutableStateOf(prefs.weatherPlace) }
    var typed by remember { mutableStateOf(prefs.weatherPlace != null) }
    var f by remember { mutableStateOf(prefs.weatherFahrenheit) }
    var rain by remember { mutableStateOf(prefs.weatherRain) }
    var located by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(prefs.weatherAskedLocation) }
    var tick by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { located = it; asked = true; prefs.weatherAskedLocation = true }
    // Android stops showing its prompt after a refusal or two; then "off" is the honest state
    val denied = !located && asked && (ctx as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) != true
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        located = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(located, saved, typed) {
        if ((typed && saved != null) || (!typed && located)) {
            if (Weather.fetch(ctx, force = false)) changed()
            tick++
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }

    SectionLabel("Place")
    ChipChoices(listOf(false, true), typed, { if (it) "A place" else "Where I am" }) { want ->
        typed = want
        if (!want) {
            prefs.weatherPlace = null; saved = null; changed()
            if (!located && !denied) ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    if (!typed && !located) {
        if (denied) StatusBanner("Location is off. Type a place instead.", Modifier.padding(top = Tokens.Small), button = "Type a place") { typed = true }
        else StatusBanner("Allow rough location so the weather is for where you are.", Modifier.padding(top = Tokens.Small), button = "Allow") {
            ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    if (typed) {
        androidx.compose.material3.OutlinedTextField(
            place, { place = it.take(60) }, Modifier.fillMaxWidth().padding(top = Tokens.Small), singleLine = true,
            placeholder = { Text("Town or city", color = Tokens.Secondary) },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { prefs.weatherPlace = place; saved = prefs.weatherPlace; changed() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        )
        if (place.trim() != saved.orEmpty() && place.isNotBlank()) GreyButton("Use this place", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {
            prefs.weatherPlace = place; saved = prefs.weatherPlace; changed()
        }
    }
    val line = remember(tick, saved, located) {
        weatherLine(System.currentTimeMillis(), prefs.weatherAt, prefs.weatherOfflineAt) { t ->
            android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(t))
        }
    }
    val where = saved?.takeIf { typed }
    when {
        line == null -> if (typed && saved != null || !typed && located) Hint("Getting the weather…")
        line.startsWith("No connection") -> StatusBanner(line, Modifier.padding(top = Tokens.Small))
        else -> Hint(listOfNotNull(line, where).joinToString(" · "))
    }
    SectionLabel("Units")
    ChipChoices(listOf(false, true), f, { if (it) "°F" else "°C" }) { f = it; prefs.weatherFahrenheit = it; changed() }
    RowGroup(Modifier.padding(top = Tokens.Section)) {
        DesignSwitchRow("Show rain chance", "Press the Glyph Button to see today's chance of rain", rain) { rain = it; prefs.weatherRain = it }
    }
    Hint("Weather from Open-Meteo. Only the place goes to it; nothing else leaves your phone.")
}

/** DF Day dots: Usage access (asked here, with an honest "off" state), the daily goal and when a new day starts. */
@Composable
private fun DayDotsOptions(changed: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = AppGraph.toys
    var goal by remember { mutableStateOf(prefs.dayGoalMinutes) }
    var reset by remember { mutableStateOf(prefs.dayResetHour) }
    var allowed by remember { mutableStateOf(DayUsage.allowed(ctx)) }
    var asked by remember { mutableStateOf(prefs.dayAskedUsage) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = DayUsage.allowed(ctx)
        if (now != allowed) changed()
        allowed = now
    }
    fun open() {
        asked = true
        prefs.dayAskedUsage = true
        runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (!allowed) {
        if (asked) StatusBanner("Usage access is off, so Day dots can't count screen time.", Modifier.padding(top = Tokens.Small), button = "Open settings") { open() }
        else StatusBanner("Day dots reads screen time from Usage access. It stays on this phone.", Modifier.padding(top = Tokens.Small), button = "Allow") { open() }
    }
    SectionLabel("Daily goal")
    ChipChoices(com.dotforge.toys.ToySettings.DAY_GOALS, goal / 60, { "$it h" }) { h -> goal = h * 60; prefs.dayGoalMinutes = h * 60; changed() }
    PartNote("${goal / com.dotforge.device.DayDots.MINUTES_PER_DOT} dots, one for every 10 minutes.")
    SectionLabel("New day starts")
    ChipChoices(listOf(0, 4, 6), reset, { if (it == 0) "Midnight" else "$it am" }) { hr -> reset = hr; prefs.dayResetHour = hr; changed() }
}

/** The Music toy reads the sound that plays: ask with the honest line, or say it's off. */
@Composable
private fun MicAsk() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val asked by AppGraph.features.micAsked.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; AppGraph.features.setMicAsked(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    if (granted) return
    val off = asked && (ctx as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) != true
    if (off) StatusBanner(MIC_OFF, Modifier.padding(top = Tokens.Small), button = "Open settings") {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    else StatusBanner(MIC_WHY, Modifier.padding(top = Tokens.Small), button = "Allow") { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}

@Composable
private fun ClockOptions(changed: () -> Unit) {
    val prefs = AppGraph.toys
    var h24 by remember { mutableStateOf(prefs.clock24h) }
    var style by remember { mutableStateOf(prefs.clockStyle) }
    SectionLabel("Clock style")
    ChoiceRows(ClockStyle.entries, style, { it.label }, { it.hint }) { style = it; prefs.clockStyle = it; changed() }
    SectionLabel("Time format")
    ChipChoices(listOf(true, false), h24, { if (it) "24-hour" else "12-hour" }) { h24 = it; prefs.clock24h = it; changed() }
    PartNote(if (h24) "Shows 13:45." else "Shows 01:45.")
}
