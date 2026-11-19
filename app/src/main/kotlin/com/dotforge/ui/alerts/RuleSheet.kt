package com.dotforge.ui.alerts

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.GlyphLibrary
import com.dotforge.device.Timing
import com.dotforge.device.TimingOverride
import com.dotforge.engine.Until
import com.dotforge.rules.AlertSentence
import com.dotforge.rules.CallShow
import com.dotforge.rules.GlyphChoice
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.Rule
import com.dotforge.rules.SentencePart
import com.dotforge.rules.SimpleRules
import com.dotforge.rules.SoundMode
import com.dotforge.rules.WordKind
import com.dotforge.system.NothingSettings
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.rememberAppLabel

/** The name a rule's row and sheet use: the app's name for a one-app rule, else the rule's. */
fun ruleTitle(r: Rule, @Suppress("UNUSED_PARAMETER") label: (String) -> String): String = r.name

/**
 * The one sheet (row 47) for an app, a person, Phone calls, Reminders or a phone moment: a 96 dp preview and the
 * sentence whose words open small pickers; Try it on the Glyph; Advanced (collapsed, at most 8 rows); Remove.
 * Every change is saved at once; Done closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleSheet(
    ruleId: String,
    nav: Nav,
    setup: Setup,
    banners: AlertBanners,
    onDismiss: () -> Unit,
    onSpamRules: (String) -> Unit = {},
    onDoubleLights: () -> Unit = {},
    startAdvanced: Boolean = false,
    startPicker: WordKind? = null,
    startBlock: Boolean = false,
) {
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val r = rules.firstOrNull { it.id == ruleId } ?: return
    val label = rememberAppLabel()
    val ctx = LocalContext.current
    val snacks = rememberSnacks()
    var picker by remember { mutableStateOf(startPicker) }
    var advanced by rememberSaveable { mutableStateOf(startAdvanced) }
    var timing by remember { mutableStateOf(false) }
    var textSheet by remember { mutableStateOf<String?>(null) }
    var tryIt by remember { mutableStateOf(false) }
    var block by remember { mutableStateOf(startBlock) }
    fun save(x: Rule) = AppGraph.rules.upsert(x)

    val title = ruleTitle(r, label)
    val pkg = r.packages.singleOrNull()
    val isApp = SimpleRules.isAppRule(r) && r.id != SimpleRules.REMINDERS
    val isPerson = SimpleRules.isPersonRule(r)
    val isCalls = r.id == SimpleRules.PHONE_CALLS
    val anim = remember(r) { sheetAnimation(r, title) }
    val poster = remember(r) { posterOf(r, title) }
    val designName: (String?) -> String? = { id -> AppGraph.designs.get(id)?.name }

    FullSheet(onDismiss, title) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphPreview(anim, AlertSentence.glyphWord(r, designName, ::hasAppIcon), size = PreviewSize.MEDIUM, posterFrame = poster)
            // said only when it is true: the dots really come from the app's icon
            if (r.glyph == GlyphStyle.APP_ICON && pkg != null) {
                Hint(if (hasAppIcon(pkg)) "Drawn from $title's icon" else "$title's icon can't be drawn in dots, so the Chat bubble glyph shows", Modifier.padding(top = Tokens.Tiny))
            }
        }
        Sentence(AlertSentence.parts(r, title, designName, ::hasAppIcon), Modifier.padding(top = Tokens.Small)) { w ->
            when (w.kind) {
                WordKind.APP -> nav.push(Page.AlertApps("change:${r.id}"))
                else -> picker = w.kind
            }
        }
        if (isCalls) {
            Text("Show", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                CallShow.entries.forEach { s -> Chip(s.label, r.callShow == s) { save(r.copy(callShow = s)) } }
            }
            // right under the choice it affects, and only while a name is picked
            if (banners.ringtoneLights && r.callShow != CallShow.GLYPH) StatusBanner(
                "Names show only when Glyph ringtone lights are set to Sound only", Modifier.padding(top = Tokens.Small), button = "Change setting",
            ) { runCatching { ctx.startActivity(NothingSettings.glyphSettings(ctx.packageManager).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        if (isPerson) Hint("${r.name}'s glyph shows instead of the app's glyph, in every app.")

        // what stops it showing as it should
        when {
            banners.device != DeviceState.PHONE3 ->
                StatusBanner("Needs Phone (3) to light. Try it plays on screen.", Modifier.padding(top = Tokens.Gap))
            isApp && banners.doubleLights -> StatusBanner("Nothing also lights for $title", Modifier.padding(top = Tokens.Gap), button = "Fix", onClick = onDoubleLights)
        }

        if (isPerson) {
            val needs = r.callRingtone != null && !setup.screening
            ListRow(
                "When ${r.name} calls",
                subtitle = if (needs) "Needs Call screening · Turn on" else r.callRingtone?.let { "A Glyph ringtone: ${r.callRingtoneName ?: "chosen"}" } ?: "Their glyph",
                inset = 0.dp, trailing = { Chevron() },
            ) { if (needs) setup.needCallScreening() else picker = WordKind.CALL_RING }
        }
        if (r.id == SimpleRules.REMINDERS) {
            val apps = SimpleRules.reminderApps(r)
            ListRow(
                "From", subtitle = apps.take(3).joinToString(", ") { label(it) } + if (apps.size > 3) " +${apps.size - 3}" else "",
                inset = 0.dp, trailing = { Chevron() },
            ) { nav.push(Page.AlertApps("reminders")) }
            Text("Before the next event or alarm, fill the ring", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                listOf(0, 5, 10, 15, 30).forEach { m -> Chip(if (m == 0) "Off" else "$m min", r.approachMinutes == m) { save(r.copy(approachMinutes = m)) } }
            }
        }

        SecondaryButton("Try it on the Glyph", Modifier.fillMaxWidth().padding(top = Tokens.Gap)) { tryIt = true }

        // blocking someone happens here, in the app's own sheet (row 60)
        if (isApp && pkg != null) {
            val blocked = rules.count { SimpleRules.pattern(it)?.let { p -> p.type == com.dotforge.rules.PatternType.IS && SimpleRules.covers(p, pkg) } == true }
            ListRow(
                "Block a sender or group", subtitle = if (blocked == 0) "Their messages won't light or sound" else "$blocked blocked in $title",
                inset = 0.dp, trailing = { Chevron() },
            ) { block = true }
        }

        // Advanced (row 53): at most 8 rows
        ListRow(
            "Advanced", inset = 0.dp,
            trailing = { Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Tokens.Secondary) },
        ) { advanced = !advanced }
        if (advanced) {
            val t = r.timing?.over(Timing()) ?: Timing()
            if (isApp) {
                val words = SimpleRules.words(r)
                ListRow("Words", subtitle = if (words.isEmpty()) "Any message" else "Contains any of: ${words.joinToString(", ")}", inset = 0.dp, trailing = { Chevron() }) { textSheet = "words" }
                val chats = SimpleRules.chats(r)
                ListRow("Only these chats", subtitle = if (chats.isEmpty()) "All chats" else chats.joinToString(", "), inset = 0.dp, trailing = { Chevron() }) { textSheet = "chats" }
                ListRow("Times", subtitle = SimpleRules.timesText(r), inset = 0.dp, trailing = { Chevron() }) { textSheet = "times" }
            }
            ListRow("Brightness", subtitle = "${t.brightness}%", inset = 0.dp, trailing = { Chevron() }) { timing = true }
            ListRow(if (isCalls) "Scroll speed" else "Speed", subtitle = com.dotforge.ui.design.speedText(t.speed), inset = 0.dp, trailing = { Chevron() }) { timing = true }
            if (r.until == Until.READ && !isCalls) {
                val m = SimpleRules.remindMinutes(r)
                ListRow("Remind me", subtitle = if (m == 0) "Off" else "Every $m min", inset = 0.dp, trailing = { Chevron() }) { picker = WordKind.SHOW_FOR }
            }
            if (isApp || isPerson) SwitchRow("One alert per chat per minute", null, r.noRepeat, inset = 0.dp) { save(r.copy(noRepeat = it)) }
            if (isPerson) SwitchRow("Include group chats", "Light when ${r.name} writes in a group", r.groupChats, inset = 0.dp) { save(r.copy(groupChats = it)) }
            if (isApp || isPerson) ListRow("Who wins", subtitle = "Calls, then people, then apps", inset = 0.dp, trailing = { Chevron() }) { nav.push(Page.WhoWins) }
            if (isCalls) Hint("Names come from Contacts, then your phone app's caller ID, then other caller ID apps, then the number. A spam label shows the Spam glyph.")
        }

        if (isApp && pkg != null) {
            val n = rules.count { SimpleRules.pattern(it) != null && it.packages.contains(pkg) }
            ListRow(if (n == 0) "Spam rules for $title" else "Spam rules for $title ($n)", inset = 0.dp, trailing = { Chevron() }) { onSpamRules(pkg) }
        }
        if (isApp || isPerson) {
            TextButton(onClick = {
                AppGraph.rules.delete(r.id)
                onDismiss()
                snacks?.show("$title removed", undo = { AppGraph.rules.upsert(r) })
            }, modifier = Modifier.heightIn(min = Tokens.Target).padding(top = Tokens.Small), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Remove $title", color = Tokens.AccentText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    when (picker) {
        WordKind.GLYPH -> GlyphPicker(r, title, isApp = isApp, onDismiss = { picker = null }) { save(SimpleRules.withGlyph(r, it)) }
        WordKind.WHO -> WhoPicker(r, title, setup, onDismiss = { picker = null }) { save(SimpleRules.withPeople(r, it)) }
        WordKind.SHOW_FOR -> ShowForPicker(r, onDismiss = { picker = null }) { save(it) }
        WordKind.SOUND -> SoundPicker(r, onDismiss = { picker = null }) { save(it) }
        WordKind.CALL_RING -> CallRingPicker(r, setup, onDismiss = { picker = null }) { save(it) }
        WordKind.APP, null -> Unit
    }
    if (timing) {
        val base = Timing(showFor = if (r.until == Until.TIMEOUT) com.dotforge.device.ShowFor.SECONDS else com.dotforge.device.ShowFor.UNTIL_CHECKED)
        val now = r.timing?.over(base) ?: base
        TimingSheet(now, TimingKind.USE, previewAnimation(r, title), title, onDismiss = { timing = false }, placeName = title) { t ->
            val until = if (t.showFor == com.dotforge.device.ShowFor.UNTIL_CHECKED) (if (r.until == Until.UNLOCK) Until.UNLOCK else Until.READ) else Until.TIMEOUT
            save(r.copy(timing = TimingOverride.diff(Timing(), t).takeUnless { it.isEmpty }, until = until))
        }
    }
    when (textSheet) {
        "words" -> ListSheet("Words", "Light only when the message contains any of these.", SimpleRules.words(r), pattern = SimpleRules.wordsPattern(r), onDismiss = { textSheet = null }) { list, p -> save(SimpleRules.withWords(r, list, p)) }
        "chats" -> ListSheet("Only these chats", "Chat or group names, as $title shows them.", SimpleRules.chats(r), pattern = null, onDismiss = { textSheet = null }) { list, _ -> save(SimpleRules.withChats(r, list)) }
        "times" -> TimesSheet(r, onDismiss = { textSheet = null }) { save(it) }
    }
    if (tryIt) TryItSheet(r, title, banners.device, onDismiss = { tryIt = false })
    if (block && pkg != null) BlockSendersSheet(pkg, title, onDismiss = { block = false })
}

// ---------------------------------------------------------------- pickers

/** Row 92: Library and Yours tabs, four to a row. "App icon" and "Their name" come first. Tiles are stills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlyphPicker(r: Rule, title: String, isApp: Boolean, onDismiss: () -> Unit, onPick: (GlyphChoice) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    val height = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.62f).dp
    FullSheet(onDismiss, "Glyph", scrolls = tab == 1) {
        com.dotforge.ui.design.Segmented(listOf("Library", "Yours"), tab) { tab = it }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = Tokens.Gap)) {
        val tile = (maxWidth - Tokens.Small * (COLUMNS - 1)) / COLUMNS
        CompositionLocalProviderTile(tile) {
        if (tab == 0) {
            Column {
                // the alert's own pictures, then the shared Library browser
                fun still(style: GlyphStyle) = posterOf(r.copy(glyph = style), title)
                val app = isApp && r.packages.size == 1
                val name = r.moment == null && r.id != SimpleRules.PHONE_CALLS
                val bell = r.id == SimpleRules.REMINDERS
                if (app || name || bell) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Small), maxItemsInEachRow = COLUMNS) {
                    if (app) GlyphTile("App icon", r.glyph == GlyphStyle.APP_ICON, { GlyphPreview(remember { still(GlyphStyle.APP_ICON) }, "App icon") }) { onPick(GlyphChoice(GlyphStyle.APP_ICON)) }
                    if (name) GlyphTile("Their name", r.glyph == GlyphStyle.NAME, { GlyphPreview(remember { still(GlyphStyle.NAME) }, "Their name") }) { onPick(GlyphChoice(GlyphStyle.NAME)) }
                    if (bell) GlyphTile("Bell", r.glyph == GlyphStyle.BELL, { GlyphPreview(remember { still(GlyphStyle.BELL) }, "Bell") }) { onPick(GlyphChoice(GlyphStyle.BELL)) }
                }
                com.dotforge.ui.library.LibraryBrowser(
                    Modifier.fillMaxWidth().height(height).padding(top = Tokens.Small),
                    selectedId = r.designId.takeIf { r.glyph == GlyphStyle.ICON }, actionLabel = "Use",
                ) { item ->
                    com.dotforge.ui.library.LibraryPrefs.used(item.id)
                    onPick(GlyphChoice.icon(item.id))
                }
            }
        } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small), maxItemsInEachRow = COLUMNS) {
            if (designs.isEmpty()) Hint("Nothing saved yet. Designs you make in Studio show here.")
            else designs.forEach { d ->
                GlyphTile(d.name, r.glyph == GlyphStyle.DESIGN && r.designId == d.id, {
                    GlyphPreview(remember(d.id) { AppGraph.designAnimation(d.id)?.let(com.dotforge.device.Posters::of) }, d.name)
                }) { onPick(GlyphChoice(GlyphStyle.DESIGN, d.id)) }
            }
        }
        }
        }
    }
}

/** Glyph tiles per row: a quarter of the sheet each. */
private const val COLUMNS = 4
private val LocalTile = androidx.compose.runtime.compositionLocalOf { 72.dp }

@Composable
private fun CompositionLocalProviderTile(w: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalTile provides w, content = content)

/** A picker tile: the still, then its name on up to 2 centred lines (12 sp, growing at most 1.5x). */
@Composable
private fun GlyphTile(name: String, selected: Boolean, preview: @Composable () -> Unit, onClick: () -> Unit) {
    val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val size = 12.sp * (minOf(fs, 1.5f) / fs)
    Column(
        Modifier.width(LocalTile.current).clickable(onClickLabel = "Use $name", onClick = onClick)
            .padding(vertical = Tokens.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.heightIn(min = 48.dp).then(
                if (selected) Modifier.background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f), MaterialTheme.shapes.medium) else Modifier,
            ).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) { preview() }
        Text(
            name, style = MaterialTheme.typography.bodySmall.copy(fontSize = size, lineHeight = size * 1.25f),
            color = if (selected) androidx.compose.ui.graphics.Color.White else Tokens.Secondary,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** From: everyone, or people picked from who wrote recently in this app, then Contacts, then typed names. */
@Composable
private fun WhoPicker(r: Rule, title: String, setup: Setup, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var names by remember { mutableStateOf(SimpleRules.people(r)) }
    var only by remember { mutableStateOf(names.isNotEmpty()) }
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val recent = remember(history) {
        com.dotforge.rules.HistoryRepository.recentSenders(history, r.packages.singleOrNull()).filterNot { it.chat }.map { it.name }
    }
    val contacts = if (setup.contacts) rememberContacts() else null
    FullSheet(onDismiss, "From", half = true, onConfirm = { onSave(if (only) names else emptyList()); onDismiss() }) {
        OptionRow("Everyone", "Anything $title sends", selected = !only) { only = false }
        OptionRow("Only people I pick", if (names.isEmpty()) "Pick below" else names.joinToString(", "), selected = only) { only = true }
        if (only) {
            // names picked by hand that aren't in either list still show, ticked, at the top
            val extra = names.filter { n -> recent.none { it.equals(n, true) } && contacts.orEmpty().none { it.name.equals(n, true) } }
            PeoplePicker(
                extra + recent, contacts, picked = names.toSet(), multi = true, recentTitle = "Recent in $title",
                onAllowContacts = if (setup.contacts) null else ({ setup.needContacts() }),
            ) { n, _ -> names = if (names.any { it.equals(n, true) }) names.filterNot { it.equals(n, true) } else names + n }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowForPicker(r: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    val secs = SimpleRules.showSeconds(r)
    val lasts = r.moment?.lasts == true
    FullSheet(onDismiss, "Show for", half = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Timing.SECONDS_CHIPS.forEach { s -> Chip("$s s", secs == s) { onSave(SimpleRules.withShowFor(r, s)) } }
            if (lasts) Chip("While it lasts", r.until != Until.TIMEOUT) { onSave(SimpleRules.withShowFor(r, null).copy(until = Until.UNLOCK)) }
            else if (r.moment == null) Chip("Until you check", secs == null) { onSave(SimpleRules.withShowFor(r, null)) }
        }
        if (secs == null && r.moment == null) {
            Text("Remind me", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                val m = SimpleRules.remindMinutes(r)
                Chip("Off", m == 0) { onSave(SimpleRules.withRemind(r, 0)) }
                Timing.REMIND_CHIPS.forEach { x -> Chip(if (x == 1) "Every 1 min" else "$x min", m == x) { onSave(SimpleRules.withRemind(r, x)) } }
            }
            Hint("It stays until you check: you open or dismiss the notification, or tap Stop on Home. With Remind me it rests, then shows again.")
        }
    }
}

@Composable
private fun SoundPicker(r: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION") val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return@rememberLauncherForActivityResult
        val name = runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
        onSave(r.copy(sound = SoundMode.CUSTOM, soundUri = uri.toString(), soundName = name))
    }
    FullSheet(onDismiss, "Sound", half = true) {
        SoundMode.entries.forEach { m ->
            OptionRow(
                if (m == SoundMode.CUSTOM && r.soundName != null) "Custom tune: ${r.soundName}" else m.label, m.hint, selected = r.sound == m,
            ) {
                if (m == SoundMode.CUSTOM) {
                    launcher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION))
                } else onSave(r.copy(sound = m))
            }
        }
        Hint("Never a double sound: with No sound or a custom tune, the app's notification is silenced and a quiet copy stays in your notification list.")
    }
}

@Composable
private fun CallRingPicker(r: Rule, setup: Setup, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION") val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return@rememberLauncherForActivityResult
        val name = runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
        onSave(r.copy(callRingtone = uri.toString(), callRingtoneName = name))
    }
    FullSheet(onDismiss, "When ${r.name} calls", half = true) {
        OptionRow("Their glyph", "Their glyph and name show while it rings", selected = r.callRingtone == null) { onSave(r.copy(callRingtone = null, callRingtoneName = null)) }
        OptionRow("A Glyph ringtone", "Nothing's ringtone lights play its pattern", selected = r.callRingtone != null) {
            if (!setup.screening) setup.needCallScreening()
            launcher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
        }
        Hint("Both need Call screening, and Glyph ringtone lights set to Sound only for their glyph.")
    }
}

/** Words or chat names, one per line or comma separated. [pattern] null hides "Use pattern". */
@Composable
private fun ListSheet(title: String, hint: String, values: List<String>, pattern: Boolean?, onDismiss: () -> Unit, onSave: (List<String>, Boolean) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    var regex by remember { mutableStateOf(pattern == true) }
    val list = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val bad = regex && list.any { !com.dotforge.rules.RuleEngine.isValidPattern(it) }
    FullSheet(onDismiss, title, half = true, confirm = "Save", confirmEnabled = !bad, onConfirm = { onSave(list, regex); onDismiss() }) {
        Hint(hint)
        LabeledField(
            if (regex) "Pattern" else title, text, { text = it }, placeholder = if (regex) "A regular expression" else "Separate with commas",
            error = "That pattern doesn't work".takeIf { bad }, showError = bad,
        )
        if (pattern != null) SwitchRow("Use pattern", "For experts: a regular expression", regex, inset = 0.dp) { regex = it }
    }
}

@Composable
private fun TimesSheet(r: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var days by remember { mutableStateOf(SimpleRules.days(r).ifEmpty { (1..7).toList() }) }
    val range = SimpleRules.hours(r)?.split('-')
    fun minutes(t: String?) = t?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    var from by remember { mutableStateOf(minutes(range?.getOrNull(0)) ?: 0) }
    var to by remember { mutableStateOf(minutes(range?.getOrNull(1)) ?: 0) }
    FullSheet(onDismiss, "Times", half = true, confirm = "Save", confirmEnabled = days.isNotEmpty(), onConfirm = {
        onSave(SimpleRules.withTimes(r, days, if (from == to) null else "${hhmm(from)}-${hhmm(to)}"))
        onDismiss()
    }) {
        Text("Days", style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary)
        DayToggles(days) { days = it }
        com.dotforge.ui.design.RowGroup(Modifier.padding(top = Tokens.Small)) {
            TimeRow("From", from) { from = it }
            com.dotforge.ui.design.RowDivider()
            TimeRow("To", to) { to = it }
        }
        Hint(if (from == to) "All day" else "From ${hhmm(from)} to ${hhmm(to)}" + if (to < from) ", overnight" else "")
    }
}

/** Shown under section labels in the sheet. */
@Composable
fun SheetLabel(text: String) = SectionLabel(text)
