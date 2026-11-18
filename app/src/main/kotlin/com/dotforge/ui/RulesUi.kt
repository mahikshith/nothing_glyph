package com.dotforge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.dotforge.device.Animation
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.engine.Until
import com.dotforge.notify.ContactLookup
import com.dotforge.notify.ContactName
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.KeywordMode
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RuleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Slot { APPS, PEOPLE, KEYWORDS, GLYPH, UNTIL }

private const val TEST_ID = "rule-test"
private const val PICK_ID = "glyph-pick"

private val SAMPLE = NotificationInfo("preview", "", "Messages", "Alex", "Are we still on for tonight?", 0, fromPerson = true)

private fun animationFor(rule: Rule, sample: NotificationInfo): Animation =
    RuleEngine.animation(rule, sample, AppGraph.profile, AppGraph::designAnimation)

/** The rule editor: a live preview, the rule as a sentence with five slots, and what it would match. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorPage(ruleId: String?, setup: Setup, onClose: () -> Unit) {
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
    val log by AppGraph.log.collectAsStateWithLifecycle()

    val matches = remember(draft, log) { log.map { it.info }.filter { RuleEngine.explain(draft, it) != null }.take(5) }
    val sample = matches.firstOrNull() ?: SAMPLE
    val animation = remember(draft.glyph, draft.designId, sample) { animationFor(draft, sample) }
    val frame = rememberLoopingFrame(animation)

    fun close() = if (draft != start) confirmLeave = true else onClose()
    fun save() {
        val name = draft.name.trim().ifEmpty {
            draft.people.firstOrNull() ?: draft.packages.firstOrNull()?.let(label) ?: "New rule"
        }
        repo.upsert(draft.copy(name = name))
        if (draft.enabled) setup.needNotificationAccess()
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
            Word(", show")
            SlotChip(RuleText.glyph(draft, ::designName), accent = true) { slot = Slot.GLYPH }
            Word("until")
            SlotChip(draft.until.label) { slot = Slot.UNTIL }
        }

        Collapsible("More options", more, { more = !more }) {
            Text("Priority", modifier = Modifier.padding(bottom = 4.dp))
            Text("Only decides between rules that are equally specific.", color = Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "Low", 0 to "Normal", 1 to "High").forEach { (p, name) ->
                    FilterChip(
                        selected = draft.priority == p, onClick = { draft = draft.copy(priority = p) }, label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface2, selectedLabelColor = NothingRed),
                    )
                }
            }
        }

        GroupTitle("WOULD MATCH")
        if (matches.isEmpty()) {
            Text("No recent notifications match.", color = Muted)
            Text("New ones that match will show here.", color = Muted, fontSize = 13.sp)
        }
        matches.forEach { n ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("${n.appLabel} · ${n.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    when (slot) {
        Slot.APPS -> AppsDialog(draft.packages, onDismiss = { slot = null }) { draft = draft.copy(packages = it); slot = null }
        Slot.PEOPLE -> PeopleDialog(draft, setup, onDismiss = { slot = null }) { draft = it; slot = null }
        Slot.KEYWORDS -> KeywordsDialog(draft, onDismiss = { slot = null }) { draft = it; slot = null }
        Slot.GLYPH -> GlyphDialog(draft, sample, onDismiss = { slot = null }) { draft = it; slot = null }
        Slot.UNTIL -> UntilDialog(draft.until, onDismiss = { slot = null }) { draft = draft.copy(until = it); slot = null }
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

/** A full-screen picker: Cancel, a dot title, Done, then [content]. */
@Composable
private fun PickerDialog(title: String, onDismiss: () -> Unit, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { DotTitle(title, dot = 3.dp) }
                TextButton(onClick = onDone) { Text("Done", color = NothingRed) }
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordsDialog(rule: Rule, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
    var words by remember { mutableStateOf(rule.keywords) }
    var mode by remember { mutableStateOf(rule.keywordMode) }
    var regex by remember { mutableStateOf(rule.regex) }
    var advanced by remember { mutableStateOf(rule.regex) }
    SlotDialog("Keywords", onDismiss, { onDone(rule.copy(keywords = words, keywordMode = mode, regex = regex)) }) {
        Text("Words in the title or text. Empty means anything.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeywordMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m, onClick = { mode = m }, label = { Text(m.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface1, selectedLabelColor = NothingRed),
                )
            }
        }
        ChipsEditor(
            words, { words = it }, if (regex) "Add a pattern" else "Add a word",
            validate = { if (regex && !RuleEngine.isValidPattern(it)) "Not a valid pattern" else null },
        )
        Collapsible("Advanced", advanced, { advanced = !advanced }) {
            SwitchRow("Regular expressions", "Each entry is a pattern, e.g. \\d{6}", regex) { on ->
                regex = on
                if (on) words = words.filter(RuleEngine::isValidPattern)
            }
        }
    }
}

@Composable
private fun UntilDialog(current: Until, onDismiss: () -> Unit, onPick: (Until) -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Show until") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Until.entries.forEach { u ->
                    ChoiceCard(u.label.replaceFirstChar { it.uppercase() }, u.hint, u == current, Modifier.fillMaxWidth()) { onPick(u) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )

/**
 * Glyph cards: the animation presets, the app's letter, the sender's name and every saved design. The choice
 * plays in the preview and on the real matrix while the picker is open.
 */
@Composable
private fun GlyphDialog(rule: Rule, sample: NotificationInfo, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
    val profile = AppGraph.profile
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf(rule.copy(designId = rule.designId.takeIf { rule.glyph == GlyphStyle.DESIGN })) }
    val options = remember(designs) {
        GlyphStyle.entries.filter { it != GlyphStyle.DESIGN }.map { rule.copy(glyph = it, designId = null) } +
            designs.map { rule.copy(glyph = GlyphStyle.DESIGN, designId = it.id) }
    }
    val animation = remember(pick.glyph, pick.designId) { animationFor(pick, sample) }
    val frame = rememberLoopingFrame(animation)
    LaunchedEffect(animation) {
        AppGraph.arbiter.post(DisplayRequest(PICK_ID, Priority.STUDIO_PREVIEW, animation, loop = true, timeoutMs = 60_000, label = "Choosing a glyph"))
    }
    DisposableEffect(Unit) { onDispose { AppGraph.arbiter.remove(PICK_ID) } }

    PickerDialog("GLYPH", onDismiss, { onDone(pick) }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MatrixView(frame?.pixels, profile, Modifier.fillMaxWidth(0.4f).padding(vertical = 8.dp))
        }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options, key = { "${it.glyph}:${it.designId}" }) { o ->
                val selected = o.glyph == pick.glyph && (o.glyph != GlyphStyle.DESIGN || o.designId == pick.designId ||
                    (pick.designId == null && o.designId == designs.firstOrNull()?.id))
                val thumb = remember(o.glyph, o.designId) { animationFor(o, sample).let { it.frames[it.frames.size / 3].frame } }
                Surface(
                    color = if (selected) Surface2 else Surface1,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) NothingRed else Color(0xFF2E2E2E)),
                ) {
                    Column(Modifier.fillMaxWidth().clickable { pick = o }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MatrixView(thumb.pixels, profile, Modifier.size(64.dp))
                        Text(
                            if (o.glyph == GlyphStyle.DESIGN) designs.firstOrNull { it.id == o.designId }?.name.orEmpty() else o.glyph.label,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(o.glyph.hint, color = Muted, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
            if (designs.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text("Designs you save in Create appear here too.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

/**
 * People: starred contacts, unknown senders, and names picked from Contacts or recent senders (or typed).
 * Contacts are asked for here, the first time the picker opens, with "Not now".
 */
@Composable
private fun PeopleDialog(rule: Rule, setup: Setup, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
    val ctx = LocalContext.current
    var names by remember { mutableStateOf(rule.people.filter { it.isNotBlank() }) }
    var starred by remember { mutableStateOf(rule.starred) }
    var unknown by remember { mutableStateOf(rule.unknown) }
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ContactName>>(emptyList()) }
    val log by AppGraph.log.collectAsStateWithLifecycle()
    LaunchedEffect(setup.contacts) {
        if (setup.contacts) contacts = withContext(Dispatchers.IO) { ContactLookup(ctx).names() }
    }

    val recent = log.filter { it.info.fromPerson }.map { it.info.title.trim() }.filter { it.isNotEmpty() }.distinct()
    val rows = (names.map { it to "Chosen" } + contacts.map { it.name to if (it.starred) "Starred contact" else "Contact" } + recent.map { it to "Recent sender" })
        .distinctBy { it.first.lowercase() }
        .filter { query.isBlank() || it.first.contains(query.trim(), ignoreCase = true) }

    PickerDialog("PEOPLE", onDismiss, { onDone(rule.copy(people = names, starred = starred, unknown = unknown)) }) {
        ContactsAsk(
            setup, "Pick people from Contacts", "Also needed for starred and unknown senders.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Search or type a name", color = Muted) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            item { CheckRow(RuleText.ANYONE, "No filter", names.isEmpty() && !starred && !unknown) { names = emptyList(); starred = false; unknown = false } }
            item { CheckRow("Starred contacts", "Your favourites in Contacts", starred) { starred = !starred } }
            item { CheckRow(RuleText.UNKNOWN, "Messages and calls from people not in Contacts", unknown) { unknown = !unknown } }
            val typed = query.trim()
            if (typed.isNotEmpty() && rows.none { it.first.equals(typed, ignoreCase = true) }) {
                item {
                    CheckRow("Add \"$typed\"", "Matched against the sender's name", false) {
                        names = names + typed
                        query = ""
                    }
                }
            }
            items(rows, key = { "p:" + it.first.lowercase() }) { (name, kind) ->
                val on = names.any { it.equals(name, ignoreCase = true) }
                CheckRow(name, kind, on) { names = if (on) names.filterNot { it.equals(name, ignoreCase = true) } else names + name }
            }
        }
    }
}

/** Full-screen app picker: recent notification apps first, then every launcher app. */
@Composable
private fun AppsDialog(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val label = rememberAppLabel()
    var selected by remember { mutableStateOf(initial.toSet()) }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<AppEntry>?>(null) }
    val log by AppGraph.log.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { all = launcherApps(ctx) }

    val recentApps = log.map { it.info.packageName }.distinct().map { AppEntry(it, label(it)) }
    val chosenFirst = initial.map { AppEntry(it, label(it)) }
    val entries = (chosenFirst + recentApps + all.orEmpty()).distinctBy { it.packageName }
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }

    PickerDialog("APPS", onDismiss, { onDone(selected.toList()) }) {
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Search apps", color = Muted) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            item {
                CheckRow(RuleText.ANY_APP, "Every app", selected.isEmpty()) { selected = emptySet() }
            }
            items(entries, key = { it.packageName }) { app ->
                CheckRow(app.label, app.packageName, app.packageName in selected) {
                    selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
                }
            }
            if (all == null) item { Text("Loading apps…", color = Muted, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
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
