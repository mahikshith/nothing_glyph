package com.dotforge.engine

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertPlayerTest {
    private val p = DeviceProfile.Phone3

    private class FakeDevice : MatrixDevice {
        override val isHardware = false
        var last: Frame? = null
        override fun show(frame: Frame) { last = frame }
        override fun clear() { last = null }
    }

    private val FakeDevice.level: Int? get() = last?.pixels?.get(p.ledCells[0])

    private fun alert(key: String, level: Int, until: Until = Until.TIMEOUT, call: Boolean = false) =
        AlertQueue.Alert(key, key, call, until, AlertGlyph(Animation.single(Frame.blank(p).fill(level), 1_000), key))

    @Test fun secondAlertWaitsForTheCycleThenTimeoutClears() = runTest {
        val device = FakeDevice()
        val arbiter = DisplayArbiter(backgroundScope, device)
        var now = 0L
        val player = AlertPlayer(backgroundScope, arbiter, AlertQueue(now = { now }, showMs = 2_000))
        player.offer(alert("a", 10))
        runCurrent()
        assertEquals(10, device.level)

        advanceTimeBy(300)
        now = 300
        player.offer(alert("b", 20))
        runCurrent()
        assertEquals(10, device.level) // not cut mid-animation

        now = 1_000
        advanceTimeBy(701)
        assertEquals(10, device.level) // never cut in its first 2 s

        now = 2_000
        advanceTimeBy(1_000)
        assertEquals(20, device.level)

        now = 4_100
        advanceTimeBy(2_100)
        runCurrent()
        assertNull(device.level)
    }

    @Test fun unlockClearsAHeldAlertWhenItCountsAsChecking() = runTest {
        val device = FakeDevice()
        val arbiter = DisplayArbiter(backgroundScope, device)
        val player = AlertPlayer(backgroundScope, arbiter, AlertQueue(now = { 0L }))
        player.offer(alert("msg", 30, until = Until.READ))
        advanceTimeBy(5_000)
        assertEquals(30, device.level)
        player.unlocked(counts = true)
        runCurrent()
        assertNull(device.level)
    }
}
