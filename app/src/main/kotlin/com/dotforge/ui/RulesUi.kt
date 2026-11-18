package com.dotforge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.rules.ContactStatus
import com.dotforge.rules.Effect
import com.dotforge.rules.KeywordMode
import com.dotforge.rules.MatchResult
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RuleText
import com.dotforge.rules.SpamFilter

private val Effect.hint: String
    get() = when (this) {
        Effect.PULSE -> "Slow glow, in and out"
        Effect.RING -> "A dot runs round the edge"
        Effect.LETTER -> "Sender's first letter blinks"
        Effect.FLASH -> "Three quick flashes"
        Effect.MARQUEE -> "Sender's name scrolls by"
        Effect.SPARKLE -> "Random twinkling dots"
        Effect.CUSTOM -> "Your design from Create"
    }

@Composable
fun RulesPage(nav: Nav, setup: Setup) {
    val repo = AppGraph.rules
    val rules by repo.rules.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    val missingPresets = RuleEngine.presets().any { p -> rules.none { it.id == p.id } }

    PageScaffold("RULES", onBack = nav::pop, actions = {
        IconButton(onClick = { nav.push(Page.RuleEditor(null)) }) { Icon(Icons.Filled.Add, "New rule") }
    }) {
        AccessBanner(setup, "Rules need notification access.")
        if (rules.isEmpty()) {
            EmptyState("No rules yet", "Start from the presets, or make your own.", "Add presets") { repo.addPresets() }
        }
        rules.forEach { r ->
            Surface(color = Surface1, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(
                    Modifier.clickable { nav.push(Page.RuleEditor(r.id)) }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(RuleText.sentence(r, label), color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(checked = r.enabled, colors = nothingSwitchColors(), onCheckedChange = { on ->
                        repo.upsert(r.copy(enabled = on))
                        if (on) setup.needNotificationAccess()
                    })
                }
            }
        }
        if (rules.size > 1) Text("When several rules match, the top one wins.", color = Muted, fontSize = 13.sp)
        SecondaryButton("New rule", Modifier.fillMaxWidth().padding(top = 16.dp)) { nav.push(Page.RuleEditor(null)) }
        if (missingPresets && rules.isNotEmpty()) {
            TextButton(onClick = repo::addPresets, modifier = Modifier.padding(top = 8.dp)) { Text("Add missing presets", color = Muted) }
        }
    }
}

private enum class Slot { APPS, PEOPLE, KEYWORDS, GLYPH }

private const val TEST_ID = "rule-test"

/** The rule editor: a live preview, the rule as a sentence with four slots, and what it would match. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorPage(ruleId: String?, onClose: () -> Unit) {
    val repo = AppGraph.rules
    val profile = AppGraph.profile
    val label = rememberAppLabel()
    val initial = remember { repo.rules.value.firstOrNull { it.id == ruleId } }
    val isNew = initial == null
    val start = remember { initial ?: Rule(name = "") }
    var draft by remember { mutableStateOf(start) }
    var slot by rememberSaveable { mutableStateOf<Slot?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var more by rememberToggle()
    val recent by AppGraph.recent.collectAsStateWithLifecycle()
    val spam by AppGraph.spam.settings.collectAsStateWithLifecycle()

    val matches = remember(draft, recent) {
        val probe = listOf(draft.copy(enabled = true))
        recent.filter { RuleEngine.match(it, probe) is MatchResult.Show }.take(5)
    }
    val sample = matches.firstOrNull() ?: NotificationInfo("preview", "", "Messages", "Alex", "Are we still on for tonight?", 0)
    val animation = remember(draft.effect, sample) { RuleEngine.animationFor(draft, sample, profile, AppGraph.playerAnimation()) }
    val frame = rememberLoopingFrame(animation)

    fun close() = if (draft != start) confirmLeave = true else onClose()
    fun save() {
        val name = draft.name.trim().ifEmpty {
            draft.senders.firstOrNull() ?: draft.packages.firstOrNull()?.let(label) ?: "New rule"
        }
        repo.upsert(draft.copy(name = name))
        onClose()
    }

    PageScaffold(
        if (isNew) "NEW RULE" else "EDIT RULE",
        onBack = ::close,
        actions = { if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete rule") } },
        bottomBar = { PrimaryButton("Save", Modifier.fillMaxWidth(), onClick = ::save) },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MatrixView(frame?.pixels, profile, Modifier.fillMaxWidth(0.42f).padding(top = 8.dp))
        }
        TextButton(
            onClick = {
                AppGraph.arbiter.post(DisplayRequest(TEST_ID, Priority.STUDIO_PREVIEW, animation, loop = true, timeoutMs = 5_000, label = "Rule test"))
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Try on the matrix", color = Color.White) }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            placeholder = { Text("Name, e.g. Family", color = Muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        FlowRow(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Word("When")
            SlotChip(RuleText.apps(draft, label)) { slot = Slot.APPS }
            Word("from")
            SlotChip(RuleText.people(draft)) { slot = Slot.PEOPLE }
            Word("mention")
            SlotChip(RuleText.keywords(draft)) { slot = Slot.KEYWORDS }
            Word(", light up")
            SlotChip(draft.effect.label, accent = true) { slot = Slot.GLYPH }
        }

        GroupTitle("SHOW FOR")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard("A few seconds", "Then it stops", !draft.holdUntilDismissed, Modifier.weight(1f)) {
                draft = draft.copy(holdUntilDismissed = false)
            }
            ChoiceCard("Until dismissed", "Until you clear it", draft.holdUntilDismissed, Modifier.weight(1f)) {
                draft = draft.copy(holdUntilDismissed = true)
            }
        }
        Collapsible("More options", more, { more = !more }) {
            SwitchRow("Clear when I unlock", "Stop once you've seen the phone", draft.clearOnUnlock) { draft = draft.copy(clearOnUnlock = it) }
            SwitchRow("Light up in Do Not Disturb", null, draft.breakDnd) { draft = draft.copy(breakDnd = it) }
        }

        GroupTitle("WOULD MATCH")
        if (matches.isEmpty()) {
            Text("No recent notifications match.", color = Muted)
            Text("New ones that match will show here.", color = Muted, fontSize = 13.sp)
        }
        matches.forEach { n ->
            val blocked = SpamFilter.isSpam(n, spam) { ContactStatus.UNKNOWN }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("${n.appLabel} · ${n.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (blocked) "Blocked by the spam filter" else n.text,
                    color = if (blocked) NothingRed else Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    when (slot) {
        Slot.APPS -> AppsDialog(draft.packages, onDismiss = { slot = null }) { draft = draft.copy(packages = it); slot = null }
        Slot.PEOPLE -> ListDialog(
            "People", "Matched against the notification title. Empty means anyone.", draft.senders, "Add a name",
            suggestions = recent.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct().take(8),
            onDismiss = { slot = null },
        ) { draft = draft.copy(senders = it); slot = null }
        Slot.KEYWORDS -> KeywordsDialog(draft, onDismiss = { slot = null }) { draft = it; slot = null }
        Slot.GLYPH -> EffectDialog(draft.effect, onDismiss = { slot = null }) { draft = draft.copy(effect = it); slot = null }
        null -> Unit
    }
    if (confirmLeave) ConfirmDialog("Discard changes?", "This rule hasn't been saved.", "Discard", onConfirm = onClose, onDismiss = { confirmLeave = false })
    if (confirmDelete) ConfirmDialog("Delete this rule?", "\"${start.name}\" will stop lighting up.", "Delete", onConfirm = {
        repo.delete(start.id)
        onClose()
    }, onDismiss = { confirmDelete = false })
}

@Composable
private fun Word(text: String) = Text(text, fontSize = 18.sp)

@Composable
private fun SlotChip(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Surface2,
        border = BorderStroke(1.dp, if (accent) NothingRed else Color(0xFF444444)),
    ) {
        Text(
            text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SlotDialog(title: String, onDismiss: () -> Unit, onDone: () -> Unit, content: @Composable () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onDone) { Text("Done", color = NothingRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListDialog(
    title: String,
    hint: String,
    initial: List<String>,
    placeholder: String,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
) {
    var items by remember { mutableStateOf(initial) }
    SlotDialog(title, onDismiss, { onDone(items) }) {
        Text(hint, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        ChipsEditor(items, { items = it }, placeholder)
        val left = suggestions.filter { s -> items.none { it.equals(s, ignoreCase = true) } }
        if (left.isNotEmpty()) {
            Text("From recent notifications", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                left.forEach { s -> FilterChip(selected = false, onClick = { items = items + s }, label = { Text(s, maxLines = 1) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordsDialog(rule: Rule, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
    var words by remember { mutableStateOf(rule.keywords) }
    var mode by remember { mutableStateOf(rule.keywordMode) }
    var advanced by remember { mutableStateOf(rule.keywordMode != KeywordMode.ANY) }
    SlotDialog("Keywords", onDismiss, { onDone(rule.copy(keywords = words, keywordMode = mode)) }) {
        Text("Words in the title or text. Empty means anything.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        ChipsEditor(
            words, { words = it }, if (mode == KeywordMode.REGEX) "Add a pattern" else "Add a word",
            validate = { if (mode == KeywordMode.REGEX && !SpamFilter.isValidPattern(it)) "Not a valid pattern" else null },
        )
        Collapsible("Advanced", advanced, { advanced = !advanced }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeywordMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
            }
        }
    }
}

@Composable
private fun EffectDialog(current: Effect, onDismiss: () -> Unit, onPick: (Effect) -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Light up with") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Effect.entries.forEach { e -> ChoiceCard(e.label, e.hint, e == current, Modifier.fillMaxWidth()) { onPick(e) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )

/** Full-screen app picker: recent notification apps first, then every launcher app. */
@Composable
private fun AppsDialog(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val label = rememberAppLabel()
    var selected by remember { mutableStateOf(initial.toSet()) }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<AppEntry>?>(null) }
    val recent by AppGraph.recent.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { all = launcherApps(ctx) }

    val recentApps = recent.map { it.packageName }.distinct().map { AppEntry(it, label(it)) }
    val chosenFirst = initial.map { AppEntry(it, label(it)) }
    val entries = (chosenFirst + recentApps + all.orEmpty()).distinctBy { it.packageName }
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { DotTitle("APPS", dot = 3.dp) }
                TextButton(onClick = { onDone(selected.toList()) }) { Text("Done", color = NothingRed) }
            }
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search apps", color = Muted) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                item {
                    AppRow(RuleText.ANY_APP, "Every app", selected.isEmpty()) { selected = emptySet() }
                }
                items(entries, key = { it.packageName }) { app ->
                    AppRow(app.label, app.packageName, app.packageName in selected) {
                        selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
                    }
                }
                if (all == null) item { Text("Loading apps…", color = Muted, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun AppRow(title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(checked = checked, onCheckedChange = { onClick() }, colors = CheckboxDefaults.colors(checkedColor = NothingRed, checkmarkColor = Color.White))
    }
}
