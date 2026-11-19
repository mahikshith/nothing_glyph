package com.dotforge.system

import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEventsTest {
    private val p = DeviceProfile.Phone3

    @Test fun eventsFireOnceOnTheirEdge() {
        assertNull(BatteryEvents.between(null, 50, true)) // first reading after start
        assertEquals(BatteryEvent.PLUGGED, BatteryEvents.between(50 to false, 50, true))
        assertNull(BatteryEvents.between(50 to true, 51, true))
        assertEquals(BatteryEvent.FULL, BatteryEvents.between(99 to true, 100, true))
        assertNull(BatteryEvents.between(100 to true, 100, true))
        assertEquals(BatteryEvent.LOW, BatteryEvents.between(16 to false, 15, false))
        assertNull(BatteryEvents.between(15 to false, 14, false))
        assertNull(BatteryEvents.between(16 to true, 15, true)) // charging: no low flash
        assertNull(BatteryEvents.between(100 to true, 100, false)) // unplugged when full
    }

    @Test fun shakesAreBigJumps() {
        assertFalse(BatteryEvents.isShake(null, floatArrayOf(0f, 0f, 9.8f)))
        assertFalse(BatteryEvents.isShake(floatArrayOf(0f, 0f, 9.8f), floatArrayOf(0.5f, 0.3f, 9.6f)))
        assertTrue(BatteryEvents.isShake(floatArrayOf(0f, 0f, 9.8f), floatArrayOf(9f, -6f, 2f)))
    }

    @Test fun meterAlarmAndVolumeDraw() {
        val meter = BatteryEvents.meter(p, 50).frames.single().frame
        assertEquals(p.ring.size / 2, p.ring.count { meter.pixels[it] == 255 })
        assertEquals(3 * 2 + 1, BatteryEvents.alarm(p, BatteryEvent.FULL, 100).frames.size)
        assertEquals(4 * 2 + 1, BatteryEvents.alarm(p, BatteryEvent.LOW, 15).frames.size)
        val v = BatteryEvents.volume(p, 5, 15)
        assertEquals(Math.round(p.ring.size / 3f), p.ring.count { v.pixels[it] == 255 })
        assertEquals(0, p.ring.count { BatteryEvents.volume(p, 3, 0).pixels[it] == 255 })
    }

    @Test fun twoShakesInASecondToggleTheTorch() {
        var t = BatteryEvents.shakeTimes(emptyList(), 1_000)
        assertFalse(BatteryEvents.isDoubleShake(t))
        t = BatteryEvents.shakeTimes(t, 1_600)
        assertTrue(BatteryEvents.isDoubleShake(t))
        assertFalse(BatteryEvents.isDoubleShake(BatteryEvents.shakeTimes(listOf(1_000), 2_500))) // too slow
    }

    @Test fun fastChargersAreFifteenWattsOrMore() {
        assertTrue(BatteryEvents.isFast(3_000_000, 9_000_000)) // 27 W
        assertTrue(BatteryEvents.isFast(3_000_000, 5_000_000)) // 15 W
        assertFalse(BatteryEvents.isFast(2_000_000, 5_000_000)) // 10 W
        assertTrue(BatteryEvents.isFast(3_000_000, 0)) // no voltage: 5 V
        assertFalse(BatteryEvents.isFast(0, 9_000_000))
        assertFalse(BatteryEvents.isFast(500_000, 0))
    }

    @Test fun fillGrowsToTheLevelAndLevelShowsTheNumber() {
        val ring = p.ring.toSet()
        fun litRing(f: com.dotforge.device.Frame) = p.ring.count { f.pixels[it] == 255 }
        val fill = BatteryEvents.charging(p, ChargingStyle.FILL, 50, fast = false)
        assertEquals(BatteryEvents.FILL_STEPS, fill.frames.size)
        val counts = fill.frames.map { litRing(it.frame) }
        assertEquals(counts.sorted(), counts)
        assertEquals((p.ring.size * 0.5f).toInt(), counts.last(), 1f)
        // Fill draws only the ring
        assertTrue(fill.frames.last().frame.let { f -> p.ledCells.none { it !in ring && f.pixels[it] > 0 } })
        assertEquals(BatteryEvents.HOLD_MS, fill.frames.last().durationMs)
        val level = BatteryEvents.charging(p, ChargingStyle.LEVEL, 50, fast = false)
        assertEquals(1, level.frames.size)
        assertTrue(level.frames[0].frame.let { f -> p.ledCells.any { it !in ring && f.pixels[it] > 0 } })
        // a fast charger adds two pulses: dim, bright, dim, bright
        val fast = BatteryEvents.charging(p, ChargingStyle.LEVEL, 50, fast = true)
        assertEquals(5, fast.frames.size)
        assertTrue(fast.frames[1].frame.pixels.max() < fast.frames[2].frame.pixels.max())
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Float) = assertTrue("$expected ~ $actual", kotlin.math.abs(expected - actual) <= tolerance)

    @Test fun theVolumeRingSlidesToTheNewLevel() {
        val up = BatteryEvents.volumeSlide(p, 2, 10, 15)
        val lit = up.frames.map { f -> p.ring.count { f.frame.pixels[it] == 255 } }
        assertEquals(lit.sorted(), lit)
        assertEquals(p.ring.count { BatteryEvents.volume(p, 10, 15).pixels[it] == 255 }, lit.last())
        assertEquals(com.dotforge.device.Animation.MAX_FRAME_MS, up.frames.last().durationMs)
        // no change: just the level
        assertEquals(1, BatteryEvents.volumeSlide(p, 7, 7, 15).frames.size)
        val down = BatteryEvents.volumeSlide(p, 10, 2, 15).frames.map { f -> p.ring.count { f.frame.pixels[it] == 255 } }
        assertEquals(down.sortedDescending(), down)
    }

    @Test fun myDesignPlaysThenHoldsItsFullestFrame() {
        val p = com.dotforge.device.DeviceProfile.Phone3
        val dim = com.dotforge.device.Frame.blank(p).fill(40)
        val full = com.dotforge.device.Frame.blank(p).fill(255)
        val mine = com.dotforge.device.Animation(p, listOf(com.dotforge.device.AnimFrame(dim, 100), com.dotforge.device.AnimFrame(full, 100)))
        val a = BatteryEvents.charging(p, ChargingStyle.MINE, 50, fast = false, mine = mine)
        assertEquals(3, a.frames.size)
        assertEquals(BatteryEvents.HOLD_MS, a.frames.last().durationMs)
        assertEquals(full.pixels.toList(), a.frames.last().frame.pixels.toList())
        // without a design: the ring and the number
        assertEquals(BatteryEvents.charging(p, ChargingStyle.LEVEL, 50, false).frames.map { it.frame.pixels.toList() }, BatteryEvents.charging(p, ChargingStyle.MINE, 50, false).frames.map { it.frame.pixels.toList() })
    }
}
