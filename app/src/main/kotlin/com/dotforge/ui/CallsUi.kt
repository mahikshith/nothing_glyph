package com.dotforge.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.calls.CallerGlyph
import com.dotforge.calls.CallerGlyphs
import com.dotforge.calls.CallerLookup
import com.dotforge.calls.GlyphTarget
import com.dotforge.ringtones.RingtoneActions
import com.dotforge.rules.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Calls page: call rules (the same rules and editor as notifications, for phone calls), caller glyphs,
 * options and the blocked calls. The spam filter, lists and spam list are on the Spam page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallsPage(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val repo = AppGraph.calls
    val all by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val rules = all.filter { it.source != Source.APPS }
    val glyphs by repo.glyphs.collectAsStateWithLifecycle()
    val catches by AppGraph.filters.catches.collectAsStateWithLifecycle()
    val calls = catches.filter { it.call }
    val settings by repo.settings.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    val scope = rememberCoroutineScope()
    var glyphEdit by remember { mutableStateOf<CallerGlyph?>(null) }
    var repeatDialog by rememberSaveable { mutableStateOf(false) }
    fun newRule() = nav.push(Page.RuleEditor(null, RuleTemplates.encode(RuleTemplates.newCallRule())))

    PageScaffold("CALLS", onBack = nav::pop, actions = {
        IconButton(onClick = ::newRule) { Icon(Icons.Filled.Add, "New call rule") }
    }) {
        if (!setup.screening && rules.any { it.enabled }) {
            Surface(color = Surface2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Call rules need DotForge as the call screening app.", Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { setup.needCallScreening() }) { Text("Allow", color = NothingRed) }
                }
            }
        }
        if (rules.any { it.enabled && it.usesContacts }) {
            ContactsAsk(setup, "Allow Contacts", "Android only lets DotForge see calls from your contacts with it, and contact rules need it.")
        }

        GroupTitle("CALL RULES")
        if (rules.isEmpty()) {
            EmptyState("No call rules", "Silence, reject or always allow callers.", "New call rule", ::newRule)
        }
        rules.forEach { r -> RuleCard(r, label, nav, setup) }
        Text(
            "Allow rules come first (contacts, repeated callers, your allowed list). Then the most specific rule " +
                "that silences or rejects wins. Calls no rule stops ring as usual.",
            color = Muted, fontSize = 13.sp,
        )
        SecondaryButton("New call rule", Modifier.fillMaxWidth().padding(top = 12.dp), ::newRule)
        ValueRow("Spam filter", "Lists, spam list, alert", subtitle = "Shared by calls and messages") { nav.push(Page.Spam) }

        GroupTitle("CALLER GLYPHS")
        if (glyphs.isEmpty()) {
            EmptyState("No caller glyphs", "Pick a composition for a person or a group.", "Add caller glyph") { glyphEdit = CallerGlyph() }
        } else {
            glyphs.forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { glyphEdit = g }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("When ${g.name.ifBlank { "someone" }} calls, ring with ${g.ringtoneName}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (g.applied == 1) "Set for 1 contact" else "Set for ${g.applied} contacts",
                            color = if (g.applied == 0) NothingRed else Muted, fontSize = 13.sp,
                        )
                    }
                }
            }
            SecondaryButton("Add caller glyph", Modifier.fillMaxWidth().padding(top = 8.dp)) { glyphEdit = CallerGlyph() }
        }

        GroupTitle("OPTIONS")
        ValueRow("Repeated callers", "Twice in ${settings.repeatMinutes} min", subtitle = "A second call this soon counts as urgent") { repeatDialog = true }
        SwitchRow("Glyph for screened calls", "A short glyph when a call is silenced or rejected", settings.screenedGlyph) { on ->
            repo.updateSettings { it.copy(screenedGlyph = on) }
        }
        SwitchRow("Missed call glyph", "Repeats on the matrix until you unlock", settings.missedGlyph) { on ->
            repo.updateSettings { it.copy(missedGlyph = on) }
            if (on) setup.needNotificationAccess()
        }

        GroupTitle("BLOCKED CALLS")
        if (calls.isEmpty()) {
            EmptyState("No blocked calls", "Silenced and rejected calls show here.")
        } else {
            calls.take(10).forEach { c -> SpamLogRow(c, nav) }
        }
    }

    glyphEdit?.let { g ->
        CallerGlyphDialog(g, setup, onDismiss = { glyphEdit = null }, onDelete = {
            scope.launch(Dispatchers.IO) { CallerGlyphs.clear(ctx, g) }
            repo.deleteGlyph(g.id)
            glyphEdit = null
        }) { saved ->
            glyphEdit = null
            scope.launch {
                val n = withContext(Dispatchers.IO) { CallerGlyphs.apply(ctx, saved) }
                repo.upsertGlyph(saved.copy(applied = n))
                Toast.makeText(ctx, if (n == 0) "Couldn't set the ringtone." else if (n == 1) "Set for 1 contact." else "Set for $n contacts.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (repeatDialog) {
        AlertDialog(
            onDismissRequest = { repeatDialog = false },
            containerColor = Surface2,
            title = { Text("Repeated callers") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 15).forEach { m ->
                        ChoiceCard("Twice in $m minutes", "The second call counts as a repeated caller", settings.repeatMinutes == m, Modifier.fillMaxWidth()) {
                            repo.updateSettings { it.copy(repeatMinutes = m) }
                            repeatDialog = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { repeatDialog = false }) { Text("Cancel", color = Color.White) } },
        )
    }
}

/**
 * A caller glyph: a contact (picked with Android's contact picker) or a Contacts group, and one of the user's
 * compositions. Changing contacts' ringtones needs WRITE_CONTACTS, asked here with Not now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallerGlyphDialog(initial: CallerGlyph, setup: Setup, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (CallerGlyph) -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(initial) }
    val ringtones by AppGraph.ringtones.items.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var canWrite by remember { mutableStateOf(RingtoneActions.canWriteContacts(ctx)) }
    var notNow by remember { mutableStateOf(false) }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canWrite = it }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) g = g.copy(target = GlyphTarget.CONTACT, key = uri.toString(), name = contactName(ctx, uri) ?: "Contact")
    }
    LaunchedEffect(setup.contacts) {
        groups = withContext(Dispatchers.IO) { CallerLookup(ctx).groupTitles() }
        if (ringtones.isEmpty()) withContext(Dispatchers.IO) { AppGraph.ringtones.refresh() }
    }
    val ready = g.key.isNotBlank() && g.ringtoneUri.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Caller glyph") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When ${g.name.ifBlank { "…" }} calls, the phone rings with the composition and its lights.", color = Muted, fontSize = 13.sp)
                Text("Who", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface1, selectedLabelColor = NothingRed)
                    FilterChip(
                        selected = g.target == GlyphTarget.CONTACT && g.key.isNotBlank(), colors = chip,
                        label = { Text(if (g.target == GlyphTarget.CONTACT && g.key.isNotBlank()) g.name else "Pick a contact") },
                        onClick = { runCatching { pick.launch(null) } },
                    )
                    groups.forEach { t ->
                        FilterChip(
                            selected = g.target == GlyphTarget.GROUP && g.key == t, colors = chip, label = { Text(t) },
                            onClick = { g = g.copy(target = GlyphTarget.GROUP, key = t, name = "someone in $t") },
                        )
                    }
                }
                if (!setup.contacts) Text("Allow Contacts to pick a group.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Composition", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (ringtones.isEmpty()) {
                    Text("No compositions yet.", color = Muted)
                    Text("Make or import one on the Ringtones page.", color = Muted, fontSize = 13.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ringtones.forEach { r ->
                        ChoiceCard(r.title, clock(r.durationMs), g.ringtoneUri == r.uri.toString(), Modifier.fillMaxWidth()) {
                            g = g.copy(ringtoneUri = r.uri.toString(), ringtoneName = r.title)
                        }
                    }
                }
                if (!canWrite && !notNow) {
                    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Allow editing Contacts", fontWeight = FontWeight.Medium)
                            Text("DotForge sets the ringtone on the contact; nothing else changes.", color = Muted, fontSize = 13.sp)
                            Row {
                                TextButton(onClick = { writeLauncher.launch(Manifest.permission.WRITE_CONTACTS) }) { Text("Allow", color = NothingRed) }
                                TextButton(onClick = { notNow = true }) { Text("Not now", color = Color.White) }
                            }
                        }
                    }
                }
                if (initial.key.isNotBlank()) {
                    TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.Delete, null, tint = NothingRed)
                        Text("Remove and restore the default ringtone", color = NothingRed)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(g) }, enabled = ready && canWrite) { Text("Save", color = if (ready && canWrite) NothingRed else Muted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

private fun contactName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
