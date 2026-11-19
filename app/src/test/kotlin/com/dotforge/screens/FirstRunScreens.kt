package com.dotforge.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.notify.GlyphNotificationListener
import com.dotforge.setup.FirstRunFlow.Step
import com.dotforge.setup.FirstRunPrefs
import com.dotforge.system.NothingSettings
import com.dotforge.ui.AppEntry
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.SetupSheets
import com.dotforge.ui.Tab
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.home.HomeNow
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.home.HomeStatus
import com.dotforge.ui.onboarding.FirstRun
import com.dotforge.ui.settings.PermissionState
import com.dotforge.ui.settings.PermissionsPage
import com.dotforge.ui.settings.ProfilePage
import com.dotforge.ui.settings.rememberPermissions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/**
 * First run and Settings > Permissions: every card not allowed and allowed, a refused role, the not-a-Phone (3)
 * welcome and the restore link, Pick apps (several installed, only Phone, all apps), Done, the Permissions list
 * all allowed and mixed, Profile's row, and Home's banner. Permission states are set on the (Robolectric) phone,
 * so the screens read them the way they do on a real one.
 */
abstract class FirstRunScreens : ScreenshotTest() {
    private val f = "first-run"
    private val ctx get() = activity

    @Before fun fresh() {
        ShadowRoleManager.reset()
        FirstRunPrefs.reset(activity)
        // NotificationManagerCompat keeps the last list it read while the setting is unset: say "none" outright
        Settings.Secure.putString(activity.contentResolver, "enabled_notification_listeners", "")
        AppGraph.arbiter.clearAll()
    }

    @After fun clean() {
        AppGraph.arbiter.clearAll()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private fun allowAccess() = Settings.Secure.putString(
        ctx.contentResolver, "enabled_notification_listeners", ComponentName(ctx, GlyphNotificationListener::class.java).flattenToString(),
    )
    private fun allowCalls() {
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        shadowOf(ctx.application).grantPermissions(Manifest.permission.READ_CONTACTS)
    }
    private fun allowKeepRunning() = shadowOf(ctx.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
    private fun soundOnly() = Settings.Global.putString(ctx.contentResolver, NothingSettings.RINGTONE_MODE, "1")
    private fun glyphSettingsExist() {
        val glyphs = ComponentName("com.android.settings", "com.nothing.settings.GlyphSettings")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(glyphs)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(glyphs, IntentFilter("android.settings.ACTION_GLYPHS_SETTINGS").apply { addCategory(android.content.Intent.CATEGORY_DEFAULT) })
    }

    private val many = listOf(
        AppEntry("com.whatsapp", "WhatsApp"), AppEntry("com.google.android.apps.messaging", "Messages"),
        AppEntry("com.google.android.dialer", "Phone"), AppEntry("org.telegram.messenger", "Telegram"),
        AppEntry("com.instagram.android", "Instagram"), AppEntry("com.google.android.gm", "Gmail"),
        AppEntry("com.google.android.apps.maps", "Maps"), AppEntry("com.spotify.music", "Spotify"),
        AppEntry("com.google.android.calendar", "Calendar"), AppEntry("in.swiggy.android", "Swiggy"),
    )
    private val onlyPhone = listOf(AppEntry("com.google.android.dialer", "Phone"), AppEntry("com.google.android.apps.maps", "Maps"), AppEntry("com.android.chrome", "Chrome"))

    private fun firstRun(
        start: Step? = null, device: DeviceState = DeviceState.PHONE3, apps: List<AppEntry> = many, found: String = "", refusals: Int = 0,
    ) = show {
        val setup = androidx.compose.runtime.remember { Setup(activity) }
        FirstRun(setup, onRestore = {}, onDone = {}, device = device, apps = apps, backupFound = found, startAt = start, roleRefusals = refusals)
        SetupSheets(setup)
    }

    // ---------------------------------------------------------------- Welcome

    @Test fun welcome() {
        firstRun()
        settle()
        capture(f, "01-welcome-restore-link")
        firstRun(found = "DotForge backup 2026-11-12.dotforge")
        settle()
        capture(f, "01b-welcome-backup-found-in-downloads")
    }

    @Test fun welcomeNotAPhone3() {
        firstRun(device = DeviceState.OTHER)
        settle()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("The Glyph lights on", substring = true))
        settle()
        capture(f, "01c-welcome-not-a-phone-3")
    }

    // ---------------------------------------------------------------- the cards

    @Test fun notificationAccess() {
        firstRun(Step.NOTIFICATIONS)
        settle()
        capture(f, "02-card-notification-access")
        allowAccess()
        firstRun(Step.NOTIFICATIONS)
        settle()
        capture(f, "02b-card-notification-access-done")
    }

    @Test fun calls() {
        firstRun(Step.CALLS)
        settle()
        capture(f, "03-card-calls")
        firstRun(Step.CALLS, refusals = 1)
        settle()
        capture(f, "03b-card-calls-role-refused")
        firstRun(Step.CALLS, refusals = 2)
        settle()
        capture(f, "03c-card-calls-role-refused-twice")
    }

    @Test fun callsAllowed() {
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        firstRun(Step.CALLS)
        settle()
        capture(f, "03d-card-calls-contacts-still-off")
        shadowOf(ctx.application).grantPermissions(Manifest.permission.READ_CONTACTS)
        firstRun(Step.CALLS)
        settle()
        capture(f, "03e-card-calls-done")
    }

    @Test fun keepRunning() {
        firstRun(Step.KEEP_RUNNING)
        settle()
        capture(f, "04-card-keep-running")
        allowKeepRunning()
        firstRun(Step.KEEP_RUNNING)
        settle()
        capture(f, "04b-card-keep-running-done")
    }

    @Test fun callerName() {
        glyphSettingsExist()
        firstRun(Step.CALLER_NAME)
        settle()
        capture(f, "05-card-caller-name-optional")
        rule.onNodeWithText("Open Glyph settings").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        capture(f, "05b-card-caller-name-after-settings")
        soundOnly()
        firstRun(Step.CALLER_NAME)
        settle()
        capture(f, "05c-card-caller-name-done")
    }

    @Test fun callerNameNoGlyphSettings() {
        firstRun(Step.CALLER_NAME)
        settle()
        capture(f, "05d-card-caller-name-settings-fallback")
    }

    // ---------------------------------------------------------------- Pick apps, Done

    @Test fun pickApps() {
        firstRun(Step.PICK_APPS)
        settle()
        capture(f, "06-pick-apps")
        rule.onNodeWithText("See all apps").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("All apps"))
        settle()
        capture(f, "06b-pick-apps-see-all")
    }

    @Test fun pickAppsOnlyPhone() {
        firstRun(Step.PICK_APPS, apps = onlyPhone)
        settle()
        capture(f, "06c-pick-apps-only-phone-installed")
    }

    @Test fun done() {
        allowAccess(); allowCalls(); allowKeepRunning()
        firstRun(Step.DONE)
        settle()
        capture(f, "07-done")
    }

    @Test fun doneWithTwoMissing() {
        allowAccess(); allowKeepRunning()
        firstRun(Step.DONE)
        settle()
        capture(f, "07b-done-two-still-need-permission")
    }

    // ---------------------------------------------------------------- Settings > Permissions, Profile, Home

    @Test fun permissionsAllAllowed() {
        show {
            val setup = androidx.compose.runtime.remember { Setup(activity) }
            val all = rememberPermissions(setup).map { it.copy(state = PermissionState.ALLOWED) }
            UndoSnackbarHost { PermissionsPage(Nav(), all) }
        }
        settle()
        capture(f, "10-permissions-all-allowed")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Usage access"))
        settle()
        capture(f, "10b-permissions-all-allowed-lower")
    }

    @Test fun permissionsMixed() {
        allowAccess(); allowKeepRunning()
        shadowOf(ctx.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show {
            val setup = androidx.compose.runtime.remember { Setup(activity) }
            UndoSnackbarHost { PermissionsPage(Nav(), rememberPermissions(setup)) }
        }
        settle()
        capture(f, "11-permissions-mixed")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Usage access"))
        settle()
        capture(f, "11b-permissions-mixed-lower")
        show {
            val setup = androidx.compose.runtime.remember { Setup(activity) }
            UndoSnackbarHost { ProfilePage(Nav(), rememberPermissions(setup)) }
        }
        settle()
        capture(f, "12-profile-permissions-row-two-needed")
    }

    @Test fun homeBanner() {
        show {
            UndoSnackbarHost(bottomBar = true) {
                androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.HOME) {} }) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize().background(Tokens.Background)) {
                        HomeScreen(Nav(Tab.HOME), Setup(activity), rememberLazyListState(), HomeStatus(screening = false, contacts = false), HomeNow())
                    }
                }
            }
        }
        settle()
        capture(f, "13-home-banner-two-need-permission")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FirstRunPhone3 : FirstRunScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class FirstRunSmall13 : FirstRunScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FirstRunPhone3Font2 : FirstRunScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
