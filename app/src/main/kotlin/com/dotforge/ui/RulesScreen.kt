package com.dotforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.rules.Effect
import com.dotforge.rules.KeywordMode
import com.dotforge.rules.MatchResult
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun RulesScreen() {
    val repo = AppGraph.rules
    val rules by repo.rules.collectAsStateWithLifecycle()
    val recent by AppGraph.recent.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Rule?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                SectionTitle("Notification rules")
                Text("Checked top to bottom: the first match wins. Use the arrows to set priority.", color = Muted)
            }
            items(rules, key = { it.id }) { r ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (r.suppress) "⦸ " else "") + r.name)
                            Text(summary(r), color = Muted, fontFamily = FontFamily.Monospace)
                        }
                        Switch(checked = r.enabled, onCheckedChange = { repo.upsert(r.copy(enabled = it)) })
                    }
                    Row {
                        IconButton(onClick = { repo.move(r.id, -1) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Higher priority") }
                        IconButton(onClick = { repo.move(r.id, 1) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Lower priority") }
                        IconButton(onClick = { editing = r }) { Icon(Icons.Outlined.Edit, "Edit") }
                        IconButton(onClick = { repo.delete(r.id) }) { Icon(Icons.Outlined.Delete, "Delete") }
                    }
                }
            }
            item {
                SectionTitle("Rule tester (last notifications)")
                if (recent.isEmpty()) Text("Notifications you receive appear here with the rule they matched.", color = Muted)
            }
            items(recent, key = { it.key }) { n ->
                val verdict = when (val m = RuleEngine.match(n, rules)) {
                    is MatchResult.Show -> "→ ${m.rule.name} (${m.rule.effect.label})"
                    is MatchResult.Suppressed -> "⦸ blocked by ${m.rule.name}"
                    MatchResult.NoMatch -> "– no rule"
                }
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("${timeFormat.format(Date(n.postedAt))}  ${n.appLabel}: ${n.title}")
                    Text(verdict, fontFamily = FontFamily.Monospace, color = if (verdict.startsWith("⦸")) NothingRed else Muted)
                }
            }
            item { Box(Modifier.padding(48.dp)) }
        }
        FloatingActionButton(
            onClick = { editing = Rule() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Outlined.Add, "Add rule") }
    }

    editing?.let { r ->
        RuleEditor(r, onDismiss = { editing = null }, onSave = { repo.upsert(it); editing = null })
    }
}

private fun summary(r: Rule): String = buildList {
    add(if (r.packages.isEmpty()) "any app" else r.packages.joinToString { it.substringAfterLast('.') })
    if (r.senders.isNotEmpty()) add("from " + r.senders.joinToString())
    if (r.keywords.isNotEmpty()) add("${r.keywordMode.label.lowercase()}: " + r.keywords.joinToString())
    add(if (r.suppress) "block" else r.effect.label + if (r.holdUntilDismissed) ", hold" else "")
}.joinToString(" · ")

private fun csv(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(initial: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var r by remember { mutableStateOf(initial) }
    var pk by remember { mutableStateOf(initial.packages.joinToString(", ")) }
    var se by remember { mutableStateOf(initial.senders.joinToString(", ")) }
    var kw by remember { mutableStateOf(initial.keywords.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(r.copy(packages = csv(pk), senders = csv(se), keywords = csv(kw))) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(pk, { pk = it }, label = { Text("Apps (package names, comma separated, empty = all)") })
                OutlinedTextField(se, { se = it }, label = { Text("Senders / chats (comma separated)") })
                OutlinedTextField(kw, { kw = it }, label = { Text("Keywords (comma separated)") })
                Text("Keyword match")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeywordMode.entries.forEach { m ->
                        FilterChip(selected = r.keywordMode == m, onClick = { r = r.copy(keywordMode = m) }, label = { Text(m.label) })
                    }
                }
                Toggle("Spam / ignore (never light up)", r.suppress) { r = r.copy(suppress = it) }
                if (!r.suppress) {
                    Text("Effect")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Effect.entries.forEach { e ->
                            FilterChip(selected = r.effect == e, onClick = { r = r.copy(effect = e) }, label = { Text(e.label) })
                        }
                    }
                    Toggle("Keep showing until dismissed", r.holdUntilDismissed) { r = r.copy(holdUntilDismissed = it) }
                    Toggle("Clear when I unlock", r.clearOnUnlock) { r = r.copy(clearOnUnlock = it) }
                    Toggle("Light up during Do Not Disturb", r.breakDnd) { r = r.copy(breakDnd = it) }
                }
            }
        },
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
