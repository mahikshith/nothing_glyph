package com.dotforge.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.filter.FilterActions
import com.dotforge.rules.FilterSettings
import com.dotforge.rules.LightWhen
import com.dotforge.rules.QuietMode
import com.dotforge.rules.QuietText
import com.dotforge.rules.Rule
import com.dotforge.rules.SimpleRules
import com.dotforge.ui.AppEntry
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DotSlider
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.LoadingRows
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.launcherApps
import com.dotforge.ui.rememberAppLabel

private fun filters(f: (FilterSettings) -> FilterSettings) = AppGraph.filters.updateFilters(f)

/**
 * Add an app (row 56): search, "Sent notifications this week" first, a skeleton while the list builds. One tap
 * adds the app and opens its sheet; apps already added say "Added" and open theirs. [mode]: "app" adds an app
 * row; "reminders" picks Reminders & to-dos apps (several); "change:<rule id>" moves a row to another app.
 * [preset] stands in for the installed apps (screenshots).
 */
@Composable
fun AlertAppsPage(mode: String, nav: Nav, preset: List<AppEntry>? = null, loading: Boolean = false) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(preset) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) { if ((apps == null || attempt > 0) && !loading) apps = runCatching { launcherApps(ctx) }.getOrDefault(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    val snacks = rememberSnacks()
    val reminders = rules.firstOrNull { it.id == SimpleRules.REMINDERS }
    val changing = mode.removePrefix("change:").takeIf { mode.startsWith("change:") }?.let { id -> rules.firstOrNull { it.id == id } }
    val added: Set<String> = when {
        mode == "reminders" -> reminders?.let(SimpleRules::reminderApps).orEmpty().toSet()
        changing != null -> changing.packages.toSet()
        else -> rules.filter(SimpleRules::isAppRule).flatMap { it.packages }.toSet()
    }
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    val recent = (history.filter { it.at >= weekAgo && it.packageName.isNotBlank() }.map { it.packageName } + AppGraph.log.value.map { it.info.packageName }).distinct()
    val all = apps
    fun matches(e: AppEntry) = query.isBlank() || e.label.contains(query, ignoreCase = true)
    fun pick(e: AppEntry) {
        when {
            mode == "reminders" && reminders != null -> {
                val now = SimpleRules.reminderApps(reminders)
                AppGraph.rules.upsert(SimpleRules.withReminderApps(reminders, if (e.packageName in now) now - e.packageName else now + e.packageName))
            }
            changing != null -> {
                AppGraph.rules.upsert(changing.copy(packages = listOf(e.packageName), name = e.label))
                nav.pop()
            }
            e.packageName in added -> {
                rules.firstOrNull { SimpleRules.isAppRule(it) && it.packages == listOf(e.packageName) }?.let { nav.alertsRequest = "rule:${it.id}" }
                nav.pop()
            }
            else -> {
                val r = SimpleRules.newApp(e.packageName, e.label)
                AppGraph.rules.upsert(r)
                // straight to its sheet, to pick its glyph
                nav.alertsRequest = "rule:${r.id}"
                nav.pop()
            }
        }
    }
    com.dotforge.ui.design.Page(
        when { mode == "reminders" -> "Reminder apps"; changing != null -> "Change app"; else -> "Add an app" }, onBack = nav::pop,
    ) {
        item(key = "search") {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(bottom = Tokens.Small), singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, placeholder = { Text("Search apps") },
            )
        }
        if (all == null) {
            item(key = "loading") { LoadingRows(6) }
            return@Page
        }
        val byPkg = all.associateBy { it.packageName }
        val sent = recent.mapNotNull { byPkg[it] }.filter(::matches)
        if (sent.isNotEmpty()) {
            item(key = "sent-label") { SectionLabel("Sent notifications this week") }
            item(key = "sent") { AppList(sent, added, mode == "reminders", ::pick) }
        }
        val rest = all.filter(::matches).filterNot { a -> sent.any { it.packageName == a.packageName } }
        item(key = "all-label") { SectionLabel(if (sent.isEmpty()) "All apps" else "Other apps") }
        if (all.isEmpty()) item(key = "none") {
            // an empty list means the apps couldn't be read, not that there are none
            com.dotforge.ui.design.EmptyState("Couldn't list your apps. Android may still be starting up.", "Retry", glyph = "dot") { apps = null; attempt++ }
        } else if (rest.isEmpty()) item(key = "none") { Hint("No app called “$query”.") }
        else item(key = "all") { AppList(rest, added, mode == "reminders", ::pick) }
    }
}

/** Apps to pick: "Added" on apps already there; ticks only where several can be picked ([multi]). */
@Composable
private fun AppList(apps: List<AppEntry>, added: Set<String>, multi: Boolean, pick: (AppEntry) -> Unit) = RowGroup {
    apps.forEachIndexed { i, e ->
        if (i > 0) RowDivider(leading = true)
        val on = e.packageName in added
        ListRow(
            e.label, leading = { AppIcon(e.packageName, e.label) },
            trailing = {
                if (on && multi) Icon(Icons.Filled.Check, contentDescription = "Picked", tint = androidx.compose.ui.graphics.Color.White)
                else if (on) Text("Added", style = MaterialTheme.typography.labelLarge, color = Tokens.Secondary)
            },
        ) { pick(e) }
    }
}

/** Alerts > ⋮ > Alert settings: Quiet, Who wins, when alerts light, what counts as checking. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertSettingsPage(nav: Nav) {
    val f by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = remember(f) { runCatching { FilterActions.conditions(ctx) }.getOrNull() }
    com.dotforge.ui.design.Page("Alert settings", onBack = nav::pop) {
        item(key = "links") {
            RowGroup {
                ListRow("Quiet", subtitle = c?.let { QuietText.status(f, it) } ?: "Off", trailing = { Chevron() }) { nav.push(Page.Quiet) }
                RowDivider()
                ListRow("Who wins", subtitle = "Calls, then people, then apps", trailing = { Chevron() }) { nav.push(Page.WhoWins) }
            }
        }
        item(key = "light-label") { SectionLabel("Light alerts") }
        item(key = "light") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                LightWhen.entries.forEach { w -> Chip(w.label, f.lightWhen == w) { filters { it.copy(lightWhen = w) } } }
            }
            Hint("Nothing hides lights from apps when the phone is face down or in a pocket. DotForge alerts show when the phone is face up.")
        }
        item(key = "check-label") { SectionLabel("Until you check") }
        item(key = "check") {
            RowGroup {
                SwitchRow("Unlocking counts as checking", "Off: alerts stay until you open or dismiss them", f.unlockChecks) { on -> filters { it.copy(unlockChecks = on) } }
            }
            Text("Stop waiting after", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                listOf(1 to "1 h", 4 to "4 h", 0 to "Never").forEach { (h, t) -> Chip(t, f.waitCapHours == h) { filters { it.copy(waitCapHours = h) } } }
            }
        }
    }
}

/** Row 7: Quiet. Schedule, phone DND and Nothing's bedtime, while in use, dim or don't light, who gets through. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuietPage(nav: Nav) {
    val f by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = runCatching { FilterActions.conditions(ctx) }.getOrNull()
    fun hh(m: Int) = "%02d:%02d".format(m / 60 % 24, m % 60)
    com.dotforge.ui.design.Page("Quiet", onBack = nav::pop) {
        item(key = "now") {
            RowGroup {
                val on = c != null && f.quietUntil > c.nowMs
                SwitchRow("Quiet now", c?.let { QuietText.status(f, it) } ?: "Off", on) { now ->
                    filters { it.copy(quietUntil = if (now && c != null) QuietText.untilMs(it, c) else 0) }
                }
            }
        }
        item(key = "schedule-label") { SectionLabel("Schedule") }
        item(key = "schedule") {
            RowGroup {
                SwitchRow("On a schedule", if (f.quiet.enabled) "${hh(f.quiet.start)}–${hh(f.quiet.end)}" else "Off", f.quiet.enabled) { on ->
                    filters { it.copy(quiet = it.quiet.copy(enabled = on)) }
                }
            }
            if (f.quiet.enabled) {
                RowGroup(Modifier.padding(top = Tokens.Small)) {
                    TimeRow("From", f.quiet.start) { m -> filters { it.copy(quiet = it.quiet.copy(start = m)) } }
                    RowDivider()
                    TimeRow("To", f.quiet.end) { m -> filters { it.copy(quiet = it.quiet.copy(end = m)) } }
                }
                DayToggles(f.quiet.days, Modifier.padding(top = Tokens.Small)) { d -> filters { s -> s.copy(quiet = s.quiet.copy(days = d)) } }
            }
        }
        item(key = "also-label") { SectionLabel("Also quiet") }
        item(key = "also") {
            RowGroup {
                SwitchRow("Follow phone's Do Not Disturb", null, f.respectDnd) { on -> filters { it.copy(respectDnd = on) } }
                RowDivider()
                SwitchRow("Follow Nothing's bedtime", null, f.quiet.followBedtime) { on -> filters { it.copy(quiet = it.quiet.copy(followBedtime = on)) } }
                RowDivider()
                SwitchRow("While I'm using the phone", "Alerts made while you use it show briefly once it locks", f.whileUsing) { on -> filters { it.copy(whileUsing = on) } }
            }
        }
        item(key = "during-label") { SectionLabel("During Quiet") }
        item(key = "during") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                QuietMode.entries.forEach { m -> Chip(m.label, f.quietMode == m) { filters { it.copy(quietMode = m) } } }
            }
        }
        item(key = "through-label") { SectionLabel("Let through") }
        item(key = "through") {
            RowGroup {
                SwitchRow("Favourite contacts", "Starred in Contacts", f.letFavourites) { on -> filters { it.copy(letFavourites = on) } }
                RowDivider()
                SwitchRow("Repeat callers", "Same number twice within 3 min", f.letRepeat) { on -> filters { it.copy(letRepeat = on) } }
            }
        }
    }
}

/** Row 49: Who wins. Calls first, then people, then apps, each level in the order set here. */
@Composable
fun WhoWinsPage(nav: Nav) {
    val f by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    val list = SimpleRules.whoWins(rules, f.order)
    fun move(r: Rule, by: Int) = filters { it.copy(order = SimpleRules.move(rules, it.order, r.id, by)) }
    com.dotforge.ui.design.Page("Who wins", onBack = nav::pop) {
        item(key = "hint") { Hint("Calls always come first. Then people, then apps: the higher one shows first when two arrive together. The newest shows first within one, and nothing is cut in its first 2 seconds.") }
        listOf("People" to list.filter(SimpleRules::isPersonRule), "Apps" to list.filterNot(SimpleRules::isPersonRule)).forEach { (title, group) ->
            item(key = "label-$title") { SectionLabel(title) }
            item(key = "group-$title") {
                if (group.isEmpty()) Hint(if (title == "People") "No people yet." else "No apps yet.")
                else RowGroup {
                    group.forEachIndexed { i, r ->
                        if (i > 0) RowDivider(leading = true)
                        val name = ruleTitle(r, label)
                        ListRow(
                            name, leading = { if (r.packages.size == 1 && !SimpleRules.isPersonRule(r)) AppIcon(r.packages[0], name) else com.dotforge.ui.design.Avatar(name) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { move(r, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $name up") }
                                    IconButton(onClick = { move(r, 1) }, enabled = i < group.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $name down") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Alerts > Apps > Phone moments: unlock, shake, camera, alarm or timer and headphones, each its glyph, a
 * line, and a switch; the row opens the one sheet. All off until turned on.
 */
@Composable
fun PhoneMomentsPage(nav: Nav, setup: com.dotforge.ui.Setup, banners: AlertBanners? = null) {
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val needsScreening by com.dotforge.ui.rememberNeedsScreening()
    val b = banners ?: rememberAlertBanners(setup, needsScreening)
    var open by remember { mutableStateOf<String?>(null) }
    val moments = rules.filter { it.moment != null }.sortedBy { it.moment!!.ordinal }
    com.dotforge.ui.design.Page("Phone moments", onBack = nav::pop) {
        item(key = "hint") { Hint("The Glyph shows something when these happen on the phone.") }
        item(key = "moments") {
            RowGroup {
                moments.forEachIndexed { i, r ->
                    if (i > 0) RowDivider(leading = true)
                    ListRow(
                        r.name, subtitle = if (r.enabled) com.dotforge.rules.AlertSentence.summary(r) else r.moment!!.hint, subtitleLines = 3,
                        leading = { RulePoster(r, r.name) },
                        trailing = { RowSwitch(r.enabled, r.name) { AppGraph.rules.upsert(r.copy(enabled = it)) } },
                    ) { open = r.id }
                }
            }
        }
    }
    open?.let { id -> RuleSheet(id, nav, setup, b, onDismiss = { open = null }) }
}
