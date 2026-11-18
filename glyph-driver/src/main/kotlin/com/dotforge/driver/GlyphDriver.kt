package com.dotforge.driver

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.dotforge.device.DeviceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface DriverState {
    data object Disconnected : DriverState
    data object Connecting : DriverState
    data object Ready : DriverState
    data class Error(val message: String) : DriverState
}

/** Which SDK entry point a frame goes through: app channel (setAppMatrixFrame) or Glyph Toy channel (setMatrixFrame). */
enum class Channel { APP, TOY }

/** The outcome of one SDK call. The SDK gives no confirmation that LEDs lit; this only says the call returned/threw. */
data class CallRecord(val timeMs: Long, val name: String, val ok: Boolean, val message: String? = null)

data class DriverStats(
    val framesSent: Long = 0,
    val framesFailed: Long = 0,
    val lastError: String? = null,
)

/**
 * Single process-wide owner of [GlyphMatrixManager]. Ref-counted [acquire]/[release]; every SDK call runs
 * on one IO thread; frames are coalesced per channel so only the newest pending frame is sent (slow service
 * => dropped older frames); reconnects on failure.
 *
 * Frames are submitted as 0-255 per cell. The raw `int[]` path of the SDK takes 0-4095 per cell (the SDK's
 * own bitmap path multiplies 8-bit values up the same way), so the driver rescales on send. App-channel
 * brightness (0-255) is applied as a global scale on every frame.
 *
 * Adapted in structure from GlyphLink in https://github.com/Swapnil-Kar/glyph-tuner (MIT), see NOTICE.
 */
object GlyphDriver {
    private const val TAG = "GlyphDriver"
    private const val CLOSE_TIMEOUT_MS = 1500L
    private const val MAX_CALL_LOG = 40
    /** Full scale of one matrix cell on the SDK's raw int[] path. */
    private const val HW_MAX = 4095

    /** The only supported device; registered as Glyph.DEVICE_23112. */
    val profile: DeviceProfile = DeviceProfile.Phone3

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var io: ExecutorService = newIo()
    private fun newIo(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "GlyphIO").apply { isDaemon = true } }

    private val lock = Any()
    private var refs = 0
    @Volatile private var manager: GlyphMatrixManager? = null
    private var appContext: Context? = null
    @Volatile private var connectGeneration = 0
    private var reconnectAttempts = 0
    private var reconnectScheduled = false

    @Volatile private var drewToy = false
    @Volatile private var drewApp = false

    private val _state = MutableStateFlow<DriverState>(DriverState.Disconnected)
    val state: StateFlow<DriverState> = _state.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    /** Frames per second actually accepted by the SDK over the last second. */
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _stats = MutableStateFlow(DriverStats())
    val stats: StateFlow<DriverStats> = _stats.asStateFlow()

    private val _calls = MutableStateFlow<List<CallRecord>>(emptyList())
    /** Most recent SDK calls (newest last) with success/failure. Frame sends are only logged on failure; see [stats]. */
    val calls: StateFlow<List<CallRecord>> = _calls.asStateFlow()

    private val _lastFrame = MutableStateFlow(IntArray(profile.cellCount))
    /** The last frame handed to [submit] (after brightness scaling), for UI previews. */
    val lastFrame: StateFlow<IntArray> = _lastFrame.asStateFlow()

    private val _brightness = MutableStateFlow(255)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val sentTotal = AtomicLong(0)
    /** Total frames accepted by the SDK since process start (for stress tests: take a delta). */
    val framesSentTotal: Long get() = sentTotal.get()

    /** Newest unsent frame per channel, already in the hardware range. */
    private val pending = Channel.entries.associateWith { AtomicReference<IntArray?>(null) }
    private fun hasPending() = pending.values.any { it.get() != null }
    private fun clearPending() = pending.values.forEach { it.set(null) }
    private val pumpScheduled = AtomicBoolean(false)
    private val sendTimes = ArrayDeque<Long>()
    private var lastFpsPublish = 0L

    private fun record(name: String, ok: Boolean, message: String? = null) {
        if (!ok) Log.w(TAG, "$name failed: $message") else Log.d(TAG, "$name ok")
        _calls.update { (it + CallRecord(System.currentTimeMillis(), name, ok, message)).takeLast(MAX_CALL_LOG) }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    fun acquire(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            refs++
            if (refs == 1) connect()
        }
    }

    fun release() {
        synchronized(lock) {
            if (refs == 0) return
            refs--
            if (refs > 0) return
            shutdown()
        }
    }

    private fun connect() {
        val ctx = appContext ?: return
        val gen = ++connectGeneration
        _state.value = DriverState.Connecting
        io.execute {
            try {
                val m = GlyphMatrixManager.getInstance(ctx)
                manager = m
                m.init(callback(gen))
                record("init", true)
            } catch (t: Throwable) {
                record("init", false, t.toString())
                fail("init failed: ${t.message}")
            }
        }
    }

    private fun callback(gen: Int) = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            io.execute {
                if (gen != connectGeneration) return@execute
                val m = manager ?: return@execute
                try {
                    val ok = m.register(Glyph.DEVICE_23112)
                    record("register(DEVICE_23112)", ok, if (ok) null else "returned false")
                    if (ok) {
                        reconnectAttempts = 0
                        // a frame left on the panel by an earlier process stays until someone closes it
                        if (closeOnConnect && pending.getValue(Channel.APP).get() == null) {
                            call("closeAppMatrix(on connect)") { m.closeAppMatrix() }
                            drewApp = false
                        }
                        closeOnConnect = false
                        _state.value = DriverState.Ready
                        if (hasPending()) schedulePump()
                    } else fail("register returned false")
                } catch (t: Throwable) {
                    record("register", false, t.toString())
                    fail("register failed: ${t.message}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            record("onServiceDisconnected", false, name?.flattenToShortString())
            if (gen == connectGeneration) fail("service disconnected")
        }
    }

    private fun fail(message: String) {
        _state.value = DriverState.Error(message)
        _stats.update { it.copy(lastError = message) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (refs == 0 || reconnectScheduled) return
            reconnectScheduled = true
            val delay = (500L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(8000L)
            reconnectAttempts++
            main.postDelayed({
                synchronized(lock) {
                    reconnectScheduled = false
                    if (refs == 0) return@postDelayed
                    val old = manager
                    manager = null
                    io.execute {
                        try { old?.unInit() } catch (_: Throwable) {}
                        record("reconnect", true, "attempt $reconnectAttempts")
                    }
                    connect()
                }
            }, delay)
        }
    }

    private fun shutdown() {
        val m = manager
        manager = null
        connectGeneration++
        clearPending()
        _state.value = DriverState.Disconnected
        _fps.value = 0f
        if (m == null) return
        val finished = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val finish = Runnable {
            if (finished.compareAndSet(false, true)) {
                if (!closed.get()) io = newIo() // abandon a thread stuck in the service
                try { m.unInit(); record("unInit", true) } catch (t: Throwable) { record("unInit", false, t.toString()) }
            }
        }
        io.execute {
            // read on the IO thread so earlier queued closeApp()/endToy() calls are taken into account
            if (drewApp) call("closeAppMatrix") { m.closeAppMatrix() }
            if (drewToy) call("turnOff") { m.turnOff() }
            drewApp = false
            drewToy = false
            closed.set(true)
            main.post(finish)
        }
        main.postDelayed(finish, CLOSE_TIMEOUT_MS)
    }

    private inline fun call(name: String, block: () -> Unit): Boolean = try {
        block(); record(name, true); true
    } catch (t: Throwable) {
        record(name, false, t.toString()); false
    }

    // ---- drawing ---------------------------------------------------------------------------

    /** Set global brightness 0-255 (scales every frame). Takes effect on the next frame. */
    fun setBrightness(level: Int) { _brightness.value = level.coerceIn(0, 255) }

    /**
     * Queue a full frame (625 values 0-255, row-major) on [channel]. Newest wins: an unsent older frame on the
     * same channel is replaced.
     */
    fun submit(frame: IntArray, channel: Channel = Channel.APP) {
        require(frame.size == profile.cellCount) { "frame must have ${profile.cellCount} cells" }
        val b = if (channel == Channel.TOY) 255 else _brightness.value
        val out = IntArray(frame.size) { (frame[it].coerceIn(0, 255) * b + 127) / 255 }
        _lastFrame.value = out
        pending.getValue(channel).set(IntArray(out.size) { out[it] * HW_MAX / 255 })
        if (_state.value == DriverState.Ready) schedulePump()
    }

    fun clear(channel: Channel = Channel.APP) = submit(IntArray(profile.cellCount), channel)

    /** Toy teardown: blank toy frame (sent synchronously on the IO thread) and turnOff. */
    fun endToy() {
        val m = manager ?: return
        pending.getValue(Channel.TOY).set(null)
        io.execute {
            if (drewToy) {
                call("setMatrixFrame(blank)") { m.setMatrixFrame(IntArray(profile.cellCount)) }
                call("turnOff") { m.turnOff() }
                drewToy = false
            }
        }
    }

    @Volatile private var closeOnConnect = false

    /**
     * Stop showing the app channel even if this process never drew: an app frame survives our process, so after
     * a restart only an unconditional closeAppMatrix takes it off. Runs once connected if not yet.
     */
    fun closeAppAlways() {
        pending.getValue(Channel.APP).set(null)
        val m = manager
        if (m == null || _state.value != DriverState.Ready) {
            closeOnConnect = true
            return
        }
        io.execute { call("closeAppMatrix") { m.closeAppMatrix() }; drewApp = false }
    }

    /** Stop showing the app channel (closeAppMatrix) while keeping the connection. */
    fun closeApp() {
        val m = manager ?: return
        pending.getValue(Channel.APP).set(null)
        io.execute { if (drewApp) { call("closeAppMatrix") { m.closeAppMatrix() }; drewApp = false } }
    }

    private fun schedulePump() {
        if (pumpScheduled.compareAndSet(false, true)) io.execute(::pump)
    }

    private fun pump() {
        try {
            loop@ while (true) {
                var sent = false
                for ((channel, slot) in pending) {
                    val pixels = slot.getAndSet(null) ?: continue
                    val m = manager
                    if (m == null || _state.value != DriverState.Ready) { slot.compareAndSet(null, pixels); break@loop }
                    try {
                        when (channel) {
                            Channel.TOY -> { m.setMatrixFrame(pixels); drewToy = true }
                            Channel.APP -> { m.setAppMatrixFrame(pixels); drewApp = true }
                        }
                        sent = true
                        sentTotal.incrementAndGet()
                        _stats.update { it.copy(framesSent = it.framesSent + 1) }
                        tickFps()
                    } catch (t: Throwable) {
                        _stats.update { it.copy(framesFailed = it.framesFailed + 1) }
                        record("setFrame($channel)", false, t.toString())
                        slot.compareAndSet(null, pixels) // retry after reconnect
                        fail("frame send failed: ${t.message}")
                        break@loop
                    }
                }
                if (!sent) break
            }
        } finally {
            pumpScheduled.set(false)
            if (hasPending() && _state.value == DriverState.Ready) schedulePump()
        }
    }

    private fun tickFps() {
        val now = System.nanoTime() / 1_000_000
        sendTimes.addLast(now)
        while (sendTimes.isNotEmpty() && now - sendTimes.first() > 1000) sendTimes.removeFirst()
        if (now - lastFpsPublish >= 250) {
            lastFpsPublish = now
            _fps.value = sendTimes.size.toFloat()
        }
    }
}
