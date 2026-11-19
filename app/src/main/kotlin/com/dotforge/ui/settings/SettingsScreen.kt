package com.dotforge.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dotforge.backup.BackupStore
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.design.PersonAvatar
import com.dotforge.ui.design.RowDivider
import androidx.compose.runtime.mutableIntStateOf
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.HapticsSetting
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.Page as PageLayout

/**
 * One permission's state, said in words beside a dot: Allowed (green), Needed (red: a core permission, or one
 * that something switched on needs), Optional (grey: nothing needs it yet).
 */
enum class PermissionState(val label: String, val spoken: String) {
    ALLOWED("Allowed", "Allowed"),
    NEEDED("Needed", "Not allowed, needed"),
    OPTIONAL("Optional", "Not allowed, optional"),
}

/** A permission row's content, so the list can be tested and rendered without a phone. [core]: alerts need it. */
data class PermissionItem(
    val title: String,
    val why: String,
    val state: PermissionState,
    val glyph: String = "dot",
    val core: Boolean = false,
    val onClick: () -> Unit,
)

fun permissionState(granted: Boolean, needed: Boolean) =
    if (granted) PermissionState.ALLOWED else if (needed) PermissionState.NEEDED else PermissionState.OPTIONAL

/** "All allowed", "2 need permission" or "1 needs permission": Profile's Permissions row. */
fun permissionsSummary(items: List<PermissionItem>): String {
    val n = items.count { it.state == PermissionState.NEEDED }
    return when (n) {
        0 -> "All set"
        1 -> "1 needs permission"
        else -> "$n need permission"
    }
}

/**
 * Every permission DotForge uses, with what it is for. The core four (as First run asks them) are Needed until
 * allowed; the rest are Needed only while something switched on uses them. Tapping opens the same Android screen
 * First run opens.
 */
@Composable
fun rememberPermissions(setup: Setup): List<PermissionItem> {
    val ctx = LocalContext.current
    val resumes = setup.resumes
    val location = remember(resumes) {
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val usage = remember(resumes) { com.dotforge.toys.DayUsage.allowed(ctx) }
    fun open(intent: android.content.Intent) {
        runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    // the same Android screens First run opens: the role dialog and the Contacts dialog, no sheet first
    val role = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { setup.onScreeningResult() }
    val contacts = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { setup.onContactsResult() }
    val appInfo = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
    return listOf(
        PermissionItem("Notification access", "Lights the Glyph for your apps, caller names and live progress", permissionState(setup.notificationAccess, true), "bell", core = true) {
            if (setup.notificationAccess) setup.manageAccess() else setup.openAccessSettings()
        },
        PermissionItem("Call screening", "Blocks spam calls and shows who's calling", permissionState(setup.screening, true), "phone", core = true) {
            if (setup.screening || !setup.requestScreeningRole { role.launch(it) }) setup.openDefaultApps()
        },
        PermissionItem("Contacts", "Names for people and callers", permissionState(setup.contacts, true), "people", core = true) {
            if (setup.contacts) setup.openAppInfo() else setup.allowContacts { contacts.launch(android.Manifest.permission.READ_CONTACTS) }
        },
        PermissionItem("Keep running", "So the Glyph still lights when the screen is off", permissionState(setup.keepRunning, true), "battery", core = true) { setup.needKeepRunning() },
        PermissionItem("Microphone", "Only for the Music visualiser", permissionState(setup.microphone, false), "music") { setup.needMicrophone() },
        PermissionItem("Notifications", "The timer on the lock screen, and quiet copies of silenced ones", permissionState(setup.notifications, false), "chat") { setup.needNotifications() },
        PermissionItem("Modify system settings", "Only to set Glyph ringtones", permissionState(setup.systemSettings, false), "ring") { setup.openWriteSettings() },
        PermissionItem("Location", "Only for DF Weather's \"Where I am\"", permissionState(location, false), "pin") { open(appInfo) },
        PermissionItem("Usage access", "Only for DF Day dots' screen time", permissionState(usage, false), "clock") {
            open(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
    )
}

/** "Last backup 3 days ago" or "Never backed up". */
fun lastBackupText(lastMs: Long, now: Long = System.currentTimeMillis()): String =
    if (lastMs <= 0) "Never backed up"
    else "Last backup " + DateUtils.getRelativeTimeSpanString(lastMs, now, DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }

/**
 * Profile: a placeholder account card, then every Settings row: Permissions (a status dot and text each),
 * Backup and restore, Haptics, Help, About. Opened only from the avatar on Home.
 */
@Composable
fun ProfilePage(nav: Nav, permissions: List<PermissionItem>, helpOpen: Boolean = false) {
    val ctx = LocalContext.current
    var help by rememberSaveable { mutableStateOf(helpOpen) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    val snacks = com.dotforge.ui.design.rememberSnacks()
    var taps by remember { mutableIntStateOf(0) }

    PageLayout("Profile", onBack = nav::pop) {
        item(key = "profile") { ProfileCard() }

        item(key = "permissions") {
            SectionLabel("Settings")
            RowGroup {
                // one row with the worst state's dot; the list is its own page (Settings > Permissions)
                val worst = if (permissions.any { it.state == PermissionState.NEEDED }) PermissionState.NEEDED else PermissionState.ALLOWED
                ListRow(
                    "Permissions", subtitle = permissionsSummary(permissions),
                    trailing = { Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(worst); Chevron() } },
                ) { nav.push(Page.Permissions) }
            }
        }

        item(key = "app") {
            SectionLabel("DotForge")
            RowGroup {
                ListRow("Backup and restore", subtitle = lastBackupText(BackupStore.lastMs), trailing = { Chevron() }) { nav.push(Page.Backup) }
                RowDivider()
                // parity row 92: the widget, from here as well as Features > Toys
                ListRow("Home screen widget", subtitle = "A design or the live Glyph on your home screen", subtitleLines = 3, trailing = { Chevron() }) {
                    if (!com.dotforge.widget.GlyphWidgetProvider.requestPin(ctx)) snacks?.show("Touch and hold your home screen, then Widgets > DotForge.", action = "")
                }
                RowDivider()
                SwitchRow("Haptics", "A light tick when you flip a switch", HapticsSetting.on) { HapticsSetting.set(it) }
            }
        }

        item(key = "help") {
            SectionLabel("Help")
            RowGroup {
                ListRow("How DotForge works", trailing = { Chevron() }) { help = true }
                RowDivider()
                ListRow("My glyph didn't light", subtitle = "See what happened to each alert", trailing = { Chevron() }) { nav.showHistory() }
            }
        }

        item(key = "about") {
            SectionLabel("About")
            RowGroup {
                // tapping the version 7 times shows the developer rows (old screens, the component gallery)
                ListRow("DotForge", subtitle = "Version $version") {
                    if (DevMode.on) return@ListRow
                    taps++
                    if (taps >= DevMode.TAPS) { DevMode.set(true); snacks?.show("Developer rows are on") }
                }
                RowDivider()
                ListRow("Licences", trailing = { Chevron() }) { nav.push(Page.Licences) }
                RowDivider()
                ListRow("Diagnostics", subtitle = "Logs to share if something breaks", trailing = { Chevron() }) { nav.push(Page.Diagnostics) }
            }
            Hint("Everything runs on this phone. No account, no ads, no tracking.", Modifier.padding(top = Tokens.Small))
        }

        if (DevMode.on) item(key = "old") {
            SectionLabel("Developer")
            RowGroup {
                ListRow("Old screens", subtitle = "The screens from before the redesign", trailing = { Chevron() }) { nav.push(Page.OldScreens) }
                RowDivider()
                ListRow("Component gallery", trailing = { Chevron() }) { nav.push(Page.Components) }
                RowDivider()
                ListRow("Hide developer rows") { DevMode.set(false); taps = 0 }
            }
        }
    }

    if (help) HowItWorksSheet { help = false }
}

/** Developer rows in Profile, shown after tapping the version 7 times (prefs "ui"). */
object DevMode {
    const val TAPS = 7
    private const val KEY = "dev_rows"
    private var prefs: android.content.SharedPreferences? = null
    var on by mutableStateOf(false)
        private set

    fun init(p: android.content.SharedPreferences) {
        prefs = p
        on = p.getBoolean(KEY, false)
    }

    fun set(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        on = value
    }
}

/** The account placeholder: sign-in comes later. */
@Composable
fun ProfileCard() =
    RowGroup(Modifier.padding(top = Tokens.Small)) {
        Row(Modifier.fillMaxWidth().padding(Tokens.Gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter)) {
            PersonAvatar(size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text("Your profile", style = MaterialTheme.typography.titleLarge)
                Text("Sign-in and cloud backup come in a later update.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
            }
        }
    }

/**
 * A permission: its dot icon, the name, what it is for, and its state under them as a dot and a word (never
 * colour alone), then a chevron. The state sits under the words, not at the end, so the name keeps the whole
 * width and never breaks inside a word at 2x text.
 */
@Composable
fun PermissionRow(p: PermissionItem) =
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.RowTall)
            .clickable(onClick = p.onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${p.title}, ${p.state.spoken}. ${p.why}" }
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        Box(Modifier.size(Tokens.Avatar), contentAlignment = Alignment.Center) { com.dotforge.ui.design.LibraryGlyph(p.glyph, still = true) }
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge)
            Text(p.why, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
            StateLabel(p.state, Modifier.padding(top = Tokens.Tiny))
        }
        Chevron()
    }

/** A 10 dp status dot: green Allowed, red Needed, grey Optional. */
@Composable
fun StatusDot(state: PermissionState, modifier: Modifier = Modifier) =
    Box(
        modifier.size(10.dp).background(
            when (state) {
                PermissionState.ALLOWED -> Tokens.Allowed
                PermissionState.NEEDED -> Tokens.Accent
                PermissionState.OPTIONAL -> Tokens.Secondary
            },
            CircleShape,
        ),
    )

/** The dot and its word: "Allowed", "Needed", "Optional". */
@Composable
fun StateLabel(state: PermissionState, modifier: Modifier = Modifier) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(state)
        Text(state.label, style = MaterialTheme.typography.labelLarge, color = if (state == PermissionState.OPTIONAL) Tokens.Secondary else Color.White, maxLines = 1)
    }

/**
 * Settings > Permissions: every permission as a row (dot icon, name, plain reason, a status dot and word).
 * Needed for alerts first, then the ones only some features use. Home's banner opens this page.
 */
@Composable
fun PermissionsPage(nav: Nav, permissions: List<PermissionItem>) =
    PageLayout("Permissions", onBack = nav::pop) {
        item(key = "what") { Hint("Tap one to open Android's page for it. Nothing leaves this phone.") }
        listOf("Needed for alerts" to permissions.filter { it.core }, "Only for some features" to permissions.filterNot { it.core }).forEach { (label, items) ->
            if (items.isNotEmpty()) item(key = label) {
                SectionLabel(label)
                RowGroup {
                    items.forEachIndexed { i, p ->
                        if (i > 0) RowDivider(leading = true)
                        PermissionRow(p)
                    }
                }
            }
        }
    }

/** Profile > Help > How DotForge works: the few rules a user needs, in plain words. */
@Composable
fun HowItWorksSheet(onDismiss: () -> Unit) {
    FullSheet(onDismiss, "How DotForge works") {
        HELP.forEach { (title, body) ->
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Tokens.Gutter))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Tiny))
        }
    }
}

val HELP = listOf(
    "Alerts" to "DotForge reads your notifications on this phone and lights the Glyph on the back for the apps and people you pick.",
    "Until I check" to "An alert set to show until you check stays until its notification is gone (read or dismissed anywhere), you open the app that sent it, or you tap Stop on Home. It stops waiting after 4 hours.",
    "Face down" to "Nothing hides lights from apps when the phone is face down or in a pocket. DotForge alerts show when the phone is face up.",
    "Nothing's own lights" to "When Nothing shows its own light (charging, its own notification lights), ours wait and come back afterwards.",
    "Spam" to "Spam calls are declined silently and stay in your call log. Spam texts and app notifications are hidden; the message stays in its app.",
    "Your data" to "Everything stays on this phone. Back up from Profile so a new phone or a reinstall gets your designs and rules back.",
)
