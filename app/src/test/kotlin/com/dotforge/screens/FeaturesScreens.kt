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
 * Features, part A: the grid, Timer & stopwatch (idle, several running with a Clock app timer, ended, the custom
 * time, the styles, a timer's actions, the stopwatch with laps) and Live progress (empty, on the way, the apps,
 * coming up and options, off).
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

    private fun grid() = show {
        UndoSnackbarHost(bottomBar = true) {
            androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.FEATURES) {} }) { pad ->
                Box(Modifier.padding(pad)) { FeaturesScreen(Nav(Tab.FEATURES), Setup(activity), rememberLazyListState()) }
            }
        }
    }

    private fun page(key: String) = show {
        UndoSnackbarHost {
            Box(Modifier.fillMaxSize().background(Tokens.Background)) { FeaturePage(key, Nav(Tab.FEATURES), Setup(activity)) }
        }
    }

    private fun scrollTo(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        settle()
    }

    private fun bottom() {
        rule.onNode(hasScrollAction()).performScrollToIndex(100.coerceAtMost(lastIndex()))
        settle()
    }

    private fun lastIndex(): Int {
        val list = rule.onNode(hasScrollAction()).fetchSemanticsNode()
        return (list.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo) { null }?.rowCount ?: 20) - 1
    }

    private fun words(vararg t: String) = { app: ProgressApp? ->
        ProgressSignals(texts = t.toList(), kind = app?.kind ?: TripKind.DELIVERY, known = app != null, ongoing = true)
    }

    /** Zomato on the way, an Uber arriving, a download at 48%. */
    private fun onTheWay() {
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Rahul is on the way", "Arriving in 12 mins"), allowedByRule = { false })
        LiveProgressRuntime.update("u", "com.ubercab", "Uber", words("Your driver is arriving", "2 min away"), allowedByRule = { false })
        LiveProgressRuntime.update("d", "com.android.providers.downloads", "Downloads", { _ -> ProgressSignals(bar = 0.48f) }, allowedByRule = { true })
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
        scrollTo("How it works")
        capture(f, "11-timer-idle-options")
    }

    @Test fun timerRunning() {
        timers()
        page("timer")
        settle()
        capture(f, "12-timer-running")
        scrollTo("Show Clock app timers")
        capture(f, "12b-timer-running-lower")
        rule.onNodeWithText("Tea").tap()
        settle()
        capture(f, "16-timer-actions-sheet")
    }

    @Test fun timerEnded() {
        GlyphTimers.start(5 * 60_000)
        GlyphTimers.start(60_000, "Eggs")
        clock += 61_000
        GlyphTimers.tick()
        page("timer")
        settle()
        capture(f, "13-timer-ended")
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
        rule.onNodeWithText("Custom…").tap()
        settle()
        capture(f, "14-timer-custom-sheet")
    }

    @Test fun styles() {
        page("timer")
        scrollTo("Style")
        rule.onNodeWithText("Style").tap()
        settle()
        capture(f, "15-timer-style-sheet")
    }

    @Test fun stopwatch() {
        GlyphTimers.stopwatchStart()
        clock += 42_100
        GlyphTimers.stopwatchLap()
        clock += 38_700
        GlyphTimers.stopwatchLap()
        clock += 12_400
        page("timer")
        rule.onNodeWithText("Stopwatch").tap()
        settle()
        capture(f, "17-stopwatch-running-laps")
    }

    @Test fun progressEmpty() {
        page("progress")
        settle()
        capture(f, "20-live-progress-empty")
    }

    @Test fun progressOnTheWay() {
        onTheWay()
        page("progress")
        settle()
        capture(f, "21-live-progress-on-the-way")
        scrollTo("Rapido")
        capture(f, "22-live-progress-apps")
        scrollTo("Minutes in the middle")
        capture(f, "23-live-progress-coming-up-options")
    }

    @Test fun progressOff() {
        AppGraph.setProgressRings(false)
        page("progress")
        settle()
        capture(f, "24-live-progress-off")
        AppGraph.setProgressRings(true)
    }

    @Test fun progressLeads() {
        AppGraph.liveProgress.setAlarmLead(15)
        page("progress")
        scrollTo("Next alarm")
        capture(f, "25-live-progress-alarm-lead")
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
