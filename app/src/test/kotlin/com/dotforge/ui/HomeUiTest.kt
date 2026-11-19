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
import com.dotforge.ui.home.blockedToday
import com.dotforge.ui.home.nowLine
import com.dotforge.ui.home.showingName
import com.dotforge.ui.home.spamLine
import com.dotforge.ui.home.torchLeft
import com.dotforge.ui.home.waitingLine
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

    @Test fun nothingShowingSaysSoAndHasNoStop() {
        home()
        rule.onNodeWithText("Nothing on the Glyph. Alerts appear here as they arrive.").assertExists()
        rule.onNodeWithText("Stop").assertDoesNotExist()
        rule.onNodeWithText("Stop all").assertDoesNotExist()
    }

    @Test fun oneThingShowingHasStopAndItsName() {
        val r = DisplayRequest(TRY, Priority.STUDIO_PREVIEW, heart, loop = true, label = "Library: Heart")
        AppGraph.arbiter.post(r)
        home(now = HomeNow(heart.frames.first().frame, r))
        rule.onNodeWithText("Showing: Heart").assertExists()
        rule.onNodeWithText("Stops the light. Your notification stays.").assertDoesNotExist()
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

    @Test fun severalWaitingGetTheWaitingLineAndStopAll() {
        val r = DisplayRequest(TRY, Priority.STUDIO_PREVIEW, heart, loop = true, label = "Library: Heart")
        AppGraph.arbiter.post(r)
        home(now = HomeNow(heart.frames.first().frame, r, listOf("Mom", "WhatsApp")))
        rule.onNodeWithText("2 waiting: Mom, WhatsApp").assertExists()
        rule.onNodeWithText("Stop").assertDoesNotExist()
        rule.onNodeWithText("Stops the light. Your notification stays.").assertExists()
        rule.onNodeWithText("Stop all").tap()
        waitGone(TRY)
        // Stop all also takes off what showed above the alerts
        assertTrue(AppGraph.arbiter.active.value.none { it.id == TRY })
    }

    @Test fun nothingsOwnLightSaysTheGlyphIsBusy() {
        home(now = HomeNow(busy = "charging"))
        rule.onNodeWithText("The Glyph is busy (Nothing: charging). Yours shows again after.").assertExists()
        NothingLights.covered("charging", NothingLights.CHARGING_MS, now = 1_000)
        assertEquals("charging", NothingLights.busy.value)
        NothingLights.expire(now = 1_000 + NothingLights.CHARGING_MS - 1)
        assertEquals("charging", NothingLights.busy.value)
        NothingLights.expire(now = 1_000 + NothingLights.CHARGING_MS)
        assertNull(NothingLights.busy.value)
    }

    @Test fun theLinesUnderTheMatrix() {
        val n = HomeNow()
        assertEquals("Nothing on the Glyph. Alerts appear here as they arrive.", nowLine(n, DeviceState.PHONE3))
        assertEquals("The Glyph needs Phone (3). Designs play here on screen.", nowLine(n, DeviceState.OTHER))
        assertEquals("The Glyph needs Phone (3). Designs play here on screen.", nowLine(n, DeviceState.SERVICE_MISSING))
        assertEquals("The Glyph is off in Settings, so nothing can light it.", nowLine(n, DeviceState.GLYPH_OFF))
        // Nothing's light wins over what we show
        assertEquals("The Glyph is busy (Nothing: charging). Yours shows again after.", nowLine(HomeNow(showing = alert("Mom"), busy = "charging"), DeviceState.PHONE3))
        val torch = DisplayRequest(TorchTileService.ID, Priority.TORCH, heart, label = "Torch")
        assertTrue(showingName(torch, nowMs = 0).startsWith("Torch · off in "))
        assertEquals("4:12", torchLeft(nowMs = 48_000, since = 0))
        assertEquals("0:00", torchLeft(nowMs = 400_000, since = 0))
        assertEquals("Try on Glyph", showingName(DisplayRequest("x", 1, heart, label = "Try it: Try on Glyph"), 0))
        assertNull(waitingLine(listOf("Mom")))
        assertEquals("2 waiting: Mom, WhatsApp", waitingLine(listOf("Mom", "WhatsApp")))
        assertEquals("5 waiting: A, B, C…", waitingLine(listOf("A", "B", "C", "D", "E")))
    }

    // ---------------------------------------------------------------- phone states (row 67)

    @Test fun notAPhone3DisablesTheGlyphSwitches() {
        home(status = HomeStatus(device = DeviceState.OTHER))
        rule.onNodeWithText("DotForge lights the Glyph Matrix on Nothing Phone (3). On this phone you can still design and block spam.").assertExists()
        rule.onNodeWithText("The Glyph needs Phone (3). Designs play here on screen.").assertExists()
        scrollTo("Visualiser")
        switch("Torch").assertIsNotEnabled()
        switch("Visualiser").assertIsNotEnabled()
        switch("Alerts").assertIsEnabled()
        assertEquals(2, rule.onAllNodesWithText("Needs Phone (3)").fetchSemanticsNodes().size)
    }

    @Test fun glyphOffAndServiceMissingHaveTheirOwnBanner() {
        home(status = HomeStatus(device = DeviceState.GLYPH_OFF))
        rule.onNodeWithText("The Glyph is off in Settings").assertExists()
        rule.onNodeWithText("Open Glyph settings").assertExists()
        home(status = HomeStatus(device = DeviceState.SERVICE_MISSING))
        rule.onNodeWithText("Update Nothing OS to use the Glyph").assertExists()
        rule.onNodeWithText("Check for updates").assertExists()
    }

    // ---------------------------------------------------------------- the checklist (parity row 66)

    @Test fun theChecklistShowsWhatIsLeftAndHidesWhenDone() {
        home(status = HomeStatus(access = false, keepRunning = false))
        rule.onNodeWithText("Finish setting up").assertExists()
        rule.onNodeWithText("2 of 4 done").assertExists()
        rule.onNodeWithText("Keep DotForge running").assertExists()
        rule.onNodeWithText("Turn on").assertExists()
        home(status = HomeStatus())
        rule.onNodeWithText("Finish setting up").assertDoesNotExist()
    }

    @Test fun pickYourAppsOpensTheAppsPage() {
        val nav = Nav(Tab.HOME)
        home(nav, status = HomeStatus(appsPicked = false))
        rule.onNodeWithText("Pick").tap()
        assertEquals(Page.AlertApps("app"), nav.page)
    }

    // ---------------------------------------------------------------- quick switches (row 73)

    @Test fun theAlertsSwitchPausesAppAlerts() {
        home()
        scrollTo("Quiet")
        switch("Alerts").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.filters.filters.value.alertsPaused)
        rule.onNodeWithText("Paused · calls on").assertExists()
        switch("Alerts").tap()
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

    @Test fun theVisualiserSaysItNeedsTheMicrophone() {
        home(status = HomeStatus(microphone = false))
        scrollTo("Needs the microphone")
        rule.onNodeWithText("Needs the microphone").assertExists()
    }

    @Test fun theTorchCountsDown() {
        TorchTileService.onSince = System.currentTimeMillis() - 48_000
        val torch = DisplayRequest(TorchTileService.ID, Priority.TORCH, heart, loop = true, label = "Torch")
        home(now = HomeNow(heart.frames.first().frame, torch))
        rule.onNodeWithText("Showing: Torch · off in 4:1", substring = true).assertExists()
        scrollTo("Torch")
        rule.onNodeWithText("Off in 4:1", substring = true).assertExists()
        TorchTileService.onSince = 0
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
        rule.onNodeWithText("Spam filter on · nothing blocked today").tap()
        assertEquals(Tab.ALERTS, nav.tab)
        assertEquals("history:blocked", nav.alertsRequest)
    }

    @Test fun spamLineWords() {
        assertEquals("Spam filter on · nothing blocked today", spamLine(0, 0))
        assertEquals("Spam filter needs Call screening", spamLine(0, 0, screening = false))
        assertEquals("1 spam call blocked today", spamLine(1, 0))
        assertEquals("3 spam calls blocked today", spamLine(3, 0, screening = false))
        assertEquals("2 spam messages blocked today", spamLine(0, 2))
        assertEquals("3 spam calls and messages blocked today", spamLine(2, 1))
        val day = LocalDate.of(2026, 11, 19)
        val start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val entries = listOf(
            HistoryEntry("a", start - 1, Outcome.BLOCKED, call = true),
            HistoryEntry("b", start, Outcome.BLOCKED, call = true),
            HistoryEntry("c", start + 10, Outcome.BLOCKED),
            HistoryEntry("d", start + 20, Outcome.LIT),
        )
        assertEquals(1 to 1, blockedToday(entries, day, ZoneOffset.UTC))
    }

    @Test fun withoutCallScreeningTheSpamLineAsksForIt() {
        home(status = HomeStatus(screening = false))
        scrollTo("Spam filter needs Call screening")
        rule.onNodeWithText("Spam filter needs Call screening").assertExists()
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
        assertEquals(cake, com.dotforge.ui.home.alwaysOnName())
        AppGraph.toys.galleryIds = ids
        AppGraph.toys.hubFace = HubFace.CLOCK
    }

    @Test fun anotherAlwaysOnToyReadsSetUp() {
        home(status = HomeStatus(alwaysOn = false))
        scrollTo("Always-on")
        rule.onNodeWithText("Always-on: another toy").assertExists()
        rule.onNodeWithText("Set up").assertExists()
    }

    @Test fun theAvatarOpensProfile() {
        val nav = Nav(Tab.HOME)
        home(nav)
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
    }

    @Test fun widgetSettingsSaveTheChoice() {
        LibraryPrefs.toggleFavourite("g-heart")
        var saved: WidgetChoice? = null
        rule.setContent { DotForgeTheme { WidgetConfigScreen(WidgetChoice(), onCancel = {}) { saved = it } } }
        rule.onNodeWithText("Live Glyph").assertExists()
        rule.onNodeWithText(AnimationLibrary.item("g-heart")!!.name).tap()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Next favourite"))
        rule.onNodeWithText("Next favourite").tap()
        rule.onNodeWithText("Add widget").tap()
        assertEquals(WidgetChoice("lib:g-heart", WidgetTap.NEXT), saved)
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
