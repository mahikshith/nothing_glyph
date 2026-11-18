package com.dotforge.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private val Frame.litCount: Int get() = pixels.count { it > 0 }

class EffectsTest {
    private val p = DeviceProfile.Phone3

    @Test fun fillLights489() = assertEquals(489, Frame.blank(p).fill(255).litCount)

    @Test fun checkerboardIsHalfAndMasked() {
        val c = Effects.checkerboard(p).pixels
        assertTrue(c.indices.all { c[it] == 0 || p.mask[it] })
        assertEquals(241, c.count { it > 0 })
    }

    @Test fun cometOnlyOnRingWithHeadAtFull() {
        val ring = p.ring.toSet()
        for (s in listOf(0, 5, 67, 200, -3)) {
            val f = Effects.comet(p, s).pixels
            assertTrue(f.indices.all { f[it] == 0 || it in ring })
            assertEquals(255, f[p.ring[Math.floorMod(s, 68)]])
        }
    }

    @Test fun ringChaseHasOneFramePerRingLed() = assertEquals(68, Effects.ringChase(p).frames.size)

    @Test fun chaseLightsThreeHeadsOnTheRingOnly() {
        val f = Effects.chase(p).frames.first().frame.pixels
        assertEquals(3, p.ring.count { f[it] == 255 })
        assertEquals(0, p.ledCells.filterNot { it in p.ring }.count { f[it] > 0 })
    }

    @Test fun cometLapsGoesRoundTwice() = assertEquals(2 * 68, Effects.cometLaps(p).frames.size)

    @Test fun ringFillsClockwiseFromTop() {
        val f = Effects.ring(p, 0.25f, on = 255, off = 0).pixels
        assertEquals(17, f.count { it == 255 })
        assertEquals(255, f[p.ring.first()])
        assertEquals(0, f[p.ring.last()])
    }

    @Test fun clockAndDateFitInsideTheRoundMask() {
        // same drawing on a grid where every cell has an LED: nothing may be clipped by the circle
        val square = DeviceProfile("square", 25, radiusSquared = 25 * 25)
        for (h in 0..31) for (m in 0..59) {
            assertEquals("$h:$m", Effects.clock(square, h, m).litCount, Effects.clock(p, h, m).litCount)
        }
    }

    @Test fun progressShowsDigitsWhenTheyFit() {
        val with = Effects.progress(p, 0.42f).frames.single().frame
        val ringOnly = Effects.ring(p, 0.42f)
        assertTrue(with.litCount > ringOnly.litCount)
        val full = Effects.progress(p, 1f).frames.single().frame
        assertArrayEquals(Effects.ring(p, 1f).pixels, full.pixels) // "100" is too wide, ring only
    }

    @Test fun dicePipsAreDistinctAndMasked() {
        val counts = (1..6).map { Effects.dice(p, it).litCount }
        assertEquals(counts.sorted(), counts)
        assertEquals(6, counts.toSet().size)
        assertThrows(IllegalArgumentException::class.java) { Effects.dice(p, 7) }
    }

    @Test fun letterIsCentredAndMasked() {
        val f = Effects.letter(p, 'a')
        assertTrue(f.litCount > 0)
        assertEquals(Effects.letter(p, 'A').pixels.toList(), f.pixels.toList())
        assertTrue(f.pixels.indices.all { f.pixels[it] == 0 || p.mask[it] })
    }

    @Test fun marqueeScrollsFullyThrough() {
        val a = Effects.marquee(p, "Hi")
        assertEquals(0, a.frames.first().frame.litCount)
        assertEquals(0, a.frames.last().frame.litCount)
        assertTrue(a.frames.any { it.frame.litCount > 0 })
    }

    @Test fun sparkleIsDeterministicWithSeed() {
        val a = Effects.sparkle(p, random = Random(7))
        val b = Effects.sparkle(p, random = Random(7))
        assertEquals(a.frames.map { it.frame.pixels.toList() }, b.frames.map { it.frame.pixels.toList() })
    }
}

class FrameAndAnimationTest {
    private val p = DeviceProfile.Phone3

    @Test fun setIgnoresCellsWithoutLed() {
        val f = Frame.blank(p)
        f[0, 0] = 255
        f[12, 0] = 300
        assertEquals(0, f.pixels[p.index(0, 0)])
        assertEquals(255, f.pixels[p.index(12, 0)])
        assertEquals(1, f.litCount)
    }

    @Test fun invertKeepsMask() {
        val f = Frame.blank(p).inverted()
        assertEquals(489, f.litCount)
        assertEquals(0, f.pixels[p.index(0, 0)])
    }

    @Test fun museumJsonRoundTripKeepsDurations() {
        val anim = Animation(p, listOf(AnimFrame(Effects.dice(p, 3), 80), AnimFrame(Frame.blank(p).fill(9), 500)))
        val back = Animation.fromMuseumJson(p, anim.toMuseumJson())
        assertEquals(listOf(80, 500), back.frames.map { it.durationMs })
        assertArrayEquals(anim.frames[0].frame.pixels, back.frames[0].frame.pixels)
    }

    @Test fun importClampsDurationsAndRejectsEmpty() {
        val vals = (0 until 489).joinToString(",") { "300" }
        val json = """{"version":1,"frames":[{"duration":1,"values":[$vals]}]}"""
        val a = Animation.fromMuseumJson(p, json)
        assertEquals(Animation.MIN_FRAME_MS, a.frames.single().durationMs)
        assertEquals(255, a.frames.single().frame.pixels[p.index(12, 0)])
        assertThrows(IllegalArgumentException::class.java) { Animation.fromMuseumJson(p, """{"version":1,"frames":[]}""") }
    }

    @Test fun scaledTextFitsWidth() {
        assertEquals(22, DotText.width("AB", 2))
        assertEquals(10, DotText.height(2))
    }
}
