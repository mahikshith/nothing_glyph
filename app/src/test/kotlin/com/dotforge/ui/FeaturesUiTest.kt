package com.dotforge.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.device.TimerStyle
import com.dotforge.live.ProgressSignals
import com.dotforge.notify.LiveProgressRuntime
import com.dotforge.screens.ScreenshotApp
import com.dotforge.timer.GlyphTimers
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.FeaturePage
import com.dotforge.ui.features.FeaturesScreen
import com.dotforge.ui.features.progressStatus
import com.dotforge.ui.features.timerStatus
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Features: the grid, Timer & stopwatch and Live progress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FeaturesUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var clock = 1_000_000_000L

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    @Before fun fresh() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        GlyphTimers.reset()
        GlyphTimers.now = { clock }
        LiveProgressRuntime.reset()
        AppGraph.liveProgress.reset()
        AppGraph.setProgressRings(true)
    }

    @After fun clean() {
        GlyphTimers.reset()
        LiveProgressRuntime.reset()
        AppGraph.arbiter.clearAll()
    }

    private fun page(key: String, nav: Nav = Nav(Tab.FEATURES), access: Boolean = true) {
        rule.setContent {
            val setup = androidx.compose.runtime.remember {
                android.provider.Settings.Secure.putString(
                    rule.activity.contentResolver, "enabled_notification_listeners",
                    if (access) rule.activity.packageName + "/com.dotforge.notify.GlyphNotificationListener" else "",
                )
                Setup(rule.activity)
            }
            DotForgeTheme { UndoSnackbarHost { FeaturePage(key, nav, setup) } }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    /** Scrolls the open sheet (the last scrollable) to [text]. */
    private fun sheetTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    /** Opens a page's On the Glyph sheet. */
    private fun openOnTheGlyph() {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(androidx.compose.ui.test.hasTestTag(com.dotforge.ui.features.ON_THE_GLYPH_TAG))
        rule.onNode(androidx.compose.ui.test.hasTestTag(com.dotforge.ui.features.ON_THE_GLYPH_TAG)).tap()
        rule.waitForIdle()
    }

    @Test fun gridHasSixCardsAndIntegrations() {
        val nav = Nav(Tab.FEATURES)
        rule.setContent { DotForgeTheme { FeaturesScreen(nav, Setup(rule.activity), rememberLazyListState()) } }
        rule.waitForIdle()
        for (t in listOf("Timer & stopwatch", "Live progress", "Toys", "Music visualiser", "Torch", "Battery and volume")) {
            scrollTo(t)
            rule.onNodeWithText(t).assertExists()
        }
        scrollTo("Integrations")
        rule.onNodeWithText("None running").assertExists()
        rule.onNodeWithText("Timer & stopwatch").tap()
        assertEquals(Page.Feature("timer"), nav.page)
    }

    @Test fun aPresetStartsATimerInOneTap() {
        page("timer")
        rule.onNodeWithContentDescription("Start a 5 minute timer").tap()
        rule.waitForIdle()
        assertEquals(1, GlyphTimers.state.value.timers.size)
        assertEquals(5 * 60_000L, GlyphTimers.state.value.timers.single().totalMs)
        scrollTo("5 min timer")
        rule.onNodeWithText("5:00 left").assertExists()
        rule.onNodeWithContentDescription("Pause").tap()
        rule.waitForIdle()
        assertFalse(GlyphTimers.state.value.timers.single().running)
        rule.onNodeWithText("Paused · 5 min timer").assertExists()
        rule.onNodeWithContentDescription("Stop").tap()
        rule.waitForIdle()
        assertTrue(GlyphTimers.state.value.timers.isEmpty())
        // Stop has Undo
        rule.onNodeWithText("5 min timer stopped").assertExists()
        rule.onNodeWithText("Undo").tap()
        rule.waitForIdle()
        assertEquals(1, GlyphTimers.state.value.timers.size)
    }

    @Test fun theOneOnTheGlyphIsMarkedAndAnotherCanBeShown() {
        GlyphTimers.start(5 * 60_000, "Tea")
        GlyphTimers.start(10 * 60_000, "Eggs")
        page("timer")
        scrollTo("Eggs")
        // the badge is on Tea's row (it ends first), not on Eggs'
        rule.onNode(hasText("Tea") and hasText("On the Glyph")).assertExists()
        assertEquals(0, rule.onAllNodes(hasText("Eggs") and hasText("On the Glyph")).fetchSemanticsNodes().size)
        rule.onNodeWithText("Show on Glyph").assertHeightIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        assertEquals("Tea".let { GlyphTimers.state.value.timers.first { t -> t.label == "Eggs" }.id }, GlyphTimers.state.value.focus)
        rule.onAllNodesWithText("Eggs · 10:00 left").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
    }

    @Test fun idlePreviewIsTheStyleDemo() {
        page("timer")
        rule.onNodeWithText("Style preview · Ring drain").assertExists()
        // the face-down line only matters while something shows
        rule.onNodeWithText("Face down hides the Glyph. Stand the phone up.").assertDoesNotExist()
        rule.onNodeWithText("unless you pick one", substring = true).assertExists()
        rule.onNodeWithText("Start a timer").assertExists()
        rule.onNodeWithContentDescription("Start a 1 minute timer").assertHeightIsAtLeast(48.dp)
    }

    @Test fun faceDownLineWhileRunning() {
        GlyphTimers.start(5 * 60_000)
        page("timer")
        rule.onNodeWithText("Face down hides the Glyph. Stand the phone up.").assertExists()
    }

    @Test fun pageOrderPresetsThenTimersThenOnTheGlyphThenStyleThenAlso() {
        GlyphTimers.start(5 * 60_000, "Tea")
        page("timer")
        // each is below the last, measured after scrolling the earlier one into view
        rule.onNodeWithText("Start a timer").assertExists()
        val order = listOf("Start a timer", "Your timer", "On the Glyph", "Style", "Also")
        order.zipWithNext().forEach { (a, b) ->
            scrollTo(a)
            val ya = rule.onAllNodes(hasText(a), useUnmergedTree = true).onFirst().fetchSemanticsNode().positionInRoot.y
            val yb = rule.onAllNodes(hasText(b), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
            if (yb != null) assertTrue("$a above $b", ya < yb)
        }
    }

    @Test fun onTheGlyphSettings() {
        page("timer")
        // one summary row; the default finish runs 30 s and comes back every minute
        rule.onNodeWithText("100% · finish 30 s, repeats every 1 min").assertExists()
        openOnTheGlyph()
        // all four controls: how long, speed, repeat, brightness
        rule.onNodeWithText("When it ends").assertExists()
        rule.onNodeWithText("Until dismissed").tap()
        rule.waitForIdle()
        assertEquals(com.dotforge.device.ShowFor.UNTIL_CHECKED, GlyphTimers.timing.value.showFor)
        sheetTo("Finish speed")
        rule.onNodeWithText("Fast 2x").tap()
        rule.waitForIdle()
        assertEquals(2f, GlyphTimers.timing.value.speed)
        sheetTo("Every 5 min")
        rule.onNodeWithText("Repeat").assertExists()
        rule.onNodeWithText("Every 5 min").tap()
        rule.waitForIdle()
        assertEquals(com.dotforge.device.ShowFor.SECONDS, GlyphTimers.timing.value.showFor)
        assertEquals(5, GlyphTimers.timing.value.remindMinutes)
        sheetTo("Exact brightness")
        rule.onNodeWithText("Brightness").assertExists()
        rule.onNodeWithText("50%").tap()
        rule.waitForIdle()
        assertEquals(50, GlyphTimers.timing.value.brightness)
        sheetTo("Last 10 seconds")
        rule.onNodeWithText("Last 10 seconds").tap()
        rule.waitForIdle()
        assertFalse(GlyphTimers.lastTen.value)
        rule.onNodeWithText("Done").tap()
        rule.waitForIdle()
        rule.onNodeWithText("50% · 2x · finish 30 s, repeats every 5 min · last 10 s off").assertExists()
        scrollTo("Vibrate")
        rule.onNode(hasText("Sound") and isToggleable()).assertIsOn()
        rule.onNode(hasText("Vibrate") and isToggleable()).assertIsOn().tap()
        rule.waitForIdle()
        assertFalse(GlyphTimers.vibrate.value)
    }

    @Test fun timesUpScreenDismisses() {
        GlyphTimers.start(60_000, "Eggs")
        clock += 61_000
        GlyphTimers.tick()
        var closed = false
        rule.setContent { DotForgeTheme { com.dotforge.timer.TimesUp(onClose = { closed = true }) } }
        rule.waitForIdle()
        rule.onNodeWithText("Time's up").assertExists()
        rule.onNodeWithText("Eggs").assertExists()
        rule.onNodeWithText("Dismiss").tap()
        rule.waitForIdle()
        assertTrue(closed)
        assertTrue(GlyphTimers.state.value.timers.isEmpty())
    }

    @Test fun severalTimersAndTheEndedOne() {
        GlyphTimers.start(5 * 60_000)
        GlyphTimers.start(60_000, "Eggs")
        clock += 61_000
        GlyphTimers.tick()
        page("timer")
        rule.onAllNodesWithText("Time's up · Eggs").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        rule.onAllNodesWithText("Dismiss")[0].tap()
        rule.waitForIdle()
        assertEquals(1, GlyphTimers.state.value.timers.size)
    }

    @Test fun customTimer() {
        page("timer")
        rule.onNodeWithText("Custom…").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Start 10 min").tap()
        rule.waitForIdle()
        assertEquals(10 * 60_000L, GlyphTimers.state.value.timers.single().totalMs)
    }

    @Test fun customTimerKeypadAndPreset() {
        page("timer")
        rule.onNodeWithText("Custom…").tap()
        rule.waitForIdle()
        repeat(4) { rule.onNodeWithContentDescription("Delete").tap() }
        for (k in listOf("1", "3", "0")) rule.onNodeWithText(k).tap()
        rule.waitForIdle()
        rule.onNode(hasText("Save as preset") and isToggleable()).tap()
        rule.onNodeWithText("Start 1 min 30 s").tap()
        rule.waitForIdle()
        assertEquals(90_000L, GlyphTimers.state.value.timers.single().totalMs)
        assertEquals(listOf(90_000L), GlyphTimers.presets.value)
        rule.onNodeWithContentDescription("Start a 1 min 30 s timer").assertExists()
    }

    @Test fun styleSheetHasSixLivePreviewsAndPicks() {
        page("timer")
        scrollTo("Timer style")
        rule.onNodeWithText("Timer style").tap()
        rule.waitForIdle()
        for (s in TimerStyle.entries) assertTrue(s.label, rule.onAllNodes(hasContentDescription("${s.label} style", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Hourglass").tap()
        rule.waitForIdle()
        assertEquals(TimerStyle.HOURGLASS, GlyphTimers.style.value)
    }

    @Test fun stopwatchWithLaps() {
        page("timer")
        rule.onNodeWithText("Stopwatch").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Start").tap()
        clock += 12_300
        rule.waitForIdle()
        rule.onNodeWithText("Lap").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Lap 1").assertExists()
        rule.onNodeWithText("Pause").tap()
        rule.waitForIdle()
        assertFalse(GlyphTimers.state.value.stopwatch.running)
        rule.onNodeWithText("Reset").tap()
        rule.waitForIdle()
        assertFalse(GlyphTimers.state.value.stopwatch.started)
    }

    @Test fun stopwatchHasItsOwnStyles() {
        page("timer")
        rule.onNodeWithText("Stopwatch").tap()
        rule.waitForIdle()
        scrollTo("Stopwatch style")
        rule.onNodeWithText("Stopwatch style").tap()
        rule.waitForIdle()
        for (s in com.dotforge.device.StopwatchStyle.entries) assertTrue(s.label, rule.onAllNodesWithText(s.label).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(com.dotforge.device.StopwatchStyle.RING_FILLS.label).tap()
        rule.waitForIdle()
        assertEquals(com.dotforge.device.StopwatchStyle.RING_FILLS, GlyphTimers.watchStyle.value)
    }

    @Test fun lapsMarkTheFastestAndSlowest() {
        val rows = listOf(com.dotforge.live.Lap(3, 9_000, 30_000), com.dotforge.live.Lap(2, 12_000, 21_000), com.dotforge.live.Lap(1, 9_500, 9_500))
        assertEquals(mapOf(3 to "Fastest", 2 to "Slowest"), com.dotforge.ui.features.lapMarks(rows))
        assertEquals(emptyMap<Int, String>(), com.dotforge.ui.features.lapMarks(rows.take(2)))
    }

    @Test fun clockAppSwitchIsOnByDefault() {
        page("timer")
        scrollTo("Show Clock app timers")
        rule.onNode(hasText("Show Clock app timers") and isToggleable()).assertIsOn()
        assertTrue(GlyphTimers.mirrorClock.value)
        rule.onNodeWithText("Show Clock app timers").tap()
        rule.waitForIdle()
        assertFalse(GlyphTimers.mirrorClock.value)
    }

    @Test fun clockAppSwitchNeedsAccess() {
        page("timer", access = false)
        scrollTo("Show Clock app timers")
        rule.onNode(hasText("Show Clock app timers") and isToggleable()).assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("Needs notification access").assertExists()
        // the one line to allow things, under the presets
        rule.onNodeWithText("Allow notification access").assertExists()
    }

    @Test fun allowLineReviewsEverything() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            page("timer", access = false)
            rule.onNodeWithText("2 things to allow").assertExists()
            rule.onNodeWithText("Review").tap()
            rule.waitForIdle()
            rule.onNodeWithText("Exact alarms").assertExists()
            rule.onNodeWithText("Notification access").assertExists()
        } finally {
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }

    @Test fun cardStatus() {
        val s = GlyphTimers.State()
        assertEquals("None running", timerStatus(s, clock))
        GlyphTimers.start(5 * 60_000)
        assertEquals("5:00 left", timerStatus(GlyphTimers.state.value, clock))
        GlyphTimers.start(10 * 60_000)
        assertEquals("2 running · 5:00", timerStatus(GlyphTimers.state.value, clock))
    }

    @Test fun progressCardStatus() {
        assertEquals("On", progressStatus(emptyList()))
        LiveProgressRuntime.now = { clock }
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", { app ->
            ProgressSignals(texts = listOf("Arriving in 12 mins"), known = app != null, kind = app!!.kind, ongoing = true)
        }, allowedByRule = { false })
        assertEquals("Zomato · 12 min left", progressStatus(LiveProgressRuntime.items.value))
        LiveProgressRuntime.update("d", "com.android.providers.downloads", "Downloads", { _ -> ProgressSignals(bar = 0.48f) }, allowedByRule = { true })
        LiveProgressRuntime.update("u", "com.ubercab", "Uber", { app ->
            ProgressSignals(texts = listOf("Your driver is 2 min away"), known = app != null, kind = app!!.kind, ongoing = true)
        }, allowedByRule = { false })
        assertEquals("3 now · Uber 2 min", progressStatus(LiveProgressRuntime.items.value))
    }

    @Test fun liveProgressSaysPlainlyWhatItNeeds() {
        page("progress")
        rule.onNodeWithText("Works when the app shows the arrival time in its notification.", substring = true).assertExists()
        rule.onNodeWithText("Nothing on the way", substring = true).assertExists()
    }

    private fun install(pkg: String) {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .installPackage(android.content.pm.PackageInfo().apply { packageName = pkg })
    }

    @Test fun liveProgressMainSwitchAndApps() {
        install("com.application.zomato")
        page("progress")
        rule.onNode(hasText("Live progress") and isToggleable()).assertIsOn().tap()
        rule.waitForIdle()
        assertFalse(AppGraph.progressRings.value)
        rule.onNode(hasText("Live progress") and isToggleable()).tap()
        scrollTo("Other apps")
        rule.onNode(hasText("Zomato") and isToggleable()).assertIsOn().tap()
        rule.waitForIdle()
        assertFalse(AppGraph.liveProgress.appOn("zomato"))
        rule.onNode(hasText("Zomato") and isToggleable()).assertIsOff()
    }

    @Test fun liveProgressShowsWhatIsOnTheWay() {
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", { app ->
            ProgressSignals(texts = listOf("Rahul is on the way", "Arriving in 12 mins"), known = app != null, kind = app!!.kind)
        }, allowedByRule = { false })
        page("progress")
        rule.onNodeWithText("12 min left · On the way").assertExists()
    }

    @Test fun liveProgressNeedsAccess() {
        page("progress", access = false)
        rule.onNode(hasText("Live progress") and isToggleable()).assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("Needs access").assertExists()
        rule.onNodeWithText("Let DotForge read notifications", substring = true).assertExists()
    }

    @Test fun nowRowsCanStopShowing() {
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", { app ->
            ProgressSignals(texts = listOf("Arriving in 12 mins"), known = app != null, kind = app!!.kind)
        }, allowedByRule = { false })
        page("progress")
        rule.onNodeWithContentDescription("Hide Zomato from the Glyph").assertHeightIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        assertTrue(LiveProgressRuntime.items.value.isEmpty())
        assertTrue(LiveProgressRuntime.isHidden("z"))
    }

    @Test fun liveProgressOnTheGlyph() {
        page("progress")
        rule.onNodeWithText("100% · whole time · done once").assertExists()
        openOnTheGlyph()
        rule.onNodeWithText("10 s on each change").tap()
        rule.waitForIdle()
        assertEquals(com.dotforge.notify.ProgressShowFor.ON_CHANGE, AppGraph.liveProgress.showFor.value)
        sheetTo("Fast 2x")
        rule.onNodeWithText("Fast 2x").tap()
        rule.waitForIdle()
        assertEquals(2f, AppGraph.liveProgress.timing.value.speed)
        sheetTo("3 times")
        rule.onNodeWithText("Repeat the done animation").assertExists()
        rule.onNodeWithText("3 times").tap()
        rule.waitForIdle()
        assertEquals(com.dotforge.device.Repeat.TIMES, AppGraph.liveProgress.timing.value.repeat)
        assertEquals(3, AppGraph.liveProgress.timing.value.times)
        sheetTo("Exact brightness")
        rule.onNodeWithText("Brightness").assertExists()
        sheetTo("Stage")
        rule.onNodeWithText("Stage").tap()
        rule.waitForIdle()
        assertFalse(AppGraph.liveProgress.showMinutes.value)
        rule.onNodeWithText("Done").tap()
        rule.waitForIdle()
        rule.onNodeWithText("100% · 2x · 10 s on each change · done 3 times").assertExists()
    }

    @Test fun liveProgressOrderAndBadge() {
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", { app ->
            ProgressSignals(texts = listOf("Arriving in 12 mins"), known = app != null, kind = app!!.kind)
        }, allowedByRule = { false })
        assertTrue(com.dotforge.timer.awaitArbiter { AppGraph.arbiter.active.value.firstOrNull()?.id == LiveProgressRuntime.id("z") })
        page("progress")
        // the On the Glyph row and Now come before the apps
        rule.onNode(androidx.compose.ui.test.hasTestTag(com.dotforge.ui.features.ON_THE_GLYPH_TAG)).assertExists()
        rule.onNode(hasText("Zomato") and hasText("On the Glyph")).assertExists()
        val now = rule.onNodeWithText("Now").fetchSemanticsNode().positionInRoot.y
        val row = rule.onNode(androidx.compose.ui.test.hasTestTag(com.dotforge.ui.features.ON_THE_GLYPH_TAG)).fetchSemanticsNode().positionInRoot.y
        assertTrue(row < now)
        assertEquals(0, rule.onAllNodesWithText("Apps").fetchSemanticsNodes().count { it.positionInRoot.y < now })
    }

    @Test fun appsNotOnThePhoneFoldAwayAndSwitchesGreyWhenOff() {
        page("progress")
        scrollTo("not on this phone")
        // nothing is installed under Robolectric: every known app folds into one row, with no switches
        rule.onNodeWithText("${com.dotforge.live.ProgressApps.ALL.size} not on this phone").assertExists()
        assertEquals(0, rule.onAllNodes(hasText("Zomato") and isToggleable()).fetchSemanticsNodes().size)
        rule.onNodeWithText("${com.dotforge.live.ProgressApps.ALL.size} not on this phone").tap()
        rule.waitForIdle()
        scrollTo("Zomato")
        rule.onNodeWithText("Zomato").assertExists()
        // main switch off: Other apps reads off and grey
        AppGraph.setProgressRings(false)
        rule.waitForIdle()
        scrollTo("Other apps")
        rule.onNode(hasText("Other apps") and isToggleable()).assertIsOff().assertIsNotEnabled()
    }

    @Test fun alarmLead() {
        page("progress")
        scrollTo("Next alarm")
        rule.onAllNodesWithText("10")[1].tap()
        rule.waitForIdle()
        assertEquals(10, AppGraph.liveProgress.alarmLead.value)
    }
}
