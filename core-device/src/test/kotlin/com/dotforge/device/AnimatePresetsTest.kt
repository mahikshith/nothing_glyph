package com.dotforge.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatePresetsTest {
    private val p = DeviceProfile.Phone3
    private val heart = GlyphLibrary.get("heart")!!.still(p)
    private fun lit(f: Frame) = f.pixels.count { it > 0 }

    private fun make(preset: AnimatePreset) = preset.apply(heart)

    @Test fun everyPresetStaysInsideTheMaskWithValidTiming() {
        for (preset in AnimatePreset.entries) {
            val a = make(preset)
            assertTrue(preset.name, a.frames.size >= 2)
            for (f in a.frames) {
                assertTrue(preset.name, f.frame.pixels.indices.all { f.frame.pixels[it] == 0 || p.mask[it] })
                assertTrue(f.durationMs in Animation.MIN_FRAME_MS..Animation.MAX_FRAME_MS)
            }
        }
    }

    @Test fun blinkIsThePictureThenDark() {
        val a = make(AnimatePreset.BLINK)
        assertEquals(2, a.frames.size)
        assertArrayEquals(heart.pixels, a.frames.first().frame.pixels)
        assertEquals(0, lit(a.frames.last().frame))
    }

    @Test fun pulseStartsAndEndsDimAndPeaksAtFull() {
        val a = make(AnimatePreset.PULSE)
        assertEquals(15, a.frames.size)
        assertTrue(a.frames.first().frame.pixels.max() < 64)
        assertTrue(a.frames.last().frame.pixels.max() < 64)
        assertTrue(a.frames.any { it.frame.pixels.contentEquals(heart.pixels) })
    }

    @Test fun rotateStartsAsThePictureAndTurns() {
        val a = make(AnimatePreset.ROTATE)
        assertEquals(12, a.frames.size)
        assertArrayEquals(heart.pixels, a.frames.first().frame.pixels)
        assertTrue(!a.frames[3].frame.pixels.contentEquals(heart.pixels))
    }

    @Test fun slidesStartOffTheEdgeAndEndOnThePicture() {
        for (preset in listOf(AnimatePreset.SLIDE_LEFT, AnimatePreset.SLIDE_RIGHT, AnimatePreset.SLIDE_TOP, AnimatePreset.SLIDE_BOTTOM)) {
            val a = make(preset)
            assertEquals(preset.name, 13, a.frames.size)
            assertEquals(preset.name, 0, lit(a.frames.first().frame))
            assertArrayEquals(preset.name, heart.pixels, a.frames.last().frame.pixels)
        }
        // from the left: halfway in, only the left half has anything
        val mid = make(AnimatePreset.SLIDE_LEFT).frames[6].frame.pixels
        assertTrue(mid.indices.filter { mid[it] > 0 }.all { it % p.size < 13 })
    }

    @Test fun growEndsOnThePictureAndStartsSmaller() {
        val a = make(AnimatePreset.GROW)
        assertEquals(10, a.frames.size)
        assertTrue(lit(a.frames.first().frame) < lit(heart) / 4)
        assertArrayEquals(heart.pixels, a.frames.last().frame.pixels)
    }

    @Test fun wipeRevealsLeftToRight() {
        val a = make(AnimatePreset.WIPE)
        assertEquals(25, a.frames.size)
        assertArrayEquals(heart.pixels, a.frames.last().frame.pixels)
        val counts = a.frames.map { lit(it.frame) }
        assertEquals(counts.sorted(), counts)
    }

    @Test fun marqueePassesOnceFromRightToLeft() {
        val a = make(AnimatePreset.MARQUEE)
        assertEquals(51, a.frames.size)
        assertEquals(0, lit(a.frames.first().frame))
        assertEquals(0, lit(a.frames.last().frame))
        assertArrayEquals(heart.pixels, a.frames[25].frame.pixels)
    }

    @Test fun sparkleKeepsTheShapeAndIsTheSameEveryTime() {
        val a = make(AnimatePreset.SPARKLE)
        val b = make(AnimatePreset.SPARKLE)
        assertEquals(10, a.frames.size)
        a.frames.indices.forEach { assertArrayEquals(a.frames[it].frame.pixels, b.frames[it].frame.pixels) }
        // most of the picture stays lit in every frame
        for (f in a.frames) assertTrue(heart.pixels.indices.count { heart.pixels[it] > 0 && f.frame.pixels[it] > 0 } > lit(heart) / 2)
    }

    @Test fun presetsWorkOnABlankFrame() {
        for (preset in AnimatePreset.entries) assertTrue(preset.apply(Frame.blank(p)).frames.isNotEmpty())
    }
}
