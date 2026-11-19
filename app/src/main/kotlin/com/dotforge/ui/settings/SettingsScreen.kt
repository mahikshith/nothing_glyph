package com.dotforge.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import com.dotforge.ui.rememberNeedsContacts
import com.dotforge.ui.rememberNeedsScreening
import com.dotforge.ui.design.Page as PageLayout

/**
 * One permission's state, said in words with an icon: On (allowed), Needed (not allowed, and something that is on
 * needs it), Optional (not allowed, and nothing needs it yet).
 */
enum class PermissionState(val label: String, val spoken: String) {
    ALLOWED("On", "Allowed"),
    NEEDED("Needed", "Not allowed, needed"),
    OPTIONAL("Optional", "Not allowed, optional"),
}

/** A permission row's content, so the list can be tested and rendered without a phone. */
data class PermissionItem(val title: String, val why: String, val state: PermissionState, val onClick: () -> Unit)

fun permissionState(granted: Boolean, needed: Boolean) =
    if (granted) PermissionState.ALLOWED else if (needed) PermissionState.NEEDED else PermissionState.OPTIONAL

/** Every permission DotForge uses, with what it is for. */
@Composable
fun rememberPermissions(setup: Setup): List<PermissionItem> {
    val needsContacts by rememberNeedsContacts()
    val needsScreening by rememberNeedsScreening()
    return listOf(
        PermissionItem("Notification access", "Lights the Glyph for your apps", permissionState(setup.notificationAccess, true)) {
            if (setup.notificationAccess) setup.manageAccess() else setup.needNotificationAccess()
        },
        PermissionItem("Call screening", "Blocks spam calls, shows callers", permissionState(setup.screening, needsScreening)) { setup.needCallScreening() },
        PermissionItem("Contacts", "Names for people and callers", permissionState(setup.contacts, needsContacts)) {
            if (setup.contacts) setup.openAppInfo() else setup.needContacts()
        },
        PermissionItem("Keep DotForge running", "Keeps alerts working in the background", permissionState(setup.keepRunning, true)) { setup.needKeepRunning() },
        PermissionItem("Microphone", "Only for the Music visualiser", permissionState(setup.microphone, false)) { setup.needMicrophone() },
        PermissionItem("Modify system settings", "Only to set Glyph ringtones", permissionState(setup.systemSettings, false)) { setup.openWriteSettings() },
        PermissionItem("Notifications", "Quiet copies of silenced ones", permissionState(setup.notifications, false)) { setup.needNotifications() },
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
            SectionLabel("Permissions")
            RowGroup {
                permissions.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    PermissionRow(p)
                }
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

/** A permission: what it is for in one line, and its state on the right as a word with an icon (never colour alone). */
@Composable
fun PermissionRow(p: PermissionItem) =
    ListRow(
        p.title,
        Modifier.semantics(mergeDescendants = true) { contentDescription = "${p.title}, ${p.state.spoken}. ${p.why}" },
        subtitle = p.why,
        subtitleLines = 1,
        trailing = { StateLabel(p.state) },
        onClick = p.onClick,
    )

/** "On" with a check, "Needed" with a red warning, "Optional" with a grey ring. */
@Composable
fun StateLabel(state: PermissionState) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
        val m = Modifier.size(18.dp)
        when (state) {
            PermissionState.ALLOWED -> Icon(Icons.Filled.CheckCircle, null, m, tint = Color.White)
            PermissionState.NEEDED -> Icon(Icons.Filled.Warning, null, m, tint = Tokens.Accent)
            PermissionState.OPTIONAL -> Box(m.padding(2.dp).border(1.5.dp, Tokens.Secondary, CircleShape))
        }
        Text(state.label, style = MaterialTheme.typography.labelLarge, color = if (state == PermissionState.OPTIONAL) Tokens.Secondary else Color.White)
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
