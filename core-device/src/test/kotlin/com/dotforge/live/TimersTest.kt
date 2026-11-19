package com.dotforge.live

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.TimerFaces
import com.dotforge.device.TimerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimersTest {
    private val t0 = 10_000_000L

    @Test fun startPauseResumeAndAMinute() {
        var t = GlyphTimer.start("a", 5 * 60_000, t0)
        assertEquals(5 * 60_000L, t.left(t0))
        assertEquals(3 * 60_000L, t.left(t0 + 2 * 60_000))
        t = t.pause(t0 + 2 * 60_000)
        assertFalse(t.running)
        assertEquals(3 * 60_000L, t.left(t0 + 99 * 60_000))
        t = t.resume(t0 + 10 * 60_000)
        assertEquals(t0 + 13 * 60_000, t.endsAt)
        t = t.plusMinute(t0 + 10 * 60_000)
        assertEquals(4 * 60_000L, t.left(t0 + 10 * 60_000))
        assertEquals(6 * 60_000L, t.totalMs)
    }

    @Test fun anEndedTimerStartsAgainWithAMinute() {
        val t = GlyphTimer.start("a", 60_000, t0).copy(doneAt = t0 + 60_000)
        val again = t.plusMinute(t0 + 70_000)
        assertTrue(again.running)
        assertEquals(0L, again.doneAt)
        assertEquals(60_000L, again.left(t0 + 70_000))
    }

    @Test fun settleMarksEndedTimers() {
        val list = listOf(GlyphTimer.start("a", 60_000, t0), GlyphTimer.start("b", 120_000, t0))
        val (out, ended) = Timers.settle(list, t0 + 90_000)
        assertEquals(listOf("a"), ended.map { it.id })
        assertEquals(t0 + 60_000, out[0].doneAt)
        assertEquals(0L, out[1].doneAt)
        assertEquals(t0 + 120_000, Timers.nextEnd(out))
        // settling again finds nothing new
        assertTrue(Timers.settle(out, t0 + 91_000).second.isEmpty())
    }

    @Test fun theGlyphShowsTheNearest() {
        val five = GlyphTimer.start("five", 5 * 60_000, t0)
        val two = GlyphTimer.start("two", 2 * 60_000, t0 + 10_000)
        val shown = Timers.shown(listOf(five, two), null, t0 + 20_000) as TimerShown.Running
        assertEquals("two", shown.timer.id)
        assertEquals(1, shown.others)
        // an ended one wins until it is dismissed
        val done = Timers.shown(listOf(five, two.copy(doneAt = t0 + 130_000)), null, t0 + 140_000)
        assertTrue(done is TimerShown.Done)
        // a running timer beats the stopwatch; the stopwatch beats a paused timer
        val watch = Stopwatch().start(t0)
        assertTrue(Timers.shown(listOf(five), watch, t0 + 1) is TimerShown.Running)
        assertTrue(Timers.shown(listOf(five.pause(t0 + 1)), watch, t0 + 2) is TimerShown.Watch)
        assertTrue(Timers.shown(listOf(five.pause(t0 + 1)), null, t0 + 2) is TimerShown.Paused)
        assertNull(Timers.shown(emptyList(), Stopwatch(), t0))
    }

    @Test fun stopwatchLaps() {
        var s = Stopwatch().start(t0)
        s = s.lap(t0 + 30_000)
        s = s.lap(t0 + 72_000)
        s = s.pause(t0 + 80_000)
        assertEquals(80_000L, s.elapsed(t0 + 999_000))
        s = s.start(t0 + 100_000)
        assertEquals(90_000L, s.elapsed(t0 + 110_000))
        val rows = s.lapRows()
        assertEquals(listOf(2, 1), rows.map { it.number })
        assertEquals(42_000L, rows[0].splitMs)
        assertEquals(72_000L, rows[0].totalMs)
        assertFalse(s.reset().started)
    }

    @Test fun words() {
        assertEquals("5 min", Timers.lengthName(300_000))
        assertEquals("1 h 30 min", Timers.lengthName(5_400_000))
        assertEquals("2 min 30 s", Timers.lengthName(150_000))
        assertEquals("5 min timer", GlyphTimer.start("a", 300_000, 0).name)
        assertEquals("Tea", GlyphTimer.start("a", 300_000, 0, "Tea").name)
        assertEquals("4:05", TimerFaces.clock(244_001))
        assertEquals("1:02:03", TimerFaces.clock(3_723_000))
        assertEquals("0:42.1", TimerFaces.lap(42_150))
        assertEquals(1_000L, Timers.custom(0, 0, 0))
        assertEquals(Timers.MAX_MS - 1000, Timers.custom(99, 99, 99))
        assertEquals(90_000L, Timers.custom(0, 1, 30))
    }
}

class ClockMirrorTest {
    private val now = 50_000_000L
    private val clock = "com.google.android.deskclock"

    @Test fun runningTimer() {
        val m = ClockMirror.read(ClockMirror.Notice(clock, chronometer = true, countDown = true, whenMs = now + 90_000, title = "Timer"), now)!!
        assertEquals(ClockMirror.Kind.TIMER, m.kind)
        assertTrue(m.running)
        val t = ClockMirror.toTimer("k", m, now, null)!!
        assertEquals(90_000L, t.left(now))
        assertEquals(TimerSource.CLOCK, t.source)
        // "Timer" is the Clock app's word for an unnamed one; a real name is kept
        assertEquals("", m.label)
        assertEquals("Pasta", ClockMirror.read(ClockMirror.Notice(clock, chronometer = true, countDown = true, whenMs = now + 90_000, title = "Pasta"), now)!!.label)
        // later sightings keep the first total, so the ring drains
        val later = ClockMirror.toTimer("k", m, now + 30_000, t)!!
        assertEquals(90_000L, later.totalMs)
        assertEquals(60_000L, later.left(now + 30_000))
    }

    @Test fun pausedTimerAndTimesUp() {
        val p = ClockMirror.read(ClockMirror.Notice(clock, title = "Timer", text = "Paused · 4:32"), now)!!
        assertFalse(p.running)
        assertEquals(272_000L, p.shownMs)
        val done = ClockMirror.read(ClockMirror.Notice(clock, title = "Time's up", text = "5 min timer"), now)!!
        assertTrue(done.done)
        val t = ClockMirror.toTimer("k", done, now, null)!!
        assertTrue(t.over(now))
    }

    @Test fun stopwatch() {
        val m = ClockMirror.read(ClockMirror.Notice(clock, category = "stopwatch", chronometer = true, whenMs = now - 65_000, title = "Stopwatch"), now)!!
        assertEquals(ClockMirror.Kind.STOPWATCH, m.kind)
        assertEquals(65_000L, ClockMirror.toStopwatch(m, now)!!.elapsed(now))
    }

    @Test fun otherAppsAndAlarmsAreNotTimers() {
        assertNull(ClockMirror.read(ClockMirror.Notice("com.whatsapp", chronometer = true, countDown = true, whenMs = now + 1), now))
        assertNull(ClockMirror.read(ClockMirror.Notice(clock, title = "Upcoming alarm", text = "Wed 7:00 AM"), now))
        assertTrue(ClockMirror.isClock("com.nothing.deskclock"))
        assertTrue(ClockMirror.isClock("com.example.alarmclock"))
        assertFalse(ClockMirror.isClock("com.whatsapp"))
    }

    @Test fun parseClock() {
        assertEquals(272_000L, ClockMirror.parseClock("4:32"))
        assertEquals(3_723_000L, ClockMirror.parseClock("1:02:03 left"))
        assertNull(ClockMirror.parseClock("no time"))
        assertNull(ClockMirror.parseClock("4:75"))
    }
}

class TimerFacesTest {
    private val p = DeviceProfile.Phone3
    private fun Frame.lit(above: Int = 0) = p.ledCells.count { pixels[it] > above }
    private fun Frame.onlyLeds() = pixels.indices.all { p.mask[it] || pixels[it] == 0 }

    @Test fun everyStyleDrawsAndChangesAsTimePasses() {
        for (style in TimerStyle.entries) {
            val a = TimerFaces.countdown(p, style, 4 * 60_000, 5 * 60_000)
            val b = TimerFaces.countdown(p, style, 2 * 60_000, 5 * 60_000)
            assertTrue(style.name, a.lit() > 5)
            assertTrue(style.name, a.onlyLeds())
            assertNotEquals(style.name, a.pixels.toList(), b.pixels.toList())
            val w = TimerFaces.stopwatch(p, style, 25_000)
            assertTrue(style.name, w.lit() > 5)
        }
    }

    @Test fun ringDrainsAndPieShrinks() {
        val full = TimerFaces.drain(p, 1f)
        val half = TimerFaces.drain(p, 0.5f)
        assertEquals(p.ring.size, p.ring.count { full.pixels[it] == 255 })
        assertEquals(p.ring.size / 2.0, p.ring.count { half.pixels[it] == 255 }.toDouble(), 1.0)
        assertTrue(TimerFaces.pie(p, 0.75f).lit(100) > TimerFaces.pie(p, 0.25f).lit(100))
    }

    @Test fun dotsGoOutOneByOne() {
        val count = { f: Frame -> f.lit(200) / 5 }
        assertEquals(25, count(TimerFaces.dots(p, 1f, blink = false)))
        assertEquals(13, count(TimerFaces.dots(p, 0.5f, blink = false)))
        assertEquals(12, count(TimerFaces.dots(p, 0.5f, blink = true))) // the next to go blinks
        assertEquals(0, count(TimerFaces.dots(p, 0f, blink = false)))
    }

    @Test fun hourglassMovesSandDown() {
        val c = (p.size - 1) / 2
        fun top(f: Frame) = p.ledCells.count { it / p.size < c && f.pixels[it] == 220 }
        fun bottom(f: Frame) = p.ledCells.count { it / p.size > c && f.pixels[it] == 220 }
        val start = TimerFaces.hourglass(p, 1f, 0f)
        val end = TimerFaces.hourglass(p, 0f, 0f)
        assertTrue(top(start) > 20 && bottom(start) == 0)
        assertTrue(top(end) == 0 && bottom(end) > 20)
    }

    @Test fun orbitGoesRound() {
        val a = TimerFaces.orbit(p, 0f)
        val b = TimerFaces.orbit(p, 0.5f)
        assertEquals(255, a.pixels[p.ring[0]])
        assertEquals(255, b.pixels[p.ring[p.ring.size / 2]])
    }

    @Test fun middleNumber() {
        assertEquals("5", TimerFaces.middle(4 * 60_000 + 1))
        assertEquals("60", TimerFaces.middle(60_000))
        assertEquals("9", TimerFaces.middle(8_001))
        assertEquals("120", TimerFaces.middle(119 * 60_000 + 1))
    }

    @Test fun bigDigitsReadMinutesOverSeconds() {
        assertEquals(Effects.centredDigits(p, listOf("04", "05")).pixels.toList(), TimerFaces.bigDigits(p, 245).pixels.toList())
        assertEquals(Effects.centredDigits(p, listOf("1", "02")).pixels.toList(), TimerFaces.bigDigits(p, 3_720).pixels.toList())
        assertEquals(Effects.centredDigits(p, listOf("42")).pixels.toList(), TimerFaces.bigDigits(p, 42).pixels.toList())
    }

    @Test fun lastTenSecondsPulse() {
        val a = TimerFaces.second(p, TimerStyle.PIE, 9_000, 60_000)
        assertEquals(4, a.frames.size)
        val ring = a.frames.map { f -> f.frame.pixels[p.ring[0]] }
        assertTrue(ring.zipWithNext().all { (x, y) -> x > y }) // bright, then fading
        // before the last 10 s it is the style, four steps a second
        val b = TimerFaces.second(p, TimerStyle.ORBIT, 30_000, 60_000)
        assertEquals(1000, b.totalMs)
        assertNotEquals(b.frames[0].frame.pixels.toList(), b.frames[2].frame.pixels.toList())
    }

    @Test fun finishedBurstsAndEnds() {
        val a = TimerFaces.finished(p)
        assertTrue(a.frames.size > 10)
        assertTrue(a.frames.any { f -> f.frame.lit() == p.ledCount })
        assertTrue(a.frames.last().frame.lit() == 0)
    }

    @Test fun brightnessDims() {
        val full = TimerFaces.second(p, TimerStyle.RING_DRAIN, 60_000, 60_000).frames[0].frame
        val dim = TimerFaces.second(p, TimerStyle.RING_DRAIN, 60_000, 60_000, brightness = 0.5f).frames[0].frame
        assertEquals(255, full.pixels[p.ring[0]])
        assertEquals(127, dim.pixels[p.ring[0]])
    }

    @Test fun previewsDifferPerStyle() {
        val posters = TimerStyle.entries.map { TimerFaces.preview(p, it).frames[0].frame.pixels.toList() }
        assertEquals(posters.size, posters.toSet().size)
    }
}

class LiveRingTest {
    private val p = DeviceProfile.Phone3
    private fun Frame.lit() = p.ledCells.count { pixels[it] > 0 }

    @Test fun iconFitsInsideTheRing() {
        for (app in ProgressApps.ALL) assertTrue(app.id, LiveRing.shrink(p, LiveRing.icon(p, app.icon)!!).lit() > 10)
        for (s in Stage.entries) assertTrue(s.name, LiveRing.icon(p, s.icon)!!.lit() > 10)
        val small = LiveRing.shrink(p, LiveRing.icon(p, "g-food-bag")!!)
        val ring = p.ring.toHashSet()
        assertTrue(small.lit() > 10)
        assertTrue(p.ledCells.filter { small.pixels[it] > 0 }.none { it in ring })
        val n = p.size
        val xs = p.ledCells.filter { small.pixels[it] > 0 }.map { it % n }
        assertTrue(xs.max() - xs.min() < LiveRing.ICON_BOX)
    }

    @Test fun centreShowsMinutesOrIcon() {
        val m = LiveRing.frame(p, 0.5f, Centre.Minutes(12))
        assertEquals(p.ring.size / 2.0, p.ring.count { m.pixels[it] == 255 }.toDouble(), 1.0)
        assertTrue(m.lit() > p.ring.size)
        val none = LiveRing.frame(p, 0.5f, Centre.None)
        assertTrue(m.lit() > none.lit())
    }

    @Test fun arrivingPulsesAndDoneEndsFull() {
        assertEquals(1, LiveRing.animation(p, 0.9f, Centre.Minutes(2), arriving = false).frames.size)
        val pulse = LiveRing.animation(p, 0.9f, Centre.Minutes(2), arriving = true)
        assertTrue(pulse.frames.map { it.frame.pixels[p.ring[0]] }.toSet().size > 3)
        val done = LiveRing.done(p, null)
        assertEquals(p.ring.size, p.ring.count { done.frames.last().frame.pixels[it] == 255 })
    }

    @Test fun approachingRing() {
        val a = LiveRing.approaching(p, 0.5f, 7, null)
        assertEquals(1, a.frames.size)
        assertTrue(LiveRing.approaching(p, 0.98f, 1, null).frames.size > 1)
    }
}
