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
}
