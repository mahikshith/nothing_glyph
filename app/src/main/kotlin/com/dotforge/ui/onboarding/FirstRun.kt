package com.dotforge.ui.onboarding

import android.Manifest
import android.content.Context
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.backup.BackupStore
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotText
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.setup.CallsCard
import com.dotforge.setup.FirstRunFlow
import com.dotforge.setup.FirstRunFlow.Step
import com.dotforge.setup.FirstRunPrefs
import com.dotforge.setup.PickApps
import com.dotforge.system.NothingSettings
import com.dotforge.ui.AppEntry
import com.dotforge.ui.Setup
import com.dotforge.ui.alerts.AppIcon
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.alerts.deviceState
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.LibraryGlyph
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.LoadingRows
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.Tag
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.features.NOT_PHONE3
import com.dotforge.ui.launcherApps

/** The hello animation: "HI" wiped in from the left, held, then faded. About 3.5 s; never faster than the preview cap. */
object Hello {
    const val ID = "first-run-hello"

    fun animation(p: DeviceProfile): Animation {
        val hi = DotText.centered(p.size, "HI", scale = 2)
        val full = Frame.of(p, hi)
        val frames = ArrayList<AnimFrame>()
        for (c in 5..p.size step 3) frames += AnimFrame(Frame.of(p, IntArray(hi.size) { i -> if (i % p.size < c) hi[i] else 0 }), 170)
        frames += AnimFrame(full, 1_600)
        frames += AnimFrame(full.scaled(0.45f), 170)
        frames += AnimFrame(full.scaled(0.15f), 170)
        return Animation(p, frames)
    }
}

/** The phone's own SMS and Phone apps (Pick apps ticks them), or null when Android doesn't say. */
private fun defaultSms(ctx: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
private fun defaultDialer(ctx: Context): String? = runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

/**
 * First run (production design > User journeys > First run): Welcome, one card per permission (Notification
 * access, Calls, Keep running, then the optional caller name card on a Phone (3)), Pick apps, Done. Under a
 * minute, every step skippable, and never shown again once finished ([onDone]). The cards re-check on resume and
 * show a Done tick. POST_NOTIFICATIONS is not asked here: the timer asks when it first needs it.
 *
 * A resumed install (restored, or set up before First run existed) sees only the core cards still to do and Done.
 *
 * [device], [apps], [backupFound], [startAt], [roleRefusals] and [resumed] stand in for the phone in screenshots
 * and tests.
 */
@Composable
fun FirstRun(
    setup: Setup,
    onRestore: () -> Unit,
    onDone: () -> Unit,
    device: DeviceState? = null,
    apps: List<AppEntry>? = null,
    backupFound: String? = null,
    startAt: Step? = null,
    roleRefusals: Int = 0,
    resumed: Boolean? = null,
) {
    val ctx = LocalContext.current
    val dev = device ?: remember { deviceState(ctx) }
    val again = resumed ?: remember { FirstRunPrefs.resumed(ctx) }
    val order = remember(dev, again) { FirstRunFlow.order(dev == DeviceState.PHONE3, again) }
    val perms = setup.perms
    var step by rememberSaveable { mutableStateOf(startAt ?: FirstRunFlow.start(order, again, perms)) }
    var refusals by rememberSaveable { mutableIntStateOf(roleRefusals) }
    // the hello plays on the Glyph once, not again after Back from Done and Continue
    var helloPlayed by rememberSaveable { mutableStateOf(false) }
    val back = FirstRunFlow.back(step, order)
    fun next() { step = FirstRunFlow.next(step, order, perms) }
    BackHandler(enabled = back != null) { back?.let { step = it } }

    Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Welcome has one way on (Get started) and the restore link; Skip setup is on every card
        TopRow(step, order, back?.let { b -> { step = b } }, onSkip = onDone.takeIf { step != Step.DONE && step != Step.WELCOME })
        when (step) {
            Step.WELCOME -> Welcome(dev, backupFound, onRestore) { next() }
            Step.NOTIFICATIONS -> Card(
                "bell", "Notification access", NOTIFICATIONS_WHY,
                done = perms.access, button = "Allow", line = "On the next screen, turn on DotForge.",
                onAllow = setup::openAccessSettings, onNext = ::next,
            )
            Step.CALLS -> CallsStep(setup, refusals, onRefused = { refusals++ }, onNext = ::next)
            Step.KEEP_RUNNING -> Card(
                "battery", "Keep running", "So the Glyph still lights when the screen is off.",
                done = perms.keepRunning, button = "Allow", onAllow = setup::needKeepRunning, onNext = ::next,
            )
            Step.CALLER_NAME -> CallerNameStep(setup, done = perms.callerName == true, onNext = ::next)
            Step.PICK_APPS -> PickAppsStep(apps, onNext = ::next)
            Step.DONE -> Done(dev, perms.missingNames, played = helloPlayed, onPlayed = { helloPlayed = true }, onDone)
        }
    }
}

/** The Notification access card's sentence, also the Permissions page's (Android's next screen says the rest). */
const val NOTIFICATIONS_WHY = "DotForge reads notifications on this phone to light the Glyph. Nothing leaves the phone."

/** Back, where this step sits ("Step 2 of 5" as dots), and Skip setup. */
@Composable
private fun TopRow(step: Step, order: List<Step>, onBack: (() -> Unit)?, onSkip: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(Tokens.Target)) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FirstRunFlow.position(step, order)?.let { (at, of) ->
                Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Step $at of $of" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(of) { i -> Box(Modifier.size(6.dp).background(if (i + 1 == at) Color.White else Tokens.DotOff, CircleShape)) }
                }
            }
        }
        if (onSkip != null) TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = Tokens.Target)) {
            Text("Skip setup", style = MaterialTheme.typography.labelLarge, color = Tokens.Secondary)
        } else Spacer(Modifier.width(Tokens.Target))
    }
}

/**
 * A step: what scrolls (icon, title, words) and the pinned buttons under it. While more scrolls under the
 * buttons, a hairline sits on top of them so nothing looks cut flat.
 */
@Composable
private fun ColumnScope.StepLayout(
    top: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = Tokens.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = top,
    )
    androidx.compose.material3.HorizontalDivider(color = if (scroll.canScrollForward) Tokens.Hairline else Color.Transparent)
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter).padding(top = Tokens.Small, bottom = Tokens.Gutter), content = buttons)
}

@Composable
private fun Title(text: String) = Text(
    text, Modifier.fillMaxWidth().padding(top = Tokens.Section).semantics { heading() },
    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
)

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) = Text(
    text, modifier.fillMaxWidth().padding(top = Tokens.Small),
    style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, textAlign = TextAlign.Center,
)

@Composable
private fun Line(text: String) = Text(
    text, Modifier.fillMaxWidth().padding(top = Tokens.Gutter),
    style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.Center,
)

/** "Not now" under the main button: a full-width 48 dp text button. */
@Composable
private fun QuietButton(text: String, onClick: () -> Unit) =
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.Target).padding(top = Tokens.Tiny)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }

/** The quiet button's slot left empty, so the main button stays where it was. */
@Composable
private fun QuietSlot() = Spacer(Modifier.fillMaxWidth().height(Tokens.Target + Tokens.Tiny))

// ---------------------------------------------------------------- Welcome

@Composable
private fun ColumnScope.Welcome(device: DeviceState, backupFound: String?, onRestore: () -> Unit, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val hello = remember { Hello.animation(AppGraph.profile) }
    // a given name (screenshots, tests) skips the look in Downloads; "" means none was found
    var found by remember { mutableStateOf(backupFound?.ifEmpty { null }) }
    var file by remember { mutableStateOf<BackupStore.Found?>(null) }
    LaunchedEffect(Unit) {
        if (backupFound != null) return@LaunchedEffect
        file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BackupStore.findInDownloads(ctx) }
        found = file?.name
    }
    StepLayout(
        top = {
            Spacer(Modifier.height(Tokens.Section))
            GlyphPreview(hello, "Hello", Modifier.size(bigPreview()), size = PreviewSize.LARGE)
            Text(
                "DotForge", Modifier.fillMaxWidth().padding(top = Tokens.Section).semantics { heading() },
                style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
            )
            Body("Light the Glyph for the apps and people you pick, and keep spam out.")
            if (device != DeviceState.PHONE3) StatusBanner(NOT_PHONE3, Modifier.padding(top = Tokens.Section), info = true)
        },
        buttons = {
            PrimaryButton("Get started", Modifier.fillMaxWidth(), onClick = onStart)
            // the Backup page opens the found file straight away; without one it offers the file picker
            TextButton(onClick = { BackupStore.offered = file; onRestore() }, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.Target).padding(top = Tokens.Tiny)) {
                Text(
                    if (found != null) "Restore from a backup · found in Downloads" else "Restore from a backup",
                    style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.Center,
                )
            }
        },
    )
}

// ---------------------------------------------------------------- permission cards

/**
 * One permission card: the dot icon (a tick over it once done), the name, one plain sentence, an optional
 * [warning] banner and a quieter [line], Allow and Not now. Once done: a tick with [doneLabel] ("Done", or
 * "Opened" when the phone can't confirm) and Next in the main button's place, which never moves.
 */
@Composable
private fun ColumnScope.Card(
    glyph: String,
    title: String,
    reason: String,
    done: Boolean,
    button: String,
    onAllow: () -> Unit,
    onNext: () -> Unit,
    optional: Boolean = false,
    line: String? = null,
    warning: String? = null,
    doneLabel: String = "Done",
    quiet: Pair<String, () -> Unit>? = null,
) = StepLayout(
    top = {
        Spacer(Modifier.height(Tokens.Section))
        Box(contentAlignment = Alignment.BottomEnd) {
            LibraryGlyph(glyph, size = PreviewSize.MEDIUM, still = true)
            if (done) Icon(
                Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(32.dp).background(Tokens.Background, CircleShape),
            )
        }
        Title(title)
        if (optional) Tag("Optional", Modifier.padding(top = Tokens.Small))
        Body(reason)
        if (done) Row(
            Modifier.padding(top = Tokens.Gutter).semantics(mergeDescendants = true) { contentDescription = "$title: ${doneLabel.lowercase()}" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            Text(doneLabel, style = MaterialTheme.typography.titleMedium)
        } else {
            if (warning != null) StatusBanner(warning, Modifier.padding(top = Tokens.Section))
            if (line != null) Line(line)
        }
    },
    buttons = {
        if (done) {
            PrimaryButton("Next", Modifier.fillMaxWidth(), onClick = onNext)
            if (quiet != null) QuietButton(quiet.first, quiet.second) else QuietSlot()
        } else {
            PrimaryButton(button, Modifier.fillMaxWidth(), onClick = onAllow)
            QuietButton("Not now", onNext)
        }
    },
)

/**
 * Calls: the call screening role and Contacts behind one Allow. The role dialog comes first; when it closes,
 * Contacts is asked in the same go, unless Android would only send the user to App info: then the card says so
 * first. A refused role says so and offers Try again, then Default apps with the steps.
 */
@Composable
private fun ColumnScope.CallsStep(setup: Setup, refusals: Int, onRefused: () -> Unit, onNext: () -> Unit) {
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup.onContactsResult() }
    fun askContacts() = setup.allowContacts { contacts.launch(Manifest.permission.READ_CONTACTS) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        setup.onScreeningResult()
        if (!setup.screening) onRefused()
        if (!setup.contacts && !setup.contactsBlocked()) askContacts()
    }
    val roleMissing = remember { !setup.screeningRoleAvailable() }
    val card = CallsCard.of(setup.screening, setup.contacts, refusals, contactsBlocked = setup.contactsBlocked(), roleMissing = roleMissing)
    Card(
        "phone", "Calls",
        "Lets DotForge block spam calls and show who's calling, using your contacts.",
        done = card.action == CallsCard.Action.NEXT, button = card.button, line = card.line, warning = card.warning,
        onAllow = {
            when (card.action) {
                CallsCard.Action.ASK_ROLE -> if (!setup.requestScreeningRole { role.launch(it) }) setup.openDefaultApps()
                CallsCard.Action.OPEN_DEFAULT_APPS -> setup.openDefaultApps()
                CallsCard.Action.ASK_CONTACTS -> askContacts()
                CallsCard.Action.OPEN_APP_INFO -> setup.openAppSettings()
                CallsCard.Action.NEXT -> onNext()
            }
        },
        onNext = onNext,
    )
}

/**
 * The optional caller name card. Coming back from Glyph settings counts as progress ("Opened" and Next): whether
 * the phone really says Sound only is read too, but only ever adds a "Done" (NothingSettings.soundOnly is not
 * checked on a Phone (3) yet).
 */
@Composable
private fun ColumnScope.CallerNameStep(setup: Setup, done: Boolean, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val link = remember { NothingSettings.glyphSettings(ctx.packageManager) }
    val fallback = link.intent.action == android.provider.Settings.ACTION_SETTINGS
    var opened by rememberSaveable { mutableStateOf(false) }
    val open = { opened = true; setup.openCallerNameSettings() }
    Card(
        "person", "Show the caller's name", CALLER_NAME_WHY,
        done = done || opened, doneLabel = if (done) "Done" else "Opened", optional = true,
        button = if (fallback) "Open settings" else "Open Glyph Interface",
        line = if (fallback) CALLER_NAME_STEPS else null,
        quiet = if (done) null else "Open again" to open,
        onAllow = open, onNext = onNext,
    )
}

const val CALLER_NAME_WHY = "To see who's calling, set Glyph ringtone lights to Sound only. Nothing's own ringtone light show turns off."
const val CALLER_NAME_STEPS = "Open Glyph Interface, then Ringtone lights, and pick Sound only."

// ---------------------------------------------------------------- Pick apps

/**
 * Pick apps: the installed messaging and call apps, WhatsApp, Messages (the phone's own SMS app first) and Phone
 * ticked when installed, and See all apps. Each row shows the glyph it will get. Continue writes normal Alerts
 * rows (each app's own icon); Phone is the Phone calls row.
 */
@Composable
private fun ColumnScope.PickAppsStep(preset: List<AppEntry>?, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf(preset) }
    LaunchedEffect(Unit) { if (installed == null) installed = runCatching { launcherApps(ctx) }.getOrDefault(emptyList()) }
    val sms = remember { if (preset != null) null else defaultSms(ctx) }
    val dialer = remember { if (preset != null) null else defaultDialer(ctx) }
    val all = installed
    val offered = remember(all) { all?.let { PickApps.offered(it, sms, dialer) } }
    var picked by rememberSaveable(offered) { mutableStateOf(offered?.let { PickApps.preTicked(it, sms, dialer) }.orEmpty().toList()) }
    var everything by rememberSaveable { mutableStateOf(false) }
    fun toggle(pkg: String) { picked = if (pkg in picked) picked - pkg else picked + pkg }
    StepLayout(
        top = {
            Title("Pick your apps")
            Body("Each one lights the Glyph with its own icon, shown on the right. Change them any time in Alerts.")
            Spacer(Modifier.height(Tokens.Gutter))
            when {
                offered == null -> LoadingRows(4)
                else -> {
                    if (offered.isEmpty()) Hint("No messaging or call apps found. See all apps to pick any app.")
                    else AppRows(offered, picked, dialer, ::toggle)
                    val rest = all.orEmpty().filterNot { a -> offered.any { it.packageName == a.packageName } }
                    if (!everything && rest.isNotEmpty()) TextButton(
                        onClick = { everything = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.Target).padding(top = Tokens.Small),
                    ) { Text("See all apps", style = MaterialTheme.typography.labelLarge, color = Color.White) }
                    if (everything && rest.isNotEmpty()) {
                        SectionLabel("All apps")
                        AppRows(rest, picked, dialer, ::toggle)
                    }
                }
            }
            Spacer(Modifier.height(Tokens.Gutter))
        },
        buttons = {
            PrimaryButton(if (picked.isEmpty()) "Continue" else "Continue with ${picked.size}", Modifier.fillMaxWidth(), enabled = offered != null) {
                val shown = offered.orEmpty() + if (everything) all.orEmpty() else emptyList()
                PickApps.apply(AppGraph.rules.rules.value, shown.distinctBy { it.packageName }, picked.toSet(), dialer).forEach(AppGraph.rules::upsert)
                onNext()
            }
            QuietButton("Not now", onNext)
        },
    )
}

/** Apps with the glyph each would get (40 dp) and a tick: the whole row toggles. */
@Composable
private fun AppRows(apps: List<AppEntry>, picked: List<String>, dialer: String?, toggle: (String) -> Unit) = RowGroup {
    apps.forEachIndexed { i, e ->
        if (i > 0) RowDivider(leading = true)
        val on = e.packageName in picked
        val glyph = remember(e.packageName) {
            val r = if (PickApps.isPhone(e.packageName, dialer)) com.dotforge.rules.SimpleRules.phoneCalls()
            else com.dotforge.rules.SimpleRules.newApp(e.packageName, e.label)
            com.dotforge.ui.alerts.posterOf(r, e.label)
        }
        ListRow(
            e.label,
            Modifier.toggleable(on, role = Role.Checkbox) { toggle(e.packageName) },
            leading = { AppIcon(e.packageName, e.label) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    GlyphPreview(glyph, "${e.label}'s glyph")
                    Checkbox(
                        on, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Tokens.Secondary),
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------- Done

/**
 * Done: the hello plays on screen and once on the real Glyph (an app frame; when it ends the arbiter clears the
 * matrix with closeAppMatrix), where to change things, and Go to Home. With a core permission still missing:
 * "Almost set" and what is missing, by name.
 */
@Composable
private fun ColumnScope.Done(device: DeviceState, missing: List<String>, played: Boolean, onPlayed: () -> Unit, onDone: () -> Unit) {
    val hello = remember { Hello.animation(AppGraph.profile) }
    val firstTime = remember { !played }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        onPlayed()
        if (device == DeviceState.PHONE3) AppGraph.arbiter.post(DisplayRequest(Hello.ID, Priority.STUDIO_PREVIEW, hello, loop = false, label = "Hello"))
    }
    StepLayout(
        top = {
            Spacer(Modifier.height(Tokens.Section))
            GlyphPreview(hello, "Hello", Modifier.size(bigPreview()), size = PreviewSize.LARGE, userPlay = firstTime)
            Title(if (missing.isEmpty()) "You're set" else "Almost set")
            Body("Change your apps in Alerts and your permissions in Profile.")
            com.dotforge.setup.Perms.needLine(missing)?.let { need ->
                Line("$need. Home shows how to fix ${if (missing.size == 1) "it" else "them"}.")
            }
        },
        buttons = {
            PrimaryButton("Go to Home", Modifier.fillMaxWidth(), onClick = onDone)
            QuietSlot()
        },
    )
}

/** The 200 dp preview, or 128 dp on a short screen (360x640 and the like) so the words and buttons fit. */
@Composable
private fun bigPreview() = if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 720) 128.dp else Tokens.PreviewLarge
