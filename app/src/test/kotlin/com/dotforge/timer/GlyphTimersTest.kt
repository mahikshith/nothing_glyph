package com.dotforge.timer

import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dotforge.AppGraph
import com.dotforge.engine.Priority
import com.dotforge.live.ClockMirror
import com.dotforge.live.LiveProgress
import com.dotforge.live.ProgressSignals
import com.dotforge.live.TimerShown
import com.dotforge.notify.LiveProgressRuntime
import com.dotforge.rules.RuleEngine
import com.dotforge.screens.ScreenshotApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Waits (up to 2 s) for the arbiter's engine thread to have [cond] true. */
internal fun awaitArbiter(cond: () -> Boolean): Boolean {
    repeat(100) { if (cond()) return true; Thread.sleep(20) }
    return cond()
}

/** Timer & stopwatch: our timers on the Glyph, the nearest wins, the burst until dismissed, the Clock app, the notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class)
class GlyphTimersTest {
    private var clock = 1_000_000_000L
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun fresh() {
        GlyphTimers.reset()
        GlyphTimers.now = { clock }
        AppGraph.arbiter.clearAll()
    }

    @After fun done() {
        GlyphTimers.reset()
        AppGraph.arbiter.clearAll()
    }

    private fun active(id: String) = AppGraph.arbiter.active.value.firstOrNull { it.id == id }

    @Test fun aTimerShowsOnTheGlyphAsAnAppFrame() {
        GlyphTimers.start(5 * 60_000)
        assertTrue(awaitArbiter { active(GlyphTimers.ID) != null })
        val r = active(GlyphTimers.ID)!!
        assertEquals(Priority.TIMER, r.priority)
        assertTrue(r.loop)
        assertEquals("Timer · 5:00 left", r.label)
    }

    @Test fun theNearestTimerShows() {
        GlyphTimers.start(5 * 60_000)
        GlyphTimers.start(2 * 60_000, "Eggs")
        clock += 10_000
        val shown = GlyphTimers.shown() as TimerShown.Running
        assertEquals("Eggs", shown.timer.name)
        assertEquals(1, shown.others)
    }

    @Test fun anEndedTimerBurstsUntilDismissed() {
        val t = GlyphTimers.start(60_000)
        clock += 61_000
        GlyphTimers.tick()
        assertTrue(GlyphTimers.state.value.timers.single().doneAt > 0)
        assertTrue(awaitArbiter { active(GlyphTimers.DONE_ID) != null })
        assertEquals(Priority.TIMER_DONE, active(GlyphTimers.DONE_ID)!!.priority)
        assertTrue(active(GlyphTimers.DONE_ID)!!.loop)
        // still bursting a minute later
        clock += 60_000
        GlyphTimers.tick()
        assertTrue(awaitArbiter { active(GlyphTimers.DONE_ID) != null })
        GlyphTimers.dismiss(t.id)
        assertTrue(awaitArbiter { active(GlyphTimers.DONE_ID) == null && active(GlyphTimers.ID) == null })
        assertTrue(GlyphTimers.state.value.timers.isEmpty())
    }

    @Test fun lastTenSecondsPulse() {
        GlyphTimers.start(60_000)
        clock += 52_000
        GlyphTimers.tick()
        assertTrue(awaitArbiter { active(GlyphTimers.ID)?.label == "Timer · 0:08 left" })
        val frames = active(GlyphTimers.ID)!!.animation.frames
        val p = AppGraph.profile
        assertTrue(frames.first().frame.pixels[p.ring[0]] > frames.last().frame.pixels[p.ring[0]])
    }

    @Test fun pauseResumeAndPlusMinute() {
        val t = GlyphTimers.start(5 * 60_000)
        clock += 60_000
        GlyphTimers.pause(t.id)
        clock += 10 * 60_000
        assertEquals(4 * 60_000L, GlyphTimers.state.value.timers.single().left(clock))
        GlyphTimers.resume(t.id)
        GlyphTimers.plusMinute(t.id)
        assertEquals(5 * 60_000L, GlyphTimers.state.value.timers.single().left(clock))
    }

    @Test fun timersSurviveTheProcess() {
        GlyphTimers.start(5 * 60_000, "Tea")
        GlyphTimers.stopwatchStart()
        clock += 30_000
        GlyphTimers.stopwatchLap()
        GlyphTimers.init(ctx)
        val s = GlyphTimers.state.value
        assertEquals("Tea", s.timers.single().name)
        assertEquals(1, s.stopwatch.laps.size)
        assertTrue(s.stopwatch.running)
    }

    @Test fun stopwatchShowsWhenNoTimerRuns() {
        GlyphTimers.stopwatchStart()
        clock += 65_000
        GlyphTimers.tick()
        assertTrue(awaitArbiter { active(GlyphTimers.ID)?.label == "Stopwatch · 1:05" })
        GlyphTimers.stopwatchReset()
        assertTrue(awaitArbiter { active(GlyphTimers.ID) == null })
    }

    @Test fun clockAppTimersMirror() {
        val m = ClockMirror.read(ClockMirror.Notice("com.google.android.deskclock", chronometer = true, countDown = true, whenMs = clock + 90_000, title = "Timer"), clock)!!
        GlyphTimers.mirror("k1", m)
        assertTrue(awaitArbiter { active(GlyphTimers.ID)?.label == "Clock timer · 1:30 left" })
        // hidden from Home's Stop, back when its notification goes and comes again
        GlyphTimers.stopShown()
        assertNull(GlyphTimers.shown())
        GlyphTimers.unmirror("k1")
        assertTrue(GlyphTimers.state.value.clock.isEmpty())
        // the switch off: nothing mirrors
        GlyphTimers.setMirrorClock(false)
        GlyphTimers.mirror("k2", m)
        assertTrue(GlyphTimers.state.value.clock.isEmpty())
        assertFalse(GlyphTimers.mirrorClock.value)
    }

    @Test fun mirrorIsOnByDefault() {
        assertTrue(GlyphTimers.mirrorClock.value)
    }

    @Test fun liveUpdateNotification() {
        val t = GlyphTimers.start(5 * 60_000)
        clock += 48_000
        val n = TimerNotifications.ongoing(ctx, GlyphTimers.state.value, clock)
        assertEquals("4:12 left", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(t.endsAt, n.`when`)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Pause", "+1 min", "Stop"), n.actions.map { it.title.toString() })
        clock += 5 * 60_000
        GlyphTimers.tick()
        val up = TimerNotifications.timesUp(ctx, GlyphTimers.state.value.timers)
        assertEquals("Time's up", up.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("5 min timer", up.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun notificationButtons() {
        val t = GlyphTimers.start(5 * 60_000)
        val r = TimerReceiver()
        r.onReceive(ctx, Intent(TimerReceiver.PAUSE).putExtra(TimerReceiver.EXTRA_ID, t.id))
        assertFalse(GlyphTimers.state.value.timers.single().running)
        r.onReceive(ctx, Intent(TimerReceiver.RESUME).putExtra(TimerReceiver.EXTRA_ID, t.id))
        assertTrue(GlyphTimers.state.value.timers.single().running)
        r.onReceive(ctx, Intent(TimerReceiver.PLUS).putExtra(TimerReceiver.EXTRA_ID, t.id))
        assertEquals(6 * 60_000L, GlyphTimers.state.value.timers.single().totalMs)
        r.onReceive(ctx, Intent(TimerReceiver.CANCEL).putExtra(TimerReceiver.EXTRA_ID, t.id))
        assertTrue(GlyphTimers.state.value.timers.isEmpty())
        r.onReceive(ctx, Intent(TimerReceiver.WATCH_START))
        assertTrue(GlyphTimers.state.value.stopwatch.running)
    }

    @Test fun startingATimerStartsTheService() {
        GlyphTimers.init(ctx)
        GlyphTimers.now = { clock }
        GlyphTimers.start(60_000)
        val started = org.robolectric.Shadows.shadowOf(ctx).nextStartedService
        assertEquals(TimerService::class.java.name, started?.component?.className)
    }
}

/** Live progress from notifications: known apps by package or label, their switches, other apps by rule, done once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class)
class LiveProgressRuntimeTest {
    private var clock = 1_800_000_000_000L

    @Before fun fresh() {
        LiveProgressRuntime.reset()
        LiveProgressRuntime.now = { clock }
        AppGraph.setProgressRings(true)
        AppGraph.liveProgress.reset()
        AppGraph.arbiter.clearAll()
    }

    @After fun done() {
        LiveProgressRuntime.reset()
        AppGraph.arbiter.clearAll()
    }

    private fun words(vararg t: String, ongoing: Boolean = true) = { app: com.dotforge.live.ProgressApp? ->
        ProgressSignals(texts = t.toList(), kind = app?.kind ?: com.dotforge.live.TripKind.DELIVERY, known = app != null, ongoing = ongoing)
    }

    private fun has(id: String) = AppGraph.arbiter.active.value.any { it.id == id }

    @Test fun aKnownAppsArrivalTimeLightsARing() {
        assertTrue(LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Rahul is on the way", "Arriving in 12 mins"), allowedByRule = { false }))
        val item = LiveProgressRuntime.items.value.single()
        assertEquals("Zomato", item.name)
        assertEquals(12, item.reading.minutesLeft)
        assertEquals("12 min left · On the way", LiveProgress.summary(item.reading))
        assertTrue(awaitArbiter { has("p:z") })
        assertEquals("Zomato · 12 min left · On the way", AppGraph.arbiter.active.value.first { it.id == "p:z" }.label)
    }

    @Test fun matchedByLabelToo() {
        assertTrue(LiveProgressRuntime.update("s", "com.example.instamart", "Instamart", words("Delivering in 9 mins", ongoing = false), allowedByRule = { false }))
        assertEquals("Swiggy", LiveProgressRuntime.items.value.single().name)
    }

    @Test fun aSwitchedOffAppDoesNot() {
        AppGraph.liveProgress.setApp("zomato", false)
        assertFalse(LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Arriving in 12 mins"), allowedByRule = { true }))
        assertTrue(LiveProgressRuntime.items.value.isEmpty())
    }

    @Test fun otherAppsNeedARule() {
        assertFalse(LiveProgressRuntime.update("o", "com.example.shop", "Shop", words("Arriving in 12 mins"), allowedByRule = { false }))
        assertTrue(LiveProgressRuntime.update("o", "com.example.shop", "Shop", words("Arriving in 12 mins"), allowedByRule = { true }))
        // a plain message from an app we don't know is never read
        assertFalse(LiveProgressRuntime.update("m", "com.whatsapp", "WhatsApp", words("I'll be there in 10 mins", ongoing = false), allowedByRule = { true }))
    }

    @Test fun doneAnimatesOnceThenGoes() {
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Arriving in 2 mins"), allowedByRule = { false })
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Delivered. Enjoy your meal!"), allowedByRule = { false })
        assertTrue(LiveProgressRuntime.items.value.isEmpty())
        assertTrue(awaitArbiter { has("pd:z") && !has("p:z") })
        AppGraph.arbiter.clearAll()
        assertTrue(awaitArbiter { !has("pd:z") })
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Delivered"), allowedByRule = { false })
        Thread.sleep(100)
        assertFalse(has("pd:z"))
    }

    @Test fun offTakesEveryRingOff() {
        LiveProgressRuntime.update("z", "com.application.zomato", "Zomato", words("Arriving in 12 mins"), allowedByRule = { false })
        AppGraph.setProgressRings(false)
        assertTrue(LiveProgressRuntime.items.value.isEmpty())
        assertTrue(awaitArbiter { !has("p:z") })
        AppGraph.setProgressRings(true)
    }

    @Test fun otherAppsSwitchIsTheShowProgressRule() {
        assertTrue(AppGraph.rules.rules.value.any { it.id == RuleEngine.PROGRESS_PRESET })
    }
}
