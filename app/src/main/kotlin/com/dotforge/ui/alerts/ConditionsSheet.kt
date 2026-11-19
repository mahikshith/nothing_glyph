package com.dotforge.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.calls.CallerLookup
import com.dotforge.filter.Condition
import com.dotforge.filter.Group
import com.dotforge.filter.NamedList
import com.dotforge.rules.MoreConditions
import com.dotforge.rules.MoreConditions.Pick
import com.dotforge.rules.MoreConditions.Test
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.ui.design.AddRow
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Segmented
import com.dotforge.ui.design.Tokens

/**
 * More conditions: tests on any field, built as plain sentences ("Title has 'OTP'", "and not from 'Bank'"),
 * combined with Match all or Match any, and named lists made or edited in place. It writes one group of the
 * rule's match ([MoreConditions]), which the filter engine runs like any other condition. [calls] and [apps]
 * pick the fields that mean something for the rule; [preview] is the whole rule with a group, so the last
 * 30 days can be counted ("Would have blocked 3"). [startEdit] (-1: a new condition) and [startList] ("": a new
 * list) open the stacked sheets, for screenshots.
 */
@Composable
fun ConditionsSheet(
    start: Group,
    calls: Boolean,
    apps: Boolean,
    blocking: Boolean,
    preview: (Group) -> Rule,
    onDismiss: () -> Unit,
    startEdit: Int? = null,
    startList: String? = null,
    onSave: (Group) -> Unit,
) {
    var all by remember { mutableStateOf(start.all) }
    var conditions by remember { mutableStateOf(start.conditions) }
    var edit by remember { mutableStateOf(startEdit) }
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val lists by AppGraph.rules.lists.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val region = remember { runCatching { CallerLookup(ctx).region() }.getOrNull() }
    val g = Group(all = all, conditions = conditions)
    val matched = remember(g, history, lists) { if (conditions.isEmpty()) 0 else wouldHaveMatched(preview(g), history, region, lists) }

    FullSheet(onDismiss, "More conditions", confirm = "Save", onConfirm = { onSave(g); onDismiss() }) {
        Hint(if (blocking) "Block only when these are true too." else "Light only when these are true too.")
        if (conditions.size > 1) Segmented(listOf("Match all", "Match any"), if (all) 0 else 1, Modifier.padding(top = Tokens.Small)) { all = it == 0 }
        RowGroup(Modifier.padding(top = Tokens.Small)) {
            conditions.forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                // the rows read as one sentence: the first, then "and …" or "or …"
                val words = MoreConditions.sentence(c)
                ListRow(
                    if (i == 0) words.replaceFirstChar { it.uppercase() } else (if (all) "and " else "or ") + words,
                    titleLines = Int.MAX_VALUE, inset = Tokens.Gutter,
                    trailing = {
                        IconButton(onClick = { conditions = conditions.filterIndexed { j, _ -> j != i } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $words", tint = Tokens.Secondary)
                        }
                    },
                ) { edit = i }
            }
            if (conditions.isNotEmpty()) RowDivider(leading = true)
            AddRow("Add a condition") { edit = -1 }
        }
        when {
            conditions.isEmpty() -> Hint("For example: Title contains “OTP”, or Sender isn't in my contacts.")
            blocking -> Hint(if (matched == 0) "Would have blocked nothing in the last 30 days" else "Would have blocked $matched in the last 30 days")
            else -> Hint(if (matched == 0) "Wouldn't have lit in the last 30 days" else "Would have lit $matched ${if (matched == 1) "time" else "times"} in the last 30 days")
        }
    }
    edit?.let { i ->
        ConditionEditor(conditions.getOrNull(i), calls, apps, lists, startList, onDismiss = { edit = null }) { c ->
            conditions = if (i !in conditions.indices) conditions + c else conditions.mapIndexed { j, old -> if (j == i) c else old }
        }
    }
}

/** One condition: Field, Test and Value as chips and a field; a list test picks, makes or edits a named list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionEditor(
    start: Condition?, calls: Boolean, apps: Boolean, lists: List<NamedList>, startList: String?,
    onDismiss: () -> Unit, onDone: (Condition) -> Unit,
) {
    val picks = Pick.available(calls, apps)
    var pick by remember { mutableStateOf(start?.let { Pick.of(it.field) }?.takeIf { it in picks } ?: picks.first()) }
    var test by remember { mutableStateOf(start?.let(Test::of)?.takeIf { it in pick.tests } ?: pick.tests.first()) }
    var value by remember { mutableStateOf(start?.cleanValues?.joinToString(", ").orEmpty()) }
    var list by remember { mutableStateOf(start?.list) }
    var listSheet by remember { mutableStateOf(startList) }
    val c = MoreConditions.condition(pick, test, value, list)
    FullSheet(
        onDismiss, if (start == null) "Add a condition" else "Edit condition", confirm = if (start == null) "Add condition" else "Save condition",
        confirmEnabled = MoreConditions.complete(c),
        onConfirm = { onDone(c); onDismiss() },
    ) {
        Label("Field")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            picks.forEach { p -> Chip(p.label, p == pick) { pick = p; if (test !in p.tests) test = p.tests.first() } }
        }
        Label("Test")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            pick.tests.forEach { t -> Chip(t.label, t == test) { test = t } }
        }
        when {
            test.flag -> Unit
            test.list -> {
                Label("List")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    lists.forEach { l -> Chip(l.name, l.name.equals(list, ignoreCase = true)) { list = l.name } }
                    Chip("+ New list", false) { listSheet = "" }
                }
                list?.let { name ->
                    TextButton(onClick = { listSheet = name }, modifier = Modifier.heightIn(min = Tokens.Target), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Edit “$name”", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            else -> LabeledField(
                "Value", value, { value = it },
                placeholder = when (pick) {
                    Pick.NUMBER -> "Like +44 or 140"
                    Pick.SENDER -> "Like Bank"
                    Pick.APP -> "Like WhatsApp"
                    Pick.CHANNEL -> "Like Promotions"
                    else -> "Like OTP"
                },
                helper = "Separate several with commas",
                keyboard = if (pick == Pick.NUMBER) KeyboardType.Phone else KeyboardType.Text,
            )
        }
        if (MoreConditions.complete(c)) Hint("Reads: " + MoreConditions.sentence(c).replaceFirstChar { it.uppercase() }, Modifier.padding(top = Tokens.Small))
    }
    listSheet?.let { name ->
        ListEditor(lists.firstOrNull { it.name.equals(name, ignoreCase = true) }, onDismiss = { listSheet = null }) { saved -> list = saved }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap, bottom = Tokens.Tiny))

/**
 * A named list, made or edited in place: its name and entries. Renaming keeps every rule that uses it; the
 * spam filter's own lists can't be deleted. [onSaved] gets the name (null once deleted).
 */
@Composable
fun ListEditor(existing: NamedList?, onDismiss: () -> Unit, onSaved: (String?) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var entries by remember { mutableStateOf(existing?.entries?.joinToString(", ").orEmpty()) }
    val clean = name.trim()
    val taken = clean.isNotEmpty() && AppGraph.rules.lists.value.any { it.name.equals(clean, ignoreCase = true) && !it.name.equals(existing?.name, ignoreCase = true) }
    val builtIn = existing != null && RuleEngine.presetLists().any { it.name.equals(existing.name, ignoreCase = true) }
    FullSheet(
        onDismiss, if (existing == null) "New list" else "Edit list", confirm = "Save list", confirmEnabled = clean.isNotEmpty() && !taken,
        onConfirm = {
            AppGraph.rules.putList(NamedList(clean, MoreConditions.split(entries)), oldName = existing?.name)
            onSaved(clean)
            onDismiss()
        },
    ) {
        Hint("A list several rules can share, like your banks or delivery apps.")
        LabeledField(
            "Name", name, { name = it }, placeholder = "Like Banks",
            error = "There's already a list called that".takeIf { taken }, showError = taken,
        )
        LabeledField("Entries", entries, { entries = it }, placeholder = "Like HDFC, ICICI, SBI", helper = "Separate with commas")
        if (existing != null && !builtIn) TextButton(
            onClick = { AppGraph.rules.deleteList(existing.name); onSaved(null); onDismiss() },
            modifier = Modifier.heightIn(min = Tokens.Target).padding(top = Tokens.Small), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("Delete “${existing.name}”", color = Tokens.AccentText, style = MaterialTheme.typography.labelLarge) }
    }
}
