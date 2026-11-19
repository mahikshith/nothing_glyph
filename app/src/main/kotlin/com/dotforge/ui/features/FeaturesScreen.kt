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
        "timer", "Timer & stopwatch", "Count down or time something on the Glyph. Several timers can run; the Glyph shows the one that ends first.",
        emptyList(),
        "Timers run in DotForge, not as a Glyph Toy, so they never stop after 30 seconds. The Glyph shows the timer that " +
            "ends first, or the one you pick with Show on Glyph. Its last 10 seconds count down big, and when it ends the " +
            "Glyph bursts (for as long as you set) while the phone rings and buzzes, and \"Time's up\" fills the screen. Face " +
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
        "toys", "Toys", "Little things for the Glyph. Press the Glyph Button on the back to switch toys.",
        listOf("Clock" to "Added in Glyph Toys", "Battery" to "Not added", "Get more toys" to "15 to choose from"),
        "Nothing's Glyph Toys list decides which toys you can switch to. Add DotForge's toys there. Toys rest after 30 s.",
        "g-football",
    ),
    MUSIC(
        "music", "Music visualiser", "The Glyph moves with whatever is playing.",
        listOf("Style" to "Bars", "Brightness" to "100%"),
        "It reads the sound that is playing on this phone. Android counts that as microphone access; nothing is recorded.",
        "g-headphones",
    ),
    TORCH(
        "torch", "Torch", "The whole Glyph as a light.",
        listOf("Turns off after" to "5 min", "Brightness" to "100%"),
        "The torch uses the Glyph at full brightness and turns itself off so it never drains the battery by accident.",
        "g-torch",
    ),
    BATTERY(
        "battery", "Battery and volume", "The Glyph shows the battery while charging and the volume as you change it.",
        listOf("While charging" to "Battery ring", "Volume" to "Bar"),
        "When you plug in, a ring fills to the battery level. When you press the volume keys, a bar shows the level.",
        "g-battery-full",
    ),
    INTEGRATIONS(
        "integrations", "Integrations", "Tasker and other apps can light the Glyph.",
        listOf("Tasker" to "Not set up", "Glyph widget" to "Off"),
        "Other apps send DotForge a design name or a short text; DotForge shows it like an alert.",
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
    var now by remember { mutableLongStateOf(GlyphTimers.now()) }
    LaunchedEffect(timers) { while (GlyphTimers.shown() != null) { now = GlyphTimers.now(); delay(1_000) } }
    return mapOf(
        Feature.TIMER to timerStatus(timers, now),
        Feature.PROGRESS to when {
            !progressOn -> "Off"
            !setup.notificationAccess -> "Needs access"
            else -> progressStatus(items)
        },
        Feature.TOYS to "Press the Glyph Button",
        Feature.MUSIC to if (live) "On" else "Off",
        Feature.TORCH to if (active.any { it.id == com.dotforge.tile.TorchTileService.ID }) "On" else "Off",
        Feature.BATTERY to if (battery) "Shows when charging" else "Off",
        Feature.INTEGRATIONS to if (allowed.isEmpty()) "Tasker and other apps" else "${allowed.size} allowed · Tasker and other apps",
    )
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
