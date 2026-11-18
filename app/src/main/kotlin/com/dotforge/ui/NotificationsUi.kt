package com.dotforge.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.rules.Action
import com.dotforge.rules.LogEntry
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.QuietHours
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RuleText
import com.dotforge.rules.Source
import com.dotforge.rules.SpamFilter
import com.dotforge.rules.Verdict
import com.dotforge.system.NothingSettings
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private const val REPLAY_ID = "replay"

internal fun hm(minutes: Int) = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)

/** "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri". */
internal fun daysText(days: List<Int>): String {
    val d = days.filter { it in 1..7 }.toSortedSet()
    return when (d) {
        (1..7).toSortedSet() -> "Every day"
        (1..5).toSortedSet() -> "Weekdays"
        sortedSetOf(6, 7) -> "Weekends"
        else -> if (d.isEmpty()) "No days" else d.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
}

/** A name for a rule's design, for the rule sentence. */
fun designName(id: String?): String? = (AppGraph.designs.get(id) ?: AppGraph.designs.designs.value.firstOrNull())?.name

/**
 * The Notifications page: rules, filters, the rule tester and the note about Nothing's own glyphs, on one
 * scrolling page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsPage(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val rulesRepo = AppGraph.rules
    val rules by rulesRepo.rules.collectAsStateWithLifecycle()
    val catches by AppGraph.filters.catches.collectAsStateWithLifecycle()
    val filters by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val log by AppGraph.log.collectAsStateWithLifecycle()
    val frame by AppGraph.arbiter.frame.collectAsStateWithLifecycle()
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    var quietDialog by rememberSaveable { mutableStateOf(false) }
    val bedtimeReadable = remember(setup.resumes) { NothingSettings.bedtimeReadable(ctx.contentResolver) }
    val glyphSettings = remember { NothingSettings.glyphNotificationSettings(ctx.packageManager) }
    val missing = remember(rules) { rulesRepo.missingPresets() }

    PageScaffold("NOTIFICATIONS", onBack = nav::pop, actions = {
        IconButton(onClick = { nav.push(Page.RuleEditor(null)) }) { Icon(Icons.Filled.Add, "New rule") }
    }) {
        AccessBanner(setup, "Notification glyphs need notification access.")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MatrixView(frame?.pixels, AppGraph.profile, Modifier.fillMaxWidth(0.36f))
        }
        Text(
            active.firstOrNull()?.label ?: "Nothing showing",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        GroupTitle("RULES")
        val shown = rules.filter { it.action == Action.SHOW }
        if (shown.isEmpty()) {
            EmptyState("No rules yet", "Start from the presets, or make your own.", "Add presets") {
                rulesRepo.addPresets(missing.filter { it.action == Action.SHOW })
            }
        }
        shown.forEach { r -> RuleCard(r, label, nav, setup) }
        if (shown.size > 1) {
            Text("The most specific rule wins: a person, then app and words, words, an app, anything.", color = Muted, fontSize = 13.sp)
        }
        val missingShow = missing.filter { it.action == Action.SHOW }
        if (missingShow.isNotEmpty() && shown.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("Add", color = Muted)
                missingShow.forEach { p ->
                    FilterChip(selected = false, onClick = { rulesRepo.addPresets(listOf(p)) }, label = { Text(p.name) })
                }
            }
        }
        SecondaryButton("New rule", Modifier.fillMaxWidth().padding(top = 12.dp)) { nav.push(Page.RuleEditor(null)) }

        GroupTitle("FILTERS")
        val spamRules = rules.count { it.enabled && RuleEngine.isSpamRule(it) }
        ValueRow(
            "Spam filter",
            "Blocked today: ${SpamFilter.blockedOn(LocalDate.now(), catches)}",
            subtitle = if (spamRules == 1) "1 rule on, for calls and messages" else "$spamRules rules on, for calls and messages",
        ) { nav.push(Page.Spam) }
        ValueRow("Quiet hours", quietSummary(filters.quiet, bedtimeReadable), subtitle = "No glyphs at night") { quietDialog = true }
        SwitchRow("Respect Do Not Disturb", "No glyphs while Do Not Disturb is on", filters.respectDnd) { on ->
            AppGraph.filters.updateFilters { it.copy(respectDnd = on) }
        }
        SwitchRow("Only when locked", "Skip notifications while you use the phone", filters.onlyLocked) { on ->
            AppGraph.filters.updateFilters { it.copy(onlyLocked = on) }
        }
        SwitchRow("Only when face down", "Light up only while the phone lies on its screen", filters.onlyFaceDown) { on ->
            AppGraph.filters.updateFilters { it.copy(onlyFaceDown = on) }
        }

        GroupTitle("RULE TESTER")
        if (log.isEmpty()) {
            EmptyState(
                "Nothing yet",
                "New notifications show here with the rule they hit.",
                if (setup.notificationAccess) null else "Allow access",
            ) { setup.needNotificationAccess() }
        } else {
            Text("Tap one to replay its glyph.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        log.forEach { e -> LogRow(e, rules, nav) }

        GroupTitle("NOTHING GLYPHS")
        Text("Nothing's own notification glyphs outrank DotForge's.")
        Text(
            "For apps DotForge handles, set their Glyph notification to Sound only so the two don't fight.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
        )
        SecondaryButton(glyphSettings.label, Modifier.padding(top = 12.dp)) {
            runCatching { ctx.startActivity(glyphSettings.intent) }
        }
    }

    if (quietDialog) QuietHoursDialog(filters.quiet, bedtimeReadable, onDismiss = { quietDialog = false }) { q ->
        AppGraph.filters.updateFilters { it.copy(quiet = q) }
        quietDialog = false
    }
}

private fun quietSummary(q: QuietHours, bedtimeReadable: Boolean) = when {
    q.enabled -> "${hm(q.start)}–${hm(q.end)} · ${daysText(q.days)}"
    q.followBedtime && bedtimeReadable -> "Nothing bedtime"
    else -> "Off"
}

/** One rule tester line: the notification, then which rule matched and why (or why it stayed dark). */
@Composable
private fun LogRow(e: LogEntry, rules: List<Rule>, nav: Nav) {
    var menu by remember { mutableStateOf(false) }
    val (text, color) = when (val v = e.verdict) {
        is Verdict.Show -> "${v.match.rule.name} · ${v.match.why}" + (if (e.cooldown) " · cooldown" else "") to Color.White
        is Verdict.Quiet -> "${v.match.rule.name} · ${v.match.why} · held: ${v.reason}" to Muted
        is Verdict.Blocked -> "${v.match.rule.action.done} · ${v.match.rule.name} · ${v.match.why}" to NothingRed
        Verdict.NoRule -> "No rule matched" to Muted
    }
    val matched = when (val v = e.verdict) {
        is Verdict.Show -> v.match.rule
        is Verdict.Quiet -> v.match.rule
        else -> null
    }
    Column(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 8.dp)) {
        Text("${e.info.appLabel} · ${timeFormat.format(Date(e.info.postedAt))}", color = Muted, fontSize = 12.sp)
        Text(e.info.title.ifBlank { e.info.text }.ifBlank { e.info.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text, color = color, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (menu) {
        NotificationActions(
            e.info,
            onReplay = matched?.let { m -> { replay(e, rules.firstOrNull { it.id == m.id } ?: m) } },
            onRule = { nav.push(Page.RuleEditor(null, RuleTemplates.encode(RuleTemplates.from(e.info, spam = false)))) },
            onDismiss = { menu = false },
        )
    }
}

/** What to do with a recent notification: replay, make a rule from it, mark its sender as spam. */
@Composable
private fun NotificationActions(info: NotificationInfo, onReplay: (() -> Unit)?, onRule: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val who = info.number?.takeIf { it.isNotBlank() } ?: info.sender.ifBlank { info.title.takeIf { info.fromPerson }.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(info.sender.ifBlank { info.title }.ifBlank { info.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onReplay?.let { r -> ChoiceCard("Replay its glyph", "On the matrix, a few seconds", false, Modifier.fillMaxWidth()) { r(); onDismiss() } }
                ChoiceCard("Make a rule from this", "Starts with ${info.appLabel} and this sender", false, Modifier.fillMaxWidth()) { onDismiss(); onRule() }
                if (who.isNotBlank()) {
                    ChoiceCard("Mark as spam", "Always block $who", false, Modifier.fillMaxWidth()) {
                        AppGraph.blockSender(who)
                        Toast.makeText(ctx, "$who is on Blocked senders", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
                info.number?.takeIf { it.count(Char::isDigit) > 7 }?.let { n ->
                    val prefix = RuleTemplates.prefixOf(n)
                    ChoiceCard("Block numbers starting with $prefix", "Marks them all as spam", false, Modifier.fillMaxWidth()) {
                        AppGraph.blockPrefix(prefix)
                        Toast.makeText(ctx, "Numbers starting with $prefix are blocked", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

/** A rule as a card: name, the sentence, an on/off switch. */
@Composable
internal fun RuleCard(r: Rule, label: (String) -> String, nav: Nav, setup: Setup) {
    Surface(color = Surface1, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            Modifier.clickable { nav.push(Page.RuleEditor(r.id)) }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(RuleText.sentence(r, label, ::designName), color = Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = r.enabled, colors = nothingSwitchColors(), onCheckedChange = { on ->
                AppGraph.rules.upsert(r.copy(enabled = on))
                if (on) {
                    if (r.source != Source.CALLS) setup.needNotificationAccess()
                    if (r.source != Source.APPS) setup.needCallScreening()
                    if (r.usesContacts) setup.needContacts()
                }
            })
        }
    }
}

private fun replay(e: LogEntry, rule: Rule) {
    val anim = RuleEngine.animation(rule, e.info, AppGraph.profile, AppGraph::designAnimation)
    AppGraph.arbiter.post(
        DisplayRequest(
            REPLAY_ID, Priority.STUDIO_PREVIEW, anim, loop = true,
            timeoutMs = max(4_000L, anim.totalMs.toLong()), label = "Replay: ${rule.name}",
        ),
    )
}

/** Quiet hours: on/off, the time range, days, and following Nothing's bedtime when it is readable. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun QuietHoursDialog(
    initial: QuietHours,
    bedtimeReadable: Boolean,
    onDismiss: () -> Unit,
    title: String = "Quiet hours",
    /** A schedule (a call rule's) is always on: no switch. */
    schedule: Boolean = false,
    onDone: (QuietHours) -> Unit,
) {
    var q by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!schedule) SwitchRow("Quiet hours", "No notification glyphs; call rules can use it too", q.enabled) { q = q.copy(enabled = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("From ${hm(q.start)}", color = Color.White) }
                    OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { Text("To ${hm(q.end)}", color = Color.White) }
                }
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val on = d.value in q.days
                        FilterChip(
                            selected = on,
                            onClick = { q = q.copy(days = if (on) q.days - d.value else (q.days + d.value).sorted()) },
                            label = { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NothingRed, selectedLabelColor = Color.White),
                        )
                    }
                }
                if (bedtimeReadable && !schedule) {
                    SwitchRow("Follow Nothing bedtime", "Also pause during the Glyph bedtime", q.followBedtime) { q = q.copy(followBedtime = it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(q) }) { Text("Done", color = NothingRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
    editing?.let { start ->
        val minutes = if (start) q.start else q.end
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Surface2,
            text = {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        selectorColor = NothingRed,
                        clockDialColor = Surface1,
                        timeSelectorSelectedContainerColor = NothingRed,
                        timeSelectorSelectedContentColor = Color.White,
                        timeSelectorUnselectedContainerColor = Surface1,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    q = if (start) q.copy(start = m) else q.copy(end = m)
                    editing = null
                }) { Text("OK", color = NothingRed) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Color.White) } },
        )
    }
}
