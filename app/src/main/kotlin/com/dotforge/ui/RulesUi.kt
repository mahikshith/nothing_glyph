package com.dotforge.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.FieldKind
import com.dotforge.filter.FilterActions
import com.dotforge.filter.FilterEngine
import com.dotforge.filter.Group
import com.dotforge.filter.Match
import com.dotforge.filter.Op
import com.dotforge.rules.Action
import com.dotforge.rules.Delivery
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RuleText
import com.dotforge.rules.Source
import com.dotforge.rules.SpamCatch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json

private const val TEST_ID = "rule-test"
private const val PICK_ID = "glyph-pick"

private val SAMPLE = NotificationInfo("preview", "", "Messages", "Alex", "Are we still on for tonight?", 0, fromPerson = true, sender = "Alex")

private fun animationFor(rule: Rule, sample: NotificationInfo): Animation =
    RuleEngine.animation(rule, sample, AppGraph.profile, AppGraph::designAnimation)

private val ruleJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** Ready-made starting points: "Make a rule from this", New call rule, New spam rule. */
object RuleTemplates {
    fun encode(r: Rule): String = ruleJson.encodeToString(Rule.serializer(), r)
    fun decode(s: String?): Rule? = s?.let { runCatching { ruleJson.decodeFromString(Rule.serializer(), it) }.getOrNull() }

    /** The number without its last four digits: "Block numbers starting with …". */
    fun prefixOf(number: String): String = if (number.count { it.isDigit() } > 7) number.dropLast(4) else number

    private fun who(sender: String, number: String?): List<Condition> = when {
        !number.isNullOrBlank() -> listOf(Condition(Field.NUMBER, Op.STARTS, listOf(prefixOf(number))))
        sender.isNotBlank() -> listOf(Condition(Field.SENDER, Op.STARTS, listOf(sender.trim())))
        else -> emptyList()
    }

    /** From a recent notification: its app, its sender as a starts-with pattern. */
    fun from(info: NotificationInfo, spam: Boolean) = Rule(
        id = java.util.UUID.randomUUID().toString(), name = "",
        source = Source.APPS, packages = listOfNotNull(info.packageName.ifBlank { null }),
        match = Match(conditions = who(info.sender, info.number)),
        action = if (spam) Action.SPAM else Action.SHOW,
    )

    /** From a Spam log entry: the app (or calls), the sender as a starts-with pattern. */
    fun from(c: SpamCatch) = Rule(
        id = java.util.UUID.randomUUID().toString(), name = "",
        source = if (c.call) Source.CALLS else Source.APPS,
        packages = if (c.call) emptyList() else listOfNotNull(c.packageName.ifBlank { null }),
        match = Match(conditions = who(c.sender, c.number.ifBlank { null })),
        action = if (c.call) Action.REJECT else Action.SPAM,
    )

    fun newCallRule() = Rule(id = java.util.UUID.randomUUID().toString(), name = "", source = Source.CALLS, action = Action.SILENCE)
    fun newSpamRule() = Rule(id = java.util.UUID.randomUUID().toString(), name = "", source = Source.EVERYTHING, action = Action.SPAM)
}

/** Presets fill in the app and its useful field; nothing more. */
private data class SourcePreset(val name: String, val packages: List<String>, val condition: Condition?)

private val SOURCE_PRESETS = listOf(
    SourcePreset("SMS", listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.nothing.messaging"), Condition(Field.NUMBER, Op.STARTS)),
    SourcePreset("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), Condition(Field.SENDER, Op.IS)),
    SourcePreset("Gmail", listOf("com.google.android.gm"), Condition(Field.EMAIL, Op.ENDS)),
    SourcePreset("Telegram", listOf("org.telegram.messenger"), Condition(Field.SENDER, Op.IS)),
)

private sealed interface Editing {
    /** A top-level condition ([index] -1 = new). */
    data class Top(val index: Int) : Editing
    /** A condition inside group [group] ([index] -1 = new). */
    data class InGroup(val group: Int, val index: Int) : Editing
}

private enum class RuleSlot { SOURCE, ACTION, GLYPH, UNTIL, SOUND, GROUP }

/**
 * The rule editor, the same for notification, call and spam rules: the rule as a sentence ("When [source] and
 * [condition] ..., [action]"), Add condition (field, then operator, then value), and advanced options (groups,
 * priority) folded away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorPage(ruleId: String?, template: String?, setup: Setup, onClose: () -> Unit) {
    val repo = AppGraph.rules
    val profile = AppGraph.profile
    val label = rememberAppLabel()
    val initial = remember { repo.rules.value.firstOrNull { it.id == ruleId } }
    val isNew = initial == null
    val start = remember { initial ?: RuleTemplates.decode(template) ?: Rule(name = "") }
    var draft by remember { mutableStateOf(start) }
    var slot by rememberSaveable { mutableStateOf<RuleSlot?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var groupIndex by remember { mutableStateOf(-1) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberToggle(start.match.groups.isNotEmpty() || start.priority != 0)
    val log by AppGraph.log.collectAsStateWithLifecycle()
    val lists by repo.lists.collectAsStateWithLifecycle()
    val calls = draft.source != Source.APPS
    val apps = draft.source != Source.CALLS
    val ctx = LocalContext.current
    val phone = remember { FilterActions.conditions(ctx) }

    val matches = remember(draft, log, lists) {
        if (!apps) emptyList() else log.map { it.info }.filter { info ->
            val s = Delivery.subject(info, AppGraph.filters.filters.value, { phone }, { null })
            RuleEngine.explain(draft, s, lists) != null
        }.take(5)
    }
    val sample = matches.firstOrNull() ?: SAMPLE
    val animation = remember(draft.glyph, draft.designId, sample) { animationFor(draft, sample) }
    val frame = rememberLoopingFrame(animation)

    fun close() = if (draft != start) confirmLeave = true else onClose()
    fun save() {
        val clean = draft.copy(
            match = draft.match.copy(
                conditions = draft.match.conditions.filter { it.isComplete },
                groups = draft.match.groups.map { g -> g.copy(conditions = g.conditions.filter { it.isComplete }) }.filter { it.conditions.isNotEmpty() },
            ),
        )
        val name = clean.name.trim().ifEmpty {
            clean.match.allConditions.firstOrNull()?.let { FilterEngine.describe(it, label) }?.replaceFirstChar { it.uppercase() }
                ?: RuleText.source(clean, label)
        }
        repo.upsert(clean.copy(name = name.take(60)))
        if (clean.enabled) {
            if (clean.source != Source.CALLS) setup.needNotificationAccess()
            if (clean.source != Source.APPS) setup.needCallScreening()
            if (clean.action == Action.SILENCE || clean.action == Action.SPAM) setup.needPostNotifications()
        }
        onClose()
    }
    fun setMatch(m: Match) {
        draft = draft.copy(match = m)
    }

    PageScaffold(
        if (isNew) "NEW RULE" else "EDIT RULE",
        onBack = ::close,
        actions = { if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete rule") } },
        bottomBar = { PrimaryButton("Save", Modifier.fillMaxWidth(), onClick = ::save) },
    ) {
        if (draft.action == Action.SHOW) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MatrixView(frame?.pixels, profile, Modifier.fillMaxWidth(0.42f).padding(top = 8.dp))
            }
            TextButton(
                onClick = {
                    AppGraph.arbiter.post(DisplayRequest(TEST_ID, Priority.STUDIO_PREVIEW, animation, loop = true, timeoutMs = 5_000, label = "Rule test"))
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Try on the matrix", color = Color.White) }
        }

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
            SlotChip(RuleText.source(draft, label)) { slot = RuleSlot.SOURCE }
            val joiner = if (draft.match.all) "and" else "or"
            draft.match.conditions.forEachIndexed { i, c ->
                Word(if (i == 0) "and" else joiner)
                SlotChip(FilterEngine.describe(c, label)) { editing = Editing.Top(i) }
            }
            draft.match.groups.forEachIndexed { i, g ->
                Word(if (i == 0 && draft.match.conditions.isEmpty()) "and" else joiner)
                SlotChip(FilterEngine.describe(g, label)) { groupIndex = i; slot = RuleSlot.GROUP }
            }
            Word(",")
            SlotChip(draft.action.label, accent = true) { slot = RuleSlot.ACTION }
            if (draft.action == Action.SHOW) {
                SlotChip(if (draft.glyph == GlyphStyle.NONE) "no glyph" else RuleText.glyph(draft, ::designName)) { slot = RuleSlot.GLYPH }
                if (draft.glyph != GlyphStyle.NONE) {
                    Word("until")
                    SlotChip(draft.until.label) { slot = RuleSlot.UNTIL }
                }
                Word("with")
                SlotChip(RuleText.sound(draft) ?: "no sound") { slot = RuleSlot.SOUND }
                SlotChip(if (draft.vibrate) "vibration" else "no vibration") { draft = draft.copy(vibrate = !draft.vibrate) }
            }
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Add condition") { editing = Editing.Top(-1) }
            if (draft.match.conditions.size + draft.match.groups.size > 1) {
                listOf(true to "All", false to "Any").forEach { (all, name) ->
                    FilterChip(
                        selected = draft.match.all == all, onClick = { setMatch(draft.match.copy(all = all)) }, label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface2, selectedLabelColor = NothingRed),
                    )
                }
            }
        }
        if (draft.match.conditions.size + draft.match.groups.size > 1) {
            Text(if (draft.match.all) "All conditions must hold." else "Any one condition is enough.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (draft.usesContacts) ContactsAsk(setup, "Allow Contacts", "Needed to know who is a contact.", Modifier.padding(top = 12.dp))
        if (apps && draft.action != Action.SHOW && draft.action != Action.PROGRESS) {
            Text(
                "The message itself stays in its app (Messages, WhatsApp, ...). DotForge only changes the notification, its sound and the glyph.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }

        Collapsible("Advanced", advanced, { advanced = !advanced }) {
            Text("Groups", modifier = Modifier.padding(bottom = 4.dp))
            Text("A group holds its own All or Any conditions, e.g. (sender is A or sender is B).", color = Muted, fontSize = 13.sp)
            TextButton(onClick = {
                setMatch(draft.match.copy(groups = draft.match.groups + Group(all = false)))
                groupIndex = draft.match.groups.size - 1
                slot = RuleSlot.GROUP
            }) { Text("Add group", color = Color.White) }
            if (draft.action == Action.SHOW) {
                Text("Priority", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
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
        }

        if (apps) {
            GroupTitle("WOULD MATCH")
            if (matches.isEmpty()) {
                Text("No recent notifications match.", color = Muted)
                Text("New ones that match will show here.", color = Muted, fontSize = 13.sp)
            }
            matches.forEach { n ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${n.appLabel} · ${n.sender.ifBlank { n.title }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(n.text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    editing?.let { e ->
        val current = when (e) {
            is Editing.Top -> draft.match.conditions.getOrNull(e.index)
            is Editing.InGroup -> draft.match.groups.getOrNull(e.group)?.conditions?.getOrNull(e.index)
        }
        ConditionDialog(
            current, calls = calls, apps = apps, lists = lists.map { it.name },
            onDismiss = { editing = null },
            onRemove = if (current == null) null else {
                {
                    setMatch(edit(draft.match, e, null))
                    editing = null
                }
            },
        ) { c ->
            setMatch(edit(draft.match, e, c))
            editing = null
        }
    }
    when (slot) {
        RuleSlot.SOURCE -> SourceDialog(draft, onDismiss = { slot = null }) { r ->
            draft = if (r.action.availableFor(r.source)) r else r.copy(action = if (r.source == Source.APPS) Action.SHOW else Action.SILENCE)
            slot = null
        }
        RuleSlot.ACTION -> AlertDialog(
            onDismissRequest = { slot = null },
            containerColor = Surface2,
            title = { Text("Then") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action.entries.filter { it.availableFor(draft.source) }.forEach { a ->
                        ChoiceCard(a.label.replaceFirstChar { it.uppercase() }, a.hint, a == draft.action, Modifier.fillMaxWidth()) {
                            draft = draft.copy(action = a)
                            slot = null
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { slot = null }) { Text("Cancel", color = Color.White) } },
        )
        RuleSlot.GLYPH -> GlyphDialog(draft, sample, onDismiss = { slot = null }) { draft = it; slot = null }
        RuleSlot.UNTIL -> UntilDialog(draft.until, onDismiss = { slot = null }) { draft = draft.copy(until = it); slot = null }
        RuleSlot.SOUND -> SoundDialog(draft.soundUri, onDismiss = { slot = null }) { uri, name ->
            draft = draft.copy(soundUri = uri, soundName = name)
            slot = null
        }
        RuleSlot.GROUP -> draft.match.groups.getOrNull(groupIndex)?.let { g ->
            GroupDialog(
                g, label,
                onEdit = { i -> editing = Editing.InGroup(groupIndex, i) },
                onChange = { ng -> setMatch(draft.match.copy(groups = draft.match.groups.toMutableList().also { it[groupIndex] = ng })) },
                onRemove = {
                    setMatch(draft.match.copy(groups = draft.match.groups.filterIndexed { i, _ -> i != groupIndex }))
                    slot = null
                },
                onDismiss = { slot = null },
            )
        } ?: run { slot = null }
        null -> Unit
    }
    if (confirmLeave) ConfirmDialog("Discard changes?", "This rule hasn't been saved.", "Discard", onConfirm = onClose, onDismiss = { confirmLeave = false })
    if (confirmDelete) ConfirmDialog("Delete this rule?", "\"${start.name}\" will stop working.", "Delete", onConfirm = {
        repo.delete(start.id)
        onClose()
    }, onDismiss = { confirmDelete = false })
}

/** [m] with the condition at [e] replaced by [c], added (index -1) or removed ([c] null). */
private fun edit(m: Match, e: Editing, c: Condition?): Match {
    fun apply(list: List<Condition>, index: Int): List<Condition> = when {
        index < 0 -> if (c != null) list + c else list
        c == null -> list.filterIndexed { i, _ -> i != index }
        else -> list.toMutableList().also { it[index] = c }
    }
    return when (e) {
        is Editing.Top -> m.copy(conditions = apply(m.conditions, e.index))
        is Editing.InGroup -> m.copy(groups = m.groups.mapIndexed { i, g -> if (i == e.group) g.copy(conditions = apply(g.conditions, e.index)) else g })
    }
}

/** Where a rule applies: any app, chosen apps, phone calls, both, or a preset (SMS, WhatsApp, Gmail, Telegram). */
@Composable
private fun SourceDialog(rule: Rule, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
    var pickApps by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Applies to") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard("Any app", "Every notification", rule.source == Source.APPS && rule.packages.isEmpty(), Modifier.fillMaxWidth()) {
                    onDone(rule.copy(source = Source.APPS, packages = emptyList()))
                }
                ChoiceCard("Some apps", "Pick one or more", rule.source == Source.APPS && rule.packages.isNotEmpty(), Modifier.fillMaxWidth()) { pickApps = true }
                ChoiceCard(RuleText.CALLS, "Incoming calls (DotForge screens them)", rule.source == Source.CALLS, Modifier.fillMaxWidth()) {
                    onDone(rule.copy(source = Source.CALLS, packages = emptyList()))
                }
                ChoiceCard(RuleText.EVERYTHING, "Calls and every notification", rule.source == Source.EVERYTHING, Modifier.fillMaxWidth()) {
                    onDone(rule.copy(source = Source.EVERYTHING, packages = emptyList()))
                }
                Text("Presets", modifier = Modifier.padding(top = 8.dp))
                Text("Fill in the app and its most useful field.", color = Muted, fontSize = 13.sp)
                SOURCE_PRESETS.forEach { p ->
                    ChoiceCard(p.name, p.condition?.field?.label?.let { "Then pick the $it" } ?: "", false, Modifier.fillMaxWidth()) {
                        val add = p.condition?.takeIf { c -> rule.match.allConditions.none { it.field == c.field } }
                        onDone(rule.copy(source = Source.APPS, packages = p.packages, match = rule.match.copy(conditions = rule.match.conditions + listOfNotNull(add))))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
    if (pickApps) AppsDialog(rule.packages, onDismiss = { pickApps = false }) { onDone(rule.copy(source = Source.APPS, packages = it)) }
}

/** A nested group: All or Any of its conditions, optionally negated. */
@Composable
private fun GroupDialog(
    g: Group,
    label: (String) -> String,
    onEdit: (Int) -> Unit,
    onChange: (Group) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Group") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "All", false to "Any").forEach { (all, name) ->
                        FilterChip(
                            selected = g.all == all, onClick = { onChange(g.copy(all = all)) }, label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface1, selectedLabelColor = NothingRed),
                        )
                    }
                }
                g.conditions.forEachIndexed { i, c ->
                    Text(FilterEngine.describe(c, label), modifier = Modifier.fillMaxWidth().clickable { onEdit(i) }.padding(vertical = 12.dp))
                }
                TextButton(onClick = { onEdit(-1) }) { Text("Add condition", color = Color.White) }
                SwitchRow("Not", "Matches when the group doesn't", g.not) { onChange(g.copy(not = it)) }
                TextButton(onClick = onRemove) { Text("Remove group", color = NothingRed) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = NothingRed) } },
    )
}

private val FIELD_GROUPS = listOf(
    "Who" to listOf(Field.SENDER, Field.NUMBER, Field.EMAIL, Field.CONTACT, Field.STARRED),
    "Message" to listOf(Field.MESSAGE, Field.TITLE, Field.TEXT, Field.BIG_TEXT, Field.SUB_TEXT, Field.SUMMARY),
    "App" to listOf(Field.APP, Field.CHANNEL, Field.CATEGORY, Field.GROUP_CHAT, Field.ONGOING),
    "Call" to listOf(Field.REPEATED, Field.HIDDEN, Field.UNVERIFIED, Field.SPAM_LIST),
    "When" to listOf(Field.TIME, Field.DAY, Field.QUIET, Field.LOCKED, Field.DND, Field.CHARGING),
)

/**
 * Add or change a condition: a field, then an operator, then a value (several values mean any of them, and a
 * named list can add its entries). Regex and Not are under More.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionDialog(
    initial: Condition?,
    calls: Boolean,
    apps: Boolean,
    lists: List<String>,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)?,
    onDone: (Condition) -> Unit,
) {
    var field by remember { mutableStateOf(initial?.field) }
    var c by remember { mutableStateOf(initial ?: Condition()) }
    var more by remember { mutableStateOf(initial?.op == Op.REGEX || initial?.not == true) }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface1, selectedLabelColor = NothingRed)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(if (field == null) "Pick a field" else "When ${field!!.label}…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val f = field
                if (f == null) {
                    FIELD_GROUPS.forEach { (group, fields) ->
                        val usable = fields.filter { it.availableFor(calls, apps) }
                        if (usable.isNotEmpty()) {
                            Text(group, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                usable.forEach { fl ->
                                    FilterChip(selected = false, label = { Text(fl.label) }, colors = chip, onClick = {
                                        field = fl
                                        c = when (fl.kind) {
                                            FieldKind.FLAG -> Condition.flag(fl)
                                            FieldKind.TIME -> Condition(fl, Op.IS, listOf("22:00-07:00"))
                                            FieldKind.DAYS -> Condition(fl, Op.IS, (1..5).map { it.toString() })
                                            else -> Condition(fl, if (fl == Field.NUMBER) Op.STARTS else Op.CONTAINS)
                                        }
                                    })
                                }
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { field = null }) { Text("Change field", color = Color.White) }
                    when (f.kind) {
                        FieldKind.FLAG -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val yes = c.cleanValues.firstOrNull()?.lowercase() != "no"
                            ChoiceCard(f.yes.replaceFirstChar { it.uppercase() }, "", yes, Modifier.fillMaxWidth()) { c = c.copy(values = listOf("yes")) }
                            ChoiceCard(f.no.replaceFirstChar { it.uppercase() }, "", !yes, Modifier.fillMaxWidth()) { c = c.copy(values = listOf("no")) }
                        }
                        FieldKind.TIME -> {
                            val v = c.cleanValues.firstOrNull().orEmpty()
                            OutlinedTextField(
                                v, { c = c.copy(values = listOf(it)) }, singleLine = true,
                                label = { Text("From–to, e.g. 22:00-07:00") },
                                isError = FilterEngine.parseRange(v) == null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        FieldKind.DAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                val on = d.value.toString() in c.cleanValues
                                FilterChip(
                                    selected = on, colors = chip,
                                    label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                                    onClick = { c = c.copy(values = if (on) c.cleanValues - d.value.toString() else c.cleanValues + d.value.toString()) },
                                )
                            }
                        }
                        FieldKind.TEXT, FieldKind.NUMBER -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Op.forKind(f.kind).filter { !it.advanced || more || c.op == it }.forEach { op ->
                                    FilterChip(selected = c.op == op, colors = chip, label = { Text(op.label) }, onClick = { c = c.copy(op = op) })
                                }
                            }
                            if (c.op != Op.IN_LIST) {
                                ChipsEditor(
                                    c.values, { c = c.copy(values = it) },
                                    when {
                                        c.op == Op.REGEX -> "Add a pattern"
                                        f == Field.NUMBER -> "Add a number, e.g. +44 or 0800"
                                        f == Field.APP -> "Add an app name or package"
                                        else -> "Add a value"
                                    },
                                    validate = { if (c.op == Op.REGEX && !RuleEngine.isValidPattern(it)) "Not a valid pattern" else null },
                                )
                                if (c.cleanValues.size > 1) {
                                    Text(if (c.op == Op.IS_NOT || c.op == Op.NOT_CONTAINS) "None of these." else "Any of these.", color = Muted, fontSize = 13.sp)
                                }
                            }
                            if (lists.isNotEmpty()) {
                                Text(if (c.op == Op.IN_LIST) "List" else "Also use a list", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    lists.forEach { name ->
                                        val on = c.list.equals(name, ignoreCase = true)
                                        FilterChip(selected = on, colors = chip, label = { Text(name) }, onClick = { c = c.copy(list = if (on) null else name) })
                                    }
                                }
                            } else if (c.op == Op.IN_LIST) {
                                Text("No lists yet. Make one on the Spam page.", color = Muted, fontSize = 13.sp)
                            }
                            if (f == Field.NUMBER) {
                                Text("Numbers are compared in any format: +91 98…, 0091 98… and 098… are the same.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    Collapsible("More", more, { more = !more }) {
                        SwitchRow("Not", "Matches when this condition doesn't", c.not) { c = c.copy(not = it) }
                    }
                    onRemove?.let { TextButton(onClick = it) { Text("Remove condition", color = NothingRed) } }
                }
            }
        },
        confirmButton = {
            val ok = field != null && c.isComplete && (c.field.kind != FieldKind.TIME || FilterEngine.parseRange(c.cleanValues.firstOrNull().orEmpty()) != null)
            TextButton(onClick = { onDone(c.copy(field = field!!)) }, enabled = ok) { Text("Done", color = if (ok) NothingRed else Muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

/** A sound: none, one of the user's compositions, or a system sound (Android's picker). */
@Composable
internal fun SoundDialog(current: String?, onDismiss: () -> Unit, onPick: (String?, String?) -> Unit) {
    val ctx = LocalContext.current
    val ringtones by AppGraph.ringtones.items.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (ringtones.isEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppGraph.ringtones.refresh() } }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        if (uri == null) onPick(null, null)
        else onPick(uri.toString(), runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull() ?: "System sound")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Sound") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard("No sound", "Only the glyph", current == null, Modifier.fillMaxWidth()) { onPick(null, null) }
                ChoiceCard("System sound", "Android's notification sounds", current?.startsWith("content://settings") == true, Modifier.fillMaxWidth()) {
                    runCatching {
                        system.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse)),
                        )
                    }
                }
                if (ringtones.isNotEmpty()) Text("Your compositions", modifier = Modifier.padding(top = 8.dp))
                ringtones.forEach { r ->
                    ChoiceCard(r.title, "Plays for at most 8 s", current == r.uri.toString(), Modifier.fillMaxWidth()) { onPick(r.uri.toString(), r.title) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

@Composable
internal fun Word(text: String) = Text(text, fontSize = 18.sp)

@Composable
internal fun SlotChip(text: String, accent: Boolean = false, onClick: () -> Unit) {
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
internal fun SlotDialog(title: String, onDismiss: () -> Unit, onDone: () -> Unit, content: @Composable () -> Unit) =
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
internal fun PickerDialog(title: String, onDismiss: () -> Unit, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
internal fun UntilDialog(current: Until, onDismiss: () -> Unit, onPick: (Until) -> Unit) =
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
internal fun GlyphDialog(rule: Rule, sample: NotificationInfo, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
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

/** Full-screen app picker: recent notification apps first, then every launcher app. */
@Composable
internal fun AppsDialog(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
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
internal fun CheckRow(title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
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
