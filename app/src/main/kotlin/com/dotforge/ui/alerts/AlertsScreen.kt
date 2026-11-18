package com.dotforge.ui.alerts

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.rules.AlertSentence
import com.dotforge.rules.HistoryFilter
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.rules.Where
import com.dotforge.system.NothingSettings
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.design.AddRow
import com.dotforge.ui.design.Avatar
import com.dotforge.ui.design.EmptyState
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Screen
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.Segmented
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.rememberAppLabel
import com.dotforge.ui.rememberNeedsScreening

/** What sheet is open over Alerts. */
sealed interface AlertSheet {
    data class RuleOf(val id: String, val advanced: Boolean = false) : AlertSheet
    data class AddBlock(val editId: String? = null, val where: Set<Where> = setOf(Where.CALLS, Where.SMS), val pkg: String? = null) : AlertSheet
    data class Entry(val key: String) : AlertSheet
    data object AddPerson : AlertSheet
    data object SpamAlert : AlertSheet
    data object HowItWorks : AlertSheet
    data object HowBlocking : AlertSheet
    data object DoubleLights : AlertSheet
}

/** The segments: Apps · People · Spam · History (row 52, row 91). */
enum class Segment(val label: String) { APPS("Apps"), PEOPLE("People"), SPAM("Spam"), HISTORY("History") }

/**
 * Alerts: a segmented control (each segment keeps its scroll), the warnings that stop alerts working, then the
 * segment. Requests from other tabs ("history:blocked", "spam:com.whatsapp") arrive through [Nav.alertsRequest].
 * [banners] and [start] are for screenshots.
 */
@Composable
fun AlertsScreen(
    nav: Nav,
    setup: Setup,
    state: LazyListState,
    banners: AlertBanners? = null,
    start: AlertSheet? = null,
) {
    val needsScreening by rememberNeedsScreening()
    val b = banners ?: rememberAlertBanners(setup, needsScreening)
    var segment by rememberSaveable { mutableStateOf(Segment.APPS) }
    var historyFilter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var spamApp by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(start) }
    LaunchedEffect(nav.alertsRequest) {
        val req = nav.alertsRequest ?: return@LaunchedEffect
        val (seg, arg) = req.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        when (seg) {
            "history" -> { segment = Segment.HISTORY; historyFilter = HistoryFilter.of(arg) }
            "spam" -> { segment = Segment.SPAM; spamApp = arg }
            "people" -> segment = Segment.PEOPLE
            "apps" -> segment = Segment.APPS
        }
        nav.alertsRequest = null
    }
    val states = listOf(state, rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val label = rememberAppLabel()
    val snacks = rememberSnacks()
    val ctx = LocalContext.current

    Screen(
        "Alerts", state = states[segment.ordinal],
        actions = {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Tokens.Raised) {
                    DropdownMenuItem({ Text("Alert settings") }, onClick = { open = false; nav.push(Page.AlertSettings) })
                    DropdownMenuItem({ Text("How it works") }, onClick = { open = false; sheet = AlertSheet.HowItWorks })
                    if (segment == Segment.HISTORY && history.isNotEmpty()) DropdownMenuItem({ Text("Clear history") }, onClick = {
                        open = false
                        val was = AppGraph.history.clear()
                        snacks?.show("History cleared", undo = { AppGraph.history.restore(was) })
                    })
                }
            }
        },
    ) {
        item(key = "segments") {
            Segmented(Segment.entries.map { it.label }, segment.ordinal, Modifier.padding(bottom = Tokens.Gap)) { segment = Segment.entries[it] }
        }
        banners(b, setup, onScreening = { setup.needCallScreening() }, onGlyphSettings = {
            runCatching { ctx.startActivity(NothingSettings.glyphSettings(ctx.packageManager).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        when (segment) {
            Segment.APPS -> appsItems(rules, label, b, nav, open = { sheet = it })
            Segment.PEOPLE -> peopleItems(rules, open = { sheet = it })
            Segment.SPAM -> spamItems(rules, history, setup, spamApp, label, clearApp = { spamApp = null }, open = { sheet = it })
            Segment.HISTORY -> historyItems(history, historyFilter, label, onFilter = { historyFilter = it }, open = { sheet = it })
        }
    }

    when (val s = sheet) {
        is AlertSheet.RuleOf -> RuleSheet(
            s.id, nav, setup, b, onDismiss = { sheet = null },
            onSpamRules = { pkg -> sheet = null; spamApp = pkg; segment = Segment.SPAM },
            onDoubleLights = { sheet = AlertSheet.DoubleLights },
            startAdvanced = s.advanced,
        )
        is AlertSheet.AddBlock -> AddRuleSheet(s, onDismiss = { sheet = null })
        is AlertSheet.Entry -> HistorySheet(s.key, label, onDismiss = { sheet = null }, open = { sheet = it })
        AlertSheet.AddPerson -> AddPersonSheet(setup, onDismiss = { sheet = null }) { r -> sheet = AlertSheet.RuleOf(r.id) }
        AlertSheet.SpamAlert -> SpamAlertSheet(onDismiss = { sheet = null })
        AlertSheet.HowItWorks -> HowItWorksSheet(onDismiss = { sheet = null })
        AlertSheet.HowBlocking -> HowBlockingSheet(onDismiss = { sheet = null })
        AlertSheet.DoubleLights -> DoubleLightsSheet(onDismiss = { sheet = null })
        null -> Unit
    }
}

/** Row 67 and row 68: what stops alerts working, in words with an icon and one button. */
private fun LazyListScope.banners(b: AlertBanners, setup: Setup, onScreening: () -> Unit, onGlyphSettings: () -> Unit) {
    val list = buildList {
        if (!b.access) add(Triple("Alerts are off. Notification access was turned off.", "Turn on") { setup.needNotificationAccess(); Unit })
        if (b.screeningLost) add(Triple("Spam calls aren't being blocked", "Fix", onScreening))
        when (b.device) {
            DeviceState.OTHER -> add(Triple("DotForge lights the Glyph Matrix on Nothing Phone (3). On this phone you can still design and block spam.", null, {}))
            DeviceState.SERVICE_MISSING -> add(Triple("Update Nothing OS to use the Glyph", "Check for updates") {
                setup.openAccessSettings()
            })
            DeviceState.GLYPH_OFF -> add(Triple("The Glyph is off in Settings", "Open Glyph settings", onGlyphSettings))
            DeviceState.PHONE3 -> Unit
        }
    }
    list.forEachIndexed { i, (text, button, click) ->
        item(key = "banner$i") { StatusBanner(text, Modifier.padding(bottom = Tokens.Gap), button = button, onClick = click) }
    }
}

// ---------------------------------------------------------------- Apps

private fun LazyListScope.appsItems(rules: List<Rule>, label: (String) -> String, b: AlertBanners, nav: Nav, open: (AlertSheet) -> Unit) {
    val calls = rules.firstOrNull { it.id == SimpleRules.PHONE_CALLS }
    val apps = rules.filter(SimpleRules::isAppRule)
    item(key = "apps") {
        RowGroup {
            if (calls != null) {
                RuleRow(calls, "Phone calls", if (b.ringtoneLights && calls.enabled) "To show names, set ringtone lights to Sound only" else AlertSentence.summary(calls), open) {
                    GlyphPreview(com.dotforge.notify.AlertRuntime.previewCall("Mom", calls.callShow, previewAnimation(calls, "Phone")), "Phone calls")
                }
            }
            apps.forEach { r ->
                RowDivider(leading = true)
                val title = ruleTitle(r, label)
                val pkg = r.packages.singleOrNull()
                val warn = b.doubleLights && r.enabled && pkg != null
                RuleRow(r, title, if (warn) "Nothing lights it too · Fix" else AlertSentence.summary(r), open, preview = pkg != null) {
                    if (pkg != null) AppIcon(pkg, title) else GlyphPreview(stillOf(previewAnimation(r, title)), title)
                }
            }
            RowDivider(leading = true)
            AddRow("Add an app") { nav.push(Page.AlertApps("app")) }
        }
    }
    if (apps.isEmpty()) item(key = "apps-empty") { Hint("Add the apps you care about. Each lights with its own icon until you check.") }
    item(key = "phone-label") { SectionLabel("This phone") }
    item(key = "phone") {
        RowGroup {
            val reminders = rules.firstOrNull { it.id == SimpleRules.REMINDERS }
            val moments = rules.filter { it.moment != null }.sortedBy { it.moment!!.ordinal }
            (listOfNotNull(reminders) + moments).forEachIndexed { i, r ->
                if (i > 0) RowDivider(leading = true)
                val hint = r.moment?.hint ?: "Tasks, Keep, Calendar and more"
                RuleRow(r, r.name, if (r.enabled) AlertSentence.summary(r) else hint, open) { GlyphPreview(previewAnimation(r, r.name), r.name) }
            }
        }
    }
}

/** A rule row: leading icon or preview, name, one line, and its own switch. The row opens the sheet. */
@Composable
private fun RuleRow(r: Rule, title: String, subtitle: String, open: (AlertSheet) -> Unit, preview: Boolean = false, leading: @Composable () -> Unit) {
    ListRow(
        title, subtitle = subtitle, leading = leading, subtitleLines = 3,
        trailing = {
            // an app row shows its icon and, beside the switch, the glyph it lights (40 dp)
            if (preview) GlyphPreview(stillOf(previewAnimation(r, title)), "${AlertSentence.glyphWord(r)} for $title")
            RowSwitch(r.enabled, title) { AppGraph.rules.upsert(r.copy(enabled = it)) }
        },
    ) { open(AlertSheet.RuleOf(r.id)) }
}

// ---------------------------------------------------------------- People

private fun LazyListScope.peopleItems(rules: List<Rule>, open: (AlertSheet) -> Unit) {
    val people = rules.filter(SimpleRules::isPersonRule)
    if (people.isEmpty()) {
        item(key = "people-empty") {
            EmptyState("Give the people who matter their own glyph. It shows for their calls and messages in every app.", "Add a person", glyph = "heart") { open(AlertSheet.AddPerson) }
        }
        return
    }
    item(key = "people") {
        RowGroup {
            people.forEachIndexed { i, r ->
                if (i > 0) RowDivider(leading = true)
                RuleRow(r, r.name, AlertSentence.summary(r), open) { Avatar(r.name) }
            }
            RowDivider(leading = true)
            AddRow("Add a person") { open(AlertSheet.AddPerson) }
        }
    }
    item(key = "people-hint") { Hint("People win over apps: Mom's glyph shows instead of the WhatsApp glyph.") }
}

/**
 * "+ Add a person" (row 57): Contacts is asked for here, in context. Without it, a name and a number can be
 * typed; calls match every number, messages match the name the app shows.
 */
@Composable
fun AddPersonSheet(setup: Setup, onDismiss: () -> Unit, onAdded: (Rule) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickContact()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val found = runCatching { contactOf(ctx, uri) }.getOrNull() ?: return@rememberLauncherForActivityResult
        val r = SimpleRules.newPerson(found.first, found.second)
        AppGraph.rules.upsert(r)
        onAdded(r)
    }
    FullSheet(onDismiss, "Add a person", confirm = "Add", confirmEnabled = name.isNotBlank(), onConfirm = {
        val r = SimpleRules.newPerson(name.trim(), listOfNotNull(number.trim().takeIf { it.isNotEmpty() }))
        AppGraph.rules.upsert(r)
        onAdded(r)
    }) {
        if (setup.contacts) {
            PrimaryButton("Pick from Contacts", Modifier.fillMaxWidth()) { pick.launch(null) }
            Hint("Or type them in:", Modifier.padding(top = Tokens.Small))
        } else {
            StatusBanner("Contacts is off, so type a name and number.", button = "Allow contacts") { setup.needContacts() }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = Tokens.Small), label = { Text("Name, as apps show it") }, singleLine = true)
        OutlinedTextField(
            number, { number = it }, Modifier.fillMaxWidth().padding(top = Tokens.Small), label = { Text("Type a number") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Hint("Calls match every number on the contact. Messages match the contact's name in the notification.")
    }
}

/** A picked contact's name and every number. */
private fun contactOf(ctx: android.content.Context, uri: android.net.Uri): Pair<String, List<String>>? {
    val cr = ctx.contentResolver
    val (id, name) = cr.query(uri, arrayOf(android.provider.ContactsContract.Contacts._ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
    } ?: return null
    val numbers = cr.query(
        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null,
    )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.orEmpty()
    return name to numbers.distinct()
}

// ---------------------------------------------------------------- explainers

/** Alerts > ⋮ > How it works: what "until you check" means, who wins, face down. */
@Composable
fun HowItWorksSheet(onDismiss: () -> Unit) = FullSheet(onDismiss, "How alerts work") {
    Para("Until you check", "An alert stays on until you open or dismiss its notification (anywhere, even on WhatsApp Web), open the app that sent it, or tap Stop on Home. Unlocking doesn't count unless you turn that on in Alert settings. After 4 hours it stops waiting anyway.")
    Para("Several at once", "Calls come first, then people, then apps. The newest shows first and nothing is cut in its first 2 seconds. While several wait, they take turns every 3 seconds. Moving animations rest after 3 loops to save battery.")
    Para("Face down", "Nothing hides lights from apps when the phone is face down or in a pocket. DotForge alerts show when the phone is face up.")
    Para("Nothing's own lights", "Nothing's notification and ringtone lights cover ours. Set them to Sound only in Glyph settings so only DotForge lights up.")
}

@Composable
fun HowBlockingSheet(onDismiss: () -> Unit) = FullSheet(onDismiss, "What blocking does") {
    Where.entries.forEach { w -> Para(w.label, w.means) }
    Para("Always let through", "Contacts and repeat callers (the same number twice within 3 min) are never blocked.")
}

/** Row 51: the steps to stop Nothing lighting on top of us. */
@Composable
fun DoubleLightsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    FullSheet(onDismiss, "Stop double lights") {
        Para(null, "Nothing lights its own glyph for notifications, on top of DotForge's. Set it to Sound only:")
        Para(null, "Settings > Glyph Interface > Notifications > Sound only")
        PrimaryButton("Open Glyph settings", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {
            runCatching { ctx.startActivity(NothingSettings.glyphSettings(ctx.packageManager).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        SecondaryButton("I've done this", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {
            DoubleLights.markDone(ctx)
            onDismiss()
        }
    }
}

@Composable
fun Para(title: String?, text: String) {
    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Tokens.Gap))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Tiny))
}

