package com.dotforge.ui.features

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
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
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.GlyphBadge
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
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
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.speedText

/**
 * Features > Live progress (was Progress rings, mykee's ask), in the order you use it: the main switch and the
 * On the Glyph row (how long a ring shows, the speed of its pulse and the done animation, how often the done
 * animation plays, brightness, the middle and Try it, in one sheet); what is on the way now, the one on the Glyph
 * marked (a row opens its app, Hide takes it off); the known delivery and ride apps on this phone, each with its
 * own switch, and the rest folded into "N not on this phone"; other apps; the rings before the next calendar
 * event and alarm.
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
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val top = active.firstOrNull()?.id
    val others = rules.any { it.id == RuleEngine.PROGRESS_PRESET && it.enabled }
    val access = setup.notificationAccess
    val live = on && access
    var calendarAllowed by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAllowed = it }
    val installed = remember { ProgressApps.ALL.associate { it.id to installed(ctx, it) } }
    val here = ProgressApps.ALL.filter { installed[it.id] == true }
    val away = ProgressApps.ALL.filter { installed[it.id] != true }
    var awayOpen by rememberSaveable { mutableStateOf(false) }
    var glyph by rememberSaveable { mutableStateOf(false) }
    // off (or without access) the rows stay readable at half strength
    val quiet = if (live) Modifier else Modifier.alpha(0.5f)

    FeatureLayout(Feature.PROGRESS, nav) {
        item(key = "main") {
            if (!access) StatusBanner(
                "Let DotForge read notifications: rings come from your apps' notifications.", Modifier.padding(bottom = Tokens.Gap), button = "Allow",
            ) { setup.needNotificationAccess() }
            RowGroup {
                SwitchRow(
                    "Live progress", if (!access) "Needs access" else "Rings for deliveries, rides and downloads",
                    live, enabled = access,
                ) { AppGraph.setProgressRings(it) }
                RowDivider()
                ListRow(
                    "On the Glyph", Modifier.testTag(ON_THE_GLYPH_TAG), subtitle = progressSummary(timing, showFor),
                    leading = { GlyphPreview(remember { sample() }, "A ring, 12 minutes left") },
                    trailing = { Chevron() },
                ) { glyph = true }
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
                    NowRow(item, onGlyph = LiveProgressRuntime.showing(item.key, top), onOpen = { openApp(ctx, item.packageName) }) {
                        LiveProgressRuntime.hide(item.key)
                    }
                }
            }
        }
        item(key = "apps") {
            SectionLabel("Apps")
            RowGroup(quiet) {
                here.forEachIndexed { i, app ->
                    if (i > 0) RowDivider(leading = true)
                    // switched off (or without access) every app reads off and grey, whatever it's set to
                    SwitchRow(
                        app.name, app.what, app.id !in off && live, enabled = live,
                        leading = { LibraryGlyph(app.icon, still = true) },
                    ) {
                        AppGraph.liveProgress.setApp(app.id, it)
                        LiveProgressRuntime.refresh()
                    }
                }
                if (here.isNotEmpty()) RowDivider(leading = true)
                SwitchRow("Other apps", "Downloads, uploads and any app with a progress bar", others && live, enabled = live, leading = { LibraryGlyph("download", still = true) }) { want ->
                    if (want) AppGraph.rules.ensurePreset(RuleEngine.PROGRESS_PRESET)
                    AppGraph.rules.rules.value.firstOrNull { it.id == RuleEngine.PROGRESS_PRESET }?.let { AppGraph.rules.upsert(it.copy(enabled = want)) }
                }
                if (away.isNotEmpty()) {
                    RowDivider()
                    val turn by androidx.compose.animation.core.animateFloatAsState(if (awayOpen) 180f else 0f, label = "away")
                    ListRow(
                        "${away.size} not on this phone", subtitle = if (awayOpen) "They light a ring once installed" else away.joinToString(", ") { it.name },
                        subtitleLines = 1,
                        trailing = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = if (awayOpen) "Hide" else "Show", tint = Tokens.Secondary, modifier = Modifier.rotate(turn)) },
                    ) { awayOpen = !awayOpen }
                    if (awayOpen) away.forEach { app ->
                        RowDivider(leading = true)
                        ListRow(app.name, subtitle = app.what, leading = { LibraryGlyph(app.icon, still = true) }, enabled = false)
                    }
                }
            }
        }
        // the media ring (it was under Old screens > Integrations): its own switch, needs only Notification access
        item(key = "media") {
            val mediaOn by AppGraph.media.on.collectAsStateWithLifecycle()
            val mediaAway by AppGraph.media.onlyLockedOrFaceDown.collectAsStateWithLifecycle()
            SectionLabel("Music and video")
            RowGroup {
                SwitchRow(
                    "Media progress", "While music or a video plays, the outer ring shows how far along it is", mediaOn && access, enabled = access,
                    leading = { LibraryGlyph("music", still = true) },
                ) { AppGraph.media.setOn(it) }
                if (mediaOn && access) {
                    RowDivider(leading = true)
                    SwitchRow("Only when locked or face down", "Keeps the ring dark while you use the phone", mediaAway) {
                        AppGraph.media.setOnlyLockedOrFaceDown(it)
                    }
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
    }

    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, remember(minutes) { LiveProgressRuntime.story() }, "A delivery arriving, then done", { glyph = false },
        title = "On the Glyph", plainLine = progressLine(timing, showFor),
        parts = progressParts(showFor) { AppGraph.liveProgress.setShowFor(it); LiveProgressRuntime.refresh() },
        onChange = { AppGraph.liveProgress.setTiming(it); LiveProgressRuntime.refresh() },
        extra = {
            Text("Middle shows", Modifier.padding(top = Tokens.Section, bottom = Tokens.Small).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                Middle("Minutes", "12 min left", minutes, remember { sample() }) { AppGraph.liveProgress.setShowMinutes(true); LiveProgressRuntime.refresh() }
                Middle("Stage", "The order's stage", !minutes, remember { stageSample() }) { AppGraph.liveProgress.setShowMinutes(false); LiveProgressRuntime.refresh() }
            }
            Row(Modifier.fillMaxWidth().padding(top = Tokens.Gap, bottom = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
                Text("A delivery on its way, arriving, then done, on the Glyph", Modifier.weight(1f).padding(end = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                SecondaryButton("Try it", onClick = LiveProgressRuntime::tryIt)
            }
        },
    )
}

/** One ring under Now: its ring, what it is, the "On the Glyph" badge on the one showing, and Hide. */
@Composable
private fun NowRow(item: LiveProgressRuntime.Item, onGlyph: Boolean, onOpen: () -> Unit, onHide: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.RowTall).clickable(onClickLabel = "Open ${item.name}", onClick = onOpen)
            .padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Small, bottom = Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphPreview(remember(item) { LiveProgressRuntime.animation(item) }, item.name + " progress")
        Column(Modifier.weight(1f).padding(start = Tokens.Gutter)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text(nowLine(item), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = 2)
            if (onGlyph) GlyphBadge(Modifier.padding(top = Tokens.Tiny))
        }
        TextButton(onClick = onHide, modifier = Modifier.heightIn(min = Tokens.Target)) {
            Text("Hide", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.semantics { contentDescription = "Hide ${item.name} from the Glyph" })
        }
    }
}

/**
 * The sheet's parts, in the panel's order: Show for (the ring's own choices), Speed (the pulse and the done
 * animation), Repeat (the done animation: once, 3 times, until you check) and Brightness (every ring).
 */
private fun progressParts(showFor: ProgressShowFor, onShowFor: (ProgressShowFor) -> Unit) = TimingParts(
    showForTitle = "Show the ring",
    showForSlot = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            ProgressShowFor.entries.forEach { v -> Chip(v.label, showFor == v) { onShowFor(v) } }
        }
        Text(showForLine(showFor), Modifier.padding(top = Tokens.Tiny), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
    },
    speedTitle = "Speed",
    speedSlider = false,
    playsTimes = LiveProgressSettings.DONE_TIMES,
    repeatTitle = "Repeat the done animation",
    forever = false,
    more = false,
    compactBrightness = true,
)

/** The On the Glyph row's line: "100% · whole time · done once". */
internal fun progressSummary(t: com.dotforge.device.Timing, showFor: ProgressShowFor): String {
    val c = t.clamped()
    val done = when {
        c.untilChecked -> "done until you check"
        c.repeat == com.dotforge.device.Repeat.TIMES -> "done ${c.times} times"
        else -> "done once"
    }
    return listOfNotNull("${c.brightness}%", speedText(c.speed).takeIf { c.speed != 1f }, showFor.label.lowercase(), done).joinToString(" · ")
}

/** The sheet's plain line. */
private fun progressLine(t: com.dotforge.device.Timing, showFor: ProgressShowFor): String {
    val c = t.clamped()
    val done = when {
        c.untilChecked -> "plays until you check"
        c.repeat == com.dotforge.device.Repeat.TIMES -> "plays ${c.times} times"
        else -> "plays once"
    }
    return "Rings pulse at ${speedText(c.speed)} as it arrives; the done animation $done."
}

private fun showForLine(v: ProgressShowFor) = when (v) {
    ProgressShowFor.WHOLE -> "The ring stays on until it arrives."
    ProgressShowFor.LAST_10 -> "From 10 minutes before it arrives. A ring with no arrival time shows the whole time."
    ProgressShowFor.ON_CHANGE -> "For 10 seconds whenever the time or the stage changes."
}

/** "12 min left · On the way", or Delivered / Arrived for one that's done. */
private fun nowLine(item: LiveProgressRuntime.Item): String =
    if (item.done) (if (item.app?.kind == TripKind.RIDE) "Arrived" else "Delivered") + " · Tap to open"
    else LiveProgress.summary(item.reading)

/** One choice shown as a tile ("Middle shows", the charging design): its preview over its name, outlined when chosen. */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.Middle(name: String, line: String, selected: Boolean, preview: Animation, poster: com.dotforge.device.Frame? = null, description: String = "$name in the middle", onPick: () -> Unit) {
    Column(
        Modifier.weight(1f)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Tokens.Hairline, RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onPick)
            .padding(Tokens.Gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphPreview(preview, description, size = PreviewSize.MEDIUM, posterFrame = poster)
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
