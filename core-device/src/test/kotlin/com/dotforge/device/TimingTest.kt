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
        val back = Timings.plan(three, Timing(backwards = true)).animation
        assertArrayEquals(three.frames[2].frame.pixels, back.frames[0].frame.pixels)
        assertEquals(4, Timings.plan(three, Timing(bounce = true)).animation.frames.size)
        assertTrue(Timings.plan(three, Timing(repeat = Repeat.FOREVER)).loop)
    }

    @Test fun aStillBlinksAndFades() {
        val blink = Timings.plan(still, Timing(blink = Blink.FAST)).animation
        assertEquals(2, blink.frames.size)
        assertEquals(0, blink.frames[1].frame.pixels.count { it > 0 })
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

    @Test fun showForAndShowAgain() {
        val secs = Timings.plan(three, Timing(showFor = ShowFor.SECONDS, seconds = 10))
        assertTrue(secs.loop)
        assertEquals(10_000L, secs.showMs)
        val held = Timings.plan(three, Timing(showFor = ShowFor.UNTIL_CHECKED, showAgainMinutes = 5))
        assertTrue(held.untilChecked)
        assertTrue(held.loop)
        assertEquals(300_000L, held.againMs)
        assertNull(Timings.plan(three, Timing(showAgainMinutes = 5)).againMs) // only for held ones
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
}
