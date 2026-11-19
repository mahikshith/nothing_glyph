package com.dotforge.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import com.dotforge.AppGraph
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.engine.AlertPlayer
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.NothingLights
import com.dotforge.engine.Priority
import com.dotforge.rules.HistoryEntry
import com.dotforge.rules.Outcome
import com.dotforge.screens.ScreenshotApp
import com.dotforge.tile.TorchTileService
import com.dotforge.toys.HubFace
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.features.ToysFeature
import com.dotforge.ui.home.HomeNow
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.home.HomeStatus
import com.dotforge.ui.home.Notice
import com.dotforge.ui.home.alwaysOnName
import com.dotforge.ui.home.blockedThisWeek
import com.dotforge.ui.home.facePreview
import com.dotforge.ui.home.notice
import com.dotforge.ui.home.noticeLine
import com.dotforge.ui.home.nowLine
import com.dotforge.ui.home.othersWaiting
import com.dotforge.ui.home.showingName
import com.dotforge.ui.home.spamLine
import com.dotforge.ui.home.stopLabel
import com.dotforge.ui.home.torchLeft
import com.dotforge.ui.home.waitingText
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.widget.WidgetArt
import com.dotforge.widget.WidgetChoice
import com.dotforge.widget.WidgetConfigScreen
import com.dotforge.widget.WidgetContent
import com.dotforge.widget.WidgetStore
import com.dotforge.widget.WidgetTap
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
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/** Home: the Glyph now with Stop and Stop all, the checklist, the quick switches, the lines, and the widget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class HomeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    @Before fun fresh() {
        LibraryPrefs.init(rule.activity.getSharedPreferences("home-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        rule.activity.getSharedPreferences("home", Context.MODE_PRIVATE).edit().clear().commit()
        AppGraph.filters.updateFilters { it.copy(alertsPaused = false, quietUntil = 0L) }
        NothingLights.reset()
    }

    @After fun clean() {
        AppGraph.arbiter.remove(TRY)
        NothingLights.reset()
    }

    private val shownStatus = androidx.compose.runtime.mutableStateOf(HomeStatus())
    private var set = false

    /** Shows Home; a second call in one test changes its [status] in place (a test may set content once). */
    private fun home(nav: Nav = Nav(Tab.HOME), status: HomeStatus = HomeStatus(), now: HomeNow? = HomeNow()) {
        shownStatus.value = status
        if (!set) rule.setContent { DotForgeTheme { HomeScreen(nav, Setup(rule.activity), rememberLazyListState(), shownStatus.value, now) } }
        set = true
        rule.waitForIdle()
    }

    private fun scrollTo(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    private fun switch(name: String) = rule.onNode(hasContentDescription(name) and isToggleable())

    private val heart = AnimationLibrary.item("g-heart")!!.load(AppGraph.profile)
    private fun alert(label: String) = DisplayRequest(AlertPlayer.ID, Priority.NOTIFICATION, heart, loop = true, label = label)

    /** Waits for the arbiter (its own thread) to drop [id]. */
    private fun waitGone(id: String) {
        repeat(100) { if (AppGraph.arbiter.active.value.none { it.id == id }) return; Thread.sleep(20) }
    }

    // ---------------------------------------------------------------- the Glyph now

    @Test fun nothingShowingSaysSoAndOffersADesign() {
        home()
        rule.onNodeWithText("Nothing on the Glyph. Alerts appear here as they arrive.").assertExists()
        rule.onNodeWithText("Choose a design").assertExists()
        rule.onNodeWithText("Stop").assertDoesNotExist()
        rule.onNodeWithText("Stop all").assertDoesNotExist()
    }

    @Test fun oneThingShowingHasStopAndItsName() {
        val r = DisplayRequest(TRY, Priority.STUDIO_PREVIEW, heart, loop = true, label = "Library: Heart")
        AppGraph.arbiter.post(r)
        home(now = HomeNow(heart.frames.first().frame, r))
        rule.onNodeWithText("Showing: Heart").assertExists()
        rule.onNodeWithText("Stops the light. Your notification stays.").assertDoesNotExist()
        // the red button replaces Choose a design while something shows
        rule.onNodeWithText("Choose a design").assertDoesNotExist()
        rule.onNodeWithText("Stop").tap()
        waitGone(TRY)
        assertTrue(AppGraph.arbiter.active.value.none { it.id == TRY })
    }

    @Test fun anAlertSaysItsNotificationStays() {
        home(now = HomeNow(heart.frames.first().frame, alert("WhatsApp from Mom"), listOf("WhatsApp from Mom")))
        rule.onNodeWithText("Showing: WhatsApp from Mom").assertExists()
        rule.onNodeWithText("Stop").assertExists()
        rule.onNodeWithText("Stops the light. Your notification stays.").assertExists()
    }

    @Test fun severalWaitingNeverCountTheShownOneTwice() {
        home(now = HomeNow(heart.frames.first().frame, alert("Mom"), listOf("Mom", "WhatsApp", "Gmail")))
        rule.onNodeWithText("Showing: Mom · 2 more waiting: WhatsApp, Gmail").assertExists()
        rule.onNodeWithText("Stop all").assertExists()
        rule.onNodeWithText("Stops the lights. Your notifications stay.").assertExists()
    }

    @Test fun stopAllAlsoTakesOffWhatShowsAboveTheAlerts() {
        val r = DisplayRequest(TRY, Priority.STUDIO_PREVIEW, heart, loop = true, label = "Library: Heart")
        AppGraph.arbiter.post(r)
        home(now = HomeNow(heart.frames.first().frame, r, listOf("Mom", "WhatsApp")))
        rule.onNodeWithText("Showing: Heart · 2 more waiting: Mom, WhatsApp").assertExists()
        rule.onNodeWithText("Stop all").tap()
        waitGone(TRY)
        assertTrue(AppGraph.arbiter.active.value.none { it.id == TRY })
    }

    @Test fun theTorchHasTurnOffTorch() {
        TorchTileService.onSince = System.currentTimeMillis() - 48_000
        val torch = DisplayRequest(TorchTileService.ID, Priority.TORCH, heart, loop = true, label = "Torch")
        home(now = HomeNow(heart.frames.first().frame, torch))
        rule.onNodeWithText("Showing: Torch · off in 4:1", substring = true).assertExists()
        rule.onNodeWithText("Turn off torch").assertExists()
        scrollTo("On · off in")
        rule.onNodeWithText("On · off in 4:1", substring = true).assertExists()
        TorchTileService.onSince = 0
    }

    @Test fun nothingsOwnLightHasTheLineOnlyAndNoStop() {
        home(now = HomeNow(heart.frames.first().frame, alert("Mom"), listOf("Mom"), busy = "charging"))
        rule.onNodeWithText("The Glyph is busy (Nothing: charging). Mom shows when it ends.").assertExists()
        rule.onNodeWithText("Stop").assertDoesNotExist()
        rule.onNodeWithText("Stops the light. Your notification stays.").assertDoesNotExist()
        NothingLights.covered("charging", NothingLights.CHARGING_MS, now = 1_000)
        assertEquals("charging", NothingLights.busy.value)
        NothingLights.expire(now = 1_000 + NothingLights.CHARGING_MS - 1)
        assertEquals("charging", NothingLights.busy.value)
        NothingLights.expire(now = 1_000 + NothingLights.CHARGING_MS)
        assertNull(NothingLights.busy.value)
    }

    @Test fun theStatusLineGoesInPriorityOrder() {
        // lost access first: said, but only the permission banner asks for it
        AppGraph.filters.updateFilters { it.copy(alertsPaused = true, quietUntil = System.currentTimeMillis() + 3_600_000) }
        home(status = HomeStatus(access = false))
        rule.onNodeWithText("Alerts are off until Notification access is allowed.").assertExists()
        rule.onNodeWithText("Notification access needs permission").assertExists()
        assertTrue(rule.onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty())
        // then paused, then Quiet
        home(status = HomeStatus())
        rule.onNodeWithText("Alerts paused · calls still light").assertExists()
        AppGraph.filters.updateFilters { it.copy(alertsPaused = false) }
        rule.waitForIdle()
        rule.onNodeWithText("Quiet until", substring = true).assertExists()
        AppGraph.filters.updateFilters { it.copy(quietUntil = 0L) }
        rule.waitForIdle()
        rule.onNodeWithText("Nothing on the Glyph. Alerts appear here as they arrive.").assertExists()
    }

    @Test fun theLinesUnderTheMatrix() {
        val n = HomeNow()
        assertEquals("Nothing on the Glyph. Alerts appear here as they arrive.", nowLine(n, DeviceState.PHONE3))
        assertEquals("Designs play here on screen.", nowLine(n, DeviceState.OTHER))
        assertEquals("Update Nothing OS to light the Glyph.", nowLine(n, DeviceState.SERVICE_MISSING))
        assertEquals("Nothing can light the Glyph until it's on in Settings.", nowLine(n, DeviceState.GLYPH_OFF))
        assertEquals("Alerts paused · calls still light", nowLine(n, DeviceState.PHONE3, noticeLine(Notice.PAUSED)))
        assertEquals("Quiet until 16:37", noticeLine(Notice.QUIET, "On until 16:37"))
        assertEquals(Notice.ACCESS, notice(false, com.dotforge.rules.FilterSettings(alertsPaused = true), true))
        assertEquals(Notice.PAUSED, notice(true, com.dotforge.rules.FilterSettings(alertsPaused = true), true))
        assertEquals(Notice.QUIET, notice(true, com.dotforge.rules.FilterSettings(), true))
        // Nothing's light wins over what we show
        assertEquals("The Glyph is busy (Nothing: charging). Yours shows when it ends.", nowLine(HomeNow(busy = "charging"), DeviceState.PHONE3))
        val torch = DisplayRequest(TorchTileService.ID, Priority.TORCH, heart, label = "Torch")
        assertTrue(showingName(torch, nowMs = 0).startsWith("Torch · off in "))
        assertEquals("4:12", torchLeft(nowMs = 48_000, since = 0))
        assertEquals("0:00", torchLeft(nowMs = 400_000, since = 0))
        assertEquals("Turn off torch", stopLabel(HomeNow(showing = torch)))
        assertEquals("Stop", stopLabel(HomeNow(showing = alert("Mom"), waiting = listOf("Mom"))))
        assertEquals("Stop all", stopLabel(HomeNow(showing = alert("Mom"), waiting = listOf("Mom", "Gmail"))))
        assertNull(stopLabel(HomeNow(showing = alert("Mom"), busy = "charging")))
        assertEquals("Try on Glyph", showingName(DisplayRequest("x", 1, heart, label = "Try it: Try on Glyph"), 0))
        assertEquals(listOf("WhatsApp"), othersWaiting(alert("Mom"), listOf("Mom", "WhatsApp")))
        assertEquals(listOf("Mom", "WhatsApp"), othersWaiting(torch, listOf("Mom", "WhatsApp")))
        assertNull(waitingText(emptyList(), true))
        assertEquals("2 waiting: Mom, WhatsApp", waitingText(listOf("Mom", "WhatsApp"), false))
        assertEquals("5 more waiting: A, B, C +2 more", waitingText(listOf("A", "B", "C", "D", "E"), true))
    }

    // ---------------------------------------------------------------- phone states (row 67)

    @Test fun notAPhone3HasANoteWithContinueAndEveryGlyphTileOff() {
        home(status = HomeStatus(device = DeviceState.OTHER))
        rule.onNodeWithText("DotForge lights the Glyph Matrix on Nothing Phone (3). On this phone you can still design and block spam.").assertExists()
        rule.onNodeWithContentDescription("Note").assertExists()
        rule.onNodeWithText("Designs play here on screen.").assertExists()
        scrollTo("Live progress")
        for (t in listOf("Torch", "Alerts", "Quiet", "Visualiser", "Battery", "Live progress")) switch(t).assertIsNotEnabled()
        assertEquals(6, rule.onAllNodesWithText("Needs Phone (3) ›").fetchSemanticsNodes().size)
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        rule.onNodeWithText("Continue").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Continue").assertDoesNotExist()
    }

    @Test fun glyphOffAndServiceMissingSayWhyOnARealPhone3() {
        home(status = HomeStatus(device = DeviceState.GLYPH_OFF))
        rule.onNodeWithText("The Glyph is off in Settings").assertExists()
        rule.onNodeWithText("Open Glyph settings").assertExists()
        rule.onNodeWithText("Needs Phone (3) ›").assertDoesNotExist()
        scrollTo("Live progress")
        assertEquals(6, rule.onAllNodesWithText("Glyph is off ›").fetchSemanticsNodes().size)
        val opensSettings = androidx.compose.ui.test.SemanticsMatcher("opens Glyph settings") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Open Glyph settings" }
        assertEquals(6, rule.onAllNodes(opensSettings).fetchSemanticsNodes().size)
        home(status = HomeStatus(device = DeviceState.SERVICE_MISSING))
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        rule.onNodeWithText("Update Nothing OS to use the Glyph").assertExists()
        rule.onNodeWithText("Check for updates").assertExists()
        rule.onNodeWithText("Needs Phone (3) ›").assertDoesNotExist()
        scrollTo("Live progress")
        assertEquals(6, rule.onAllNodesWithText("Update Nothing OS ›").fetchSemanticsNodes().size)
    }

    // ---------------------------------------------------------------- the checklist (row 51, parity row 66)

    @Test fun theChecklistShowsOnlyWhatIsLeftAndHides() {
        home(status = HomeStatus(access = false, doubleLights = false, keepRunning = false))
        rule.onNodeWithText("Finish setting up").assertExists()
        rule.onNodeWithText("1 of 2 done").assertExists()
        rule.onNodeWithText("Stop double lights").assertExists()
        // done lines collapse into the count; permissions are the banner's one line, not the checklist's
        rule.onNodeWithText("Pick your apps").assertDoesNotExist()
        rule.onNodeWithText("Keep DotForge running").assertDoesNotExist()
        rule.onNodeWithText("Notification access and Keep running need permission").assertExists()
        rule.onNodeWithText("Hide").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Finish setting up").assertDoesNotExist()
        assertTrue(rule.activity.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("checklist_hidden", false))
    }

    @Test fun theChecklistGoesWhenAllIsDone() {
        home(status = HomeStatus())
        rule.onNodeWithText("Finish setting up").assertDoesNotExist()
    }

    @Test fun stopDoubleLightsOpensItsSteps() {
        home(status = HomeStatus(doubleLights = false))
        rule.onNodeWithText("Fix").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Settings > Glyph Interface > Notifications > Sound only").assertExists()
    }

    @Test fun pickYourAppsOpensTheAppsPage() {
        val nav = Nav(Tab.HOME)
        home(nav, status = HomeStatus(appsPicked = false))
        rule.onNodeWithText("Pick").tap()
        assertEquals(Page.AlertApps("app"), nav.page)
    }

    // ---------------------------------------------------------------- quick switches (rows 72, 73)

    @Test fun theAlertsSwitchPausesAppAlertsWithUndo() {
        set = true
        rule.setContent {
            DotForgeTheme { com.dotforge.ui.design.UndoSnackbarHost { HomeScreen(Nav(Tab.HOME), Setup(rule.activity), rememberLazyListState(), HomeStatus(), HomeNow()) } }
        }
        scrollTo("Quiet")
        switch("Alerts").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.filters.filters.value.alertsPaused)
        rule.onNodeWithText("Paused · calls still light ›").assertExists()
        rule.onNodeWithText("Alerts paused. Calls still light.").assertExists()
        rule.onNodeWithText("Undo").tap()
        rule.waitForIdle()
        assertFalse(AppGraph.filters.filters.value.alertsPaused)
    }

    @Test fun theQuietSwitchTurnsQuietOnUntilLater() {
        home()
        scrollTo("Visualiser")
        switch("Quiet").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.filters.filters.value.quietUntil > System.currentTimeMillis())
        switch("Quiet").tap()
        assertEquals(0L, AppGraph.filters.filters.value.quietUntil)
    }

    @Test fun missingPermissionsShowAllow() {
        home(status = HomeStatus(microphone = false, access = false))
        scrollTo("Needs microphone")
        rule.onNodeWithText("Needs microphone ›").assertExists()
        rule.onNodeWithContentDescription("Allow the microphone").assertExists()
        // Notification access is asked only by the banner: the Alerts tile says why it's off, with no Allow
        assertTrue(rule.onAllNodesWithContentDescription("Allow notification access").fetchSemanticsNodes().isEmpty())
        scrollTo("Off · needs permission")
        rule.onAllNodesWithText("Off · needs permission", substring = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
    }

    @Test fun aTileOpensItsFeaturePage() {
        val nav = Nav(Tab.HOME)
        home(nav)
        scrollTo("Battery")
        rule.onNodeWithText("Battery").tap()
        assertEquals(Tab.FEATURES, nav.tab)
        assertEquals(Page.Feature("battery"), nav.page)
    }

    // ---------------------------------------------------------------- the lines

    @Test fun theSpamLineOpensHistoryFilteredToBlocked() {
        val nav = Nav(Tab.HOME)
        home(nav)
        scrollTo("Spam filter on")
        rule.onNodeWithText("Spam filter on · nothing blocked this week").tap()
        assertEquals(Tab.ALERTS, nav.tab)
        assertEquals("history:blocked", nav.alertsRequest)
    }

    @Test fun spamLineWords() {
        assertEquals("Spam filter on · nothing blocked this week", spamLine(0, 0))
        assertEquals("Spam calls aren't being blocked", spamLine(0, 0, screening = false))
        assertEquals("1 spam call blocked this week", spamLine(1, 0))
        assertEquals("3 spam calls blocked this week", spamLine(3, 0, screening = false))
        assertEquals("2 spam messages blocked this week", spamLine(0, 2))
        assertEquals("3 spam calls and messages blocked this week", spamLine(2, 1))
        val now = 100L * 24 * 3_600_000
        val day = 24 * 3_600_000L
        val entries = listOf(
            HistoryEntry("a", now - 8 * day, Outcome.BLOCKED, call = true),
            HistoryEntry("b", now - 6 * day, Outcome.BLOCKED, call = true),
            HistoryEntry("c", now - 10, Outcome.BLOCKED),
            HistoryEntry("d", now - 20, Outcome.LIT),
        )
        assertEquals(1 to 1, blockedThisWeek(entries, now))
    }

    @Test fun withoutCallScreeningTheSpamLineAsksForIt() {
        home(status = HomeStatus(screening = false))
        scrollTo("Spam calls aren't being blocked")
        rule.onNodeWithText("Spam calls aren't being blocked").assertExists()
    }

    @Test fun theAlwaysOnLineChangesTheFace() {
        AppGraph.toys.hubFace = HubFace.CLOCK
        home(status = HomeStatus(alwaysOn = true))
        scrollTo("Always-on")
        rule.onNodeWithText("Always-on: Clock").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Moon").tap()
        assertEquals(HubFace.MOON, AppGraph.toys.hubFace)
        AppGraph.toys.hubFace = HubFace.CLOCK
        // every face has a picture
        for (f in HubFace.entries.filter { it != HubFace.GALLERY }) assertTrue(f.name, facePreview(f)!!.pixels.any { it > 0 })
    }

    @Test fun aLibraryFavouriteCanBeTheAlwaysOnDesign() {
        LibraryPrefs.toggleFavourite("g-cake")
        val cake = AnimationLibrary.item("g-cake")!!.name
        val ids = AppGraph.toys.galleryIds
        home(status = HomeStatus(alwaysOn = true))
        scrollTo("Always-on")
        rule.onNodeWithText("Always-on: Clock").tap()
        rule.waitForIdle()
        rule.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Library favourites"))).performScrollToNode(hasText(cake))
        rule.onNodeWithText(cake).tap()
        assertEquals(HubFace.GALLERY, AppGraph.toys.hubFace)
        assertEquals(setOf("lib:g-cake"), AppGraph.toys.galleryIds)
        assertEquals(1, com.dotforge.toys.galleryAnimations().size)
        assertEquals(cake, alwaysOnName().first)
        AppGraph.toys.galleryIds = ids
        AppGraph.toys.hubFace = HubFace.CLOCK
    }

    @Test fun anotherAlwaysOnToySaysWhereItIsSet() {
        home(status = HomeStatus(alwaysOn = false))
        scrollTo("Always-on")
        rule.onNodeWithText("Always-on: set in Nothing's Glyph Toys").assertExists()
        rule.onAllNodesWithText("Change")[0].assertExists()
    }

    @Test fun recentDesignsAreOneCompactRow() {
        LibraryPrefs.used("g-bell")
        home()
        scrollTo("Recent designs")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(AnimationLibrary.item("g-bell")!!.name))
        rule.onNodeWithText(AnimationLibrary.item("g-bell")!!.name).assertExists()
        rule.onNodeWithText("Show a design").assertDoesNotExist()
    }

    @Test fun theAvatarOpensProfileAndIs48dp() {
        val nav = Nav(Tab.HOME)
        home(nav)
        rule.onNodeWithContentDescription("Profile and settings").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Profile and settings").tap()
        assertEquals(Page.Profile, nav.page)
    }

    // ---------------------------------------------------------------- the widget (parity row 92)

    @Test fun widgetChoicesAreKeptPerWidget() {
        val store = WidgetStore(rule.activity.getSharedPreferences("widgets-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        assertEquals(WidgetChoice(), store.get(7))
        store.set(7, WidgetChoice(WidgetChoice.library("g-heart"), WidgetTap.NEXT))
        assertEquals(WidgetChoice("lib:g-heart", WidgetTap.NEXT), store.get(7))
        assertEquals(WidgetChoice(), store.get(8))
        store.remove(7)
        assertEquals(WidgetChoice(), store.get(7))
    }

    @Test fun widgetShowsADesignAndMovesThroughFavourites() {
        val c = WidgetChoice(WidgetChoice.library("g-heart"))
        val shown = WidgetContent.of(c)
        assertEquals(AnimationLibrary.item("g-heart")!!.name, shown.name)
        assertTrue(shown.still!!.pixels.any { it > 0 })
        assertNull(WidgetContent.nextFavourite(c))
        LibraryPrefs.toggleFavourite("g-cake")
        LibraryPrefs.toggleFavourite("g-bell") // newest first: bell, cake
        assertEquals("lib:g-bell", WidgetContent.nextFavourite(WidgetChoice())!!.source)
        assertEquals("lib:g-cake", WidgetContent.nextFavourite(WidgetChoice("lib:g-bell"))!!.source)
        assertEquals("lib:g-bell", WidgetContent.nextFavourite(WidgetChoice("lib:g-cake"))!!.source)
        assertEquals("Gone", WidgetContent.of(WidgetChoice("lib:nope")).name)
        val bmp = WidgetArt.bitmap(shown.still, AppGraph.profile, side = 250)
        assertEquals(250, bmp.width)
        // the live Glyph with nothing lit shows the logo, not a blank disc
        assertTrue(WidgetContent.of(WidgetChoice()).still!!.pixels.any { it > 0 })
        assertEquals(listOf(WidgetTap.OPEN, WidgetTap.NEXT), com.dotforge.widget.tapsFor(WidgetChoice.LIVE))
    }

    @Test fun widgetSettingsSaveTheChoice() {
        LibraryPrefs.toggleFavourite("g-heart")
        var saved: WidgetChoice? = null
        rule.setContent { DotForgeTheme { WidgetConfigScreen(WidgetChoice(), onCancel = {}) { saved = it } } }
        rule.onNodeWithText("Live Glyph").assertExists()
        rule.onNodeWithText(AnimationLibrary.item("g-heart")!!.name).tap()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Next favourite"))
        rule.onNodeWithText("Next favourite").tap()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add widget"))
        rule.onNodeWithText("Add widget").tap()
        assertEquals(WidgetChoice("lib:g-heart", WidgetTap.NEXT), saved)
    }

    @Test fun profileHasTheWidgetToo() {
        rule.setContent { DotForgeTheme { com.dotforge.ui.settings.ProfilePage(Nav(Tab.HOME), emptyList()) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Home screen widget"))
        rule.onNodeWithText("Home screen widget").assertExists()
    }

    @Test fun toysEndsWithAddTheWidget() {
        rule.setContent { DotForgeTheme { ToysFeature(Nav(Tab.FEATURES), Setup(rule.activity)) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add the widget"))
        rule.onNodeWithText("Add the widget").assertExists()
    }

    private companion object {
        const val TRY = "home-test"
    }
}
