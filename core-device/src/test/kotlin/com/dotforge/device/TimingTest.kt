package com.dotforge.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTest {
    private val p = DeviceProfile.Phone3
    private val heart = GlyphLibrary.get("heart")!!.still(p)
    private val three = Animation(p, (0 until 3).map { i -> AnimFrame(AnimatePresets.shift(heart, i, 0), 100) })
    private val still = Animation.single(heart, 1000)

    @Test fun defaultsChangeNothing() {
        val plan = Timings.plan(three, Timing())
        assertEquals(3, plan.animation.frames.size)
        three.frames.indices.forEach { assertArrayEquals(three.frames[it].frame.pixels, plan.animation.frames[it].frame.pixels) }
        assertEquals(300, plan.animation.totalMs)
        assertFalse(plan.loop)
        assertNull(plan.showMs)
        assertFalse(plan.untilChecked)
    }

    @Test fun speedScalesEveryFrame() {
        assertEquals(150, Timings.plan(three, Timing(speed = 2f)).animation.totalMs)
        assertEquals(1200, Timings.plan(three, Timing(speed = 0.25f)).animation.totalMs)
        // clamped to 0.25x-4x
        assertEquals(Timings.plan(three, Timing(speed = 4f)).animation.totalMs, Timings.plan(three, Timing(speed = 10f)).animation.totalMs)
    }

    @Test fun repeatTimesBackwardsAndBounce() {
        assertEquals(9, Timings.plan(three, Timing(repeat = Repeat.TIMES, times = 3)).animation.frames.size)
        val back = Timings.plan(three, Timing(direction = Direction.BACKWARD)).animation
        assertArrayEquals(three.frames[2].frame.pixels, back.frames[0].frame.pixels)
        assertEquals(4, Timings.plan(three, Timing(direction = Direction.BOUNCE)).animation.frames.size)
        assertTrue(Timings.plan(three, Timing(repeat = Repeat.FOREVER)).loop)
    }

    @Test fun aStillFades() {
        val fade = Timings.plan(still, Timing(fade = Fade.SHORT)).animation
        assertEquals(7, fade.frames.size)
        assertTrue(fade.frames.first().frame.pixels.max() < 255)
    }

    @Test fun brightnessAndStartDelay() {
        val dim = Timings.plan(still, Timing(brightness = 50)).animation
        assertEquals(127, dim.frames[0].frame.pixels.max())
        val late = Timings.plan(still, Timing(startDelayMs = 400)).animation
        assertEquals(0, late.frames[0].frame.pixels.max())
        assertEquals(400, late.frames[0].durationMs)
    }

    @Test fun showForAndRemindMe() {
        val secs = Timings.plan(three, Timing(showFor = ShowFor.SECONDS, seconds = 10))
        assertTrue(secs.loop)
        assertEquals(10_000L, secs.showMs)
        val held = Timings.plan(three, Timing(showFor = ShowFor.UNTIL_CHECKED, remindMinutes = 5))
        assertTrue(held.untilChecked)
        assertTrue(held.loop)
        assertEquals(300_000L, held.againMs)
        assertNull(Timings.plan(three, Timing(remindMinutes = 5)).againMs) // only for held ones
        assertNull(Timings.plan(three, Timing(showFor = ShowFor.UNTIL_CHECKED)).againMs) // remind off
    }

    @Test fun playbackFollowsTheTimedFrames() {
        val plan = Timings.plan(three, Timing(speed = 2f))
        val pb = Playback(plan.animation.frames.map { it.durationMs }, loop = plan.loop)
        pb.play()
        pb.tick(50)
        assertEquals(1, pb.frame)
        pb.tick(100)
        assertFalse(pb.playing) // once: stops at the end
    }

    @Test fun anOverrideChangesOnlyItsFieldsAndFollowsLaterDesignEdits() {
        val design = Timing(speed = 2f, seconds = 3, showFor = ShowFor.SECONDS)
        val edited = design.copy(brightness = 40)
        val o = TimingOverride.diff(design, edited)
        assertEquals(TimingOverride(brightness = 40), o)
        assertEquals(edited, o.over(design))
        // the design changes later: the place keeps its brightness and follows the new speed
        val later = design.copy(speed = 0.5f)
        assertEquals(0.5f, o.over(later).speed)
        assertEquals(40, o.over(later).brightness)
        assertTrue(TimingOverride.diff(design, design).isEmpty)
    }

    @Test fun anUntilCheckedAnimationRestsAfterThreeLoopsUnlessKeptMoving() {
        val rests = Timings.plan(three, Timing(showFor = ShowFor.UNTIL_CHECKED)).animation
        assertEquals(3 * 3 + 1, rests.frames.size)
        assertEquals(Animation.MAX_FRAME_MS, rests.frames.last().durationMs)
        assertArrayEquals(three.frames[0].frame.pixels, rests.frames.last().frame.pixels)
        assertEquals(3, Timings.plan(three, Timing(showFor = ShowFor.UNTIL_CHECKED, rest = false)).animation.frames.size)
        assertEquals(1, Timings.plan(still, Timing(showFor = ShowFor.UNTIL_CHECKED)).animation.frames.size) // a still has nothing to rest
    }
}
