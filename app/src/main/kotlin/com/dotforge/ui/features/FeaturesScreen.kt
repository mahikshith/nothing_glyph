package com.dotforge.ui.features

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.Page
import com.dotforge.ui.design.Screen
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.TimerFaces
import com.dotforge.live.LiveProgress
import com.dotforge.live.TimerShown
import com.dotforge.notify.LiveProgressRuntime
import com.dotforge.timer.GlyphTimers
import com.dotforge.ui.design.EqualTile
import com.dotforge.ui.design.LibraryGlyph
import com.dotforge.ui.design.TilePair
import com.dotforge.visualiser.LiveVisualiser
import kotlinx.coroutines.delay

/**
 * The six Features cards, in grid order (an even 2x3 grid). [key] is the [com.dotforge.ui.Page.Feature] key;
 * [what] is the page's first line, [options] its option rows (name to value) and [how] its How it works text.
 */
enum class Feature(val key: String, val title: String, val what: String, val options: List<Pair<String, String>>, val how: String, val icon: String = "dot") {
    TIMER(
        "timer", "Timer & stopwatch", "Count down or time something on the Glyph. It shows the timer that ends first, unless you pick one.",
        emptyList(),
        "Timers run in DotForge, not as a Glyph Toy, so they never stop after 30 seconds. The Glyph shows the timer that " +
            "ends first, or the one you pick with Show on Glyph. Its last 10 seconds count down big, and when it ends the " +
            "Glyph bursts for as long as you set, then again every minute until you dismiss it (On the Glyph sets both), while " +
            "the phone rings and buzzes, and \"Time's up\" fills the screen. Face " +
            "down, the Glyph is hidden, so stand the phone up to see it. Your phone shows the timer too, as a Live Update in " +
            "the status bar and on the lock screen. With \"Show Clock app timers\" on, " +
            "timers and the stopwatch you start in the Clock app show on the Glyph the same way.",
        "g-timer",
    ),
    PROGRESS(
        "progress", "Live progress", "A ring fills for deliveries, rides, downloads and what's coming up. Works when the app shows the arrival time in its notification.",
        emptyList(),
        "Works when the app shows the arrival time in its notification. DotForge reads the notification's progress bar, " +
            "its countdown, words like \"arriving in 12 mins\" or \"ETA 7:45 PM\", and the order's stage (\"Out for delivery\"). " +
            "The ring is how far it has got; the middle shows the minutes left, or the stage's picture; it pulses when it's " +
            "about to arrive, and plays the done animation when it's there. Tap a row under Now to open its app. Nothing " +
            "leaves your phone.",
        "g-scooter",
    ),
    TOYS(
        "toys", "Toys", "Little things for the Glyph. Press the Glyph Button on the back to switch between the toys you add.",
        emptyList(),
        "A toy is something small that lives on the Glyph: a clock, a game, the weather. Switch a DotForge toy on here, " +
            "then add it in Nothing's toy settings: that list decides which toys the Glyph Button switches between, and in " +
            "what order. \"Added in Glyph Toys\" means it's in the list. A press of the Glyph Button moves to the next toy; " +
            "a long press does the toy's own thing (the date on the clock, rain on the weather). Nothing rests the Glyph after " +
            "30 seconds without a press. On the Glyph sets the brightness and speed of every DotForge toy. DF Timer shows " +
            "your timer from Timer & stopwatch.",
        "g-football",
    ),
    MUSIC(
        "music", "Music visualiser", "The Glyph moves with whatever is playing.",
        emptyList(),
        "DotForge listens to the sound this phone plays and turns it into light, about 30 times a second. Android calls " +
            "that microphone access, so it asks once; nothing is recorded, kept or sent anywhere. If Android gives no music " +
            "sound (some apps block it), DotForge listens through the microphone instead and says so. \"While music plays\" " +
            "keeps the Glyph dark between songs. Fall-back speed is how quickly the lights drop after a beat; sensitivity is " +
            "how loud it has to be to fill the Glyph. Alerts, timers and the torch show over it, then it comes back.",
        "g-headphones",
    ),
    TORCH(
        "torch", "Torch", "The whole Glyph as a light.",
        emptyList(),
        "The torch lights every dot of the Glyph. It fades on, then turns itself off after the time you pick, so it never " +
            "drains the battery by accident; Never keeps it on until you turn it off. Home's Torch card and the Quick Settings " +
            "tile switch it too, and show the time left. Brightness is a share of the phone's own Glyph brightness. A call " +
            "shows over it; nothing else does.",
        "g-torch",
    ),
    BATTERY(
        "battery", "Battery and volume", "The Glyph shows the battery when you plug in, and the volume as you change it.",
        emptyList(),
        "When you plug in, the ring fills to the battery level (Fill), or shows the ring and the number (Level %). On a " +
            "charger of 15 W or more it pulses twice. It shows for the time you pick, or while plugged in, and can come back " +
            "every few minutes while charging. When you press the volume keys, the ring slides to the new level. Nothing's own " +
            "charging light covers the Glyph for a few seconds when you plug in; DotForge shows after it. These run while " +
            "DotForge is running, which notification access keeps it doing.",
        "g-battery-full",
    ),
    INTEGRATIONS(
        "integrations", "Integrations", "Tasker and other apps can light the Glyph.",
        emptyList(),
        "Other apps send DotForge a design name or a short text, and DotForge shows it like an alert: Tasker can play Heart " +
            "for 10 s, or 3 times. In Tasker, use the DotForge plugin, or Send intent with the key (Copy key). An app that " +
            "sends a command without the key is listed under Apps; allow it and DotForge obeys it. Make a new key if the key " +
            "got out. With \"Tell other apps when something lights\" on, apps with DotForge's permission hear what kind of " +
            "thing lit the Glyph (an alert, a call, a timer), never what it said or who sent it.",
        "g-gear",
    ),
    ;

    companion object {
        fun of(key: String): Feature? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Features: an equal 2-column grid of 120 dp cards (Timer & stopwatch and Live progress first), each with a dot
 * icon, its name and one status line, opening its page. Integrations, kept out of sight, is one row under them.
 */
@Composable
fun FeaturesScreen(nav: Nav, setup: Setup, state: LazyListState) {
    val status = featureStatus(setup)
    val open = { f: Feature -> nav.push(com.dotforge.ui.Page.Feature(f.key)) }
    val cards = Feature.entries.filter { it != Feature.INTEGRATIONS }
    Screen("Features", state = state) {
        cards.chunked(2).forEachIndexed { i, pair ->
            item(key = "cards$i") {
                TilePair(
                    { m -> FeatureCard(pair[0], status[pair[0]].orEmpty(), m) { open(pair[0]) } },
                    pair.getOrNull(1)?.let { f -> { m -> FeatureCard(f, status[f].orEmpty(), m) { open(f) } } },
                )
            }
        }
        item(key = "integrations") {
            RowGroup(Modifier.padding(top = Tokens.Gap)) {
                ListRow(
                    Feature.INTEGRATIONS.title, subtitle = status[Feature.INTEGRATIONS],
                    leading = { LibraryGlyph(Feature.INTEGRATIONS.icon, still = true) }, trailing = { Chevron() },
                ) { open(Feature.INTEGRATIONS) }
            }
        }
    }
}

/** One Features card: the feature's dot icon, its name and one status line; the whole card opens its page. */
@Composable
private fun FeatureCard(f: Feature, status: String, modifier: Modifier, onOpen: () -> Unit) =
    EqualTile(
        f.title, status, modifier, height = Tokens.FeatureCard, statusLines = 3, titleLines = 3,
        icon = { LibraryGlyph(f.icon, Modifier.size(32.dp), still = true) }, onOpen = onOpen,
    )

/** Each card's status line, live: "4:12 left", "Zomato · 12 min", "On", "Off". */
@Composable
private fun featureStatus(setup: Setup): Map<Feature, String> {
    val timers by GlyphTimers.state.collectAsStateWithLifecycle()
    val progressOn by AppGraph.progressRings.collectAsStateWithLifecycle()
    val items by LiveProgressRuntime.items.collectAsStateWithLifecycle()
    val live by LiveVisualiser.running.collectAsStateWithLifecycle()
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val battery by AppGraph.system.chargingMeter.collectAsStateWithLifecycle()
    val allowed by AppGraph.automation.allowed.collectAsStateWithLifecycle()
    val volume by AppGraph.system.volume.collectAsStateWithLifecycle()
    val showDesigns by AppGraph.features.showDesigns.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val toysOn = remember { com.dotforge.ui.Toy.entries.count { runCatching { com.dotforge.toys.ToyComponents.isOn(ctx, it.cls) }.getOrDefault(false) } }
    var now by remember { mutableLongStateOf(GlyphTimers.now()) }
    LaunchedEffect(timers) { while (GlyphTimers.shown() != null) { now = GlyphTimers.now(); delay(1_000) } }
    return mapOf(
        Feature.TIMER to timerStatus(timers, now),
        Feature.PROGRESS to when {
            !progressOn -> "Off"
            !setup.notificationAccess -> "Needs access"
            else -> progressStatus(items)
        },
        Feature.TOYS to toysOn.let { if (it == 0) "None on" else if (it == 1) "1 toy on" else "$it toys on" },
        Feature.MUSIC to if (live) "On" else "Off",
        Feature.TORCH to if (active.any { it.id == com.dotforge.tile.TorchTileService.ID }) "On" else "Off",
        Feature.BATTERY to batteryStatus(battery, volume),
        Feature.INTEGRATIONS to when {
            !showDesigns -> "Off"
            allowed.isEmpty() -> "Tasker and other apps"
            else -> "${allowed.size} allowed · Tasker and other apps"
        },
    )
}

/** The Battery and volume card: "Charging and volume", "When charging", "Volume", "Off". */
internal fun batteryStatus(charging: Boolean, volume: Boolean): String = when {
    charging && volume -> "Charging and volume"
    charging -> "When charging"
    volume -> "Volume changes"
    else -> "Off"
}

/**
 * The Live progress card: "On" with nothing on the way, "Zomato · 12 min left" for one, and for several the count
 * and the soonest ("3 now · Uber 2 min").
 */
internal fun progressStatus(all: List<LiveProgressRuntime.Item>): String {
    if (all.isEmpty()) return "On"
    val items = all.filterNot { it.done }.ifEmpty { return "${all.first().name} · " + if (all.first().app?.kind == com.dotforge.live.TripKind.RIDE) "Arrived" else "Delivered" }
    val soonest = items.filter { it.reading.minutesLeft != null }.minByOrNull { it.reading.minutesLeft!! } ?: items.first()
    if (items.size == 1) return "${soonest.name} · " + LiveProgress.summary(soonest.reading).substringBefore(" · ")
    val r = soonest.reading
    val short = when {
        r.minutesLeft != null -> if (r.minutesLeft!! <= 0) "now" else if (r.minutesLeft!! >= 120) "${r.minutesLeft!! / 60} h" else "${r.minutesLeft} min"
        r.bar != null -> "${(r.bar!! * 100).toInt()}%"
        else -> r.stage?.label?.lowercase().orEmpty()
    }
    return "${items.size} now · ${soonest.name} $short".trim()
}

/** "4:12 left", "2 running · 4:12", "Time's up", "Stopwatch · 1:05", "Paused", or "None running". */
internal fun timerStatus(s: GlyphTimers.State, now: Long): String {
    val shown = com.dotforge.live.Timers.shown(s.all, s.watch, now) ?: return "None running"
    val running = s.all.count { it.running && !it.over(now) }
    return when (shown) {
        is TimerShown.Done -> "Time's up"
        is TimerShown.Running -> if (running > 1) "$running running · " + TimerFaces.clock(shown.leftMs) else TimerFaces.clock(shown.leftMs) + " left"
        is TimerShown.Paused -> "Paused · " + TimerFaces.clock(shown.leftMs)
        is TimerShown.Watch -> "Stopwatch · " + TimerFaces.clock(shown.elapsedMs, roundUp = false)
    }
}

/** A Features page by key. */
@Composable
fun FeaturePage(key: String, nav: Nav, setup: Setup) {
    when (Feature.of(key)) {
        Feature.TIMER -> TimerFeature(nav, setup)
        Feature.TOYS -> ToysFeature(nav, setup)
        Feature.MUSIC -> MusicFeature(nav, setup)
        Feature.TORCH -> TorchFeature(nav, setup)
        Feature.BATTERY -> BatteryFeature(nav, setup)
        Feature.PROGRESS -> ProgressFeature(nav, setup)
        Feature.INTEGRATIONS -> IntegrationsFeature(nav, setup)
        null -> Page("Features", onBack = nav::pop) { item { Hint("This feature doesn't exist any more.") } }
    }
}

/**
 * The one layout every Features page shares: a short line on what it does, one main switch, at most 5 options
 * and a "How it works" row. Until a page is built, [content] is null and the placeholder shows the same layout
 * with the switch and options greyed out.
 */
@Composable
fun FeatureLayout(feature: Feature, nav: Nav, extra: (LazyListScope.() -> Unit)? = null, content: (LazyListScope.() -> Unit)? = null) {
    var how by rememberSaveable { mutableStateOf(false) }
    Page(feature.title, onBack = nav::pop) {
        item(key = "what") { Hint(feature.what) }
        if (content != null) content() else {
            item(key = "main") {
                RowGroup { SwitchRow(feature.title, "Coming in the next update", false, enabled = false) {} }
            }
            item(key = "options") {
                SectionLabel("Options")
                RowGroup {
                    feature.options.take(5).forEachIndexed { i, (name, value) ->
                        if (i > 0) RowDivider()
                        ListRow(name, subtitle = value, enabled = false, trailing = { Chevron() }) {}
                    }
                }
            }
        }
        extra?.invoke(this)
        item(key = "how") {
            RowGroup(Modifier.padding(top = Tokens.Section)) { ListRow("How it works", trailing = { Chevron() }) { how = true } }
        }
    }
    if (how) FullSheet({ how = false }, "How it works") { Text(feature.how, style = MaterialTheme.typography.bodyLarge) }
}

/**
 * The "On the Glyph" row every Features page has (Part A's pattern): what it looks like playing, one line on
 * its timing, and a chevron to the sheet with Show for, Speed, Repeat and Brightness.
 */
@Composable
fun OnTheGlyphRow(
    summary: String, preview: com.dotforge.device.Animation?, description: String, enabled: Boolean = true, tag: String = ON_THE_GLYPH_TAG, onOpen: () -> Unit,
) =
    ListRow(
        "On the Glyph", Modifier.testTag(tag), subtitle = summary, enabled = enabled,
        leading = { com.dotforge.ui.design.GlyphPreview(preview, description) }, trailing = { Chevron() }, onClick = onOpen,
    )

/** A row of choice chips under a sheet part. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) =
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Tokens.Small)) {
        options.forEach { o -> com.dotforge.ui.design.Chip(label(o), o == selected) { onPick(o) } }
    }
