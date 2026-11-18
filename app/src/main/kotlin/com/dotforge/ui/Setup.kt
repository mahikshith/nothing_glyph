package com.dotforge.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.notify.GlyphNotificationListener
import com.dotforge.rules.Action
import com.dotforge.setup.AccessFlow
import com.dotforge.visualiser.LiveVisualiser

/**
 * Live permission state and the ask-when-first-used flows. Notification access goes through [AccessFlow]
 * (explain, open settings, and the restricted-settings unlock steps if Android blocked it); Contacts is a
 * normal runtime permission, asked inline ([ContactsAsk]) when a rule's People picker opens or a spam setting
 * needs it, or from the Settings row.
 */
class Setup(private val activity: ComponentActivity) {
    private val flow = AccessFlow()
    private val prefs = activity.getSharedPreferences("setup", Context.MODE_PRIVATE)

    var notificationAccess by mutableStateOf(hasNotificationAccess())
        private set
    var contacts by mutableStateOf(hasContacts())
        private set
    var accessStep by mutableStateOf(AccessFlow.Step.NONE)
        private set
    var contactsSheet by mutableStateOf(false)
        private set
    /** The microphone sheet for the Live visualiser. */
    var micSheet by mutableStateOf(false)
        private set
    /** DotForge holds the call screening role (call rules work). */
    var screening by mutableStateOf(hasScreening())
        private set
    /** The call screening sheet. */
    var screeningSheet by mutableStateOf(false)
        private set
    /** The sheet for posting notifications (quiet copies of silenced ones). */
    var postSheet by mutableStateOf(false)
        private set
    /** The sheet for installing toys (Android's "Install unknown apps" switch). */
    var installSheet by mutableStateOf(false)
        private set
    /** Microphone (Music visualiser), notifications (quiet copies) and Modify system settings (ringtones). */
    var microphone by mutableStateOf(hasMic())
        private set
    var notifications by mutableStateOf(canPost())
        private set
    var systemSettings by mutableStateOf(Settings.System.canWrite(activity))
        private set
    /** Left out of battery optimisation, so alerts, timers and the visualiser keep running. */
    var keepRunning by mutableStateOf(hasKeepRunning())
        private set

    private fun hasKeepRunning() =
        activity.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(activity.packageName) == true

    /** "Keep DotForge running": Android's one-tap dialog, or App info when it is already allowed. */
    @android.annotation.SuppressLint("BatteryLife")
    fun needKeepRunning() {
        if (hasKeepRunning()) return openAppDetails()
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        }.onFailure { openAppDetails() }
    }
    /** Bumped on every resume, so screens can re-read system state. */
    var resumes by mutableIntStateOf(0)
        private set

    private fun hasNotificationAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)

    private fun hasContacts() =
        activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun hasScreening() =
        activity.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    /** A call rule was switched on or saved. Returns true when DotForge already screens calls. */
    fun needCallScreening(): Boolean {
        screening = hasScreening()
        if (!screening) screeningSheet = true
        return screening
    }

    fun screeningNotNow() {
        screeningSheet = false
        prefs.edit().putBoolean(KEY_SCREENING_ASKED, true).apply()
    }

    /** Setup: call or spam protection is on but DotForge doesn't screen calls; asked once, with Not now. */
    fun askScreeningOnce() {
        screening = hasScreening()
        if (!screening && !prefs.getBoolean(KEY_SCREENING_ASKED, false)) screeningSheet = true
    }

    /** Android's own dialog to make DotForge the call screening app. */
    fun allowScreening(launch: (Intent) -> Unit) {
        screeningSheet = false
        val rm = activity.getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    fun onScreeningResult() {
        screening = hasScreening()
    }

    private fun canPost() =
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * A rule that silences or marks notifications as spam was saved: DotForge reposts a quiet copy, which needs
     * permission to post notifications. Asked once; without it silenced notifications are hidden.
     */
    fun needPostNotifications(): Boolean {
        if (canPost()) return true
        if (!prefs.getBoolean(KEY_POST_ASKED, false)) postSheet = true
        return false
    }

    fun postNotNow() {
        postSheet = false
        prefs.edit().putBoolean(KEY_POST_ASKED, true).apply()
    }

    fun allowPost(launch: () -> Unit) {
        postSheet = false
        val asked = prefs.getBoolean(KEY_POST_ASKED, false)
        prefs.edit().putBoolean(KEY_POST_ASKED, true).apply()
        if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openAppDetails() else launch()
    }

    /** A toy is about to be installed. Returns true when Android lets DotForge install apps. */
    fun needInstallPermission(): Boolean {
        if (activity.packageManager.canRequestPackageInstalls()) return true
        installSheet = true
        return false
    }

    fun installNotNow() {
        installSheet = false
    }

    fun allowInstall() {
        installSheet = false
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        }.onFailure { openAppDetails() }
    }

    private fun sync() {
        accessStep = flow.step
    }

    fun onResume() {
        notificationAccess = hasNotificationAccess()
        contacts = hasContacts()
        screening = hasScreening()
        microphone = hasMic()
        notifications = canPost()
        systemSettings = Settings.System.canWrite(activity)
        keepRunning = hasKeepRunning()
        flow.resumed(notificationAccess)
        sync()
        resumes++
    }

    /** A feature that reads notifications was switched on or opened. Returns true when access is there. */
    fun needNotificationAccess(): Boolean = flow.request(notificationAccess).also { sync() }

    fun openAccessSettings() {
        flow.openedAccessSettings()
        sync()
        launchAccessSettings()
    }

    /** Opens the access page without starting the flow, e.g. to turn access off again. */
    fun manageAccess() = launchAccessSettings()

    private fun launchAccessSettings() {
        val component = ComponentName(activity, GlyphNotificationListener::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        if (runCatching { activity.startActivity(detail) }.isFailure) {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    fun openAppInfo() {
        flow.openedAppInfo()
        sync()
        openAppDetails()
    }

    private fun openAppDetails() = activity.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
    )

    fun accessNotNow() {
        flow.notNow()
        sync()
    }

    /** Ask for Contacts with the sheet (the Settings row). Returns true when Contacts can be read. */
    fun needContacts(): Boolean {
        if (contacts) return true
        contactsSheet = true
        return false
    }

    fun contactsNotNow() {
        contactsSheet = false
    }

    /** Ask with the system dialog, or open App info once Android stops showing it (denied twice). */
    fun allowContacts(launch: () -> Unit) {
        contactsSheet = false
        val asked = prefs.getBoolean(KEY_CONTACTS_ASKED, false)
        if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            openAppDetails()
        } else {
            prefs.edit().putBoolean(KEY_CONTACTS_ASKED, true).apply()
            launch()
        }
    }

    fun onContactsResult() {
        contacts = hasContacts()
    }

    /** Profile > Permissions: Android's page for "Modify system settings". */
    fun openWriteSettings() {
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }.onFailure { openAppDetails() }
    }

    /** Profile > Permissions: the microphone, asked through the same sheet as the visualiser. */
    fun needMicrophone() {
        micStartsLive = false
        if (!hasMic()) micSheet = true else openAppDetails()
    }

    /** The microphone was asked for by the visualiser (start it once allowed), not by Profile. */
    private var micStartsLive = true

    /** Profile > Permissions: posting notifications. */
    fun needNotifications() {
        if (!canPost()) postSheet = true else openAppDetails()
    }

    private fun hasMic() =
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Live switch on (Glyph tab or the Quick Settings tile): start, or ask for the microphone first. */
    fun startLive() {
        if (!hasMic()) {
            micStartsLive = true
            micSheet = true
            return
        }
        if (!LiveVisualiser.start(activity)) Toast.makeText(activity, "Android didn't let the visualiser start.", Toast.LENGTH_SHORT).show()
    }

    fun micNotNow() {
        micSheet = false
    }

    /** Ask with the system dialog, or open App info once Android stops showing it. */
    fun allowMic(launch: () -> Unit) {
        micSheet = false
        val asked = prefs.getBoolean(KEY_MIC_ASKED, false)
        if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppDetails()
        } else {
            prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply()
            launch()
        }
    }

    fun onMicResult(granted: Boolean) {
        microphone = granted
        if (granted && micStartsLive) startLive()
    }

    private companion object {
        const val KEY_CONTACTS_ASKED = "contacts_asked"
        const val KEY_MIC_ASKED = "mic_asked"
        const val KEY_POST_ASKED = "post_asked"
        const val KEY_SCREENING_ASKED = "screening_asked"
    }
}

/** The permission sheets, shown over every screen. Also re-reads permission state on each resume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupSheets(setup: Setup) {
    LifecycleResumeEffect(setup) {
        setup.onResume()
        onPauseOrDispose { }
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setup.onContactsResult()
    }

    when (setup.accessStep) {
        AccessFlow.Step.NONE -> Unit
        AccessFlow.Step.EXPLAIN -> Sheet(
            onDismiss = setup::accessNotNow,
            title = "Allow notification access",
            body = "DotForge reads notifications on this phone to light the Glyph Matrix. Nothing leaves the phone.",
            primary = "Open settings" to setup::openAccessSettings,
            secondary = null,
        )
        AccessFlow.Step.UNLOCK -> Sheet(
            onDismiss = setup::accessNotNow,
            title = "Android blocked access",
            body = "DotForge was installed from a file, so Android locks this setting until you allow it:\n\n" +
                "1.  Tap Open App info.\n" +
                "2.  Tap ⋮ (top right), then Allow restricted settings.\n" +
                "3.  Come back and tap Try again.\n\n" +
                "No such option? Tap Try again, tap the greyed-out switch once, then come back here.",
            primary = "Open App info" to setup::openAppInfo,
            secondary = "Try again" to setup::openAccessSettings,
        )
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup.onMicResult(it) }
    if (setup.micSheet) {
        Sheet(
            onDismiss = setup::micNotNow,
            title = "Allow the microphone",
            body = "The visualiser reads the sound that is playing on this phone, which Android counts as microphone " +
                "access. If no music data comes through, it listens with the microphone instead. Nothing is recorded or saved.",
            primary = "Allow" to { setup.allowMic { micLauncher.launch(Manifest.permission.RECORD_AUDIO) } },
            secondary = null,
        )
    }

    val screeningLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { setup.onScreeningResult() }
    if (setup.screeningSheet) {
        Sheet(
            onDismiss = setup::screeningNotNow,
            title = "Let DotForge screen calls",
            body = "Call rules work when DotForge is the phone's call screening app. Android asks you once. Calls are " +
                "checked on this phone; nothing about them leaves it.",
            primary = "Allow" to { setup.allowScreening { screeningLauncher.launch(it) } },
            secondary = null,
        )
    }

    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (setup.postSheet) {
        Sheet(
            onDismiss = setup::postNotNow,
            title = "Keep silenced notifications",
            body = "Android can't mute another app's notification, so DotForge removes it and puts a quiet copy in " +
                "your shade. That copy needs permission to post notifications; without it, silenced ones are hidden.",
            primary = "Allow" to { setup.allowPost { postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } },
            secondary = null,
        )
    }

    if (setup.installSheet) {
        Sheet(
            onDismiss = setup::installNotNow,
            title = "Allow installing toys",
            body = "Toys from GitHub are apps, so Android asks you to let DotForge install apps. Turn on Allow from " +
                "this source, come back and tap Install again. Android still asks before each install.",
            primary = "Open settings" to setup::allowInstall,
            secondary = null,
        )
    }

    if (setup.contactsSheet) {
        Sheet(
            onDismiss = setup::contactsNotNow,
            title = "Allow Contacts",
            body = "Lets rules and the spam filter recognise people you know. Contacts are only read on this phone.",
            primary = "Allow" to { setup.allowContacts { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) } },
            secondary = null,
        )
    }
}

/** A permission sheet in the design system: 16 dp margins, the red main button, and a full-width Not now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(
    onDismiss: () -> Unit,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.dotforge.ui.design.Tokens.Sheet,
        tonalElevation = 0.dp,
        shape = com.dotforge.ui.design.Tokens.SheetShape,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(
                body, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = com.dotforge.ui.design.Tokens.Secondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            com.dotforge.ui.design.PrimaryButton(primary.first, Modifier.fillMaxWidth(), onClick = primary.second)
            secondary?.let { (label, action) -> com.dotforge.ui.design.SecondaryButton(label, Modifier.fillMaxWidth().padding(top = 8.dp), onClick = action) }
            com.dotforge.ui.design.SecondaryButton("Not now", Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onDismiss)
        }
    }
}

/**
 * The Contacts ask inside a dialog or picker (a bottom sheet could open behind it): [title], [why], Allow and
 * Not now. Shows nothing once Contacts is granted or after Not now.
 */
@Composable
fun ContactsAsk(setup: Setup, title: String, why: String, modifier: Modifier = Modifier) {
    var notNow by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup.onContactsResult() }
    if (setup.contacts || notNow) return
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text("$why Contacts are only read on this phone.", color = Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { setup.allowContacts { launcher.launch(Manifest.permission.READ_CONTACTS) } }) {
                    Text("Allow", color = NothingRed)
                }
                TextButton(onClick = { notNow = true }) { Text("Not now", color = Color.White) }
            }
        }
    }
}

/** A card shown on pages whose feature needs notification access while it is missing. */
@Composable
fun AccessBanner(setup: Setup, text: String) {
    if (setup.notificationAccess) return
    Surface(color = Surface2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 14.sp)
            TextButton(onClick = { setup.needNotificationAccess() }) { Text("Allow", color = NothingRed) }
        }
    }
}

/**
 * True while an enabled rule that shows or blocks needs to know who is a contact. The default "always allow
 * contacts" rule alone doesn't count: it is asked for on the Calls and Spam pages instead.
 */
@Composable
fun rememberNeedsContacts(): State<Boolean> {
    val rules = AppGraph.rules.rules.collectAsStateWithLifecycle()
    return remember { derivedStateOf { rules.value.any { it.enabled && it.usesContacts && it.action != Action.ALLOW } } }
}

/** True while any enabled call or spam protection (a blocking rule that applies to calls) needs call screening. */
@Composable
fun rememberNeedsScreening(): State<Boolean> {
    val rules = AppGraph.rules.rules.collectAsStateWithLifecycle()
    return remember { derivedStateOf { com.dotforge.rules.RuleEngine.needsCallScreening(rules.value) } }
}
