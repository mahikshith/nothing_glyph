package com.dotforge.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import com.dotforge.rules.LogEntry
import com.dotforge.rules.QuietHours
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RuleText
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

private fun hm(minutes: Int) = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)

/** "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri". */
private fun daysText(days: List<Int>): String {
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
    val spam by AppGraph.filters.spam.collectAsStateWithLifecycle()
    val catches by AppGraph.filters.catches.collectAsStateWithLifecycle()
    val filters by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val log by AppGraph.log.collectAsStateWithLifecycle()
    val frame by AppGraph.arbiter.frame.collectAsStateWithLifecycle()
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    var spamDialog by rememberSaveable { mutableStateOf(false) }
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
        if (rules.isEmpty()) {
            EmptyState("No rules yet", "Start from the presets, or make your own.", "Add presets") { rulesRepo.addPresets() }
        }
        rules.forEach { r ->
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
                        rulesRepo.upsert(r.copy(enabled = on))
                        if (on) setup.needNotificationAccess()
                    })
                }
            }
        }
        if (rules.size > 1) {
            Text("The most specific rule wins: a person, then app and words, words, an app, anything.", color = Muted, fontSize = 13.sp)
        }
        if (missing.isNotEmpty() && rules.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("Add", color = Muted)
                missing.forEach { p ->
                    FilterChip(selected = false, onClick = { rulesRepo.addPresets(listOf(p)) }, label = { Text(p.name) })
                }
            }
        }
        SecondaryButton("New rule", Modifier.fillMaxWidth().padding(top = 12.dp)) { nav.push(Page.RuleEditor(null)) }

        GroupTitle("FILTERS")
        SwitchRow("Spam filter", "Blocked today: ${SpamFilter.blockedOn(LocalDate.now(), catches)}", spam.enabled) { on ->
            AppGraph.filters.updateSpam { it.copy(enabled = on) }
            if (on) setup.needNotificationAccess()
        }
        if (spam.enabled) {
            ValueRow("Spam settings", spamSummary(spam.keywords.size + spam.patterns.size, spam.blockUnknown), subtitle = "Words, unknown senders, always allow") {
                spamDialog = true
            }
            catches.take(5).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${c.appLabel} · ${timeFormat.format(Date(c.at))}", color = Muted, fontSize = 12.sp)
                        Text(c.title.ifBlank { c.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SecondaryButton("Allow", Modifier.padding(start = 12.dp)) { AppGraph.filters.allow(c) }
                }
            }
        }
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
        log.forEach { e -> LogRow(e, rules) }

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

    if (spamDialog) SpamDialog(setup) { spamDialog = false }
    if (quietDialog) QuietHoursDialog(filters.quiet, bedtimeReadable, onDismiss = { quietDialog = false }) { q ->
        AppGraph.filters.updateFilters { it.copy(quiet = q) }
        quietDialog = false
    }
}

private fun spamSummary(words: Int, unknown: Boolean) =
    (if (words == 1) "1 word" else "$words words") + if (unknown) " · unknown" else ""

private fun quietSummary(q: QuietHours, bedtimeReadable: Boolean) = when {
    q.enabled -> "${hm(q.start)}–${hm(q.end)} · ${daysText(q.days)}"
    q.followBedtime && bedtimeReadable -> "Nothing bedtime"
    else -> "Off"
}

/** One rule tester line: the notification, then which rule matched and why (or why it stayed dark). */
@Composable
private fun LogRow(e: LogEntry, rules: List<Rule>) {
    val (text, color) = when (val v = e.verdict) {
        is Verdict.Show -> "${v.match.rule.name} · ${v.match.why}" + (if (e.cooldown) " · cooldown" else "") to Color.White
        is Verdict.Quiet -> "${v.match.rule.name} · ${v.match.why} · held: ${v.reason}" to Muted
        is Verdict.Blocked -> "Spam · ${v.reason}" to NothingRed
        Verdict.NoRule -> "No rule matched" to Muted
    }
    val matched = when (val v = e.verdict) {
        is Verdict.Show -> v.match.rule
        is Verdict.Quiet -> v.match.rule
        else -> null
    }
    Column(
        Modifier.fillMaxWidth()
            .then(if (matched != null) Modifier.clickable { replay(e, rules.firstOrNull { it.id == matched.id } ?: matched) } else Modifier)
            .padding(vertical = 8.dp),
    ) {
        Text("${e.info.appLabel} · ${timeFormat.format(Date(e.info.postedAt))}", color = Muted, fontSize = 12.sp)
        Text(e.info.title.ifBlank { e.info.text }.ifBlank { e.info.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text, color = color, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** Spam words and patterns, unknown senders and the allow list, edited in place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpamDialog(setup: Setup, onDismiss: () -> Unit) {
    val repo = AppGraph.filters
    val s by repo.spam.collectAsStateWithLifecycle()
    var advanced by rememberToggle(s.patterns.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Spam settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Any one of these words blocks a notification.", color = Muted, fontSize = 13.sp)
                ChipsEditor(s.keywords, { list -> repo.updateSpam { it.copy(keywords = list) } }, "Add a word")
                SwitchRow("Block unknown senders", "Messages and calls from people not in Contacts", s.blockUnknown) { on ->
                    repo.updateSpam { it.copy(blockUnknown = on) }
                }
                Text("Always allow", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface1, selectedLabelColor = NothingRed)
                    FilterChip(selected = s.allowContacts, colors = chipColors, label = { Text("Contacts") }, onClick = {
                        repo.updateSpam { it.copy(allowContacts = !it.allowContacts) }
                    })
                    FilterChip(selected = s.allowStarred, colors = chipColors, label = { Text("Starred") }, onClick = {
                        repo.updateSpam { it.copy(allowStarred = !it.allowStarred) }
                    })
                    s.allowedSenders.forEach { who ->
                        InputChip(
                            selected = false,
                            onClick = { repo.updateSpam { it.copy(allowedSenders = it.allowedSenders - who) } },
                            label = { Text(who, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                        )
                    }
                }
                if (s.needsContacts) ContactsAsk(setup, "Allow Contacts", "Needed to tell people you know from strangers.", Modifier.padding(top = 12.dp))
                Collapsible("Advanced", advanced, { advanced = !advanced }) {
                    Text("Patterns (regex)", modifier = Modifier.padding(bottom = 4.dp))
                    ChipsEditor(
                        s.patterns, { list -> repo.updateSpam { it.copy(patterns = list) } }, "Add a pattern",
                        validate = { if (RuleEngine.isValidPattern(it)) null else "Not a valid pattern" },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = NothingRed) } },
    )
}

/** Quiet hours: on/off, the time range, days, and following Nothing's bedtime when it is readable. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursDialog(initial: QuietHours, bedtimeReadable: Boolean, onDismiss: () -> Unit, onDone: (QuietHours) -> Unit) {
    var q by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Quiet hours") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow("Quiet hours", "No notification glyphs in this time", q.enabled) { q = q.copy(enabled = it) }
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
                if (bedtimeReadable) {
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
