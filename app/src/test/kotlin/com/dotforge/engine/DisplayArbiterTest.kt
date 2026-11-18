package com.dotforge.engine

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayArbiterTest {
    private val p = DeviceProfile.Phone3

    private class FakeDevice : MatrixDevice {
        override val isHardware = false
        val shown = mutableListOf<Frame>()
        var clears = 0
        override fun show(frame: Frame) { shown += frame }
        override fun clear() { clears++ }
    }

    private class FakeGate(var open: Boolean = true) : AmbientGate {
        override fun ambientAllowed() = open
    }

    /** Every LED at [level]: the level identifies which request is on screen. */
    private fun request(id: String, priority: Int, level: Int, loop: Boolean = true, timeoutMs: Long? = null, frameMs: Int = 1000) =
        DisplayRequest(id, priority, Animation.single(Frame.blank(p).fill(level), frameMs), loop, timeoutMs)

    private val FakeDevice.level: Int? get() = shown.lastOrNull()?.pixels?.get(p.ledCells[0])

    private fun TestScope.arbiter(device: MatrixDevice, gate: AmbientGate = FakeGate()) =
        DisplayArbiter(backgroundScope, device, gate)

    @Test fun higherPriorityWinsAndFallsBackWhenRemoved() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("progress", Priority.PROGRESS, 30))
        a.post(request("torch", Priority.TORCH, 70))
        a.post(request("note", Priority.NOTIFICATION, 50))
        runCurrent()
        assertEquals(70, device.level)
        assertEquals(listOf("torch", "note", "progress"), a.active.value.map { it.id })

        a.remove("torch")
        runCurrent()
        assertEquals(50, device.level)
        a.remove("note")
        runCurrent()
        assertEquals(30, device.level)
    }

    @Test fun newestWinsOnEqualPriority() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("a", Priority.NOTIFICATION, 10))
        a.post(request("b", Priority.NOTIFICATION, 20))
        runCurrent()
        assertEquals(20, device.level)
        a.post(request("a", Priority.NOTIFICATION, 11)) // re-posting makes it the newest
        runCurrent()
        assertEquals(11, device.level)
    }

    @Test fun oneShotPlaysOnceThenFallsBack() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("progress", Priority.PROGRESS, 30))
        val twoFrames = Animation(p, listOf(AnimFrame(Frame.blank(p).fill(1), 100), AnimFrame(Frame.blank(p).fill(2), 100)))
        a.post(DisplayRequest("note", Priority.NOTIFICATION, twoFrames))
        runCurrent()
        assertEquals(1, device.level)
        advanceTimeBy(150)
        assertEquals(2, device.level)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(30, device.level)
        assertFalse(a.has("note"))
    }

    @Test fun timeoutRemovesRequestAndClearsDevice() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("studio", Priority.STUDIO_PREVIEW, 60, timeoutMs = 5_000))
        runCurrent()
        assertEquals(60, device.level)
        advanceTimeBy(5_001)
        runCurrent()
        assertNull(a.frame.value)
        assertTrue(a.active.value.isEmpty())
        assertTrue(device.clears > 0)
    }

    @Test fun masterSwitchHidesEverythingButKeepsRequests() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("torch", Priority.TORCH, 70))
        runCurrent()
        a.setEnabled(false)
        runCurrent()
        assertNull(a.frame.value)
        assertTrue(a.has("torch"))
        val before = device.shown.size
        a.setEnabled(true)
        runCurrent()
        assertEquals(before + 1, device.shown.size)
        assertEquals(70, device.level)
    }

    @Test fun closedGateHoldsBackAmbientRequestsOnly() = runTest {
        val device = FakeDevice()
        val gate = FakeGate(open = false)
        val a = arbiter(device, gate)
        a.post(request("note", Priority.NOTIFICATION, 50))
        a.post(request("progress", Priority.PROGRESS, 30))
        runCurrent()
        assertTrue(device.shown.isEmpty())

        a.post(request("torch", Priority.TORCH, 70))
        runCurrent()
        assertEquals(70, device.level)

        gate.open = true
        a.remove("torch")
        runCurrent()
        assertEquals(50, device.level)
    }

    @Test fun refreshShowsAmbientOnceTheGateOpens() = runTest {
        val device = FakeDevice()
        val gate = FakeGate(open = false)
        val a = arbiter(device, gate)
        a.post(request("note", Priority.NOTIFICATION, 50))
        runCurrent()
        assertTrue(device.shown.isEmpty())
        gate.open = true
        a.refresh()
        runCurrent()
        assertEquals(50, device.level)
    }

    @Test fun staticFrameIsResentPeriodically() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("torch", Priority.TORCH, 70, loop = false, frameMs = 10_000))
        runCurrent()
        assertEquals(1, device.shown.size)
        advanceTimeBy(DisplayArbiter.RESEND_MS * 3 + 1)
        assertEquals(4, device.shown.size)
        assertTrue(device.shown.all { it.pixels[p.ledCells[0]] == 70 })
    }

    @Test fun refreshResendsTheCurrentFrameAtOnce() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.post(request("torch", Priority.TORCH, 70))
        runCurrent()
        val before = device.shown.size
        a.refresh()
        runCurrent()
        assertEquals(before + 1, device.shown.size)
    }

    @Test fun heartbeatDropsAmbientWhenGateCloses() = runTest {
        val device = FakeDevice()
        val gate = FakeGate()
        val a = arbiter(device, gate)
        a.post(request("note", Priority.NOTIFICATION, 50, loop = false, frameMs = 60_000))
        runCurrent()
        assertEquals(50, device.level)
        gate.open = false
        advanceTimeBy(DisplayArbiter.RESEND_MS + 1)
        assertNull(a.frame.value)
        assertTrue(device.clears > 0)
        assertTrue(a.has("note")) // kept for when the gate opens again
    }

    @Test fun suspendedArbiterShowsNothingUntilResumed() = runTest {
        val device = FakeDevice()
        val a = arbiter(device)
        a.setSuspended(true)
        a.post(request("torch", Priority.TORCH, 70))
        runCurrent()
        assertTrue(device.shown.isEmpty())
        a.setSuspended(false)
        runCurrent()
        assertEquals(70, device.level)
    }
}
