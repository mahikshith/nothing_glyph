package com.dotforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.rules.SpamFilter
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Spam filter: on/off, today's count, recent catches with Allow, the allow list and (Advanced) the words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpamPage(setup: Setup, onBack: () -> Unit) {
    val repo = AppGraph.spam
    val s by repo.settings.collectAsStateWithLifecycle()
    val catches by repo.catches.collectAsStateWithLifecycle()
    var advanced by rememberToggle()
    val today = SpamFilter.blockedOn(LocalDate.now(), catches)

    PageScaffold("SPAM FILTER", onBack = onBack) {
        SwitchRow("Spam filter", "Spam never lights up the Glyphs", s.enabled) { on ->
            repo.update { it.copy(enabled = on) }
            if (on) setup.needNotificationAccess()
        }
        if (s.enabled) AccessBanner(setup, "The spam filter needs notification access.")

        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            DotTitle(today.toString(), dot = 7.dp, color = if (today > 0) NothingRed else Color.White)
            Text("Blocked today", color = Muted, modifier = Modifier.padding(start = 16.dp))
        }

        GroupTitle("ALWAYS ALLOW")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = Surface2, selectedLabelColor = NothingRed)
            FilterChip(selected = s.allowContacts, colors = chipColors, label = { Text("Contacts") }, onClick = {
                val on = !s.allowContacts
                repo.update { it.copy(allowContacts = on) }
                if (on) setup.needContacts()
            })
            FilterChip(selected = s.allowStarred, colors = chipColors, label = { Text("Starred") }, onClick = {
                val on = !s.allowStarred
                repo.update { it.copy(allowStarred = on) }
                if (on) setup.needContacts()
            })
            s.allowedSenders.forEach { who ->
                InputChip(
                    selected = false,
                    onClick = { repo.update { it.copy(allowedSenders = it.allowedSenders - who) } },
                    label = { Text(who, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                )
            }
        }
        if (s.needsContacts && !setup.contacts) {
            TextButton(onClick = { setup.needContacts() }) { Text("Contacts permission needed", color = NothingRed) }
        }

        GroupTitle("RECENT CATCHES")
        if (catches.isEmpty()) {
            EmptyState("Nothing caught yet", "Blocked notifications show up here.", if (advanced) null else "Edit spam words") { advanced = true }
        }
        catches.take(20).forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${c.appLabel} · ${timeFormat.format(Date(c.at))}", color = Muted, fontSize = 12.sp)
                    Text(c.title.ifBlank { c.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SecondaryButton("Allow", Modifier.padding(start = 12.dp)) { repo.allow(c) }
            }
        }

        Collapsible("Advanced", advanced, { advanced = !advanced }) {
            Text("Spam words", modifier = Modifier.padding(bottom = 4.dp))
            Text("Any one of these in a notification blocks it.", color = Muted, fontSize = 13.sp)
            ChipsEditor(s.keywords, { list -> repo.update { it.copy(keywords = list) } }, "Add a word")
            Text("Patterns (regex)", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            ChipsEditor(
                s.patterns, { list -> repo.update { it.copy(patterns = list) } }, "Add a pattern",
                validate = { if (SpamFilter.isValidPattern(it)) null else "Not a valid pattern" },
            )
        }
    }
}
