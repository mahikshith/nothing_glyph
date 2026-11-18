package com.dotforge.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import com.dotforge.ui.design.Avatar
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

/** One permission's state: allowed, not allowed and needed by something that is on, or not allowed and not needed yet. */
enum class PermissionState(val label: String) { ALLOWED("Allowed"), NEEDED("Not allowed"), OPTIONAL("Not allowed") }

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
        PermissionItem("Notification access", "Lights up the Glyph for your apps", permissionState(setup.notificationAccess, true)) {
            if (setup.notificationAccess) setup.manageAccess() else setup.needNotificationAccess()
        },
        PermissionItem("Call screening", "Blocks spam calls and shows caller glyphs", permissionState(setup.screening, needsScreening)) { setup.needCallScreening() },
        PermissionItem("Contacts", "Names for people and callers", permissionState(setup.contacts, needsContacts)) {
            if (setup.contacts) setup.openAppInfo() else setup.needContacts()
        },
        PermissionItem("Keep DotForge running", "Keeps alerts and timers working in the background", permissionState(setup.keepRunning, true)) { setup.needKeepRunning() },
        PermissionItem("Microphone", "Only for the Music visualiser", permissionState(setup.microphone, false)) { setup.needMicrophone() },
        PermissionItem("Modify system settings", "Only to set Glyph ringtones", permissionState(setup.systemSettings, false)) { setup.openWriteSettings() },
        PermissionItem("Notifications", "Quiet copies of the notifications DotForge silences", permissionState(setup.notifications, false)) { setup.needNotifications() },
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

    PageLayout("Profile", onBack = nav::pop) {
        item(key = "profile") { ProfileCard() }

        item(key = "permissions") {
            SectionLabel("Permissions")
            RowGroup {
                permissions.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    PermissionRow(p)
                }
            }
        }

        item(key = "app") {
            SectionLabel("DotForge")
            RowGroup {
                ListRow("Backup and restore", subtitle = lastBackupText(BackupStore.lastMs), trailing = { Chevron() }) { nav.push(Page.Backup) }
                Divider()
                SwitchRow("Haptics", "A light tick on switches. Follows your phone's touch feedback.", HapticsSetting.on) { HapticsSetting.set(it) }
            }
        }

        item(key = "help") {
            SectionLabel("Help")
            RowGroup {
                ListRow("How DotForge works", trailing = { Chevron() }) { help = true }
                Divider()
                ListRow("My glyph didn't light", subtitle = "See what happened to each alert", trailing = { Chevron() }) { nav.showHistory() }
            }
        }

        item(key = "about") {
            SectionLabel("About")
            RowGroup {
                ListRow("DotForge", subtitle = "Version $version")
                Divider()
                ListRow("Licences", trailing = { Chevron() }) { nav.push(Page.Licences) }
                Divider()
                ListRow("Diagnostics", subtitle = "Driver test bench and logs", trailing = { Chevron() }) { nav.push(Page.Diagnostics) }
            }
            Hint("Everything runs on this phone. No account, no ads, no tracking.", Modifier.padding(top = Tokens.Small))
        }

        item(key = "old") {
            SectionLabel("Temporary")
            RowGroup {
                ListRow("Old screens", subtitle = "The screens from before the redesign", trailing = { Chevron() }) { nav.push(Page.OldScreens) }
            }
        }
    }

    if (help) HowItWorksSheet { help = false }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = Tokens.Gutter), color = Tokens.Hairline)

/** The account placeholder: sign-in comes later. */
@Composable
fun ProfileCard() =
    RowGroup(Modifier.padding(top = Tokens.Small)) {
        Row(Modifier.fillMaxWidth().padding(Tokens.Gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter)) {
            Avatar("D", Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text("Your profile", style = MaterialTheme.typography.titleLarge)
                Text("Sign-in and cloud backup are coming later", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
            }
        }
    }

/** A permission with a status dot and its state in words (never colour alone). */
@Composable
fun PermissionRow(p: PermissionItem) =
    ListRow(
        p.title,
        Modifier.semantics(mergeDescendants = true) { contentDescription = "${p.title}, ${p.state.label}. ${p.why}" },
        subtitle = "${p.state.label} · ${p.why}",
        subtitleLines = 4,
        leading = { StatusDot(p.state) },
        trailing = { Chevron() },
        onClick = p.onClick,
    )

/** White when allowed, red when needed, a grey ring when not needed yet. */
@Composable
fun StatusDot(state: PermissionState) {
    val m = Modifier.size(12.dp)
    Box(Modifier.size(Tokens.Icon), contentAlignment = Alignment.Center) {
        when (state) {
            PermissionState.ALLOWED -> Box(m.background(Color.White, CircleShape))
            PermissionState.NEEDED -> Box(m.background(Tokens.AccentText, CircleShape))
            PermissionState.OPTIONAL -> Box(m.border(1.5.dp, Tokens.Secondary, CircleShape))
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
