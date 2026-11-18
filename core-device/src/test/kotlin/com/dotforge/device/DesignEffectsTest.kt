package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignEffectsTest {
    private val p = DeviceProfile.Phone3
    private val c = (p.size - 1) / 2

    /** A short bar to the right of the centre. */
    private fun bar() = Frame.blank(p).also { f -> for (x in c + 1..c + 6) f[x, c] = 200 }
    private fun lit(f: Frame) = p.ledCells.filter { f.pixels[it] > 0 }.map { it % p.size to it / p.size }.toSet()

    @Test fun rotateTurnsClockwiseAndSpinsAPicture() {
        val quarter = DesignEffects.rotated(bar(), 90.0)
        assertEquals((c + 1..c + 6).map { c to it }.toSet(), lit(quarter)) // right turns to down
        assertEquals(lit(bar()), lit(DesignEffects.rotated(bar(), 360.0)))
        val spun = DesignEffect.ROTATE.apply(Animation.single(bar()))
        assertEquals(24, spun.frames.size)
        assertEquals(lit(bar()), lit(spun.frames[0].frame))
    }

    @Test fun brightnessAndContrastStayInRangeAndKeepDarkDots() {
        val f = Frame.blank(p).also { it[c, c] = 240; it[c + 1, c] = 60 }
        val brighter = DesignEffects.brightness(f, 4f / 3)
        assertEquals(255, brighter.pixels[p.index(c, c)])
        assertEquals(80, brighter.pixels[p.index(c + 1, c)])
        val contrast = DesignEffects.contrast(f, 1.6f)
        assertEquals(255, contrast.pixels[p.index(c, c)])
        assertEquals(19, contrast.pixels[p.index(c + 1, c)])
        assertEquals(0, contrast.pixels[p.index(c - 1, c)])
        assertTrue(p.ledCells.all { DesignEffect.INVERT.apply(Animation.single(f)).frames[0].frame.pixels[it] in 0..255 })
    }

    @Test fun timingEffects() {
        val a = Animation(p, listOf(AnimFrame(bar(), 100), AnimFrame(Frame.blank(p), 30)))
        assertEquals(listOf(50, 20), DesignEffect.FASTER.apply(a).frames.map { it.durationMs }) // never under 20 ms
        assertEquals(listOf(200, 60), DesignEffect.SLOWER.apply(a).frames.map { it.durationMs })
        assertEquals(a.frames.size + 2, DesignEffect.FLASH.apply(a).frames.size)
        assertEquals(255, DesignEffect.FLASH.apply(a).frames[0].frame.pixels[p.ledCells[0]])
        val shaken = DesignEffect.SHAKE.apply(a)
        assertEquals(a.frames.last().frame.pixels.toList(), shaken.frames.last().frame.pixels.toList())
        val breath = DesignEffect.BREATHE.apply(Animation.single(bar())).frames
        assertTrue(breath.first().frame.pixels.max() < breath[breath.size / 2].frame.pixels.max())
    }

    @Test fun everyLibraryAnimationIsValid() {
        assertEquals(12, AnimationLibrary.entries.size)
        for (e in AnimationLibrary.entries) {
            val a = e.make(p)
            assertTrue(e.name, a.frames.size > 1)
            assertTrue(e.name, a.frames.all { it.durationMs in Animation.MIN_FRAME_MS..Animation.MAX_FRAME_MS })
            assertTrue(e.name, a.frames.count { f -> f.frame.pixels.any { it > 0 } } > a.frames.size / 2)
            assertTrue(e.name, a.frames.all { f -> p.ledCells.size == p.ledCount && f.frame.pixels.indices.all { p.mask[it] || f.frame.pixels[it] == 0 } })
            // round-trips through the shared JSON format
            assertEquals(a.frames.size, Animation.fromMuseumJson(p, a.toMuseumJson()).frames.size)
        }
    }
}
