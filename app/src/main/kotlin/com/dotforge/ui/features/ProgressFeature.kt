package com.dotforge.ui.features

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.live.Centre
import com.dotforge.live.LiveProgress
import com.dotforge.live.LiveRing
import com.dotforge.live.ProgressApps
import com.dotforge.live.Stage
import com.dotforge.notify.LiveProgressRuntime
import com.dotforge.notify.LiveProgressSettings
import com.dotforge.rules.RuleEngine
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.LibraryGlyph
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens

/**
 * Features > Live progress (was Progress rings, mykee's ask): the main switch, what is on the way now, the
 * known delivery and ride apps (each with its own switch) and other apps, the rings before the next calendar
 * event and alarm, and whether the middle shows minutes. It says plainly that it needs the arrival time in the
 * app's notification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val on by AppGraph.progressRings.collectAsStateWithLifecycle()
    val items by LiveProgressRuntime.items.collectAsStateWithLifecycle()
    val off by AppGraph.liveProgress.off.collectAsStateWithLifecycle()
    val minutes by AppGraph.liveProgress.showMinutes.collectAsStateWithLifecycle()
    val calendar by AppGraph.liveProgress.calendarLead.collectAsStateWithLifecycle()
    val alarm by AppGraph.liveProgress.alarmLead.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val others = rules.any { it.id == RuleEngine.PROGRESS_PRESET && it.enabled }
    var calendarAllowed by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    var pendingLead by remember { mutableStateOf(0) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAllowed = granted
        if (granted) AppGraph.liveProgress.setCalendarLead(pendingLead)
    }

    FeatureLayout(Feature.PROGRESS, nav) {
        item(key = "needs") {
            StatusBanner("Works when the app shows the arrival time in its notification.", info = true)
        }
        item(key = "main") {
            RowGroup(Modifier.padding(top = Tokens.Gap)) {
                SwitchRow("Live progress", "Rings for deliveries, rides and downloads", on) { AppGraph.setProgressRings(it) }
            }
            if (on && !setup.notificationAccess) {
                StatusBanner("Live progress reads notifications, so it needs notification access.", Modifier.padding(top = Tokens.Gap), button = "Allow") { setup.needNotificationAccess() }
            }
        }
        item(key = "now") {
            SectionLabel("Now")
            if (items.isEmpty()) RowGroup {
                Row(Modifier.fillMaxWidth().padding(Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    GlyphPreview(remember { sample() }, "Example ring: 12 minutes left", size = PreviewSize.SMALL)
                    Text(
                        if (on) "Nothing on the way. When an order, a ride or a download shows progress, its ring appears here and on the Glyph."
                        else "Live progress is off. Turn it on and an order, a ride or a download shows its ring here and on the Glyph.",
                        Modifier.weight(1f).padding(start = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary,
                    )
                }
            } else RowGroup {
                items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(leading = true)
                    ListRow(
                        item.name, subtitle = LiveProgress.summary(item.reading),
                        leading = { GlyphPreview(remember(item) { LiveProgressRuntime.animation(item) }, item.name + " progress") },
                    )
                }
            }
        }
        item(key = "apps") {
            SectionLabel("Apps")
            RowGroup {
                ProgressApps.ALL.forEachIndexed { i, app ->
                    if (i > 0) RowDivider(leading = true)
                    SwitchRow(app.name, app.what, app.id !in off, enabled = on, leading = { LibraryGlyph(app.icon, still = true) }) {
                        AppGraph.liveProgress.setApp(app.id, it)
                        LiveProgressRuntime.refresh()
                    }
                }
                RowDivider(leading = true)
                SwitchRow("Other apps", "Downloads, uploads and any app with a progress bar", others, enabled = on, leading = { LibraryGlyph("g-parcel", still = true) }) { want ->
                    if (want) AppGraph.rules.ensurePreset(RuleEngine.PROGRESS_PRESET)
                    AppGraph.rules.rules.value.firstOrNull { it.id == RuleEngine.PROGRESS_PRESET }?.let { AppGraph.rules.upsert(it.copy(enabled = want)) }
                }
            }
        }
        item(key = "coming") {
            SectionLabel("Coming up")
            RowGroup {
                Lead(
                    "Next calendar event", if (calendar > 0 && !calendarAllowed) "Needs calendar access: pick a time to allow it" else "The ring fills in the minutes before it starts",
                    if (calendarAllowed) calendar else 0, on,
                ) { m ->
                    if (m > 0 && !calendarAllowed) { pendingLead = m; askCalendar.launch(Manifest.permission.READ_CALENDAR) }
                    else AppGraph.liveProgress.setCalendarLead(m)
                }
                RowDivider()
                Lead("Next alarm", "The ring fills in the minutes before it rings", alarm, on) { AppGraph.liveProgress.setAlarmLead(it) }
            }
        }
        item(key = "options") {
            SectionLabel("Options")
            RowGroup {
                SwitchRow("Minutes in the middle", if (minutes) "Off: the order's stage as a picture" else "The order's stage shows as a picture", minutes, enabled = on) {
                    AppGraph.liveProgress.setShowMinutes(it)
                    LiveProgressRuntime.refresh()
                }
            }
        }
    }
}

/** A "fill the ring before" choice: Off or a lead time in minutes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lead(title: String, subtitle: String, value: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Gap).then(if (enabled) Modifier else Modifier.alpha(0.38f))) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        FlowRow(Modifier.padding(top = Tokens.Tiny), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            LiveProgressSettings.LEADS.forEach { m ->
                Chip(if (m == 0) "Off" else "$m min", value == m) { if (enabled) onPick(m) }
            }
        }
    }
}

/** The example in the empty state: a delivery 12 minutes away, on the way. */
private fun sample(): Animation =
    LiveRing.animation(AppGraph.profile, Stage.ON_THE_WAY.fraction, Centre.Minutes(12), arriving = false)
