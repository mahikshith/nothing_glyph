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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

/** The spam alert's line: "Blocked glyph, 2 s · No sound" (row 66). */
private fun spamAlertText(): String {
    val s = AppGraph.filters.spam.value
    val glyph = if (s.glyphOn && s.glyph != GlyphStyle.NONE) "Blocked glyph, 2 s" else "No glyph"
    return "$glyph · " + (s.soundName?.let { "Plays $it" } ?: "No sound")
}

/** Block rules made with Add a rule (and any simple pattern rule), optionally only those for [pkg]. */
fun blockRules(rules: List<Rule>, pkg: String?): List<Rule> = rules.filter { r ->
    SimpleRules.pattern(r) != null && (pkg == null || r.packages.contains(pkg) || (r.packages.isEmpty() && r.source != com.dotforge.rules.Source.CALLS))
}

/**
 * The Spam tab, in three parts: what happens to spam (the alert, the community list, who is always let
 * through); Block rules (the starter spam words among them, edited like any other); and Strangers and hidden
 * callers. History is its own tab.
 */
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
            RowDivider(leading = true)
            CommunityList(rules)
            RowDivider(leading = true)
            LetThroughRow(rules, open)
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
    item(key = "strangers-label") { SectionLabel("Strangers and hidden callers") }
    item(key = "strangers") { Strangers(rules, setup) }
    item(key = "how-blocking") {
        ListRow("What blocking does", subtitle = "Calls are silenced or declined; messages stay in their app", inset = 0.dp, trailing = { Chevron() }) { open(AlertSheet.HowBlocking) }
    }
}

/** "Messages that mention: lottery, you have won…", the starter spam words' row title. */
fun spamWordsTitle(p: BlockPattern): String {
    val words = com.dotforge.rules.MoreConditions.split(p.text)
    return "Messages that mention: " + words.take(2).joinToString(", ") + if (words.size > 2) "…" else ""
}

/** A block rule's title: the starter spam words read as what they do, any other rule by its name. */
fun blockRuleTitle(r: Rule): String = SimpleRules.pattern(r)?.takeIf { r.id == RuleEngine.SPAM_WORDS_RULE }?.let(::spamWordsTitle) ?: r.name

@Composable
private fun BlockRuleRow(r: Rule, history: List<HistoryEntry>, label: (String) -> String, open: (AlertSheet) -> Unit) {
    val p = SimpleRules.pattern(r) ?: return
    val count = HistoryRepository.counts(history, r)
    val snacks = rememberSnacks()
    val lists by AppGraph.rules.lists.collectAsStateWithLifecycle()
    // a list its More conditions use was deleted: that part matches nothing until it is fixed
    val broken = com.dotforge.rules.MoreConditions.missingLists(com.dotforge.rules.MoreConditions.of(r), lists).isNotEmpty()
    val title = blockRuleTitle(r)
    ListRow(
        // a block rule is the user's own words: it wraps rather than being cut, even at large text
        title, subtitle = if (broken) LIST_REMOVED else SimpleRules.whereText(p, label) + " · " + HistoryText.count(count),
        subtitleLines = Int.MAX_VALUE, titleLines = Int.MAX_VALUE, subtitleColor = if (broken) Tokens.AccentText else Tokens.Secondary,
        leading = { com.dotforge.ui.design.IconCircle(Icons.Filled.Close) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowSwitch(r.enabled, title) { AppGraph.rules.upsert(r.copy(enabled = it)) }
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for $title") }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Tokens.Raised) {
                    DropdownMenuItem({ Text("Edit") }, onClick = { menu = false; open(AlertSheet.AddBlock(editId = r.id)) })
                    DropdownMenuItem({ Text("Delete") }, onClick = {
                        menu = false
                        AppGraph.rules.delete(r.id)
                        snacks?.show("$title deleted", undo = { AppGraph.rules.upsert(r) })
                    })
                }
            }
        },
    ) { open(AlertSheet.AddBlock(editId = r.id, openMore = broken)) }
}

/**
 * Strangers and hidden callers: one row for people not in your contacts, with Calls and Messages choices (their
 * calls don't ring; their messages are hidden), then hidden and faked numbers. Each is a normal spam rule
 * ([RuleEngine.UNKNOWN_CALLS], [RuleEngine.UNKNOWN_SENDERS], [RuleEngine.HIDDEN_CALLS], [RuleEngine.FAKED_CALLS]).
 * What one needs and doesn't have shows in red under it, with the button that fixes it: calls need Call
 * screening, and knowing who is a stranger needs Contacts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Strangers(rules: List<Rule>, setup: com.dotforge.ui.Setup) {
    val calls = rules.firstOrNull { it.id == RuleEngine.UNKNOWN_CALLS }
    val messages = rules.firstOrNull { it.id == RuleEngine.UNKNOWN_SENDERS }
    val hidden = rules.firstOrNull { it.id == RuleEngine.HIDDEN_CALLS }
    val faked = rules.firstOrNull { it.id == RuleEngine.FAKED_CALLS }
    fun set(id: String, r: Rule?, on: Boolean, call: Boolean) {
        if (r == null) { if (on) AppGraph.rules.ensurePreset(id) } else AppGraph.rules.upsert(r.copy(enabled = on))
        if (on && call && !setup.screening) setup.needCallScreening()
    }
    val callsOn = calls?.enabled == true
    val messagesOn = messages?.enabled == true
    RowGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
            Text("People not in your contacts", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    callsOn && messagesOn -> "Their calls don't ring and stay in your call log. Their messages are hidden."
                    callsOn -> "Their calls don't ring. They stay in your call log."
                    messagesOn -> "Their messages are hidden. They stay in their app."
                    else -> "Off. Pick calls, messages or both."
                },
                style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small), modifier = Modifier.padding(top = Tokens.Tiny)) {
                Chip("Calls", callsOn) {
                    set(RuleEngine.UNKNOWN_CALLS, calls, !callsOn, call = true)
                    if (!callsOn && !setup.contacts) setup.needContacts()
                }
                Chip("Messages", messagesOn) {
                    set(RuleEngine.UNKNOWN_SENDERS, messages, !messagesOn, call = false)
                    if (!messagesOn && !setup.contacts) setup.needContacts()
                }
            }
            if ((callsOn || messagesOn) && !setup.contacts) NeedsLine("Needs Contacts to know who is a stranger", "Allow") { setup.needContacts() }
            if (callsOn && !setup.screening) NeedsLine("Needs call screening", "Allow") { setup.needCallScreening() }
        }
        RowDivider()
        SwitchRow("Hidden numbers", "Calls with no caller ID don't ring. They stay in your call log.", hidden?.enabled == true) { on -> set(RuleEngine.HIDDEN_CALLS, hidden, on, call = true) }
        if (hidden?.enabled == true && !setup.screening) NeedsLine("Needs call screening", "Allow", Modifier.padding(start = Tokens.Gutter)) { setup.needCallScreening() }
        RowDivider()
        SwitchRow("Faked numbers", "The network says the caller ID is faked. Declined, and kept in your call log.", faked?.enabled == true) { on -> set(RuleEngine.FAKED_CALLS, faked, on, call = true) }
        if (faked?.enabled == true && !setup.screening) NeedsLine("Needs call screening", "Allow", Modifier.padding(start = Tokens.Gutter)) { setup.needCallScreening() }
    }
}

/** "Always let through": Contacts, repeat callers and the numbers let through, in one row that opens its sheet. */
@Composable
private fun LetThroughRow(rules: List<Rule>, open: (AlertSheet) -> Unit) {
    val allowed = AppGraph.rules.lists.collectAsStateWithLifecycle().value.firstOrNull { it.name == RuleEngine.ALLOWED }?.entries.orEmpty()
    val parts = listOfNotNull(
        "Contacts".takeIf { rules.any { it.id == "spam-contacts" && it.enabled } },
        "repeat callers".takeIf { rules.any { it.id == "spam-repeated" && it.enabled } },
        allowed.size.takeIf { it > 0 }?.let { if (it == 1) "1 number or sender" else "$it numbers and senders" },
    )
    ListRow(
        "Always let through", subtitle = if (parts.isEmpty()) "Nobody" else parts.joinToString(", ").replaceFirstChar { it.uppercase() },
        leading = { com.dotforge.ui.design.IconCircle(Icons.Filled.Check) }, trailing = { Chevron() },
    ) { open(AlertSheet.AllowList) }
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
        else -> "Updated weekly · checks stay on your phone · Not updated yet"
    }
    SwitchRow("Community spam list", line, r?.enabled == true, leading = { com.dotforge.ui.design.IconCircle(Icons.Filled.Info) }) { on ->
        if (r == null) AppGraph.rules.ensurePreset("spam-list") else AppGraph.rules.upsert(r.copy(enabled = on))
    }
    TextButton(onClick = { SpamListWorker.now(ctx) }, modifier = Modifier.padding(start = com.dotforge.ui.design.TEXT_AFTER_ICON - 12.dp, bottom = Tokens.Tiny).heightIn(min = Tokens.Target)) {
        Text("Update now", color = androidx.compose.ui.graphics.Color.White)
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
 * blocked", where-chips each saying what blocking means there (row 61), What to do, and Add disabled with its
 * reason. Turning it to calls only takes out the conditions a call can't have, with a note and Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddRuleSheet(s: AlertSheet.AddBlock, setup: com.dotforge.ui.Setup, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val editing = s.editId?.let { id -> AppGraph.rules.rules.value.firstOrNull { it.id == id } }
    val start = editing?.let(SimpleRules::pattern)
    var type by remember { mutableStateOf(start?.type ?: PatternType.STARTS) }
    var text by remember { mutableStateOf(start?.text.orEmpty()) }
    var where by remember { mutableStateOf(start?.where ?: s.where) }
    var typed by remember { mutableStateOf(editing != null) }
    val apps = start?.apps ?: listOfNotNull(s.pkg)
    var more by remember { mutableStateOf(editing?.let(com.dotforge.rules.MoreConditions::of) ?: s.more) }
    var builder by remember { mutableStateOf(s.openMore) }
    var callAction by remember { mutableStateOf(editing?.callAction ?: s.callAction) }
    // conditions taken out when the rule turned to calls only, and where it was, for Undo
    var takenOut by remember { mutableStateOf<Pair<List<com.dotforge.filter.Condition>, Set<Where>>?>(null) }
    val p = BlockPattern(type, text, where, apps)
    val problem = SimpleRules.problem(p.copy(where = if (apps.isNotEmpty()) where + Where.ANY_APP else where))
    val region = remember { runCatching { CallerLookup(ctx).region() }.getOrNull() }
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val lists by AppGraph.rules.lists.collectAsStateWithLifecycle()
    val rule = SimpleRules.blockRule(p, id = editing?.id ?: (SimpleRules.BLOCK_PREFIX + java.util.UUID.randomUUID()), more = more)
        .copy(callAction = callAction.takeIf { Where.CALLS in where }, enabled = editing?.enabled ?: true)
    // the whole rule, More conditions included, against the last 30 days
    val count = rememberCount(rule.takeIf { problem == null }, history, lists)
    val label = com.dotforge.ui.rememberAppLabel()
    fun setWhere(next: Set<Where>) {
        val callsOnly = next == setOf(Where.CALLS) && apps.isEmpty()
        val (kept, gone) = com.dotforge.rules.MoreConditions.forCalls(more)
        if (callsOnly && gone.isNotEmpty()) {
            takenOut = gone to where
            more = kept
        } else if (!callsOnly) takenOut = null
        where = next
    }
    FullSheet(
        onDismiss, if (editing != null) "Edit rule" else "Add a rule", confirm = if (editing != null) "Save" else "Add", confirmEnabled = problem == null,
        onConfirm = {
            AppGraph.rules.upsert(rule)
            onDismiss()
        },
    ) {
        Text("Block senders whose number or name", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) { PatternType.entries.forEach { t -> Chip(t.chip, type == t) { type = t } } }
        val textProblem = problem?.takeIf { it != "Pick where it applies" }
        LabeledField(
            when (type) { PatternType.WORDS -> "Words"; PatternType.IS -> "Number or name"; else -> "Number" },
            text, { text = it; typed = true },
            placeholder = when (type) { PatternType.WORDS -> "loan, lottery"; PatternType.IS -> "Like 1405550101 or AX-LOANS"; else -> "Like 140" },
            // grey help until something is typed; red only once it is
            helper = textProblem ?: if (type == PatternType.WORDS) "Separate words with commas" else null,
            error = textProblem, showError = typed && text.isNotEmpty(),
            keyboard = if (type.numbers && type != PatternType.IS) KeyboardType.Phone else KeyboardType.Text,
        )
        SimpleRules.normalisation(p, PhoneNumbers.callingCode(region))?.let { Hint(it) }
        if (problem == null && count != null) Hint(com.dotforge.rules.Counts.blockedText(count))
        Text("Where", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Where.entries.forEach { w -> Chip(w.label, w in where) { setWhere(if (w in where) where - w else where + w) } }
            apps.forEach { a -> Chip(label(a), true) {} }
        }
        takenOut?.let { (gone, was) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    if (gone.size == 1) "Took out a condition a call can't meet: ${com.dotforge.rules.MoreConditions.sentence(gone[0])}."
                    else "Took out ${gone.size} conditions a call can't meet: calls have no title, message or notification type.",
                    Modifier.weight(1f),
                )
                TextButton(onClick = { more = more.copy(conditions = more.conditions + gone); where = was; takenOut = null }, modifier = Modifier.heightIn(min = Tokens.Target)) {
                    Text("Undo", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        // what blocking does, per place: calls pick Silence, Decline or Decline with no log; messages are hidden
        if (where.isNotEmpty() || apps.isNotEmpty()) Text("What to do", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))
        if (Where.CALLS in where) {
            Text("Calls", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Tiny))
            // without the call screening role nothing can silence or decline a call: the choices wait for it
            val can = setup.screening
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                com.dotforge.calls.CallActions.CHOICES.forEach { (label, a, _) -> Chip(label, callAction == a, enabled = can) { callAction = a } }
            }
            if (can) Hint(com.dotforge.calls.CallActions.CHOICES.first { it.second == callAction }.third)
            else NeedsLine("Needs call screening to silence or decline calls", "Allow") { setup.needCallScreening() }
        }
        if (where.any { it != Where.CALLS } || apps.isNotEmpty()) {
            Text("Messages", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Tiny))
            // one thing happens to a message, so it is said, not offered as a choice
            Text("The notification is hidden.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.padding(top = Tokens.Tiny)) {
                Where.entries.filter { it in where && it != Where.CALLS }.forEach { w -> Text("${w.label}: ${w.means}", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        if (problem == "Pick where it applies") Hint(problem)
        // any field, all or any, named lists: the same builder as an app's Advanced
        val broken = com.dotforge.rules.MoreConditions.missingLists(more, lists).isNotEmpty()
        ListRow(
            "More conditions", subtitle = if (broken) LIST_REMOVED else com.dotforge.rules.MoreConditions.summary(more), subtitleLines = Int.MAX_VALUE,
            subtitleColor = if (broken) Tokens.AccentText else Tokens.Secondary,
            inset = 0.dp, trailing = { Chevron() }, modifier = Modifier.padding(top = Tokens.Small),
        ) { builder = true }
    }
    if (builder) ConditionsSheet(
        more, calls = Where.CALLS in where, apps = where.any { it != Where.CALLS } || apps.isNotEmpty(), blocking = true,
        preview = { g -> SimpleRules.blockRule(p, more = g) }, onDismiss = { builder = false },
    ) { more = it }
}

/**
 * The last 30 days' count for [rule] (null while it is worked out, or for no rule), with Contacts looked up now
 * when the rule asks about them and Contacts can be read ([com.dotforge.rules.Counts]).
 */
@Composable
fun rememberCount(rule: Rule?, history: List<HistoryEntry>, lists: List<NamedList>): com.dotforge.rules.Counts.Count? {
    val ctx = LocalContext.current
    val region = remember { runCatching { CallerLookup(ctx).region() }.getOrNull() }
    if (rule == null) return null
    val contactFields = setOf(com.dotforge.filter.Field.CONTACT, com.dotforge.filter.Field.STARRED, com.dotforge.filter.Field.CONTACT_GROUP)
    val asks = rule.match.allConditions.any { it.field in contactFields }
    val canRead = remember(asks) { asks && runCatching { CallerLookup(ctx).canRead() }.getOrDefault(false) }
    if (!canRead) return remember(rule, history, lists) { com.dotforge.rules.Counts.count(rule, history, region, lists) }
    return androidx.compose.runtime.produceState<com.dotforge.rules.Counts.Count?>(null, rule, history, lists) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val lookup = com.dotforge.notify.ContactLookup(ctx)
            val cache = HashMap<String, com.dotforge.filter.Caller?>()
            com.dotforge.rules.Counts.count(rule, history, region, lists, contacts = { e ->
                cache.getOrPut(e.number.ifBlank { e.sender.ifBlank { e.title } }) {
                    val info = com.dotforge.rules.NotificationInfo(e.key, e.packageName, e.appLabel, e.number.ifBlank { e.sender.ifBlank { e.title } }, e.text, e.at)
                    lookup.lookup(info)?.let { c ->
                        com.dotforge.filter.Caller(c.status != com.dotforge.rules.ContactStatus.UNKNOWN, c.status == com.dotforge.rules.ContactStatus.STARRED, c.name, c.groups)
                    }
                }
            })
        }
    }.value
}

/** Row 66: what plays for spam, "a short Blocked glyph, 2 s, once" or nothing; and its sound. */
@Composable
fun SpamAlertSheet(onDismiss: () -> Unit) {
    val s by AppGraph.filters.spam.collectAsStateWithLifecycle()
    FullSheet(onDismiss, "When something is spam", half = true) {
        OptionRow("A short Blocked glyph", "2 s, once", preview = { GlyphPreview(stillOf(Effects.spam(AppGraph.profile)), "Blocked glyph") }, selected = s.glyphOn) {
            AppGraph.filters.updateSpam { it.copy(glyphOn = true, glyph = GlyphStyle.SPAM) }
        }
        OptionRow("Nothing", "Blocked quietly; it shows in History", preview = { GlyphPreview(com.dotforge.device.Frame.blank(AppGraph.profile), "No glyph") }, selected = !s.glyphOn) { AppGraph.filters.updateSpam { it.copy(glyphOn = false) } }
        SectionLabel("Sound")
        OptionRow("No sound", "Spam is silenced", selected = s.soundUri == null) { AppGraph.filters.updateSpam { it.copy(soundUri = null, soundName = null) } }
        if (s.soundUri != null) OptionRow("Custom tune: ${s.soundName ?: "chosen"}", "Plays instead of the app's sound", selected = true) {}
    }
}

// ---------------------------------------------------------------- History

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.historyItems(
    history: List<HistoryEntry>, filter: HistoryFilter, label: (String) -> String,
    onFilter: (HistoryFilter) -> Unit, open: (AlertSheet) -> Unit, toApps: () -> Unit = {},
) {
    item(key = "history-chips") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) { HistoryFilter.entries.forEach { f -> Chip(f.label, f == filter) { onFilter(f) } } }
    }
    val shown = history.filter { filter.outcome == null || it.outcome == filter.outcome }
    if (shown.isEmpty()) {
        item(key = "history-empty") {
            when (filter) {
                HistoryFilter.ALL -> EmptyState("Nothing yet. Every alert that lights, is blocked or is skipped shows here for 30 days.", "Choose apps that light", glyph = "clock", onClick = toApps)
                HistoryFilter.LIT -> EmptyState("Nothing has lit in the last 30 days.", glyph = "clock")
                HistoryFilter.BLOCKED -> EmptyState("Nothing blocked in the last 30 days.", glyph = "clock")
                HistoryFilter.SKIPPED -> EmptyState("Nothing skipped. Alerts skipped by Quiet or face down show here.", glyph = "clock")
            }
        }
        return
    }
    shown.groupBy { HistoryText.dayTitle(it.at) }.forEach { (day, entries) ->
        item(key = "h-$day") { SectionLabel(day) }
        item(key = "hg-$day") {
            val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
            RowGroup {
                entries.forEachIndexed { i, e ->
                    if (i > 0) RowDivider(leading = true)
                    val app = if (e.call) "Phone" else e.appLabel.ifBlank { label(e.packageName) }
                    ListRow(
                        e.who + if (app.isNotBlank() && app != e.who) " · $app" else "",
                        // the day is in the header: just the time
                        subtitle = HistoryText.timeText(e.at) + " · " + e.line,
                        leading = { HistoryLeading(e, rules.firstOrNull { it.id == e.ruleId }, app) },
                    ) { open(AlertSheet.Entry(e.key)) }
                }
            }
        }
    }
    item(key = "history-kept") { Hint("Kept for 30 days.") }
}

/** What an entry shows first: a cross for a block, else the glyph that lit (their name for "their name"). */
@Composable
private fun HistoryLeading(e: HistoryEntry, rule: Rule?, app: String) {
    when {
        e.outcome == Outcome.BLOCKED -> com.dotforge.ui.design.IconCircle(Icons.Filled.Close)
        rule != null -> {
            val frame = remember(rule, e.key) {
                if (rule.glyph == GlyphStyle.NAME && e.who.isNotBlank()) com.dotforge.device.Posters.text(AppGraph.profile, e.who) else posterOf(rule, app)
            }
            GlyphPreview(frame, "${com.dotforge.rules.AlertSentence.glyphWord(rule, hasIcon = ::hasAppIcon)} for ${e.who}")
        }
        e.call -> com.dotforge.ui.design.LibraryGlyph("phone", still = true)
        e.packageName.isNotBlank() -> AppIcon(e.packageName, app)
        else -> Avatar(e.who)
    }
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
            // a number reads as the phone writes numbers, like the header
            val shown = if (e.number.isNotBlank() && e.senderKey == e.number) (if (PhoneNumbers.looksLikeNumber(e.title)) e.title else CallerLookup(ctx).display(e.number)) else e.senderKey
            OptionRow("Not spam", "Always let ${shown.ifBlank { "them" }} through") {
                val k = e.senderKey
                AppGraph.rules.addToList(RuleEngine.ALLOWED, k, removeFrom = RuleEngine.BLOCKED)
                AppGraph.rules.ensurePreset("spam-allowed")
                onDismiss()
                snacks?.show("$shown will always get through", undo = {
                    val l = AppGraph.rules.list(RuleEngine.ALLOWED) ?: return@show
                    AppGraph.rules.putList(NamedList(l.name, l.entries.filterNot { it.equals(k, ignoreCase = true) }))
                })
            }
        }
        OptionRow("Make a rule", if (e.outcome == Outcome.BLOCKED) "Change what gets blocked" else if (e.call) "Choose how calls light up" else "Choose how ${e.appLabel.ifBlank { "this app" }} lights up") {
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
        // blocking someone from where you see them (row 60)
        val who = e.sender.ifBlank { e.number }.trim()
        if (e.outcome != Outcome.BLOCKED && !e.call && e.packageName.isNotBlank() && who.isNotEmpty()) {
            val existing = SimpleRules.senderBlock(AppGraph.rules.rules.value, e.packageName, who)
            if (existing == null) OptionRow("Block $who", "Their messages in ${e.appLabel.ifBlank { "this app" }} won't light or sound") {
                val r = SimpleRules.blockSender(e.packageName, who)
                AppGraph.rules.upsert(r)
                onDismiss()
                snacks?.show("Blocked $who", undo = { AppGraph.rules.delete(r.id) })
            }
        }
        if (rule != null && e.outcome != Outcome.BLOCKED) OptionRow("Try it", "Plays this alert on the Glyph") { tryIt = true }
        if (e.call && e.number.isNotBlank()) OptionRow("Call back", if (PhoneNumbers.looksLikeNumber(e.title)) e.title else CallerLookup(ctx).display(e.number)) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (!e.call && e.packageName.isNotBlank()) OptionRow("Open message", "Opens ${e.appLabel.ifBlank { "the app" }}") {
            runCatching { ctx.packageManager.getLaunchIntentForPackage(e.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
    if (tryIt && rule != null) TryItSheet(rule, e.appLabel.ifBlank { rule.name }, deviceState(ctx), onDismiss = { tryIt = false })
}

// ---------------------------------------------------------------- blocking from an app's sheet

/**
 * Block a sender or group (row 60), stacked on an app's sheet: who wrote recently in the app, each with a Block
 * switch, then "Type a name or number". Each block is an "Exactly" rule with the app's chip, and says
 * "Blocked AX-LOANS" with Undo on the sheet.
 */
@Composable
fun BlockSendersSheet(pkg: String, title: String, onDismiss: () -> Unit) {
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val recent = remember(history) { HistoryRepository.recentSenders(history, pkg) }
    // blocks made here stay listed (switched on) even when nobody of that name wrote recently
    val blockedNames = rules.mapNotNull { r -> SimpleRules.pattern(r)?.takeIf { it.type == PatternType.IS && SimpleRules.covers(it, pkg) }?.text }
    val shown = recent + blockedNames.filter { n -> recent.none { it.name.equals(n, true) } }.map { com.dotforge.rules.RecentSender(it, chat = false) }
    var typed by remember { mutableStateOf("") }
    FullSheet(onDismiss, "Block in $title") {
        val snacks = rememberSnacks()
        fun block(who: String) {
            val r = SimpleRules.blockSender(pkg, who)
            AppGraph.rules.upsert(r)
            snacks?.show("Blocked $who", undo = { AppGraph.rules.delete(r.id) })
        }
        Hint("Their messages in $title won't light or sound, and their notification is cleared. Calls aren't affected.")
        if (shown.isEmpty()) Hint("Nobody has written in $title lately. Type a name, a group or a number below.")
        else {
            SectionLabel("Recent in $title")
            RowGroup {
                shown.forEachIndexed { i, who ->
                    if (i > 0) RowDivider(leading = true)
                    val existing = SimpleRules.senderBlock(rules, pkg, who.name)
                    SwitchRow(
                        who.name, if (who.chat) "Group or chat" else who.number.ifBlank { null }, existing != null,
                        leading = { Avatar(who.name) },
                    ) { on ->
                        if (on) block(who.name)
                        else existing?.let { r -> AppGraph.rules.delete(r.id); snacks?.show("${who.name} unblocked", undo = { AppGraph.rules.upsert(r) }) }
                    }
                }
            }
        }
        val t = typed.trim()
        val valid = t.length >= 2 && SimpleRules.senderBlock(rules, pkg, t) == null
        LabeledField(
            "Type a name or number", typed, { typed = it }, placeholder = "Like AX-LOANS or Family group",
            helper = "Exactly as $title shows it",
            trailing = {
                TextButton(onClick = { block(t); typed = "" }, enabled = valid, modifier = Modifier.heightIn(min = Tokens.Target)) {
                    Text("Block", color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (valid) 1f else com.dotforge.ui.design.DISABLED_ALPHA))
                }
            },
        )
    }
}

/**
 * Spam > Always let through: Contacts and repeat callers, then the numbers and senders let through ("Not spam"),
 * each with Remove and Undo.
 */
@Composable
fun AllowListSheet(onDismiss: () -> Unit) {
    val lists by AppGraph.rules.lists.collectAsStateWithLifecycle()
    val entries = lists.firstOrNull { it.name == RuleEngine.ALLOWED }?.entries.orEmpty()
    val ctx = LocalContext.current
    val lookup = remember { CallerLookup(ctx) }
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    FullSheet(onDismiss, "Always let through") {
        val snacks = rememberSnacks()
        Hint("They're never blocked, by any rule or the community list.")
        RowGroup(Modifier.padding(top = Tokens.Small)) {
            val contacts = rules.firstOrNull { it.id == "spam-contacts" }
            val repeat = rules.firstOrNull { it.id == "spam-repeated" }
            SwitchRow("Contacts", "Everyone in your contacts", contacts?.enabled == true) { on ->
                if (contacts == null) AppGraph.rules.ensurePreset("spam-contacts") else AppGraph.rules.upsert(contacts.copy(enabled = on))
            }
            RowDivider()
            SwitchRow("Repeat callers", "Same number twice within 3 min", repeat?.enabled == true) { on ->
                if (repeat == null) AppGraph.rules.ensurePreset("spam-repeated") else AppGraph.rules.upsert(repeat.copy(enabled = on))
            }
        }
        SectionLabel("Numbers and senders you let through")
        if (entries.isEmpty()) Hint("Nobody yet. Tap Not spam on a blocked call or message in History.")
        else RowGroup {
            entries.forEachIndexed { i, k ->
                if (i > 0) RowDivider()
                ListRow(if (PhoneNumbers.looksLikeNumber(k)) lookup.display(k) else k, trailing = {
                    TextButton(onClick = {
                        val l = AppGraph.rules.list(RuleEngine.ALLOWED) ?: return@TextButton
                        AppGraph.rules.putList(NamedList(l.name, l.entries.filterNot { it == k }))
                        snacks?.show("$k removed", undo = { AppGraph.rules.addToList(RuleEngine.ALLOWED, k) })
                    }, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Remove", color = Tokens.AccentText) }
                })
            }
        }
    }
}
