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
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.IconCircle
import androidx.compose.material.icons.filled.Settings
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
    /** A rule's one sheet; [picker] and [block] open one of its stacked sheets too (links and screenshots). */
    data class RuleOf(val id: String, val advanced: Boolean = false, val picker: com.dotforge.rules.WordKind? = null, val block: Boolean = false, val more: Boolean = false) : AlertSheet
    /** Add or edit a block rule; [more] and [openMore] start it with More conditions (and their sheet open), for screenshots. */
    data class AddBlock(
        val editId: String? = null, val where: Set<Where> = setOf(Where.CALLS, Where.SMS), val pkg: String? = null,
        val more: com.dotforge.filter.Group = com.dotforge.filter.Group(), val openMore: Boolean = false,
        val callAction: com.dotforge.rules.Action? = null,
    ) : AlertSheet
    data class Entry(val key: String) : AlertSheet
    data object AddPerson : AlertSheet
    data object AddGroup : AlertSheet
    data object SpamAlert : AlertSheet
    data object HowItWorks : AlertSheet
    data object HowBlocking : AlertSheet
    data object DoubleLights : AlertSheet
    data object AllowList : AlertSheet
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
            // "rule:<id>": a rule's sheet, over Apps (Add an app opens the new app's sheet this way)
            "rule" -> if (arg != null) { segment = Segment.APPS; sheet = AlertSheet.RuleOf(arg) }
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
                    // the built-in rules (Phone calls, Messages, OTP codes, Delivery, the spam filter's) that were deleted
                    if (segment == Segment.SPAM) DropdownMenuItem({ Text("Restore the starter rules") }, onClick = {
                        open = false
                        val missing = AppGraph.rules.missingPresets()
                        if (missing.isEmpty()) snacks?.show("The starter rules are all there")
                        else {
                            AppGraph.rules.addPresets(missing)
                            snacks?.show(if (missing.size == 1) "1 starter rule restored" else "${missing.size} starter rules restored", undo = { missing.forEach { AppGraph.rules.delete(it.id) } })
                        }
                    })
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
            Segment.HISTORY -> historyItems(history, historyFilter, label, onFilter = { historyFilter = it }, open = { sheet = it }, toApps = { segment = Segment.APPS })
        }
    }

    when (val s = sheet) {
        is AlertSheet.RuleOf -> RuleSheet(
            s.id, nav, setup, b, onDismiss = { sheet = null },
            onSpamRules = { pkg -> sheet = null; spamApp = pkg; segment = Segment.SPAM },
            onDoubleLights = { sheet = AlertSheet.DoubleLights },
            startAdvanced = s.advanced,
            startPicker = s.picker,
            startBlock = s.block,
            startMore = s.more,
        )
        is AlertSheet.AddBlock -> AddRuleSheet(s, onDismiss = { sheet = null })
        is AlertSheet.Entry -> HistorySheet(s.key, label, onDismiss = { sheet = null }, open = { sheet = it })
        AlertSheet.AddPerson -> AddPersonSheet(setup, onDismiss = { sheet = null }) { r -> sheet = AlertSheet.RuleOf(r.id) }
        AlertSheet.AddGroup -> AddGroupSheet(setup, onDismiss = { sheet = null }) { r -> sheet = AlertSheet.RuleOf(r.id) }
        AlertSheet.SpamAlert -> SpamAlertSheet(onDismiss = { sheet = null })
        AlertSheet.HowItWorks -> HowItWorksSheet(onDismiss = { sheet = null })
        AlertSheet.HowBlocking -> HowBlockingSheet(onDismiss = { sheet = null })
        AlertSheet.DoubleLights -> DoubleLightsSheet(onDismiss = { sheet = null })
        AlertSheet.AllowList -> AllowListSheet(onDismiss = { sheet = null })
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
            // every row the same way: where it listens on the left, the glyph it lights and its switch on the right
            if (calls != null) {
                val ringtone = b.ringtoneLights && calls.enabled && calls.callShow != com.dotforge.rules.CallShow.GLYPH
                RuleRow(calls, "Phone calls", if (ringtone) "Names need ringtone lights set to Sound only" else AlertSentence.summary(calls, hasIcon = ::hasAppIcon), open)
            }
            apps.forEach { r ->
                RowDivider(leading = true)
                val title = ruleTitle(r, label)
                val warn = b.doubleLights && r.enabled && r.packages.size == 1
                RuleRow(r, title, if (warn) "Nothing lights it too · Fix" else AlertSentence.summary(r, hasIcon = ::hasAppIcon), open)
            }
            RowDivider(leading = true)
            AddRow("Add an app") { nav.push(Page.AlertApps("app")) }
        }
    }
    if (apps.isEmpty()) item(key = "apps-empty") { Hint("Add the apps you care about. Each lights with its own glyph until you check.") }
    item(key = "phone-label") { SectionLabel("This phone") }
    item(key = "phone") {
        RowGroup {
            rules.firstOrNull { it.id == SimpleRules.REMINDERS }?.let { r ->
                RuleRow(r, r.name, if (r.enabled) AlertSentence.summary(r) else "Tasks, Keep, Calendar and more", open)
                RowDivider(leading = true)
            }
            val moments = rules.filter { it.moment != null }
            ListRow(
                "Phone moments", subtitle = momentsText(moments), leading = { IconCircle(Icons.Filled.Settings) }, subtitleLines = 3,
                trailing = { Chevron() },
            ) { nav.push(Page.PhoneMoments) }
        }
    }
}

/** "Unlock, Shake" when some are on, else "Unlock, shake, camera… · Off". */
fun momentsText(moments: List<Rule>): String {
    val on = moments.filter { it.enabled }.sortedBy { it.moment!!.ordinal }
    return if (on.isEmpty()) "Unlock, shake, camera… · Off" else on.joinToString(", ") { it.name }
}

/** A rule row: where it listens, its name and line, then its glyph (40 dp still) and its own switch. The row opens the sheet. */
@Composable
private fun RuleRow(r: Rule, title: String, subtitle: String, open: (AlertSheet) -> Unit) {
    ListRow(
        title, subtitle = subtitle, leading = { SourceIcon(r, title) }, subtitleLines = Int.MAX_VALUE,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RulePoster(r, title)
                RowSwitch(r.enabled, title) { AppGraph.rules.upsert(r.copy(enabled = it)) }
            }
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
        item(key = "group-add") { RowGroup { AddRow("Add a group") { open(AlertSheet.AddGroup) } } }
        return
    }
    item(key = "people") {
        RowGroup {
            people.forEachIndexed { i, r ->
                if (i > 0) RowDivider(leading = true)
                RuleRow(r, r.name, AlertSentence.summary(r), open)
            }
            RowDivider(leading = true)
            AddRow("Add a person") { open(AlertSheet.AddPerson) }
            RowDivider(leading = true)
            AddRow("Add a group") { open(AlertSheet.AddGroup) }
        }
    }
    item(key = "people-hint") { Hint("People win over apps: Mom's glyph shows instead of the WhatsApp glyph. A person wins over their group.") }
}

/**
 * "+ Add a person" (row 57): people who wrote recently first, then Contacts (asked for here, in context), with
 * a search; a name and a number can always be typed. Calls match every number, messages the name apps show.
 */
@Composable
fun AddPersonSheet(setup: Setup, onDismiss: () -> Unit, onAdded: (Rule) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val have = rules.filter(SimpleRules::isPersonRule).map { it.name.lowercase() }.toSet()
    val recent = remember(history) { com.dotforge.rules.HistoryRepository.recentSenders(history, null).filterNot { it.chat }.map { it.name } }
        .filterNot { it.lowercase() in have }
    val contacts = if (setup.contacts) rememberContacts() else null
    fun add(n: String, numbers: List<String>) {
        val r = SimpleRules.newPerson(n.trim(), numbers)
        AppGraph.rules.upsert(r)
        onAdded(r)
    }
    FullSheet(onDismiss, "Add a person", confirm = "Add", confirmEnabled = name.isNotBlank(), onConfirm = {
        add(name, listOfNotNull(number.trim().takeIf { it.isNotEmpty() }))
    }) {
        PeoplePicker(
            recent, contacts, picked = emptySet(), multi = false, recentTitle = "Wrote recently",
            onAllowContacts = if (setup.contacts) null else ({ setup.needContacts() }),
        ) { n, numbers -> add(n, numbers) }
        SectionLabel("Or type them in")
        LabeledField("Name", name, { name = it }, placeholder = "As apps show it, like Mom")
        LabeledField("Number", number, { number = it }, placeholder = "Optional", keyboard = KeyboardType.Phone, helper = "Calls match every number on the contact.")
    }
}

/**
 * "+ Add a group": the Contacts groups on this phone (Contacts asked for here, in context). A group gets one glyph
 * for everyone in it; a person added on their own still wins over it.
 */
@Composable
fun AddGroupSheet(setup: Setup, onDismiss: () -> Unit, groups: List<String>? = null, onAdded: (Rule) -> Unit) {
    val ctx = LocalContext.current
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val have = rules.filter(SimpleRules::isGroupRule).map { it.name.lowercase() }.toSet()
    val found = groups ?: remember(setup.contacts) { if (setup.contacts) runCatching { com.dotforge.calls.CallerLookup(ctx).groupTitles() }.getOrDefault(emptyList()) else emptyList() }
    FullSheet(onDismiss, "Add a group", half = true, confirm = null) {
        Hint("One glyph for everyone in a Contacts group, like Family or Work, in every app.")
        when {
            groups == null && !setup.contacts -> StatusBanner("Allow Contacts to see your groups", Modifier.padding(top = Tokens.Small), button = "Allow") { setup.needContacts() }
            found.isEmpty() -> Hint("No groups in Contacts yet. Make one in your Contacts app, then come back.", Modifier.padding(top = Tokens.Small))
            else -> RowGroup(Modifier.padding(top = Tokens.Small)) {
                found.forEachIndexed { i, title ->
                    if (i > 0) RowDivider(leading = true)
                    val added = title.lowercase() in have
                    ListRow(title, subtitle = if (added) "Added" else null, leading = { com.dotforge.ui.design.LibraryGlyph("people", still = true) }, enabled = !added) {
                        val r = SimpleRules.newGroup(title)
                        AppGraph.rules.upsert(r)
                        onAdded(r)
                    }
                }
            }
        }
    }
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
    FullSheet(onDismiss, "Stop double lights", confirm = null) {
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

