package com.dotforge.live

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.TimerFaces
import com.dotforge.device.StopwatchStyle
import com.dotforge.device.TimerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimersTest {
    private val t0 = 10_000_000L

    @Test fun theFinishRunsThenFlashesAgain() {
        assertEquals(Long.MAX_VALUE, Timers.finishShowing(5 * 60_000, null, null))
        assertEquals(20_000L, Timers.finishShowing(10_000, 30_000, null))
        assertNull(Timers.finishShowing(31_000, 30_000, null))
        // flash again every minute after the run, for a few seconds
        assertNull(Timers.finishShowing(31_000, 30_000, 60_000))
        assertEquals(Timers.FLASH_AGAIN_MS, Timers.finishShowing(90_000, 30_000, 60_000))
        assertEquals(Timers.FLASH_AGAIN_MS - 1_000, Timers.finishShowing(151_000, 30_000, 60_000))
        assertNull(Timers.finishShowing(160_000, 30_000, 60_000))
    }

    @Test fun aChosenTimerShowsInsteadOfTheNearest() {
        val a = GlyphTimer.start("a", 60_000, t0)
        val b = GlyphTimer.start("b", 5 * 60_000, t0)
        assertEquals("a", Timers.shown(listOf(a, b), null, t0)!!.key)
        assertEquals("b", Timers.shown(listOf(a, b), null, t0, focus = "b")!!.key)
        // an ended timer still wins
        assertEquals("a", Timers.shown(listOf(a, b), null, t0 + 61_000, focus = "b")!!.key)
    }

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
        // a paused stopwatch still shows (blinking II), after a paused timer
        assertTrue(Timers.shown(emptyList(), watch.pause(t0 + 3), t0 + 4) is TimerShown.Watch)
        assertTrue(Timers.shown(listOf(five.pause(t0 + 1)), watch.pause(t0 + 3), t0 + 4) is TimerShown.Paused)
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
    /** True when every lit cell of [part] is lit in this frame. */
    private fun Frame.has(part: Frame) = p.ledCells.all { part.pixels[it] == 0 || pixels[it] > 0 }

    @Test fun everyStyleDrawsAndChangesAsTimePasses() {
        for (style in TimerStyle.entries) {
            val a = TimerFaces.countdown(p, style, 4 * 60_000, 5 * 60_000)
            val b = TimerFaces.countdown(p, style, 2 * 60_000, 5 * 60_000)
            assertTrue(style.name, a.lit() > 5)
            assertTrue(style.name, a.onlyLeds())
            assertNotEquals(style.name, a.pixels.toList(), b.pixels.toList())
        }
        for (style in StopwatchStyle.entries) {
            val w = TimerFaces.stopwatch(p, style, 25_000)
            assertTrue(style.name, w.lit() > 5)
            assertTrue(style.name, w.onlyLeds())
            assertNotEquals(style.name, w.pixels.toList(), TimerFaces.stopwatch(p, style, 35_000).pixels.toList())
        }
    }

    @Test fun fromAMinuteTheMiddleReadsMinutesAndSeconds() {
        for (style in listOf(TimerStyle.RING_DRAIN, TimerStyle.ORBIT)) {
            val f = TimerFaces.countdown(p, style, 4 * 60_000 + 12_000, 5 * 60_000)
            assertTrue(style.name, f.has(TimerFaces.small(p, "4:12")))
            // under a minute: big seconds, never a bare number that could be minutes
            val g = TimerFaces.countdown(p, style, 42_000, 5 * 60_000)
            assertTrue(style.name, g.has(TimerFaces.digits(p, "42")))
        }
        assertEquals("4:12", TimerFaces.readout(252))
        assertEquals("1:05", TimerFaces.readout(3_900))
        assertEquals("42", TimerFaces.readout(42))
        assertEquals(13, TimerFaces.smallWidth("4:12"))
    }

    @Test fun inTheLastMinuteTheRingCountsSecondsAndTicks() {
        val a = TimerFaces.secondsRing(p, 30, 0f)
        val b = TimerFaces.secondsRing(p, 30, 0.75f)
        assertEquals(p.ring.size / 2.0, p.ring.count { b.pixels[it] >= 150 }.toDouble(), 1.0)
        // the tick: the dot that just went out flashes at the start of the second
        assertTrue(p.ring.count { a.pixels[it] == 255 } > p.ring.count { b.pixels[it] == 255 })
    }

    @Test fun theStopwatchAlwaysShowsMinutesAndSeconds() {
        assertEquals("0:05", TimerFaces.watchReadout(5))
        assertEquals("5:00", TimerFaces.watchReadout(300))
        assertEquals("1:02", TimerFaces.watchReadout(3_720))
        assertTrue(TimerFaces.stopwatch(p, StopwatchStyle.RING_FILLS, 5_000).has(TimerFaces.small(p, "0:05")))
        assertTrue(TimerFaces.stopwatch(p, StopwatchStyle.COMET, 300_000).has(TimerFaces.small(p, "5:00")))
        assertEquals(Effects.centredDigits(p, listOf("00", "05")).pixels.toList(), TimerFaces.stopwatch(p, StopwatchStyle.BIG_DIGITS, 5_000).pixels.toList())
        assertEquals(StopwatchStyle.BIG_DIGITS, StopwatchStyle.of(null))
    }

    @Test fun ringDrainsWithABrightHeadAndPieHasAnEdge() {
        val full = TimerFaces.drain(p, 1f)
        val half = TimerFaces.drain(p, 0.5f)
        assertEquals(p.ring.size, p.ring.count { full.pixels[it] >= 150 })
        assertEquals(p.ring.size / 2.0, p.ring.count { half.pixels[it] >= 150 }.toDouble(), 1.0)
        // the head is the brightest, the body behind it softer
        assertEquals(255, half.pixels[p.ring[p.ring.size / 2 - 1]])
        assertEquals(150, half.pixels[p.ring[0]])
        assertTrue(TimerFaces.pie(p, 0.75f).lit(100) > TimerFaces.pie(p, 0.25f).lit(100))
        assertTrue(TimerFaces.pie(p, 0.5f).lit(254) > 5)
    }

    @Test fun dotsGoOutOneByOneWithASparkle() {
        val count = { f: Frame -> f.lit(200) / 5 }
        assertEquals(25, count(TimerFaces.dots(p, 1f, blink = false)))
        assertEquals(13, count(TimerFaces.dots(p, 0.5f, blink = false)))
        assertEquals(12, count(TimerFaces.dots(p, 0.5f, blink = true))) // the next to go blinks
        assertEquals(0, count(TimerFaces.dots(p, 0f, blink = false)))
        val plain = TimerFaces.dots(p, 0.5f, blink = false)
        val pop = TimerFaces.dots(p, 0.5f, blink = false, sparkle = 0.3f)
        assertTrue(pop.lit() > plain.lit())
    }

    @Test fun hourglassMovesSandDownInAStream() {
        val c = (p.size - 1) / 2
        fun top(f: Frame) = p.ledCells.count { it / p.size < c && f.pixels[it] == 220 }
        fun bottom(f: Frame) = p.ledCells.count { it / p.size > c && f.pixels[it] == 220 }
        val start = TimerFaces.hourglass(p, 1f, 0f)
        val end = TimerFaces.hourglass(p, 0f, 0f)
        assertTrue(top(start) > 20 && bottom(start) == 0)
        assertTrue(top(end) == 0 && bottom(end) > 20)
        val mid = TimerFaces.hourglass(p, 0.6f, 0f)
        val stream = (c + 1 until c + 6).count { y -> mid.pixels[y * p.size + c] == 255 }
        assertTrue(stream >= 2)
        assertNotEquals(mid.pixels.toList(), TimerFaces.hourglass(p, 0.6f, 0.3f).pixels.toList())
    }

    @Test fun orbitIsABrightCometWithATail() {
        val a = TimerFaces.orbit(p, 0f)
        val b = TimerFaces.orbit(p, 0.5f)
        assertEquals(255, a.pixels[p.ring[0]])
        assertEquals(255, b.pixels[p.ring[p.ring.size / 2]])
        val tail = (1..5).map { a.pixels[p.ring[Math.floorMod(-it, p.ring.size)]] }
        assertTrue(tail.zipWithNext().all { (x, y) -> x >= y } && tail.last() > 20)
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

    @Test fun lastTenSecondsTickOffSegmentsAndScaleIn() {
        val a = TimerFaces.second(p, TimerStyle.PIE, 9_000, 60_000)
        assertEquals(4, a.frames.size)
        // a flash on the second, then the ring settles
        val ringBright = { f: Frame -> p.ring.count { f.pixels[it] > 250 } }
        assertTrue(ringBright(a.frames[0].frame) > ringBright(a.frames[1].frame))
        // the number scales in: small first, then full size
        val middle = { f: Frame -> p.ledCells.count { !p.ring.contains(it) && f.pixels[it] > 200 } }
        assertTrue(middle(a.frames[1].frame) > middle(a.frames[0].frame))
        // nine of ten segments are lit
        val lit = a.frames[3].frame.let { f -> p.ring.count { f.pixels[it] >= 200 } }
        assertTrue(lit in (p.ring.size * 65 / 100)..(p.ring.size * 9 / 10))
        // before the last 10 s it is the style, four steps a second; with the switch off too
        val b = TimerFaces.second(p, TimerStyle.ORBIT, 30_000, 60_000)
        assertEquals(1000, b.totalMs)
        assertNotEquals(b.frames[0].frame.pixels.toList(), b.frames[2].frame.pixels.toList())
        assertEquals(TimerFaces.countdown(p, TimerStyle.PIE, 9_000, 60_000).pixels.toList(), TimerFaces.second(p, TimerStyle.PIE, 9_000, 60_000, lastTen = false).frames[0].frame.pixels.toList())
    }

    @Test fun finishedBurstsWithoutAWhiteDisc() {
        val a = TimerFaces.finished(p)
        assertTrue(a.frames.size > 20)
        assertTrue(a.frames.none { f -> f.frame.lit() > p.ledCount / 2 })
        assertTrue(a.frames.count { f -> f.frame.lit() > 30 } > 15)
        assertTrue(a.frames.last().frame.lit() == 0)
    }

    @Test fun pausedBlinksBarsOverTheFace() {
        val face = TimerFaces.countdown(p, TimerStyle.RING_DRAIN, 90_000, 120_000)
        val a = TimerFaces.paused(p, face)
        assertEquals(2, a.frames.size)
        assertTrue(a.frames[0].frame.has(TimerFaces.pauseBars(p)))
        assertEquals(face.pixels.toList(), a.frames[1].frame.pixels.toList())
        assertNotEquals(TimerFaces.lastSeconds(p, 5_000).frames.size, a.frames.size)
    }

    @Test fun brightnessDims() {
        val full = TimerFaces.second(p, TimerStyle.RING_DRAIN, 120_000, 120_000).frames[0].frame
        val dim = TimerFaces.second(p, TimerStyle.RING_DRAIN, 120_000, 120_000, brightness = 0.5f).frames[0].frame
        assertEquals(255, full.pixels[p.ring.last()])
        assertEquals(127, dim.pixels[p.ring.last()])
    }

    @Test fun previewsDifferPerStyle() {
        val posters = TimerStyle.entries.map { TimerFaces.preview(p, it).frames[0].frame.pixels.toList() }
        assertEquals(posters.size, posters.toSet().size)
        val watch = StopwatchStyle.entries.map { TimerFaces.preview(p, it).frames[5].frame.pixels.toList() }
        assertEquals(watch.size, watch.toSet().size)
    }
}

class LiveRingTest {
    private val p = DeviceProfile.Phone3
    private fun Frame.lit() = p.ledCells.count { pixels[it] > 0 }

    @Test fun iconsFitInsideTheRingAtFullSize() {
        val ids = ProgressApps.ALL.map { it.icon } + Stage.entries.map { it.icon } + listOf("g-finish-flag", "g-auto", "download", "g-alarm", "g-calendar")
        for (id in ids.distinct()) {
            val icon = LiveRing.icon(p, id)
            assertTrue(id, icon != null && icon.lit() > 10)
            assertTrue(id, LiveRing.clearOfRing(p, icon!!))
            // big enough to read: at least 11 dots across
            val xs = p.ledCells.filter { icon.pixels[it] > 0 }.map { it % p.size }
            assertTrue(id, xs.max() - xs.min() + 1 >= 11)
        }
        // the drawings made for the ring are used at 1:1, never squashed
        for (id in LiveRing.drawn) {
            val f = LiveRing.icon(p, id)!!
            assertEquals(id, f.pixels.toList(), LiveRing.fit(p, f).pixels.toList())
        }
        // fitting twice changes nothing
        val car = LiveRing.icon(p, "g-car")!!
        assertEquals(car.pixels.toList(), LiveRing.fit(p, car).pixels.toList())
    }

    @Test fun percentIsFullBrightness() {
        val f = LiveRing.frame(p, 0.48f, Centre.Percent(48))
        val middle = p.ledCells.filter { it !in p.ring && f.pixels[it] > 0 }
        assertTrue(middle.isNotEmpty() && middle.all { f.pixels[it] == 255 })
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

    @Test fun theKeypadFillsFromTheRight() {
        assertEquals(0L, Timers.keypad(""))
        assertEquals(5_000L, Timers.keypad("5"))
        assertEquals(90_000L, Timers.keypad("130"))
        assertEquals(90_000L, Timers.keypad("90"))
        assertEquals(600_000L, Timers.keypad("1000"))
        assertEquals(3_600_000L + 5 * 60_000L, Timers.keypad("10500"))
        assertEquals(Timers.MAX_MS, Timers.keypad("999999"))
    }

    @Test fun thePieStopwatchKeepsItsDigitsClear() {
        val p = DeviceProfile.Phone3
        for (ms in listOf(25_000L, 59_000L, 75_000L)) {
            val f = TimerFaces.stopwatch(p, StopwatchStyle.PIE, ms)
            val t = TimerFaces.small(p, TimerFaces.watchReadout(ms / 1000))
            val n = p.size
            for (cell in p.ledCells) {
                if (t.pixels[cell] > 0) assertEquals(255, f.pixels[cell])
                else {
                    // no lit neighbour of a digit dot that isn't itself a digit dot
                    val x = cell % n
                    val y = cell / n
                    val nextToDigit = (-1..1).any { dy -> (-1..1).any { dx -> x + dx in 0 until n && y + dy in 0 until n && t.pixels[(y + dy) * n + x + dx] > 0 } }
                    if (nextToDigit) assertEquals("$ms at $x,$y", 0, f.pixels[cell])
                }
            }
        }
    }
}
