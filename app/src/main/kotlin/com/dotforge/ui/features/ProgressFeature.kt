package com.dotforge.ui.features

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.live.Centre
import com.dotforge.live.LiveProgress
import com.dotforge.live.LiveRing
import com.dotforge.live.ProgressApp
import com.dotforge.live.ProgressApps
import com.dotforge.live.Stage
import com.dotforge.live.TripKind
import com.dotforge.notify.LiveProgressRuntime
import com.dotforge.notify.LiveProgressSettings
import com.dotforge.notify.ProgressShowFor
import com.dotforge.rules.RuleEngine
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.LibraryGlyph
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingPanel
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.Tokens

/**
 * Features > Live progress (was Progress rings, mykee's ask): the main switch, what is on the way now (a row
 * opens its app), the known delivery and ride apps (each with its own switch, marked Installed or Not on this
 * phone) and other apps, the rings before the next calendar event and alarm, and On the Glyph: how long a ring
 * shows, what the middle shows, brightness, the done animation and Try it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val on by AppGraph.progressRings.collectAsStateWithLifecycle()
    val items by LiveProgressRuntime.items.collectAsStateWithLifecycle()
    val off by AppGraph.liveProgress.off.collectAsStateWithLifecycle()
    val minutes by AppGraph.liveProgress.showMinutes.collectAsStateWithLifecycle()
    val showFor by AppGraph.liveProgress.showFor.collectAsStateWithLifecycle()
    val timing by AppGraph.liveProgress.timing.collectAsStateWithLifecycle()
    val calendar by AppGraph.liveProgress.calendarLead.collectAsStateWithLifecycle()
    val alarm by AppGraph.liveProgress.alarmLead.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val others = rules.any { it.id == RuleEngine.PROGRESS_PRESET && it.enabled }
    val access = setup.notificationAccess
    var calendarAllowed by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAllowed = it }
    val installed = remember { ProgressApps.ALL.associate { it.id to installed(ctx, it) } }
    // off (or without access) the rows stay readable at half strength; app switches still work, to set up ahead
    val quiet = if (on && access) Modifier else Modifier.alpha(0.5f)

    FeatureLayout(Feature.PROGRESS, nav) {
        item(key = "main") {
            if (!access) StatusBanner(
                "Let DotForge read notifications: rings come from your apps' notifications.", Modifier.padding(bottom = Tokens.Gap), button = "Allow",
            ) { setup.needNotificationAccess() }
            RowGroup {
                SwitchRow(
                    "Live progress", if (!access) "Needs access" else "Rings for deliveries, rides and downloads",
                    on && access, enabled = access,
                ) { AppGraph.setProgressRings(it) }
            }
        }
        item(key = "now") {
            SectionLabel("Now")
            if (items.isEmpty()) RowGroup(quiet) {
                Row(Modifier.fillMaxWidth().padding(Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    GlyphPreview(remember { sample() }, "Example ring: 12 minutes left", size = PreviewSize.SMALL)
                    Text(
                        when {
                            !access -> "Once DotForge can read notifications, an order, a ride or a download shows its ring here and on the Glyph."
                            on -> "Nothing on the way. When an order, a ride or a download shows progress, its ring appears here and on the Glyph."
                            else -> "Live progress is off. Turn it on and an order, a ride or a download shows its ring here and on the Glyph."
                        },
                        Modifier.weight(1f).padding(start = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary,
                    )
                }
            } else RowGroup {
                items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(leading = true)
                    ListRow(
                        item.name, subtitle = nowLine(item),
                        leading = { GlyphPreview(remember(item) { LiveProgressRuntime.animation(item) }, item.name + " progress") },
                        trailing = {
                            IconButton(onClick = { LiveProgressRuntime.hide(item.key) }) {
                                Icon(DfIcons.EyeOff, contentDescription = "Stop showing this", tint = Color.White)
                            }
                        },
                    ) { openApp(ctx, item.packageName) }
                }
            }
        }
        item(key = "apps") {
            SectionLabel("Apps")
            RowGroup(quiet) {
                ProgressApps.ALL.forEachIndexed { i, app ->
                    if (i > 0) RowDivider(leading = true)
                    val here = installed[app.id] == true
                    SwitchRow(
                        app.name, app.what + " · " + if (here) "Installed" else "Not on this phone", app.id !in off,
                        Modifier.then(if (here) Modifier else Modifier.alpha(0.6f)),
                        leading = { LibraryGlyph(app.icon, still = true) },
                    ) {
                        AppGraph.liveProgress.setApp(app.id, it)
                        LiveProgressRuntime.refresh()
                    }
                }
                RowDivider(leading = true)
                SwitchRow("Other apps", "Downloads, uploads and any app with a progress bar", others, leading = { LibraryGlyph("download", still = true) }) { want ->
                    if (want) AppGraph.rules.ensurePreset(RuleEngine.PROGRESS_PRESET)
                    AppGraph.rules.rules.value.firstOrNull { it.id == RuleEngine.PROGRESS_PRESET }?.let { AppGraph.rules.upsert(it.copy(enabled = want)) }
                }
            }
        }
        item(key = "coming") {
            SectionLabel("Coming up")
            RowGroup(quiet) {
                Lead("Next calendar event", "Minutes before it starts", calendar) { m ->
                    AppGraph.liveProgress.setCalendarLead(m)
                    if (m > 0 && !calendarAllowed) askCalendar.launch(Manifest.permission.READ_CALENDAR)
                }
                RowDivider()
                Lead("Next alarm", "Minutes before it rings", alarm) { AppGraph.liveProgress.setAlarmLead(it) }
            }
            if (calendar > 0 && !calendarAllowed) StatusBanner(
                "Calendar access is off, so the event ring can't show.", Modifier.padding(top = Tokens.Gap), button = "Allow",
            ) { askCalendar.launch(Manifest.permission.READ_CALENDAR) }
        }
        item(key = "glyph") {
            SectionLabel("On the Glyph")
            RowGroup(quiet) {
                Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
                    Text("Show for", Modifier.padding(top = Tokens.Tiny, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        ProgressShowFor.entries.forEach { v -> Chip(v.label, showFor == v) { AppGraph.liveProgress.setShowFor(v); LiveProgressRuntime.refresh() } }
                    }
                    Text(showForLine(showFor), Modifier.padding(top = Tokens.Tiny), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                    Text("Middle shows", Modifier.padding(top = Tokens.Section, bottom = Tokens.Small).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                        Middle("Minutes", "12 min left", minutes, remember { sample() }) { AppGraph.liveProgress.setShowMinutes(true); LiveProgressRuntime.refresh() }
                        Middle("Stage", "The order's stage", !minutes, remember { stageSample() }) { AppGraph.liveProgress.setShowMinutes(false); LiveProgressRuntime.refresh() }
                    }
                    TimingPanel(
                        timing, TimingKind.ANIMATION, showLine = false, parts = DONE_PARTS,
                        onChange = AppGraph.liveProgress::setTiming,
                    )
                }
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Gap), verticalAlignment = Alignment.CenterVertically) {
                    GlyphPreview(remember { LiveRing.done(AppGraph.profile, LiveRing.icon(AppGraph.profile, "g-parcel-done")) }, "The done animation")
                    Text("A delivery on its way, arriving, then done", Modifier.weight(1f).padding(horizontal = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                    SecondaryButton("Try it", onClick = LiveProgressRuntime::tryIt)
                }
            }
        }
    }
}

/** The ring's brightness first (every ring), then the done animation: once, 3 times or until you check. */
private val DONE_PARTS = TimingParts(
    brightnessFirst = true,
    gapAbove = true,
    showForTitle = "Done animation",
    playsTimes = LiveProgressSettings.DONE_TIMES,
    speed = false,
    more = false,
)

private fun showForLine(v: ProgressShowFor) = when (v) {
    ProgressShowFor.WHOLE -> "The ring stays on until it arrives."
    ProgressShowFor.LAST_10 -> "From 10 minutes before it arrives. A ring with no arrival time shows the whole time."
    ProgressShowFor.ON_CHANGE -> "For 10 seconds whenever the time or the stage changes."
}

/** "12 min left · On the way", or Delivered / Arrived for one that's done. */
private fun nowLine(item: LiveProgressRuntime.Item): String =
    if (item.done) (if (item.app?.kind == TripKind.RIDE) "Arrived" else "Delivered") + " · Tap to open"
    else LiveProgress.summary(item.reading)

/** One "Middle shows" choice: its preview over its name, outlined when chosen. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Middle(name: String, line: String, selected: Boolean, preview: Animation, onPick: () -> Unit) {
    Column(
        Modifier.weight(1f)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Tokens.Hairline, RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onPick)
            .padding(Tokens.Gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphPreview(preview, "$name in the middle", size = PreviewSize.MEDIUM)
        Text(name, Modifier.padding(top = Tokens.Small), style = MaterialTheme.typography.bodyLarge)
        Text(line, style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary)
    }
}

/** A "fill the ring before" choice: Off or a lead time in minutes, on one row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lead(title: String, subtitle: String, value: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Gap)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        FlowRow(Modifier.padding(top = Tokens.Tiny), horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
            LiveProgressSettings.LEADS.forEach { m -> Chip(if (m == 0) "Off" else "$m", value == m) { onPick(m) } }
        }
    }
}

private fun installed(ctx: Context, app: ProgressApp): Boolean =
    app.packages.any { pkg -> runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess }

/** A row under Now opens its app. */
private fun openApp(ctx: Context, pkg: String) {
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** The example in the empty state: a delivery 12 minutes away, on the way. */
private fun sample(): Animation =
    LiveRing.animation(AppGraph.profile, Stage.ON_THE_WAY.fraction, Centre.Minutes(12), arriving = false)

/** The same delivery with its stage in the middle. */
private fun stageSample(): Animation =
    LiveRing.animation(AppGraph.profile, Stage.ON_THE_WAY.fraction, LiveRing.icon(AppGraph.profile, "g-scooter")?.let { Centre.Icon(it) } ?: Centre.None, arriving = false)
