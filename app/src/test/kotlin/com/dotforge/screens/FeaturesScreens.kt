package com.dotforge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.live.ClockMirror
import com.dotforge.live.ProgressApp
import com.dotforge.live.ProgressSignals
import com.dotforge.live.TripKind
import com.dotforge.notify.LiveProgressRuntime
import com.dotforge.timer.GlyphTimers
import com.dotforge.timer.TimesUp
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.FeaturePage
import com.dotforge.ui.features.FeaturesScreen
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Features, part A: the grid (and Needs access), Timer & stopwatch (idle with the style demo, its On the Glyph and
 * Also settings, several running with the one on the Glyph marked, ended with Dismiss, the last seconds, the
 * keypad, both style sheets, a timer's actions, Undo, the banners, the stopwatch with laps and paused, Time's up
 * full screen) and Live progress (empty, Needs access, on the way with arriving and done rows, the apps, coming
 * up, calendar access off, On the Glyph, off).
 */
abstract class FeaturesScreens : ScreenshotTest() {
    private val f = "features"
    private var clock = 1_800_000_000_000L

    @Before fun fresh() {
        GlyphTimers.reset()
        GlyphTimers.now = { clock }
        LiveProgressRuntime.reset()
        LiveProgressRuntime.now = { clock }
        AppGraph.liveProgress.reset()
        AppGraph.setProgressRings(true)
        AppGraph.arbiter.clearAll()
        org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // a phone with some of the known apps
        val pm = org.robolectric.Shadows.shadowOf(activity.packageManager)
        for (pkg in listOf("com.application.zomato", "in.swiggy.android", "com.zeptoconsumerapp", "com.ubercab", "com.rapido.passenger", "in.amazon.mShop.android.shopping")) {
            pm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg })
        }
    }

    @After fun clean() {
        GlyphTimers.reset()
        LiveProgressRuntime.reset()
        AppGraph.arbiter.clearAll()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private fun setup(access: Boolean): Setup {
        android.provider.Settings.Secure.putString(
            activity.contentResolver, "enabled_notification_listeners",
            if (access) activity.packageName + "/com.dotforge.notify.GlyphNotificationListener" else "",
        )
        return Setup(activity)
    }

    private fun grid(access: Boolean = true) = show {
        UndoSnackbarHost(bottomBar = true) {
            androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.FEATURES) {} }) { pad ->
                Box(Modifier.padding(pad)) { FeaturesScreen(Nav(Tab.FEATURES), setup(access), rememberLazyListState()) }
            }
        }
    }

    private fun page(key: String, access: Boolean = true) = show {
        UndoSnackbarHost {
            Box(Modifier.fillMaxSize().background(Tokens.Background)) { FeaturePage(key, Nav(Tab.FEATURES), setup(access)) }
        }
    }

    private fun scrollTo(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        settle()
    }

    private fun words(vararg t: String) = { app: ProgressApp? ->
        ProgressSignals(texts = t.toList(), kind = app?.kind ?: TripKind.DELIVERY, known = app != null, ongoing = true)
    }

    /** Zomato on the way, an Uber arriving, a download at 48%, a Swiggy order just delivered. */
    private fun onTheWay() {
        LiveProgressRuntime.update("s", "in.swiggy.android", "Swiggy", words("Arriving in 3 mins"), allowedByRule = { false })
        LiveProgressRuntime.update("s", "in.swiggy.android", "Swiggy", words("Your order has been delivered"), allowedByRule = { false })
        LiveProgressRuntime.update("d", "com.android.providers.downloads", "Downloads", { _ -> ProgressSignals(bar = 0.48f) }, allowedByRule = { true })
        LiveProgressRuntime.update("u", "com.ubercab", "Uber", words("Your driver is arriving now"), allowedByRule = { false })
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Rahul is on the way", "Arriving in 12 mins"), allowedByRule = { false })
    }

    /** Two of our timers (one paused) and a Clock app timer. */
    private fun timers() {
        GlyphTimers.start(10 * 60_000)
        GlyphTimers.start(5 * 60_000, "Tea")
        val paused = GlyphTimers.start(25 * 60_000, "Focus")
        clock += 48_000
        GlyphTimers.pause(paused.id)
        val m = ClockMirror.read(ClockMirror.Notice("com.google.android.deskclock", chronometer = true, countDown = true, whenMs = clock + 7 * 60_000 + 30_000, title = "Pasta"), clock)!!
        GlyphTimers.mirror("clock1", m)
    }

    @Test fun grid01() {
        grid()
        settle()
        capture(f, "01-features-grid")
        grid(access = false)
        settle()
        capture(f, "01b-features-grid-needs-access")
        timers()
        onTheWay()
        grid()
        settle()
        capture(f, "02-features-grid-busy")
    }

    @Test fun timerIdle() {
        page("timer")
        settle()
        capture(f, "10-timer-idle")
        scrollTo("Finish speed")
        capture(f, "11-timer-on-the-glyph")
        scrollTo("Try it")
        capture(f, "11b-timer-on-the-glyph-lower")
        scrollTo("How it works")
        capture(f, "11c-timer-also")
    }

    @Test fun timerNeedsAccessAndBanners() {
        org.robolectric.Shadows.shadowOf(activity.application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        page("timer", access = false)
        settle()
        capture(f, "18-timer-banners")
        scrollTo("How it works")
        capture(f, "18b-timer-clock-needs-access")
    }

    @Test fun timerRunning() {
        timers()
        page("timer")
        settle()
        capture(f, "12-timer-running")
        scrollTo("Pasta")
        capture(f, "12b-timer-running-lower")
        rule.onNodeWithText("Tea").tap()
        settle()
        capture(f, "16-timer-actions-sheet")
    }

    @Test fun timerStopUndo() {
        GlyphTimers.start(5 * 60_000, "Tea")
        page("timer")
        settle()
        rule.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription("Stop"))
        settle()
        rule.onNodeWithContentDescription("Stop").tap()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        capture(f, "16b-timer-stopped-undo")
    }

    @Test fun timerEnded() {
        GlyphTimers.start(5 * 60_000)
        GlyphTimers.start(60_000, "Eggs")
        clock += 61_000
        GlyphTimers.tick()
        page("timer")
        settle()
        // the finish is between bursts at 2.1 s: show it mid-firework
        rule.mainClock.advanceTimeBy(700)
        capture(f, "13-timer-ended")
        show { TimesUp(onClose = {}) }
        settle()
        rule.mainClock.advanceTimeBy(700)
        capture(f, "13c-times-up-full-screen")
    }

    @Test fun timerLastSeconds() {
        GlyphTimers.start(60_000, "Eggs")
        clock += 52_000
        page("timer")
        settle()
        capture(f, "13b-timer-last-seconds")
    }

    @Test fun customTimer() {
        page("timer")
        scrollTo("Custom…")
        rule.onNodeWithText("Custom…").tap()
        settle()
        capture(f, "14-timer-custom-keypad")
    }

    @Test fun styles() {
        page("timer")
        scrollTo("Timer style")
        rule.onNodeWithText("Timer style").tap()
        settle()
        capture(f, "15-timer-style-sheet")
    }

    @Test fun stopwatch() {
        GlyphTimers.stopwatchStart()
        clock += 42_100
        GlyphTimers.stopwatchLap()
        clock += 38_700
        GlyphTimers.stopwatchLap()
        clock += 45_200
        GlyphTimers.stopwatchLap()
        clock += 12_400
        page("timer")
        rule.onNodeWithText("Stopwatch").tap()
        settle()
        capture(f, "17-stopwatch-running-laps")
        scrollTo("Lap 1")
        capture(f, "17b-stopwatch-laps")
        GlyphTimers.stopwatchPause()
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        settle()
        capture(f, "17c-stopwatch-paused")
        scrollTo("Stopwatch style")
        rule.onNodeWithText("Stopwatch style").tap()
        settle()
        capture(f, "15b-stopwatch-style-sheet")
    }

    @Test fun progressEmpty() {
        page("progress")
        settle()
        capture(f, "20-live-progress-empty")
        page("progress", access = false)
        settle()
        capture(f, "20b-live-progress-needs-access")
    }

    @Test fun progressOnTheWay() {
        onTheWay()
        page("progress")
        settle()
        capture(f, "21-live-progress-on-the-way")
        scrollTo("Swiggy")
        capture(f, "21b-live-progress-arriving-and-done")
        scrollTo("Rapido")
        capture(f, "22-live-progress-apps")
        scrollTo("Next alarm")
        capture(f, "23-live-progress-coming-up")
        scrollTo("Middle shows")
        capture(f, "23b-live-progress-on-the-glyph")
        scrollTo("Try it")
        capture(f, "23c-live-progress-done-animation")
    }

    @Test fun progressOff() {
        AppGraph.setProgressRings(false)
        page("progress")
        settle()
        capture(f, "24-live-progress-off")
        scrollTo("Rapido")
        capture(f, "24b-live-progress-off-apps")
        AppGraph.setProgressRings(true)
    }

    @Test fun progressLeads() {
        AppGraph.liveProgress.setAlarmLead(15)
        AppGraph.liveProgress.setCalendarLead(10)
        page("progress")
        scrollTo("Calendar access is off")
        capture(f, "25-live-progress-leads-calendar-off")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FeaturesPhone3 : FeaturesScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class FeaturesSmall13 : FeaturesScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FeaturesPhone3Font2 : FeaturesScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
