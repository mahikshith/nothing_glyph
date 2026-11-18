package com.dotforge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.calls.SpamListStore
import com.dotforge.calls.SpamListWorker
import com.dotforge.filter.Field
import com.dotforge.filter.FilterActions
import com.dotforge.filter.NamedList
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SpamCatch
import com.dotforge.rules.SpamFilter
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val spamTime = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

private val SPAM_SAMPLE = NotificationInfo("spam-preview", "", "Spam", "+1 555 0100", "", 0, sender = "+1 555 0100")

/**
 * The spam filter for calls and messages: its rules (checked before every other rule), the named lists rules
 * share, the spam alert's glyph and sound, the community spam list and the Spam log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpamPage(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val repo = AppGraph.rules
    val all by repo.rules.collectAsStateWithLifecycle()
    val lists by repo.lists.collectAsStateWithLifecycle()
    val catches by AppGraph.filters.catches.collectAsStateWithLifecycle()
    val alert by AppGraph.filters.spam.collectAsStateWithLifecycle()
    val status by AppGraph.spamList.status.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    val rules = all.filter(RuleEngine::isSpamRule)
    val missing = remember(all) { repo.missingPresets().filter(RuleEngine::isSpamRule) }
    val listOn = rules.any { r -> r.enabled && r.match.allConditions.any { it.field == Field.SPAM_LIST } }
    var listEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var glyphDialog by rememberSaveable { mutableStateOf(false) }
    var soundDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AppGraph.spamList.list() } }
    fun newRule() = nav.push(Page.RuleEditor(null, RuleTemplates.encode(RuleTemplates.newSpamRule())))

    PageScaffold("SPAM", onBack = nav::pop, actions = {
        IconButton(onClick = ::newRule) { Icon(Icons.Filled.Add, "New spam rule") }
    }) {
        AccessBanner(setup, "The spam filter for messages needs notification access.")
        Text(
            "Spam rules run before every other rule, for calls and for any app's notifications. A message stays in " +
                "its app (Messages, WhatsApp, ...): DotForge only silences or hides the notification.",
            color = Muted, fontSize = 13.sp,
        )
        if (rules.any { it.enabled && it.usesContacts }) {
            ContactsAsk(setup, "Allow Contacts", "Lets the spam filter always allow people you know.", Modifier.padding(top = 8.dp))
        }
        Text("Blocked today: ${SpamFilter.blockedOn(LocalDate.now(), catches)}", modifier = Modifier.padding(top = 8.dp))

        GroupTitle("SPAM RULES")
        if (rules.isEmpty()) {
            EmptyState("No spam rules", "Start from the presets, or make your own.", "Add presets") { repo.addPresets(missing) }
        }
        rules.forEach { r -> RuleCard(r, label, nav, setup) }
        if (missing.isNotEmpty() && rules.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("Add", color = Muted)
                missing.forEach { p -> FilterChip(selected = false, onClick = { repo.addPresets(listOf(p)) }, label = { Text(p.name) }) }
            }
        }
        SecondaryButton("New spam rule", Modifier.fillMaxWidth().padding(top = 12.dp), ::newRule)

        GroupTitle("LISTS")
        Text("Senders, numbers or words that several rules share. Matching ignores case.", color = Muted, fontSize = 13.sp)
        lists.forEach { l ->
            ValueRow(l.name, if (l.entries.size == 1) "1 entry" else "${l.entries.size} entries") { listEdit = l.name }
        }
        TextButton(onClick = { listEdit = "" }) { Text("New list", color = Color.White) }

        GroupTitle("SPAM ALERT")
        SwitchRow("Spam glyph", "A short warning on the matrix", alert.glyphOn) { on -> AppGraph.filters.updateSpam { it.copy(glyphOn = on) } }
        if (alert.glyphOn) {
            ValueRow("Glyph", if (alert.glyph == GlyphStyle.DESIGN) designName(alert.designId) ?: alert.glyph.label else alert.glyph.label) { glyphDialog = true }
        }
        ValueRow("Sound", alert.soundName ?: "None", subtitle = "Silent by default") { soundDialog = true }
        TextButton(onClick = { FilterActions.spamAlert(ctx, "Test") }) { Text("Try it", color = Color.White) }

        GroupTitle("SPAM LIST")
        ValueRow(
            "Community spam list",
            if (status.count > 0) "%,d numbers".format(status.count) else "Not downloaded",
            subtitle = when {
                status.error != null -> status.error
                status.updated != null -> "Updated ${status.updated}. Refreshed weekly while a rule uses it."
                listOn -> "Downloads on the next connection."
                else -> "Turn on the Spam list rule to use it."
            },
            attention = status.error != null,
        )
        TextButton(onClick = {
            SpamListWorker.now(ctx)
            Toast.makeText(ctx, "Updating the spam list…", Toast.LENGTH_SHORT).show()
        }) { Text("Update now", color = Color.White) }
        Text("From ${SpamListStore.SOURCE}: mostly US numbers from FCC and FTC complaint data and community reports.", color = Muted, fontSize = 12.sp)

        GroupTitle("SPAM LOG")
        if (catches.isEmpty()) {
            EmptyState("Nothing caught yet", "Calls and messages your rules stop show here.")
        } else {
            catches.forEach { c -> SpamLogRow(c, nav) }
            TextButton(onClick = AppGraph.filters::clearCatches) { Text("Clear", color = Color.White) }
        }
    }

    listEdit?.let { name ->
        ListDialog(lists.firstOrNull { it.name == name }, lists.map { it.name }, onDismiss = { listEdit = null }, onDelete = {
            repo.deleteList(name)
            listEdit = null
        }) { l ->
            repo.putList(l, oldName = name.ifEmpty { null })
            listEdit = null
        }
    }
    if (glyphDialog) {
        GlyphDialog(Rule(name = "", glyph = alert.glyph, designId = alert.designId), SPAM_SAMPLE, onDismiss = { glyphDialog = false }) { r ->
            AppGraph.filters.updateSpam { it.copy(glyph = r.glyph, designId = r.designId, glyphOn = r.glyph != GlyphStyle.NONE) }
            glyphDialog = false
        }
    }
    if (soundDialog) {
        SoundDialog(alert.soundUri, onDismiss = { soundDialog = false }) { uri, name ->
            AppGraph.filters.updateSpam { it.copy(soundUri = uri, soundName = name) }
            soundDialog = false
        }
    }
}

/** One Spam log entry: who, what happened and why, with Allow this sender, Always block and Make a rule. */
@Composable
internal fun SpamLogRow(c: SpamCatch, nav: Nav) {
    val blocked by AppGraph.rules.lists.collectAsStateWithLifecycle()
    val key = c.senderKey
    val isBlocked = blocked.firstOrNull { it.name == RuleEngine.BLOCKED }?.entries?.any { it.equals(key, ignoreCase = true) } == true
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("${if (c.call) "Call" else c.appLabel} · ${spamTime.format(Date(c.at))}", color = Muted, fontSize = 12.sp)
        Text(c.title.ifBlank { c.sender }.ifBlank { "Hidden number" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.text.isNotBlank()) Text(c.text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(c.action.done, c.rule, c.why.ifBlank { null }).joinToString(" · "),
            color = NothingRed, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (key.isNotBlank()) {
                TextButton(onClick = { AppGraph.allowSender(c) }) { Text("Allow this sender", color = Color.White) }
                if (!isBlocked) TextButton(onClick = { AppGraph.blockSender(key) }) { Text("Always block", color = NothingRed) }
            }
            TextButton(onClick = { nav.push(Page.RuleEditor(null, RuleTemplates.encode(RuleTemplates.from(c)))) }) {
                Text("Make a rule", color = Color.White)
            }
        }
    }
}

/** A named list: its name and entries; renaming keeps the rules that use it. */
@Composable
private fun ListDialog(initial: NamedList?, names: List<String>, onDismiss: () -> Unit, onDelete: () -> Unit, onDone: (NamedList) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var entries by remember { mutableStateOf(initial?.entries.orEmpty()) }
    val taken = names.any { it.equals(name.trim(), ignoreCase = true) && !it.equals(initial?.name, ignoreCase = true) }
    val ok = name.isNotBlank() && !taken
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(if (initial == null) "New list" else "List") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("Name, e.g. VIP senders") },
                    isError = taken, supportingText = if (taken) ({ Text("There is a list with this name") }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipsEditor(entries, { entries = it }, "Add a sender, number or word")
                Text("Numbers match in any format.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (initial != null) TextButton(onClick = onDelete) { Text("Delete list", color = NothingRed) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(NamedList(name.trim(), entries)) }, enabled = ok) { Text("Done", color = if (ok) NothingRed else Muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}
