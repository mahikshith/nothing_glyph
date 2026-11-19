package com.dotforge.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import com.dotforge.AppGraph
import com.dotforge.notify.GlyphNotificationListener
import com.dotforge.rules.SimpleRules
import com.dotforge.screens.ScreenshotApp
import com.dotforge.setup.FirstRunFlow.Step
import com.dotforge.setup.FirstRunPrefs
import com.dotforge.system.NothingSettings
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.features.NOT_PHONE3
import com.dotforge.ui.home.HomeNow
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.home.HomeStatus
import com.dotforge.ui.onboarding.FirstRun
import com.dotforge.ui.onboarding.Hello
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/** First run on a phone: resume re-checks, Allow opens the exact screen, the Calls flow, Pick apps, never twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FirstRunUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private val ctx: Context get() = rule.activity
    private lateinit var before: List<com.dotforge.rules.Rule>

    @Before fun fresh() {
        before = AppGraph.rules.rules.value
        FirstRunPrefs.reset(ctx)
        ShadowRoleManager.reset()
        // NotificationManagerCompat keeps the last list it read while the setting is unset: say "none" outright
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", "")
        AppGraph.arbiter.clearAll()
    }

    @After fun clean() {
        FirstRunPrefs.reset(ctx)
        AppGraph.arbiter.clearAll()
        // put Alerts back as it was
        AppGraph.rules.rules.value.filter { r -> before.none { it.id == r.id } }.forEach { AppGraph.rules.delete(it.id) }
        before.forEach(AppGraph.rules::upsert)
    }

    private fun firstRun(start: Step? = null, device: DeviceState = DeviceState.PHONE3, apps: List<AppEntry>? = emptyList(), onDone: () -> Unit = {}): Setup {
        val setup = Setup(rule.activity)
        rule.setContent {
            DotForgeTheme {
                FirstRun(setup, onRestore = {}, onDone = onDone, device = device, apps = apps, backupFound = "", startAt = start)
                SetupSheets(setup)
            }
        }
        rule.waitForIdle()
        return setup
    }

    private fun resume() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    private fun nextStarted(): Intent? = shadowOf(rule.activity).nextStartedActivity

    private fun allowAccess() = Settings.Secure.putString(
        ctx.contentResolver, "enabled_notification_listeners", ComponentName(ctx, GlyphNotificationListener::class.java).flattenToString(),
    )

    // ---------------------------------------------------------------- the cards

    @Test fun notificationAccessOpensItsPageAndShowsDoneOnReturn() {
        firstRun(Step.NOTIFICATIONS)
        rule.onNodeWithText("Notification access").assertExists()
        rule.onNodeWithText("Allow").tap()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStarted()?.action)
        rule.onNodeWithText("Done").assertDoesNotExist()
        allowAccess()
        resume()
        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithContentDescription("Notification access: done").assertExists()
        rule.onNodeWithText("Next").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Calls").assertExists()
    }

    @Test fun keepRunningOpensAndroidsOneTapDialogAndRechecksOnResume() {
        firstRun(Step.KEEP_RUNNING)
        rule.onNodeWithText("Allow").tap()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, nextStarted()?.action)
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
        resume()
        rule.onNodeWithText("Done").assertExists()
    }

    @Test fun notNowMovesOnWithoutAsking() {
        firstRun(Step.NOTIFICATIONS)
        rule.onNodeWithText("Not now").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Calls").assertExists()
        assertNull(nextStarted())
    }

    @Test fun callsAsksTheRoleThenContactsAndSaysWhenTheRoleIsRefused() {
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_CALL_SCREENING)
        firstRun(Step.CALLS)
        rule.onNodeWithText("Allow").tap()
        rule.waitForIdle()
        val asked = shadowOf(rule.activity).nextStartedActivityForResult
        assertEquals("android.app.role.action.REQUEST_ROLE", asked.intent.action)
        // the user says no to the role: Contacts is asked in the same go, and the card says what's off
        shadowOf(rule.activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        rule.waitForIdle()
        assertTrue(Manifest.permission.READ_CONTACTS in shadowOf(rule.activity).lastRequestedPermission.requestedPermissions)
        rule.onNodeWithText("Call screening is off, so spam calls won't be blocked.").assertExists()
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test fun refusedTwiceOpensDefaultAppsWithTheSteps() {
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_CALL_SCREENING)
        firstRun(Step.CALLS)
        repeat(2) {
            rule.onNodeWithText(if (it == 0) "Allow" else "Try again").tap()
            rule.waitForIdle()
            val asked = shadowOf(rule.activity).nextStartedActivityForResult
            shadowOf(rule.activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
            rule.waitForIdle()
        }
        rule.onNodeWithText("Tap Caller ID & spam app, then pick DotForge.").assertExists()
        rule.onNodeWithText("Open Default apps").tap()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, nextStarted()?.action)
    }

    @Test fun callsWithoutTheRoleOnThisPhoneOpensDefaultApps() {
        firstRun(Step.CALLS)
        rule.onNodeWithText("Tap Caller ID & spam app, then pick DotForge.").assertExists()
        rule.onNodeWithText("Open Default apps").tap()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, nextStarted()?.action)
    }

    @Test fun contactsRefusedTwiceSaysSoBeforeOpeningAppInfo() {
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        // asked before, and Android no longer shows its dialog: the card explains first, then opens App info
        ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("contacts_asked", true).commit()
        firstRun(Step.CALLS)
        rule.onNodeWithText("Android won't ask again. In App info, tap Permissions, then Contacts.").assertExists()
        assertNull(nextStarted())
        rule.onNodeWithText("Open App info").tap()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextStarted()?.action)
    }

    @Test fun callsIsDoneOnlyWithTheRoleAndContacts() {
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        firstRun(Step.CALLS)
        rule.onNodeWithText("Contacts is off, so callers show as numbers.").assertExists()
        rule.onNodeWithText("Allow contacts").assertExists()
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.READ_CONTACTS)
        resume()
        rule.onNodeWithText("Done").assertExists()
    }

    // ---------------------------------------------------------------- the caller name card and its fallback

    @Test fun callerNameFallsBackToSettingsWhenGlyphSettingsAreMissing() {
        assertEquals(Settings.ACTION_SETTINGS, NothingSettings.glyphSettings(ctx.packageManager).intent.action)
        firstRun(Step.CALLER_NAME)
        rule.onNodeWithText("Optional").assertExists()
        // the fallback says where to go inside Settings
        rule.onNodeWithText("Open Glyph Interface, then Ringtone lights, and pick Sound only.").assertExists()
        rule.onNodeWithText("Open settings").tap()
        assertEquals(Settings.ACTION_SETTINGS, nextStarted()?.action)
    }

    @Test fun callerNameOpensNothingsGlyphSettingsWhenTheyExist() {
        val glyphs = ComponentName("com.android.settings", "com.nothing.settings.GlyphSettings")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(glyphs)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(glyphs, IntentFilter("android.settings.ACTION_GLYPHS_SETTINGS").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        assertEquals("android.settings.ACTION_GLYPHS_SETTINGS", NothingSettings.glyphSettings(ctx.packageManager).intent.action)
        firstRun(Step.CALLER_NAME)
        rule.onNodeWithText("To see who's calling, set Glyph ringtone lights to Sound only. Nothing's own ringtone light show turns off.").assertExists()
        rule.onNodeWithText("Open Glyph Interface").tap()
        assertEquals("android.settings.ACTION_GLYPHS_SETTINGS", nextStarted()?.action)
        // Sound only, read back on resume: Done
        Settings.Global.putString(ctx.contentResolver, NothingSettings.RINGTONE_MODE, "1")
        resume()
        rule.onNodeWithText("Done").assertExists()
    }

    @Test fun callerNameMovesOnOnceOpenedEvenWhenThePhoneDoesntSay() {
        val glyphs = ComponentName("com.android.settings", "com.nothing.settings.GlyphSettings")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(glyphs)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(glyphs, IntentFilter("android.settings.ACTION_GLYPHS_SETTINGS").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        firstRun(Step.CALLER_NAME)
        rule.onNodeWithText("Not now").assertExists()
        rule.onNodeWithText("Open Glyph Interface").tap()
        // nothing readable comes back: progress doesn't wait on it
        resume()
        rule.onNodeWithContentDescription("Show the caller's name: opened").assertExists()
        rule.onNodeWithText("Not now").assertDoesNotExist()
        rule.onNodeWithText("Open again").assertExists()
        rule.onNodeWithText("Next").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Pick your apps", substring = true).assertExists()
    }

    // ---------------------------------------------------------------- Pick apps

    private fun appRow(name: String) = rule.onNode(hasText(name) and androidx.compose.ui.test.isToggleable())

    @Test fun onlyInstalledAppsArePreTickedAndContinueWritesAlertsRows() {
        firstRun(Step.PICK_APPS, apps = listOf(AppEntry("com.whatsapp", "WhatsApp"), AppEntry("com.google.android.dialer", "Phone"), AppEntry("org.telegram.messenger", "Telegram"), AppEntry("com.google.android.apps.maps", "Maps")))
        appRow("WhatsApp").assertIsOn()
        appRow("Phone").assertIsOn()
        appRow("Telegram").assertIsOff()
        rule.onNodeWithText("Messages").assertDoesNotExist()
        rule.onNodeWithText("Maps").assertDoesNotExist()
        rule.onNodeWithText("Continue with 2").assertExists()
        rule.onNodeWithText("Telegram").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Continue with 3").tap()
        rule.waitForIdle()
        val rules = AppGraph.rules.rules.value
        assertTrue(rules.any { SimpleRules.isAppRule(it) && it.packages == listOf("com.whatsapp") && it.name == "WhatsApp" })
        assertTrue(rules.any { SimpleRules.isAppRule(it) && it.packages == listOf("org.telegram.messenger") })
        assertTrue(rules.first { it.id == SimpleRules.PHONE_CALLS }.enabled)
        // nothing allowed in this test: Done names what is missing
        rule.onNodeWithText("Almost set").assertExists()
        rule.onNodeWithText("4 things need permission. Home shows how to fix them.").assertExists()
    }

    @Test fun withOnlyPhoneInstalledOnlyPhoneIsOfferedAndTicked() {
        firstRun(Step.PICK_APPS, apps = listOf(AppEntry("com.google.android.dialer", "Phone"), AppEntry("com.google.android.apps.maps", "Maps")))
        appRow("Phone").assertIsOn()
        rule.onNodeWithText("WhatsApp").assertDoesNotExist()
        rule.onNodeWithText("Continue with 1").assertExists()
        // See all apps lists the rest, unticked
        rule.onNodeWithText("See all apps").tap()
        rule.waitForIdle()
        appRow("Maps").assertIsOff()
        rule.onNodeWithText("Continue with 1").assertExists()
    }

    // ---------------------------------------------------------------- Welcome, Done, never twice

    @Test fun notAPhone3StillLetsYouLookAround() {
        firstRun(device = DeviceState.OTHER)
        rule.onNodeWithText(NOT_PHONE3).assertExists()
        rule.onNodeWithText("Restore from a backup").assertExists()
        rule.onNodeWithText("Get started").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Notification access").assertExists()
        rule.onNodeWithContentDescription("Step 1 of 4").assertExists()
    }

    @Test fun donePlaysHelloOnTheGlyphAndGoesHome() {
        var home = false
        firstRun(Step.DONE, onDone = { home = true })
        assertTrue(AppGraph.arbiter.active.value.any { it.id == Hello.ID } || AppGraph.arbiter.frame.value != null)
        rule.onNodeWithText("Change your apps in Alerts and your permissions in Profile.").assertExists()
        rule.onNodeWithText("Skip setup").assertDoesNotExist()
        rule.onNodeWithText("Go to Home").tap()
        assertTrue(home)
    }

    @Test fun doneSaysYoureSetOnlyWhenEveryCorePermissionIsAllowed() {
        allowAll()
        firstRun(Step.DONE)
        rule.onNodeWithText("You're set").assertExists()
        rule.onNodeWithText("need permission", substring = true).assertDoesNotExist()
    }

    @Test fun theHelloPlaysOnceNotAgainAfterBackAndContinue() {
        firstRun(Step.PICK_APPS, apps = listOf(AppEntry("com.whatsapp", "WhatsApp")))
        rule.onNodeWithText("Continue with 1").tap()
        rule.waitForIdle()
        assertTrue(hello())
        AppGraph.arbiter.clearAll()
        rule.onNodeWithContentDescription("Back").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Continue with 1").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Go to Home").assertExists()
        assertFalse(hello())
    }

    private fun hello() = AppGraph.arbiter.active.value.any { it.id == Hello.ID } || AppGraph.arbiter.frame.value != null

    private fun allowAll() {
        allowAccess()
        shadowOf(ctx.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.READ_CONTACTS)
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
    }

    @Test fun welcomeHasOneWayOnAndNoSkip() {
        firstRun()
        rule.onNodeWithText("Get started").assertExists()
        rule.onNodeWithText("Skip setup").assertDoesNotExist()
    }

    @Test fun firstRunNeverShowsTwice() {
        var gen by androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            DotForgeTheme {
                key(gen) {
                    val nav = remember { Nav(Tab.HOME) }
                    val setup = remember { Setup(rule.activity) }
                    FirstRunHost(nav, setup) { Text("Home here") }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Get started").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Skip setup").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Home here").assertExists()
        assertTrue(FirstRunPrefs.done(ctx))
        // a fresh start (a new launch): straight to the app
        gen++
        rule.waitForIdle()
        rule.onNodeWithText("Home here").assertExists()
        rule.onNodeWithText("Get started").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- an install that existed before First run

    private fun host(): Nav {
        val nav = Nav(Tab.HOME)
        rule.setContent {
            DotForgeTheme {
                val setup = remember { Setup(rule.activity) }
                FirstRunHost(nav, setup) { Text("Home here") }
                SetupSheets(setup)
            }
        }
        rule.waitForIdle()
        return nav
    }

    private fun ownRule() = AppGraph.rules.upsert(SimpleRules.newApp("org.telegram.messenger", "Telegram"))

    @Test fun anExistingInstallWithEverythingAllowedIsMarkedDoneSilently() {
        ownRule()
        allowAll()
        host()
        rule.onNodeWithText("Home here").assertExists()
        rule.onNodeWithText("Get started").assertDoesNotExist()
        assertTrue(FirstRunPrefs.done(ctx))
    }

    @Test fun anExistingInstallSeesOnlyTheCoreCardsStillToDoAndNeverPickApps() {
        ownRule()
        val calls = AppGraph.rules.rules.value.first { it.id == SimpleRules.PHONE_CALLS }
        allowAccess()
        host()
        // no Welcome: straight to the first core card still to do
        rule.onNodeWithText("Get started").assertDoesNotExist()
        rule.onNodeWithText("Calls").assertExists()
        rule.onNodeWithContentDescription("Step 2 of 3").assertExists()
        rule.onNodeWithText("Not now").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Keep running").assertExists()
        rule.onNodeWithText("Not now").tap()
        rule.waitForIdle()
        // the caller name card and Pick apps are skipped: Done, and the Phone calls row is as it was
        rule.onNodeWithText("Almost set").assertExists()
        rule.onNodeWithText("Pick your apps", substring = true).assertDoesNotExist()
        assertEquals(calls, AppGraph.rules.rules.value.first { it.id == SimpleRules.PHONE_CALLS })
    }

    @Test fun anUpdatedInstallCountsAsExisting() {
        assertFalse(FirstRunPrefs.existing(ctx))
        ctx.getSharedPreferences("backup", Context.MODE_PRIVATE).edit().putLong("last_ms", 5L).commit()
        assertTrue(FirstRunPrefs.existing(ctx))
    }

    @Test fun aFreshInstallStaysFreshOnceItsOwnPicksAreSaved() {
        assertFalse(FirstRunPrefs.resumed(ctx))
        ownRule()
        // decided once at the first start, so Pick apps' own rows never turn it into an existing install
        assertFalse(FirstRunPrefs.resumed(ctx))
    }

    // ---------------------------------------------------------------- Settings > Permissions

    @Test fun permissionRowsOpenTheSameScreensAsFirstRun() {
        allowAccess()
        rule.setContent {
            DotForgeTheme {
                val setup = remember { Setup(rule.activity) }
                com.dotforge.ui.settings.PermissionsPage(Nav(Tab.HOME), com.dotforge.ui.settings.rememberPermissions(setup))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Notification access, Allowed", substring = true).assertExists()
        rule.onNodeWithContentDescription("Keep running, Not allowed, needed", substring = true).tap()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, nextStarted()?.action)
        rule.onNodeWithContentDescription("Call screening, Not allowed, needed", substring = true).tap()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, nextStarted()?.action)
        rule.onNodeWithContentDescription("Microphone, Not allowed, optional", substring = true).assertExists()
    }

    @Test fun anOptionalPermissionTurnsNeededWhileSomethingOnUsesIt() {
        rule.setContent {
            DotForgeTheme {
                val setup = remember { Setup(rule.activity) }
                com.dotforge.ui.settings.PermissionsPage(Nav(Tab.HOME), com.dotforge.ui.settings.rememberPermissions(setup))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Needed for the basics").assertExists()
        rule.onNodeWithContentDescription("Show DotForge notifications, Not allowed, optional", substring = true).assertExists()
        // an app rule that silences: its quiet copy needs DotForge's own notifications
        AppGraph.rules.upsert(SimpleRules.newApp("com.whatsapp", "WhatsApp").copy(action = com.dotforge.rules.Action.SILENCE))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show DotForge notifications, Not allowed, needed", substring = true).assertExists()
    }

    // ---------------------------------------------------------------- Home's banner

    @Test fun homesBannerShowsOnlyWhenACorePermissionIsMissingAndOpensPermissions() {
        val nav = Nav(Tab.HOME)
        var status by androidx.compose.runtime.mutableStateOf(HomeStatus(access = false, keepRunning = false))
        rule.setContent { DotForgeTheme { HomeScreen(nav, Setup(rule.activity), rememberLazyListState(), status, HomeNow()) } }
        rule.waitForIdle()
        rule.onNodeWithText("Notification access and Keep running need permission").assertExists()
        rule.onNodeWithText("Notification access and Keep running need permission").tap()
        assertEquals(Page.Permissions, nav.page)
        status = HomeStatus(microphone = false)
        rule.waitForIdle()
        rule.onNodeWithText("need permission", substring = true).assertDoesNotExist()
        rule.onNodeWithText("needs permission", substring = true).assertDoesNotExist()
    }
}
