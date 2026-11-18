package com.dotforge.ui.alerts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.calls.CallerLookup
import com.dotforge.calls.PhoneNumbers
import com.dotforge.calls.SpamListWorker
import com.dotforge.device.Effects
import com.dotforge.filter.NamedList
import com.dotforge.filter.Subject
import com.dotforge.rules.BlockPattern
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.HistoryEntry
import com.dotforge.rules.HistoryFilter
import com.dotforge.rules.HistoryRepository
import com.dotforge.rules.HistoryText
import com.dotforge.rules.Outcome
import com.dotforge.rules.PatternType
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.rules.Where
import com.dotforge.ui.design.AddRow
import com.dotforge.ui.design.Avatar
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.EmptyState
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import java.time.LocalDateTime

// ---------------------------------------------------------------- Spam

/** The spam alert's line: "A short Blocked glyph, 2 s, once · No sound" (row 66). */
private fun spamAlertText(): String {
    val s = AppGraph.filters.spam.value
    val glyph = if (s.glyphOn && s.glyph != GlyphStyle.NONE) "A short Blocked glyph, 2 s, once" else "No glyph"
    return "$glyph · " + (s.soundName?.let { "Plays $it" } ?: "No sound")
}

/** Block rules made with Add a rule (and any simple pattern rule), optionally only those for [pkg]. */
fun blockRules(rules: List<Rule>, pkg: String?): List<Rule> = rules.filter { r ->
    SimpleRules.pattern(r) != null && (pkg == null || r.packages.contains(pkg) || (r.packages.isEmpty() && r.source != com.dotforge.rules.Source.CALLS))
}

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.spamItems(
    rules: List<Rule>, history: List<HistoryEntry>, setup: com.dotforge.ui.Setup, app: String?, label: (String) -> String,
    clearApp: () -> Unit, open: (AlertSheet) -> Unit,
) {
    item(key = "spam-alert") {
        RowGroup {
            ListRow(
                "When something is spam", subtitle = spamAlertText(),
                leading = { GlyphPreview(stillOf(Effects.spam(AppGraph.profile)), "Spam glyph") }, trailing = { Chevron() },
            ) { open(AlertSheet.SpamAlert) }
        }
    }
    item(key = "spam-rules-label") { SectionLabel(if (app == null) "Block rules" else "Block rules for ${label(app)}") }
    if (app != null) item(key = "spam-filter") {
        FlowRow { Chip("${label(app)}  ×", true, onClick = clearApp) }
    }
    val list = blockRules(rules, app)
    item(key = "spam-rules") {
        RowGroup {
            list.forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                BlockRuleRow(r, history, label, open)
            }
            if (list.isNotEmpty()) RowDivider(leading = true)
            AddRow("Add a rule") {
                open(
                    when {
                        app == null -> AlertSheet.AddBlock()
                        app in SimpleRules.WHATSAPP_APPS -> AlertSheet.AddBlock(where = setOf(Where.WHATSAPP))
                        app in SimpleRules.SMS_APPS -> AlertSheet.AddBlock(where = setOf(Where.SMS))
                        else -> AlertSheet.AddBlock(where = emptySet(), pkg = app)
                    },
                )
            }
        }
    }
    if (list.isEmpty()) item(key = "spam-rules-empty") { Hint("Block numbers that start with a code, or messages with certain words.") }
    item(key = "let-label") { SectionLabel("Always let through") }
    item(key = "let") {
        RowGroup {
            val contacts = rules.firstOrNull { it.id == "spam-contacts" }
            val repeat = rules.firstOrNull { it.id == "spam-repeated" }
            SwitchRow("Contacts", "Never blocked", contacts?.enabled == true) { on ->
                if (contacts == null) AppGraph.rules.ensurePreset("spam-contacts") else AppGraph.rules.upsert(contacts.copy(enabled = on))
            }
            RowDivider()
            SwitchRow("Repeat callers", "Same number twice within 3 min", repeat?.enabled == true) { on ->
                if (repeat == null) AppGraph.rules.ensurePreset("spam-repeated") else AppGraph.rules.upsert(repeat.copy(enabled = on))
            }
        }
    }
    item(key = "community") { CommunityList(rules) }
    item(key = "how-blocking") {
        ListRow("What blocking does", subtitle = "Calls are declined; messages stay in their app", inset = 0.dp, trailing = { Chevron() }) { open(AlertSheet.HowBlocking) }
    }
}

@Composable
private fun BlockRuleRow(r: Rule, history: List<HistoryEntry>, label: (String) -> String, open: (AlertSheet) -> Unit) {
    val p = SimpleRules.pattern(r) ?: return
    val count = HistoryRepository.counts(history, r)
    val snacks = rememberSnacks()
    ListRow(
        r.name, subtitle = SimpleRules.whereText(p, label) + " · " + HistoryText.count(count),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowSwitch(r.enabled, r.name) { AppGraph.rules.upsert(r.copy(enabled = it)) }
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${r.name}") }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Tokens.Raised) {
                    DropdownMenuItem({ Text("Edit") }, onClick = { menu = false; open(AlertSheet.AddBlock(editId = r.id)) })
                    DropdownMenuItem({ Text("Delete") }, onClick = {
                        menu = false
                        AppGraph.rules.delete(r.id)
                        snacks?.show("${r.name} deleted", undo = { AppGraph.rules.upsert(r) })
                    })
                }
            }
        },
    ) { open(AlertSheet.AddBlock(editId = r.id)) }
}

/** Row 64: the community list, updated weekly, checked on the phone. */
@Composable
private fun CommunityList(rules: List<Rule>) {
    val ctx = LocalContext.current
    val status by AppGraph.spamList.status.collectAsStateWithLifecycle()
    val r = rules.firstOrNull { it.id == "spam-list" }
    val line = when {
        status.error != null && status.fetchedAt > 0 -> "Couldn't update. Using the list from ${HistoryText.dayTitle(status.fetchedAt)}."
        status.error != null -> "Couldn't update. Check your connection."
        status.fetchedAt > 0 -> "Updated weekly · checks stay on your phone · Last updated ${agoText(status.fetchedAt)}"
        else -> "Updated weekly · checks stay on your phone"
    }
    RowGroup(Modifier.padding(top = Tokens.Gap)) {
        SwitchRow("Community spam list", line, r?.enabled == true) { on ->
            if (r == null) AppGraph.rules.ensurePreset("spam-list") else AppGraph.rules.upsert(r.copy(enabled = on))
        }
        TextButton(onClick = { SpamListWorker.now(ctx) }, modifier = Modifier.padding(start = Tokens.Tiny, bottom = Tokens.Tiny).heightIn(min = Tokens.Target)) {
            Text("Update now", color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

private fun agoText(at: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((now - at) / 86_400_000L).toInt()
    return when (days) {
        0 -> "today"
        1 -> "yesterday"
        else -> "$days days ago"
    }
}

/**
 * Add a rule (row 63): type chips, a number keypad for number types, the normalisation line, "Would have
 * blocked", where-chips each saying what blocking means there (row 61), and Add disabled with its reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddRuleSheet(s: AlertSheet.AddBlock, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val editing = s.editId?.let { id -> AppGraph.rules.rules.value.firstOrNull { it.id == id } }
    val start = editing?.let(SimpleRules::pattern)
    var type by remember { mutableStateOf(start?.type ?: PatternType.STARTS) }
    var text by remember { mutableStateOf(start?.text.orEmpty()) }
    var where by remember { mutableStateOf(start?.where ?: s.where) }
    val apps = start?.apps ?: listOfNotNull(s.pkg)
    val p = BlockPattern(type, text, where, apps)
    val problem = SimpleRules.problem(p.copy(where = if (apps.isNotEmpty()) where + Where.ANY_APP else where))
    val region = remember { runCatching { CallerLookup(ctx).region() }.getOrNull() }
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val would = remember(p, history) { if (problem != null) 0 else wouldHaveBlocked(p, history, region) }
    val label = com.dotforge.ui.rememberAppLabel()
    FullSheet(
        onDismiss, if (editing != null) "Edit rule" else "Add a rule", confirm = if (editing != null) "Save" else "Add", confirmEnabled = problem == null,
        onConfirm = {
            AppGraph.rules.upsert(SimpleRules.blockRule(p, id = editing?.id ?: (SimpleRules.BLOCK_PREFIX + java.util.UUID.randomUUID())))
            onDismiss()
        },
    ) {
        Text("Block senders whose number or name", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) { PatternType.entries.forEach { t -> Chip(t.chip, type == t) { type = t } } }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().padding(top = Tokens.Small),
            label = { Text(if (type.numbers) "Number, like 140" else "Words, separated by commas") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (type.numbers) KeyboardType.Phone else KeyboardType.Text),
        )
        SimpleRules.normalisation(p, PhoneNumbers.callingCode(region))?.let { Hint(it) }
        if (problem == null) Hint(if (would == 0) "Would have blocked nothing in the last 30 days" else "Would have blocked $would in the last 30 days")
        Text("Where", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Where.entries.forEach { w -> Chip(w.label, w in where) { where = if (w in where) where - w else where + w } }
            apps.forEach { a -> Chip(label(a), true) {} }
        }
        Column(Modifier.padding(top = Tokens.Tiny)) {
            Where.entries.filter { it in where }.forEach { w -> Text("${w.label}: ${w.means}", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        if (problem != null) Text(problem, style = MaterialTheme.typography.bodyMedium, color = Tokens.AccentText, modifier = Modifier.padding(top = Tokens.Small))
    }
}

/** How many History entries of the last 30 days [p] would have blocked. */
fun wouldHaveBlocked(p: BlockPattern, history: List<HistoryEntry>, region: String?): Int {
    val rule = SimpleRules.blockRule(p)
    return history.count { e ->
        val s = Subject(
            isCall = e.call, app = e.packageName, appLabel = e.appLabel, sender = e.sender, number = e.number.ifBlank { null },
            title = e.title, text = e.text, fromPerson = true, at = LocalDateTime.now(), region = region,
        )
        RuleEngine.explain(rule, s) != null
    }
}

/** Row 66: what plays for spam, "a short Blocked glyph, 2 s, once" or nothing; and its sound. */
@Composable
fun SpamAlertSheet(onDismiss: () -> Unit) {
    val s by AppGraph.filters.spam.collectAsStateWithLifecycle()
    FullSheet(onDismiss, "When something is spam", half = true) {
        OptionRow("A short Blocked glyph", "2 s, once", preview = { GlyphPreview(stillOf(Effects.spam(AppGraph.profile)), "Blocked glyph") }, selected = s.glyphOn) {
            AppGraph.filters.updateSpam { it.copy(glyphOn = true, glyph = GlyphStyle.SPAM) }
        }
        OptionRow("Nothing", "Blocked quietly; it shows in History", selected = !s.glyphOn) { AppGraph.filters.updateSpam { it.copy(glyphOn = false) } }
        SectionLabel("Sound")
        OptionRow("No sound", "Spam is silenced", selected = s.soundUri == null) { AppGraph.filters.updateSpam { it.copy(soundUri = null, soundName = null) } }
        if (s.soundUri != null) OptionRow("Custom tune: ${s.soundName ?: "chosen"}", "Plays instead of the app's sound", selected = true) {}
    }
}

// ---------------------------------------------------------------- History

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.historyItems(
    history: List<HistoryEntry>, filter: HistoryFilter, label: (String) -> String,
    onFilter: (HistoryFilter) -> Unit, open: (AlertSheet) -> Unit,
) {
    item(key = "history-chips") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) { HistoryFilter.entries.forEach { f -> Chip(f.label, f == filter) { onFilter(f) } } }
    }
    val shown = history.filter { filter.outcome == null || it.outcome == filter.outcome }
    if (shown.isEmpty()) {
        item(key = "history-empty") {
            EmptyState(
                when (filter) {
                    HistoryFilter.ALL -> "Nothing yet. Every alert that lights, is blocked or is skipped shows here for 30 days."
                    HistoryFilter.LIT -> "Nothing has lit in the last 30 days."
                    HistoryFilter.BLOCKED -> "Nothing blocked in the last 30 days."
                    HistoryFilter.SKIPPED -> "Nothing skipped. Alerts skipped by Quiet or face down show here."
                },
                glyph = "clock",
            )
        }
        return
    }
    shown.groupBy { HistoryText.dayTitle(it.at) }.forEach { (day, entries) ->
        item(key = "h-$day") { SectionLabel(day) }
        item(key = "hg-$day") {
            RowGroup {
                entries.forEachIndexed { i, e ->
                    if (i > 0) RowDivider(leading = true)
                    val app = if (e.call) "Phone" else e.appLabel.ifBlank { label(e.packageName) }
                    ListRow(
                        e.who + if (app.isNotBlank() && app != e.who) " · $app" else "",
                        subtitle = HistoryText.whenText(e.at) + " · " + e.line,
                        leading = {
                            when {
                                e.call -> com.dotforge.ui.design.LibraryGlyph("phone")
                                e.packageName.isNotBlank() -> AppIcon(e.packageName, app)
                                else -> Avatar(e.who)
                            }
                        },
                    ) { open(AlertSheet.Entry(e.key)) }
                }
            }
        }
    }
    item(key = "history-kept") { Hint("Kept for 30 days.") }
}

/** Row 52, row 62: an entry's actions. */
@Composable
fun HistorySheet(key: String, label: (String) -> String, onDismiss: () -> Unit, open: (AlertSheet) -> Unit) {
    val entries by AppGraph.history.entries.collectAsStateWithLifecycle()
    val e = entries.firstOrNull { it.key == key } ?: return
    val ctx = LocalContext.current
    val snacks = rememberSnacks()
    val rule = e.ruleId?.let { id -> AppGraph.rules.rules.value.firstOrNull { it.id == id } }
    var tryIt by remember { mutableStateOf(false) }
    FullSheet(onDismiss, e.who, half = true) {
        if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
        Hint(HistoryText.whenText(e.at) + " · " + e.line)
        if (e.outcome == Outcome.BLOCKED) {
            OptionRow("Not spam", "Always let ${e.senderKey.ifBlank { "them" }} through") {
                val k = e.senderKey
                AppGraph.rules.addToList(RuleEngine.ALLOWED, k, removeFrom = RuleEngine.BLOCKED)
                AppGraph.rules.ensurePreset("spam-allowed")
                onDismiss()
                snacks?.show("$k will always get through", undo = {
                    val l = AppGraph.rules.list(RuleEngine.ALLOWED) ?: return@show
                    AppGraph.rules.putList(NamedList(l.name, l.entries.filterNot { it.equals(k, ignoreCase = true) }))
                })
            }
        }
        OptionRow("Make a rule", if (e.outcome == Outcome.BLOCKED) "Change what gets blocked" else "Choose how ${if (e.call) "calls" else e.appLabel.ifBlank { "this app" }} light up") {
            when {
                e.outcome == Outcome.BLOCKED && rule != null && SimpleRules.pattern(rule) != null -> open(AlertSheet.AddBlock(editId = rule.id))
                e.outcome == Outcome.BLOCKED -> open(AlertSheet.AddBlock(where = if (e.call) setOf(Where.CALLS) else setOf(Where.ANY_APP)))
                rule != null -> open(AlertSheet.RuleOf(rule.id))
                e.packageName.isNotBlank() -> {
                    val r = SimpleRules.newApp(e.packageName, label(e.packageName))
                    AppGraph.rules.upsert(r)
                    open(AlertSheet.RuleOf(r.id))
                }
                else -> open(AlertSheet.RuleOf(SimpleRules.PHONE_CALLS))
            }
        }
        if (rule != null && e.outcome != Outcome.BLOCKED) OptionRow("Try it", "Plays this alert on the Glyph") { tryIt = true }
        if (e.call && e.number.isNotBlank()) OptionRow("Call back", e.number) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (!e.call && e.packageName.isNotBlank()) OptionRow("Open message", "Opens ${e.appLabel.ifBlank { "the app" }}") {
            runCatching { ctx.packageManager.getLaunchIntentForPackage(e.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
    if (tryIt && rule != null) TryItSheet(rule, e.appLabel.ifBlank { rule.name }, deviceState(ctx), onDismiss = { tryIt = false })
}
