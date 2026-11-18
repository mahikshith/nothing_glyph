package com.dotforge.toys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressDecoderTest {
    private val d = PressDecoder(longPressMs = 500, doublePressMs = 350, longDedupeMs = 1_000)

    private fun tap(at: Long, hold: Long = 100): Press? {
        d.down(at)
        return d.up(at + hold)
    }

    @Test fun shortAndLongFromButtonEvents() {
        assertEquals(Press.SHORT, tap(0))
        assertEquals(Press.LONG, tap(5_000, hold = 600))
    }

    @Test fun doublePressFollowsTheFirstShortPress() {
        assertEquals(Press.SHORT, tap(0))
        assertEquals(Press.DOUBLE, tap(300))
        assertEquals(Press.SHORT, tap(600)) // a third tap starts over
    }

    @Test fun slowSecondTapIsShort() {
        assertEquals(Press.SHORT, tap(0))
        assertEquals(Press.SHORT, tap(1_000))
    }

    @Test fun changeEventIsLongPressAndIsNotRepeatedOnRelease() {
        d.down(0)
        assertEquals(Press.LONG, d.change(400))
        assertNull(d.up(3_000))
    }

    @Test fun changeRightAfterALongReleaseIsDeduplicated() {
        assertEquals(Press.LONG, tap(0, hold = 700))
        assertNull(d.change(800))
        assertEquals(Press.LONG, d.change(5_000))
    }

    @Test fun upWithoutDownIsIgnored() = assertNull(d.up(10))
}
