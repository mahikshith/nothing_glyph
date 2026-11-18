package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEffectsTest {
    private val p = DeviceProfile.Phone3

    @Test fun theRingFillsBeforeAnEventAndPulsesInTheLastMinute() {
        val lead = 10 * 60_000L
        assertNull(AlertEffects.approach(11 * 60_000L, lead)) // not yet
        assertNull(AlertEffects.approach(0, lead)) // started
        val half = AlertEffects.approach(5 * 60_000L, lead)!!
        assertEquals(0.5f, half.fraction, 0.001f)
        assertFalse(half.pulse)
        assertTrue(AlertEffects.approach(30_000, lead)!!.pulse)
        assertEquals(1, AlertEffects.approaching(p, half).frames.size)
        assertTrue(AlertEffects.approaching(p, AlertEffects.approach(30_000, lead)!!).frames.size > 1)
    }

    @Test fun reminderBellSwingsThenRests() {
        val a = AlertEffects.reminder(p)
        assertEquals(AlertEffects.SWING_FRAMES + 1, a.frames.size)
        assertTrue(a.frames.all { f -> f.frame.pixels.any { it > 0 } })
    }

    @Test fun nameScrollsAfterItsGlyph() {
        val glyph = Animation.single(Frame.blank(p).fill(77), 1_000)
        val plain = AlertEffects.name(p, "Mom")
        val withGlyph = AlertEffects.name(p, "Mom", prefix = glyph)
        assertEquals(plain.frames.size + 1, withGlyph.frames.size)
        assertEquals(77, withGlyph.frames.first().frame.pixels[p.ledCells[0]])
        val dim = AlertEffects.name(p, "Mom", brightness = 50)
        assertTrue(dim.frames.maxOf { it.frame.pixels.max() } <= 128)
        // long names are cut
        assertTrue(AlertEffects.name(p, "x".repeat(100)).frames.size <= AlertEffects.name(p, "x".repeat(AlertEffects.MAX_NAME)).frames.size)
    }
}
