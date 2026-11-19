package com.dotforge.ui.features

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.TimerFaces
import com.dotforge.device.TimerStyle
import com.dotforge.live.GlyphTimer
import com.dotforge.live.TimerShown
import com.dotforge.live.TimerSource
import com.dotforge.live.Timers
import com.dotforge.timer.GlyphTimers
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.DotSlider
import com.dotforge.ui.design.FullSheet
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
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Features > Timer & stopwatch (mykee's ask): a live preview of what the Glyph shows, presets that start a timer
 * in one tap, a custom time, every running timer (ours and the Clock app's) with pause and stop, the stopwatch
 * with laps, the style (6, each with a moving preview) and "Show Clock app timers".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val state by GlyphTimers.state.collectAsStateWithLifecycle()
    val style by GlyphTimers.style.collectAsStateWithLifecycle()
    val mirror by GlyphTimers.mirrorClock.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (state.stopwatch.started && state.timers.isEmpty()) 1 else 0) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var styles by rememberSaveable { mutableStateOf(false) }
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
    val short = LocalConfiguration.current.screenHeightDp < 700

    FeatureLayout(Feature.TIMER, nav) {
        if (!canPost && state.ours) item(key = "post") {
            StatusBanner(
                "Allow notifications to see the timer in the status bar and on the lock screen.", Modifier.padding(bottom = Tokens.Gap),
                button = "Allow",
            ) { post.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        item(key = "preview") {
            val shown = Timers.shown(state.all, state.watch, now)
            Column(Modifier.fillMaxWidth().padding(vertical = Tokens.Small), horizontalAlignment = Alignment.CenterHorizontally) {
                val anim = remember(shown?.let { it::class }, shown?.key, now / 1000, style) { previewOf(shown, style) }
                GlyphPreview(
                    anim, shown?.let { GlyphTimers.label(it) } ?: "${style.label} timer",
                    Modifier.size(if (short) 120.dp else 160.dp), size = PreviewSize.LARGE, userPlay = shown != null,
                )
                Text(
                    shown?.let { GlyphTimers.label(it) } ?: "Nothing running. Pick a time to start.",
                    Modifier.padding(top = Tokens.Small).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = if (shown == null) Tokens.Secondary else Tokens.Text, textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "tabs") { Segmented(listOf("Timer", "Stopwatch"), tab, Modifier.padding(vertical = Tokens.Small)) { tab = it } }
        if (tab == 0) timerTab(state, now, onCustom = { custom = true }, onActions = { actionsFor = it })
        else stopwatchTab(state, now)
        item(key = "options") {
            SectionLabel("Options")
            RowGroup {
                ListRow(
                    "Style", subtitle = style.label + " · " + style.hint, subtitleLines = 3,
                    leading = { GlyphPreview(remember(style) { GlyphTimers.preview(style) }, style.label + " style") }, trailing = { Chevron() },
                ) { styles = true }
                RowDivider(leading = true)
                SwitchRow(
                    "Show Clock app timers", "Timers and the stopwatch you start in the Clock app show on the Glyph too",
                    mirror, onCheckedChange = GlyphTimers::setMirrorClock,
                )
            }
            if (mirror && !setup.notificationAccess) {
                StatusBanner("Clock app timers need notification access.", Modifier.padding(top = Tokens.Gap), button = "Allow") { setup.needNotificationAccess() }
            }
        }
    }

    if (custom) CustomTimerSheet({ custom = false }) { ms, label ->
        custom = false
        GlyphTimers.start(ms, label)
    }
    if (styles) StyleSheet(style, { styles = false }) { GlyphTimers.setStyle(it); styles = false }
    actionsFor?.let { id ->
        val t = state.all.firstOrNull { it.id == id }
        if (t == null) actionsFor = null
        else TimerActions(t, now) { actionsFor = null }
    }
}

/** What the preview shows: what the Glyph shows now, else the chosen style counting down. */
private fun previewOf(shown: TimerShown?, style: TimerStyle): Animation {
    val p = AppGraph.profile
    return when (shown) {
        null -> GlyphTimers.preview(style)
        is TimerShown.Done -> TimerFaces.finished(p)
        is TimerShown.Running -> TimerFaces.second(p, style, shown.leftMs, shown.timer.totalMs)
        is TimerShown.Paused -> Animation.single(TimerFaces.countdown(p, style, shown.leftMs, shown.timer.totalMs))
        is TimerShown.Watch -> if (shown.stopwatch.running) TimerFaces.stopwatchSecond(p, style, shown.elapsedMs) else Animation.single(TimerFaces.stopwatch(p, style, shown.elapsedMs))
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.timerTab(state: GlyphTimers.State, now: Long, onCustom: () -> Unit, onActions: (String) -> Unit) {
    item(key = "presets") {
        Text("Start a timer", Modifier.padding(top = Tokens.Small, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Timers.PRESETS.forEach { m -> Chip("$m min", false, Modifier.semantics { contentDescription = "Start a $m minute timer" }) { GlyphTimers.start(m * 60_000L) } }
            Chip("Custom…", false, onClick = onCustom)
        }
    }
    val list = state.all.sortedWith(compareBy<GlyphTimer> { !it.over(now) }.thenBy { !it.running }.thenBy { it.left(now) })
    if (list.isEmpty()) return
    item(key = "running") {
        SectionLabel(if (list.size == 1) "Your timer" else "Your timers · the Glyph shows the one that ends first")
        RowGroup {
            list.forEachIndexed { i, t ->
                if (i > 0) RowDivider(leading = true)
                TimerRow(t, now) { onActions(t.id) }
            }
        }
        if (state.all.count { it.over(now) } > 1) {
            TextButton(onClick = GlyphTimers::dismissAllDone, modifier = Modifier.padding(top = Tokens.Tiny)) {
                Text("Dismiss all ended", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

/** One timer: what is left (or Time's up), its name and where it's from; pause/resume and stop beside it. */
@Composable
private fun TimerRow(t: GlyphTimer, now: Long, onOpen: () -> Unit) {
    val left = t.left(now)
    val clock = t.source == TimerSource.CLOCK
    val over = t.over(now)
    val title = if (over) "Time's up" else TimerFaces.clock(left) + if (t.running) " left" else ""
    val subtitle = (if (!over && !t.running) "Paused · " else "") + t.name + if (clock) " · Clock app" else ""
    ListRow(
        title, subtitle = subtitle,
        leading = {
            val p = AppGraph.profile
            val face = remember(t.id, left / 1000, over, GlyphTimers.style.value) {
                if (over) TimerFaces.finished(p) else Animation.single(TimerFaces.countdown(p, GlyphTimers.style.value, left, t.totalMs))
            }
            GlyphPreview(face, title)
        },
        trailing = {
            when {
                over -> TextButton(onClick = { GlyphTimers.dismiss(t.id) }, modifier = Modifier.padding(start = Tokens.Tiny)) {
                    Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                clock -> IconButton(onClick = { GlyphTimers.dismiss(t.id) }) {
                    Icon(DfIcons.EyeOff, contentDescription = "Stop showing on the Glyph", tint = Color.White)
                }
                else -> Row {
                    IconButton(onClick = { if (t.running) GlyphTimers.pause(t.id) else GlyphTimers.resume(t.id) }) {
                        Icon(if (t.running) DfIcons.Pause else DfIcons.Play, contentDescription = if (t.running) "Pause" else "Resume", tint = Color.White)
                    }
                    IconButton(onClick = { GlyphTimers.cancel(t.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop", tint = Color.White)
                    }
                }
            }
        },
        onClick = onOpen,
    )
}

/** Tapping a timer: +1 min, pause or resume, stop. */
@Composable
private fun TimerActions(t: GlyphTimer, now: Long, onDismiss: () -> Unit) {
    val clock = t.source == TimerSource.CLOCK
    FullSheet(onDismiss, t.name, subtitle = if (t.over(now)) "Time's up" else TimerFaces.clock(t.left(now)) + " left", confirm = null) {
        if (!clock) {
            OptionRow("+1 min", "Adds a minute; an ended timer starts again", chevron = false) { GlyphTimers.plusMinute(t.id); onDismiss() }
            if (!t.over(now)) OptionRow(if (t.running) "Pause" else "Resume", null, chevron = false) {
                if (t.running) GlyphTimers.pause(t.id) else GlyphTimers.resume(t.id)
                onDismiss()
            }
            OptionRow(if (t.over(now)) "Dismiss" else "Stop", "It goes off the Glyph", destructive = !t.over(now), chevron = false) { GlyphTimers.dismiss(t.id); onDismiss() }
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
                TimerFaces.lap(w.elapsed(now)), style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { contentDescription = "Stopwatch " + TimerFaces.clock(w.elapsed(now), roundUp = false) },
            )
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
        RowGroup {
            rows.forEachIndexed { i, lap ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lap ${lap.number}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(TimerFaces.lap(lap.splitMs), style = MaterialTheme.typography.bodyLarge)
                    Text(TimerFaces.lap(lap.totalMs), Modifier.padding(start = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
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

/** A custom time: hours, minutes, seconds (sliders with their values), an optional name, then Start. */
@Composable
private fun CustomTimerSheet(onDismiss: () -> Unit, onStart: (Long, String) -> Unit) {
    var h by rememberSaveable { mutableIntStateOf(0) }
    var m by rememberSaveable { mutableIntStateOf(10) }
    var s by rememberSaveable { mutableIntStateOf(0) }
    var label by rememberSaveable { mutableStateOf("") }
    val ms = Timers.custom(h, m, s)
    FullSheet(onDismiss, "Custom timer", confirm = "Start", onConfirm = { onStart(ms, label.trim()) }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(TimerFaces.clock(ms), style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Slider("Hours", h, 0..23) { h = it }
        Slider("Minutes", m, 0..59) { m = it }
        Slider("Seconds", s, 0..59) { s = it }
        OutlinedTextField(
            label, { label = it.take(24) }, Modifier.fillMaxWidth().padding(top = Tokens.Gap),
            label = { Text("Name (optional)") }, placeholder = { Text("Tea") }, singleLine = true,
        )
    }
}

@Composable
private fun Slider(name: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Tokens.Gap)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        Text("$value", style = MaterialTheme.typography.bodyMedium)
    }
    DotSlider(name, value.toFloat(), range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1, readout = "$value ${name.lowercase()}") {
        onChange(it.roundToInt().coerceIn(range))
    }
}

/** The six styles, each with its own moving preview; the chosen one has a tick. */
@Composable
private fun StyleSheet(current: TimerStyle, onDismiss: () -> Unit, onPick: (TimerStyle) -> Unit) {
    FullSheet(onDismiss, "Style", subtitle = "How a timer looks on the Glyph", confirm = null) {
        TimerStyle.entries.forEach { st ->
            OptionRow(
                st.label, st.hint, selected = st == current,
                preview = { GlyphPreview(remember(st) { GlyphTimers.preview(st) }, st.label + " style", userPlay = true) },
            ) { onPick(st) }
        }
    }
}
