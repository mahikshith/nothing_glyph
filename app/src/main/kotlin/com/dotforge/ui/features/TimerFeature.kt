package com.dotforge.ui.features

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.StopwatchStyle
import com.dotforge.device.TimerFaces
import com.dotforge.device.TimerStyle
import com.dotforge.live.GlyphTimer
import com.dotforge.live.Lap
import com.dotforge.live.TimerShown
import com.dotforge.live.TimerSource
import com.dotforge.live.Timers
import com.dotforge.timer.GlyphTimers
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphBadge
import com.dotforge.ui.design.Inter
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.Segmented
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.speedText
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import kotlinx.coroutines.delay

/**
 * Features > Timer & stopwatch (mykee's ask), in the order you use it: a live preview of what the Glyph shows (a
 * demo of the style when nothing runs), with Dismiss when a timer has ended; presets that start a timer in one tap
 * and a Clock-style keypad for a custom time; every timer (ours and the Clock app's) with the one on the Glyph
 * marked; anything to allow, on one line; the On the Glyph row (brightness, the finish's length, speed and repeat,
 * the last 10 seconds, Try it, in one sheet); the style; Sound, Vibrate and "Show Clock app timers".
 */
@Composable
fun TimerFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val state by GlyphTimers.state.collectAsStateWithLifecycle()
    val style by GlyphTimers.style.collectAsStateWithLifecycle()
    val watchStyle by GlyphTimers.watchStyle.collectAsStateWithLifecycle()
    val mirror by GlyphTimers.mirrorClock.collectAsStateWithLifecycle()
    val timing by GlyphTimers.timing.collectAsStateWithLifecycle()
    val lastTen by GlyphTimers.lastTen.collectAsStateWithLifecycle()
    val sound by GlyphTimers.sound.collectAsStateWithLifecycle()
    val vibrate by GlyphTimers.vibrate.collectAsStateWithLifecycle()
    val presets by GlyphTimers.presets.collectAsStateWithLifecycle()
    val snacks = rememberSnacks()
    var tab by rememberSaveable { mutableIntStateOf(if (state.stopwatch.started && state.timers.isEmpty()) 1 else 0) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var styles by rememberSaveable { mutableStateOf(false) }
    var glyph by rememberSaveable { mutableStateOf(false) }
    var allow by rememberSaveable { mutableStateOf(false) }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(GlyphTimers.now()) }
    val ticking = state.timers.isNotEmpty() || state.clock.isNotEmpty() || state.watch?.running == true
    LaunchedEffect(ticking, tab) {
        while (ticking) {
            now = GlyphTimers.now()
            delay(if (tab == 1 && state.watch?.running == true) 100 else 250)
        }
        now = GlyphTimers.now()
    }
    var canPost by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val post = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canPost = it }
    val alarms = remember { ctx.getSystemService(AlarmManager::class.java) }
    var exact by remember { mutableStateOf(alarms?.canScheduleExactAlarms() != false) }
    val exactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { exact = alarms?.canScheduleExactAlarms() != false }
    val short = LocalConfiguration.current.screenHeightDp < 700
    val stop: (GlyphTimer) -> Unit = { t ->
        GlyphTimers.cancel(t.id)
        snacks?.show("${t.name} stopped") { GlyphTimers.restore(t) }
    }
    // what is left to allow, as one line under the presets
    val asks = listOfNotNull(
        Ask("Notifications", "So Time's up fills the screen and the timer shows on the lock screen") { post.launch(Manifest.permission.POST_NOTIFICATIONS) }.takeIf { !canPost },
        Ask("Exact alarms", "So a timer ends on the second, not late") {
            runCatching { exactLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }
        }.takeIf { !exact },
        Ask("Notification access", "So the Clock app's timers show on the Glyph") { setup.needNotificationAccess() }.takeIf { mirror && !setup.notificationAccess },
    )

    FeatureLayout(Feature.TIMER, nav) {
        item(key = "preview") {
            val shown = Timers.shown(state.all, state.watch, now, state.focus)
            val demo = if (tab == 1) GlyphTimers.preview(watchStyle) else GlyphTimers.preview(style)
            val anim = remember(shown?.let { it::class }, shown?.key, now / 1000, style, watchStyle, tab, timing, lastTen) {
                previewOf(shown, style, watchStyle) ?: demo
            }
            val styleName = if (tab == 1) watchStyle.label else style.label
            val description = shown?.let { GlyphTimers.label(it) } ?: "$styleName style preview"
            val caption = if (shown == null) "Style preview · $styleName" else "On the Glyph now"
            val status = shown?.let { GlyphTimers.label(it) } ?: if (tab == 1) "Start the stopwatch below." else "Nothing running. Pick a time below."
            val dim = if (shown == null) Modifier.alpha(0.6f) else Modifier
            Column(Modifier.fillMaxWidth().padding(vertical = Tokens.Small), horizontalAlignment = Alignment.CenterHorizontally) {
                if (short) {
                    // a short screen: the preview beside its words, so "Start a timer" shows without scrolling
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GlyphPreview(anim, description, Modifier.size(96.dp).then(dim), size = PreviewSize.LARGE, userPlay = true)
                        Column(Modifier.weight(1f).padding(start = Tokens.Gutter)) {
                            Text(caption, style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary)
                            Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
                        }
                    }
                } else {
                    GlyphPreview(anim, description, Modifier.size(160.dp).then(dim), size = PreviewSize.LARGE, userPlay = true)
                    Text(caption, Modifier.padding(top = Tokens.Small), style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary)
                    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text, textAlign = TextAlign.Center)
                }
                // only while the Glyph shows something: then it matters which way up the phone lies
                if (shown != null) Text(
                    "Face down hides the Glyph. Stand the phone up.", Modifier.fillMaxWidth().padding(top = Tokens.Tiny),
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, textAlign = if (short) TextAlign.Start else TextAlign.Center,
                )
                if (shown is TimerShown.Done) {
                    val t = shown.timer
                    PrimaryButton(
                        if (state.all.count { it.over(now) } > 1) "Dismiss all" else "Dismiss",
                        Modifier.fillMaxWidth().padding(top = Tokens.Gap).heightIn(min = 60.dp),
                    ) { GlyphTimers.dismissAllDone() }
                    if (t.source == TimerSource.OURS) SecondaryButton("+1 min", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { GlyphTimers.plusMinute(t.id) }
                }
            }
        }
        item(key = "tabs") { Segmented(listOf("Timer", "Stopwatch"), tab, Modifier.padding(vertical = Tokens.Small)) { tab = it } }
        if (tab == 0) timerTab(state, now, presets, onCustom = { custom = true }, onActions = { actionsFor = it }, onStop = stop)
        else stopwatchTab(state, now)
        if (asks.isNotEmpty()) item(key = "allow") {
            AllowLine(asks, Modifier.padding(top = Tokens.Gap)) { allow = true }
        }
        item(key = "glyph") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                ListRow(
                    "On the Glyph", Modifier.testTag(ON_THE_GLYPH_TAG), subtitle = glyphSummary(timing, lastTen),
                    leading = { GlyphPreview(remember(timing) { GlyphTimers.finishPreview() }, "The finish with these settings") },
                    trailing = { Chevron() },
                ) { glyph = true }
            }
        }
        item(key = "style") {
            SectionLabel("Style")
            RowGroup {
                if (tab == 0) ListRow(
                    "Timer style", subtitle = style.label,
                    leading = { GlyphPreview(remember(style) { GlyphTimers.preview(style) }, style.label + " style") }, trailing = { Chevron() },
                ) { styles = true }
                else ListRow(
                    "Stopwatch style", subtitle = watchStyle.label,
                    leading = { GlyphPreview(remember(watchStyle) { GlyphTimers.preview(watchStyle) }, watchStyle.label + " style") }, trailing = { Chevron() },
                ) { styles = true }
            }
        }
        item(key = "also") {
            SectionLabel("Also")
            RowGroup {
                SwitchRow("Sound", "Rings with your alarm sound while the finish runs", sound, onCheckedChange = GlyphTimers::setSound)
                RowDivider()
                SwitchRow("Vibrate", "Buzzes while the finish runs", vibrate, onCheckedChange = GlyphTimers::setVibrate)
                RowDivider()
                val access = setup.notificationAccess
                SwitchRow(
                    "Show Clock app timers",
                    if (!access) "Needs notification access" else "Timers and the stopwatch you start in the Clock app show on the Glyph too",
                    mirror && access, enabled = access, onCheckedChange = GlyphTimers::setMirrorClock,
                )
            }
            // promised only once it's true: Time's up needs the notification permission
            if (canPost) Hint("When a timer ends, Time's up fills the screen, even on the lock screen.")
        }
    }

    if (custom) CustomTimerSheet({ custom = false }) { ms, label, save ->
        custom = false
        if (save) GlyphTimers.savePreset(ms)
        GlyphTimers.start(ms, label)
    }
    if (styles) {
        if (tab == 0) StyleSheet(style, { styles = false }) { GlyphTimers.setStyle(it); styles = false }
        else WatchStyleSheet(watchStyle, { styles = false }) { GlyphTimers.setWatchStyle(it); styles = false }
    }
    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, remember { TimerFaces.finished(AppGraph.profile) }, "The finish", { glyph = false },
        title = "On the Glyph", plainLine = finishLine(timing), parts = FINISH_PARTS, onChange = GlyphTimers::setTiming,
        extra = {
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = Tokens.Section), color = Tokens.Hairline)
            SwitchRow("Last 10 seconds", "Each second counts down big, with a ring flash", lastTen, inset = 0.dp, onCheckedChange = GlyphTimers::setLastTen)
            Row(Modifier.fillMaxWidth().padding(vertical = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
                Text("The last 3 seconds and the finish, on the Glyph", Modifier.weight(1f).padding(end = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                SecondaryButton("Try it", onClick = GlyphTimers::tryFinish)
            }
        },
    )
    if (allow) FullSheet({ allow = false }, "Allow", subtitle = "Each one makes timers work better", confirm = null) {
        asks.forEach { a -> OptionRow(a.title, a.why, chevron = false) { a.go(); allow = false } }
        if (asks.isEmpty()) Hint("All set.")
    }
    actionsFor?.let { id ->
        val t = state.all.firstOrNull { it.id == id }
        if (t == null) actionsFor = null
        else TimerActions(t, now, onGlyph = Timers.shown(state.all, state.watch, now, state.focus)?.key == t.id, onStop = { stop(t) }) { actionsFor = null }
    }
}

/** The On the Glyph row on both pages (tests and captures open it by this tag). */
const val ON_THE_GLYPH_TAG = "on-the-glyph"

/** One thing to allow: its name, why, and what asks for it. */
private class Ask(val title: String, val why: String, val go: () -> Unit)

/** Everything to allow on one line: "2 things to allow · Review", or the one thing with Allow. */
@Composable
private fun AllowLine(asks: List<Ask>, modifier: Modifier = Modifier, onReview: () -> Unit) {
    val one = asks.singleOrNull()
    Row(
        modifier.fillMaxWidth().background(Tokens.Raised, Tokens.CardShape).padding(start = Tokens.Gutter, end = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(Tokens.Icon))
        Text(
            if (one != null) "Allow ${one.title.lowercase()}" else "${asks.size} things to allow",
            Modifier.weight(1f).padding(horizontal = Tokens.Small), style = MaterialTheme.typography.bodyMedium, color = Tokens.Text,
        )
        TextButton(onClick = { if (one != null) one.go() else onReview() }, modifier = Modifier.heightIn(min = Tokens.Target)) {
            Text(if (one != null) "Allow" else "Review", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

/** The On the Glyph row's line: "100% · finish 30 s, repeats every 1 min". */
internal fun glyphSummary(t: com.dotforge.device.Timing, lastTen: Boolean = true): String {
    val c = t.clamped()
    val finish = when (c.showFor) {
        com.dotforge.device.ShowFor.SECONDS -> "finish ${runText(c.seconds)}" + if (c.remindMinutes > 0) ", repeats every ${c.remindMinutes} min" else ", no repeat"
        else -> "finish until dismissed"
    }
    return listOfNotNull("${c.brightness}%", speedText(c.speed).takeIf { c.speed != 1f }, finish, "last 10 s off".takeIf { !lastTen }).joinToString(" · ")
}

private fun runText(seconds: Int) = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"

/**
 * The timer's On the Glyph sheet, in the panel's order: how long the finish runs, its speed, whether it comes
 * back (every 1 or 5 min until dismissed), and brightness (chips; the slider behind "Exact brightness").
 */
private val FINISH_PARTS = TimingParts(
    showForTitle = "When it ends",
    whole = false,
    secondsChips = GlyphTimers.RUN_CHIPS,
    untilLabel = "Until dismissed",
    exactTime = false,
    remindChips = listOf(1, 5),
    remindAfterSeconds = true,
    remindAlways = true,
    speedTitle = "Finish speed",
    speedSlider = false,
    forever = false,
    more = false,
    compactBrightness = true,
)

/** "The finish runs for 30 s, then plays again every 1 min until you dismiss it." */
internal fun finishLine(t: com.dotforge.device.Timing): String = when (t.showFor) {
    com.dotforge.device.ShowFor.SECONDS -> "The finish runs for ${runText(t.seconds)}" +
        if (t.remindMinutes > 0) ", then plays again every ${t.remindMinutes} min until you dismiss it." else ", once."
    else -> "The finish runs until you dismiss it."
}

/** What the preview shows: what the Glyph shows now; null when nothing runs (the style's demo plays). */
private fun previewOf(shown: TimerShown?, style: TimerStyle, watchStyle: StopwatchStyle): Animation? {
    val p = AppGraph.profile
    val k = GlyphTimers.timing.value.brightness / 100f
    return when (shown) {
        null -> null
        is TimerShown.Done -> GlyphTimers.finishPreview()
        is TimerShown.Running -> TimerFaces.second(p, style, shown.leftMs, shown.timer.totalMs, k, GlyphTimers.lastTen.value)
        is TimerShown.Paused -> TimerFaces.paused(p, TimerFaces.countdown(p, style, shown.leftMs, shown.timer.totalMs), k)
        is TimerShown.Watch -> if (shown.stopwatch.running) TimerFaces.stopwatchSecond(p, watchStyle, shown.elapsedMs, k)
        else TimerFaces.paused(p, TimerFaces.stopwatch(p, watchStyle, shown.elapsedMs), k)
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.timerTab(
    state: GlyphTimers.State,
    now: Long,
    presets: List<Long>,
    onCustom: () -> Unit,
    onActions: (String) -> Unit,
    onStop: (GlyphTimer) -> Unit,
) {
    item(key = "presets") {
        Text("Start a timer", Modifier.padding(top = Tokens.Small, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Timers.PRESETS.forEach { m -> Chip("$m min", false, Modifier.semantics { contentDescription = "Start a $m minute timer" }) { GlyphTimers.start(m * 60_000L) } }
            presets.forEach { ms ->
                val name = Timers.lengthName(ms)
                Chip(name, false, Modifier.semantics { contentDescription = "Start a $name timer" }) { GlyphTimers.start(ms) }
            }
            Chip("Custom…", false, onClick = onCustom)
        }
    }
    val list = state.all.sortedWith(compareBy<GlyphTimer> { !it.over(now) }.thenBy { !it.running }.thenBy { it.left(now) })
    if (list.isEmpty()) return
    val onGlyph = Timers.shown(state.all, state.watch, now, state.focus)?.key
    item(key = "running") {
        SectionLabel(if (list.size == 1) "Your timer" else "Your timers")
        RowGroup {
            list.forEachIndexed { i, t ->
                if (i > 0) RowDivider(leading = true)
                TimerRow(t, now, shown = t.id == onGlyph, others = list.size > 1, onStop = { onStop(t) }) { onActions(t.id) }
            }
        }
    }
}

/**
 * One timer: what is left (or Time's up), its name and where it's from, an "On the Glyph" badge on the one the
 * Glyph shows and "Show on Glyph" on the others; pause/resume and stop beside it.
 */
@Composable
private fun TimerRow(t: GlyphTimer, now: Long, shown: Boolean, others: Boolean, onStop: () -> Unit, onOpen: () -> Unit) {
    val left = t.left(now)
    val clock = t.source == TimerSource.CLOCK
    val over = t.over(now)
    val title = if (over) "Time's up" else TimerFaces.clock(left) + if (t.running) " left" else ""
    val subtitle = (if (!over && !t.running) "Paused · " else "") + t.name + if (clock) " · Clock app" else ""
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.RowTall).clickable(onClickLabel = "More for ${t.name}", onClick = onOpen)
            .padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Small, bottom = Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Tokens.Avatar), contentAlignment = Alignment.Center) {
            val p = AppGraph.profile
            val style = GlyphTimers.style.value
            val face = remember(t.id, left / 1000, over, style) {
                if (over) TimerFaces.finished(p) else Animation.single(TimerFaces.countdown(p, style, left, t.totalMs))
            }
            GlyphPreview(face, title)
        }
        Column(Modifier.weight(1f).padding(start = Tokens.Gutter)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = 2)
            if (shown) GlyphBadge(Modifier.padding(top = Tokens.Tiny))
            else if (others && !over) Box(
                // a full 48 dp target, the words at the row's start
                Modifier.heightIn(min = Tokens.Target).clickable(role = Role.Button, onClickLabel = "Show ${t.name} on the Glyph") { GlyphTimers.showOnGlyph(t.id) }
                    .padding(end = Tokens.Small),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Show on Glyph", style = MaterialTheme.typography.labelLarge, color = Color.White) }
        }
        when {
            over -> TextButton(onClick = { GlyphTimers.dismiss(t.id) }, modifier = Modifier.padding(start = Tokens.Tiny)) {
                Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            clock -> TextButton(onClick = { GlyphTimers.dismiss(t.id) }, modifier = Modifier.heightIn(min = Tokens.Target)) {
                Text("Hide", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.semantics { contentDescription = "Hide ${t.name} from the Glyph" })
            }
            else -> Row {
                IconButton(onClick = { if (t.running) GlyphTimers.pause(t.id) else GlyphTimers.resume(t.id) }) {
                    Icon(if (t.running) DfIcons.Pause else DfIcons.Play, contentDescription = if (t.running) "Pause" else "Resume", tint = Color.White)
                }
                IconButton(onClick = onStop) { Icon(Icons.Filled.Close, contentDescription = "Stop", tint = Color.White) }
            }
        }
    }
}

/** Tapping a timer: show it on the Glyph, +1 min, pause or resume, stop. */
@Composable
private fun TimerActions(t: GlyphTimer, now: Long, onGlyph: Boolean, onStop: () -> Unit, onDismiss: () -> Unit) {
    val clock = t.source == TimerSource.CLOCK
    FullSheet(onDismiss, t.name, subtitle = if (t.over(now)) "Time's up" else TimerFaces.clock(t.left(now)) + " left", confirm = null) {
        if (!onGlyph && !t.over(now)) OptionRow("Show this one on the Glyph", "Instead of the one that ends first", chevron = false) {
            GlyphTimers.showOnGlyph(t.id)
            onDismiss()
        }
        if (!clock) {
            OptionRow("+1 min", "Adds a minute; an ended timer starts again", chevron = false) { GlyphTimers.plusMinute(t.id); onDismiss() }
            if (!t.over(now)) OptionRow(if (t.running) "Pause" else "Resume", null, chevron = false) {
                if (t.running) GlyphTimers.pause(t.id) else GlyphTimers.resume(t.id)
                onDismiss()
            }
            if (t.over(now)) OptionRow("Dismiss", "It goes off the Glyph", chevron = false) { GlyphTimers.dismiss(t.id); onDismiss() }
            else OptionRow("Stop", "It goes off the Glyph; you can undo", destructive = true, chevron = false) { onStop(); onDismiss() }
        } else {
            Hint("This timer runs in the Clock app. Pause or stop it there.")
            OptionRow("Stop showing on the Glyph", "Until the Clock app's timer ends", chevron = false) { GlyphTimers.dismiss(t.id); onDismiss() }
        }
    }
}

private fun LazyListScope.stopwatchTab(state: GlyphTimers.State, now: Long) {
    val w = state.stopwatch
    item(key = "watch") {
        Column(Modifier.fillMaxWidth().padding(top = Tokens.Small), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                TimerFaces.lap(w.elapsed(now)), style = READOUT,
                modifier = Modifier.semantics { contentDescription = "Stopwatch " + TimerFaces.clock(w.elapsed(now), roundUp = false) },
            )
            if (w.started && !w.running) Text("Paused", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
            Row(Modifier.fillMaxWidth().padding(top = Tokens.Gap), horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                when {
                    w.running -> {
                        SecondaryButton("Lap", Modifier.weight(1f).heightIn(min = 52.dp), onClick = GlyphTimers::stopwatchLap)
                        PrimaryButton("Pause", Modifier.weight(1f), onClick = GlyphTimers::stopwatchPause)
                    }
                    w.started -> {
                        SecondaryButton("Reset", Modifier.weight(1f).heightIn(min = 52.dp), onClick = GlyphTimers::stopwatchReset)
                        PrimaryButton("Resume", Modifier.weight(1f), onClick = GlyphTimers::stopwatchStart)
                    }
                    else -> PrimaryButton("Start", Modifier.fillMaxWidth(), onClick = GlyphTimers::stopwatchStart)
                }
            }
        }
    }
    val rows = w.lapRows()
    if (rows.isNotEmpty()) item(key = "laps") {
        SectionLabel("Laps")
        val marks = lapMarks(rows)
        RowGroup {
            Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
                Text("Lap", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary)
                Text("Lap time", style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary)
                Text("Total", Modifier.padding(start = Tokens.Gutter).widthIn(min = 72.dp), style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary, textAlign = TextAlign.End)
            }
            rows.forEach { lap ->
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lap ${lap.number}", style = MaterialTheme.typography.bodyLarge)
                        marks[lap.number]?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (it == "Fastest") Color.White else Tokens.AccentText) }
                    }
                    Text(TimerFaces.lap(lap.splitMs), style = MaterialTheme.typography.bodyLarge)
                    Text(TimerFaces.lap(lap.totalMs), Modifier.padding(start = Tokens.Gutter).widthIn(min = 72.dp), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.End)
                }
            }
        }
    }
    state.clockWatch?.let { c ->
        item(key = "clockwatch") {
            SectionLabel("Clock app")
            RowGroup {
                ListRow(
                    "Stopwatch · " + TimerFaces.clock(c.elapsed(now), roundUp = false), subtitle = if (c.running) "Running in the Clock app" else "Paused in the Clock app",
                )
            }
        }
    }
}

/**
 * The stopwatch and keypad readout: Inter with even-width digits, so ":" and "." read as themselves (the dot
 * font drew them as crosses) and the digits don't jump as they change.
 */
private val READOUT: TextStyle
    @Composable get() = MaterialTheme.typography.displayMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

/** With 3 laps or more: the fastest and the slowest lap by number. */
internal fun lapMarks(rows: List<Lap>): Map<Int, String> {
    if (rows.size < 3) return emptyMap()
    val fast = rows.minBy { it.splitMs }
    val slow = rows.maxBy { it.splitMs }
    if (fast.splitMs == slow.splitMs) return emptyMap()
    return mapOf(fast.number to "Fastest", slow.number to "Slowest")
}

/**
 * A custom time, as in the Clock app: digits fill hh mm ss from the right (10:00 to start with), an optional
 * name, "Save as preset", and a full-width Start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomTimerSheet(onDismiss: () -> Unit, onStart: (Long, String, Boolean) -> Unit) {
    var digits by rememberSaveable { mutableStateOf("1000") }
    var label by rememberSaveable { mutableStateOf("") }
    var save by rememberSaveable { mutableStateOf(false) }
    val ms = Timers.keypad(digits)
    // on a short screen the keys are 48 dp, so Start shows without scrolling
    val key = if (LocalConfiguration.current.screenHeightDp < 700) Tokens.Target else 56.dp
    FullSheet(
        onDismiss, "Custom timer", confirm = null,
        // Start is pinned under the keys: it never sits under the scroll fade
        footer = {
            PrimaryButton(
                if (ms > 0) "Start " + Timers.lengthName(ms) else "Start", Modifier.fillMaxWidth().padding(top = Tokens.Small), enabled = ms > 0,
            ) { onStart(ms.coerceAtLeast(1_000), label.trim(), save) }
        },
    ) {
        val d = digits.padStart(6, '0')
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            listOf(d.substring(0, 2) to "h", d.substring(2, 4) to "m", d.substring(4, 6) to "s").forEach { (v, unit) ->
                Text(v, style = READOUT, color = if (digits.isEmpty()) Tokens.Secondary else Tokens.Text)
                Text(unit, Modifier.padding(start = 2.dp, end = 10.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = Tokens.Secondary)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = Tokens.Gap), verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).heightIn(min = key).background(Tokens.Raised, RoundedCornerShape(28.dp))
                                .clickable(role = Role.Button, onClickLabel = if (k == "⌫") "Delete" else null) {
                                    digits = when (k) {
                                        "⌫" -> digits.dropLast(1)
                                        else -> (digits + k).trimStart('0').take(6)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == "⌫") Icon(DfIcons.Backspace, contentDescription = "Delete", tint = Color.White)
                            else Text(k, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            label, { label = it.take(24) }, Modifier.fillMaxWidth().padding(top = Tokens.Gap),
            label = { Text("Name (optional)") }, placeholder = { Text("Tea") }, singleLine = true,
        )
        SwitchRow("Save as preset", "It joins the chips above", save, inset = 0.dp) { save = it }
    }
}

/** The six timer styles, each with its own moving preview; the chosen one has a tick. */
@Composable
private fun StyleSheet(current: TimerStyle, onDismiss: () -> Unit, onPick: (TimerStyle) -> Unit) {
    FullSheet(onDismiss, "Timer style", subtitle = "How a timer looks on the Glyph", confirm = null) {
        TimerStyle.entries.forEach { st ->
            OptionRow(
                st.label, st.hint, selected = st == current,
                preview = { GlyphPreview(remember(st) { GlyphTimers.preview(st) }, st.label + " style", userPlay = true) },
            ) { onPick(st) }
        }
    }
}

/** The stopwatch's own styles. */
@Composable
private fun WatchStyleSheet(current: StopwatchStyle, onDismiss: () -> Unit, onPick: (StopwatchStyle) -> Unit) {
    FullSheet(onDismiss, "Stopwatch style", subtitle = "How the stopwatch looks on the Glyph", confirm = null) {
        StopwatchStyle.entries.forEach { st ->
            OptionRow(
                st.label, st.hint, selected = st == current,
                preview = { GlyphPreview(remember(st) { GlyphTimers.preview(st) }, st.label + " style", userPlay = true) },
            ) { onPick(st) }
        }
    }
}
