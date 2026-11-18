package com.dotforge.ui.alerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dotforge.ui.design.Avatar
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.Tokens

// ---------------------------------------------------------------- days and times

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** Seven 40 dp single-letter day toggles in one row (1 = Monday); TalkBack reads the day's full name. */
@Composable
fun DayToggles(days: List<Int>, modifier: Modifier = Modifier, onChange: (List<Int>) -> Unit) {
    // the letters stay inside their circles at any font size
    val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val style = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = it.fontSize * (minOf(fs, 1.3f) / fs)) }
    Row(modifier.fillMaxWidth().padding(vertical = Tokens.Small), horizontalArrangement = Arrangement.SpaceBetween) {
        DAY_LETTERS.forEachIndexed { i, letter ->
            val day = i + 1
            val on = day in days
            Box(
                Modifier.size(Tokens.Target)
                    .toggleable(on, role = Role.Checkbox) { onChange(if (on) days - day else (days + day).sorted()) }
                    .semantics { contentDescription = DAY_NAMES[i] },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp).background(if (on) Color.White else Tokens.Raised, CircleShape), contentAlignment = Alignment.Center) {
                    Text(letter, style = style, color = if (on) Color.Black else Color.White)
                }
            }
        }
    }
}

/** "23:00" for minutes after midnight. */
fun hhmm(m: Int): String = "%02d:%02d".format(m / 60 % 24, m % 60)

/** A "From 23:00 ›" row that opens the Material time picker. */
@Composable
fun TimeRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListRow(label, subtitle = hhmm(minutes), trailing = { Chevron() }) { open = true }
    if (open) TimeDialog(label, minutes, onDismiss = { open = false }) { onChange(it); open = false }
}

/** The Material time picker in a dialog, 24-hour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60 % 24, initialMinute = minutes % 60, is24Hour = true)
    // the dial has a fixed size: its numbers grow with the font only up to 1.3x, so they never overlap
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, minOf(d.fontScale, 1.3f))) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Sheet,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Tokens.Raised, selectorColor = Tokens.Accent, timeSelectorSelectedContainerColor = Color.White,
                    timeSelectorSelectedContentColor = Color.Black, timeSelectorUnselectedContainerColor = Tokens.Raised,
                    timeSelectorUnselectedContentColor = Color.White, clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = Color.White,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
    }
}

// ---------------------------------------------------------------- fields

/**
 * A text field with its label always visible above it (never a placeholder alone). [helper] is grey help until
 * [showError]; then [error], when there is one, shows in red.
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    showError: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) = Column(modifier.fillMaxWidth().padding(top = Tokens.Small)) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(bottom = Tokens.Tiny))
    val bad = showError && error != null
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label }, singleLine = true,
        placeholder = placeholder?.let { { Text(it, color = Tokens.Secondary) } },
        isError = bad,
        supportingText = (if (bad) error else helper)?.let { t -> { Text(t, color = if (bad) Tokens.AccentText else Tokens.Secondary) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing,
    )
}

// ---------------------------------------------------------------- contacts and senders

/** A contact as the pickers list it. */
data class ContactEntry(val name: String, val numbers: List<String>)

/** Where the pickers read contacts from (screenshots pass a list); null reads the phone's Contacts. */
val LocalContacts = compositionLocalOf<List<ContactEntry>?> { null }

/** The phone's contacts with a number, by name; null when Contacts can't be read. */
@Composable
fun rememberContacts(): List<ContactEntry>? {
    LocalContacts.current?.let { return it }
    val ctx = LocalContext.current
    return remember { readContacts(ctx) }
}

private fun readContacts(ctx: Context): List<ContactEntry>? {
    if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    return runCatching {
        val byName = LinkedHashMap<String, MutableList<String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim().orEmpty()
                if (name.isNotEmpty()) byName.getOrPut(name) { ArrayList() } += c.getString(1).orEmpty()
            }
        }
        byName.map { (n, numbers) -> ContactEntry(n, numbers.filter { it.isNotBlank() }.distinct()) }
    }.getOrNull()
}

/**
 * People to pick from, recent senders first, then contacts, then what was typed: a search field whose Add
 * button takes the typed name, then checkbox rows ([multi]) or plain rows that pick at once. [recentTitle]
 * names the first section ("Recent in WhatsApp"); without Contacts, [onAllowContacts] offers to allow it.
 */
@Composable
fun PeoplePicker(
    recent: List<String>,
    contacts: List<ContactEntry>?,
    picked: Set<String>,
    multi: Boolean,
    recentTitle: String,
    onAllowContacts: (() -> Unit)?,
    onPick: (name: String, numbers: List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    fun hit(name: String) = q.isEmpty() || name.contains(q, ignoreCase = true)
    val known = recent.any { it.equals(q, ignoreCase = true) } || contacts.orEmpty().any { it.name.equals(q, ignoreCase = true) }
    LabeledField(
        "Search or type a name", query, { query = it }, placeholder = "Name",
        trailing = if (q.isNotEmpty() && !known) ({
            TextButton(onClick = { onPick(q, emptyList()); query = "" }, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Add", color = Color.White) }
        }) else ({ Icon(Icons.Filled.Search, contentDescription = null) }),
    )
    val shownRecent = recent.filter(::hit)
    if (shownRecent.isNotEmpty()) {
        SectionLabel(recentTitle)
        RowGroup { shownRecent.forEachIndexed { i, n -> if (i > 0) RowDivider(leading = true); PersonRow(n, null, n in picked, multi) { onPick(n, emptyList()) } } }
    }
    if (contacts == null) {
        if (onAllowContacts != null) StatusBanner("Allow Contacts to pick from them.", Modifier.padding(top = Tokens.Gap), button = "Allow", onClick = onAllowContacts)
    } else {
        val shown = contacts.filter { hit(it.name) && recent.none { r -> r.equals(it.name, ignoreCase = true) } }.take(MAX_CONTACTS)
        SectionLabel("Contacts")
        if (shown.isEmpty()) Text(if (q.isEmpty()) "No contacts yet." else "No contact called “$q”. Tap Add to use the name.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        else RowGroup {
            shown.forEachIndexed { i, c ->
                if (i > 0) RowDivider(leading = true)
                PersonRow(c.name, c.numbers.firstOrNull(), c.name in picked, multi) { onPick(c.name, c.numbers) }
            }
        }
    }
}

private const val MAX_CONTACTS = 50

@Composable
private fun PersonRow(name: String, detail: String?, checked: Boolean, multi: Boolean, onClick: () -> Unit) =
    ListRow(
        name, subtitle = detail, leading = { Avatar(name) },
        trailing = if (multi) ({
            Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Tokens.Secondary))
        }) else null,
        onClick = onClick,
    )
