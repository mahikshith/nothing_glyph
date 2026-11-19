package com.dotforge.engine

import com.dotforge.device.Animation
import com.dotforge.device.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Who wins the matrix among DotForge's own sources. Higher wins (row 50): an incoming call, then the torch,
 * then alerts, then progress rings, then the visualiser. When one ends, what was showing before resumes.
 */
object Priority {
    /** The Live music visualiser: under alerts and progress (it is switched on by hand, and comes back after them). */
    const val LIVE = 25
    const val PROGRESS = 30
    /** A running timer or the stopwatch: over Live progress, under alerts (an alert shows, then the timer is back). */
    const val TIMER = 40
    const val NOTIFICATION = 50
    /** A timer that has ended: its burst beats alerts until it is dismissed. */
    const val TIMER_DONE = 55
    const val STUDIO_PREVIEW = 60
    const val TORCH = 70
    /** A ringing call: the caller's name and glyph. */
    const val CALL = 80

    /** Ambient requests (alerts, progress) obey Nothing's Glyph switch and bedtime. */
    fun ambient(priority: Int) = priority == PROGRESS || priority == NOTIFICATION
}

data class DisplayRequest(
    val id: String,
    val priority: Int,
    val animation: Animation,
    /** Repeat until removed or timed out (hold until dismissed, progress, torch). */
    val loop: Boolean = false,
    /** Auto-remove after this long; null = until removed or (if not [loop]) after one play. */
    val timeoutMs: Long? = null,
    val label: String = id,
) {
    val isAmbient: Boolean get() = Priority.ambient(priority)
}

/** Whether ambient requests (notifications, progress) may light up right now, e.g. Nothing's Glyph switch and bedtime. */
fun interface AmbientGate {
    fun ambientAllowed(): Boolean
}

/**
 * Sits above the driver and owns the app channel: keeps a set of requests and always plays the
 * highest-priority one (newest wins on ties), falling back to the next when it ends or is removed.
 *
 * The firmware ranks system lights above app frames (ringtone > active toy > notification glyph > app frames)
 * and never tells the app when its frame was covered, so while something is showing the current frame is
 * re-sent every [resendMs], [refresh] re-sends it at once and [refreshAfter] a moment later (on screen-on,
 * unlock, and when a notification we handle arrives together with Nothing's own notification glyph).
 *
 * All state lives on [scope], which must be single-threaded.
 */
class DisplayArbiter(
    private val scope: CoroutineScope,
    private val device: MatrixDevice,
    private val gate: AmbientGate = AmbientGate { true },
    enabled: Boolean = true,
    private val resendMs: Long = RESEND_MS,
) {
    private class Entry(val request: DisplayRequest, val seq: Long)

    private val requests = LinkedHashMap<String, Entry>()
    private var playing: String? = null
    private var job: Job? = null
    private var seq = 0L
    private var suspended = false
    private var pendingRefresh: Job? = null

    private val _frame = MutableStateFlow<Frame?>(null)
    /** Current frame, mirrored in the in-app preview. */
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    private val _active = MutableStateFlow<List<DisplayRequest>>(emptyList())
    /** Queued requests, highest priority (then newest) first. The first one is showing unless gated or switched off. */
    val active: StateFlow<List<DisplayRequest>> = _active.asStateFlow()

    private val _enabled = MutableStateFlow(enabled)
    /** Master switch: when off nothing is shown (requests are kept). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        scope.launch {
            _enabled.value = on
            reschedule(force = true)
        }
    }

    /** While suspended (the Diagnostics bench drives the matrix directly) nothing is shown; requests are kept. */
    fun setSuspended(on: Boolean) {
        scope.launch {
            suspended = on
            reschedule(force = true)
        }
    }

    fun post(request: DisplayRequest) {
        scope.launch {
            val entry = Entry(request, seq++)
            requests[request.id] = entry
            request.timeoutMs?.let { timeout ->
                scope.launch {
                    delay(timeout)
                    if (requests[request.id] === entry) {
                        requests.remove(request.id)
                        reschedule(force = playing == request.id)
                    }
                }
            }
            reschedule(force = playing == request.id)
        }
    }

    fun remove(id: String) {
        scope.launch {
            if (requests.remove(id) != null) reschedule(force = playing == id)
        }
    }

    fun removeIf(predicate: (DisplayRequest) -> Boolean) {
        scope.launch {
            val ids = requests.values.map { it.request }.filter(predicate).map { it.id }
            if (ids.isEmpty()) return@launch
            ids.forEach { requests.remove(it) }
            reschedule(force = playing in ids)
        }
    }

    fun clearAll() {
        scope.launch {
            requests.clear()
            reschedule(force = true)
        }
    }

    /** Thread-safe snapshot check. */
    fun has(id: String): Boolean = _active.value.any { it.id == id }

    /** Re-check the gate and re-send the current frame now (it may have been covered by system lights). */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /**
     * Re-send the current frame once [delayMs] from now, after a system light that is likely covering it
     * (a notification glyph, the unlock animation) has finished. A newer call replaces a pending one.
     */
    fun refreshAfter(delayMs: Long) {
        scope.launch {
            pendingRefresh?.cancel()
            pendingRefresh = scope.launch {
                delay(delayMs)
                refreshNow()
            }
        }
    }

    private fun refreshNow() {
        val before = playing
        reschedule(force = false)
        if (playing != null && playing == before) _frame.value?.let(device::show)
    }

    private fun top(): DisplayRequest? {
        if (!_enabled.value || suspended) return null
        val ambientOpen = gate.ambientAllowed()
        return requests.values
            .filter { ambientOpen || !it.request.isAmbient }
            .maxWithOrNull(compareBy<Entry> { it.request.priority }.thenBy { it.seq })
            ?.request
    }

    private fun reschedule(force: Boolean) {
        _active.value = requests.values
            .sortedWith(compareByDescending<Entry> { it.request.priority }.thenByDescending { it.seq })
            .map { it.request }
        val next = top()
        if (!force && next?.id == playing) return
        job?.cancel()
        playing = next?.id
        if (next == null) {
            job = null
            _frame.value = null
            device.clear()
            return
        }
        job = scope.launch {
            launch { heartbeat(next) }
            play(next)
        }
    }

    private suspend fun heartbeat(request: DisplayRequest) {
        while (true) {
            delay(resendMs)
            if (request.isAmbient && !gate.ambientAllowed()) {
                reschedule(force = true)
                return
            }
            _frame.value?.let(device::show)
        }
    }

    private suspend fun CoroutineScope.play(request: DisplayRequest) {
        do {
            for (f in request.animation.frames) {
                if (!isActive) return
                _frame.value = f.frame
                device.show(f.frame)
                delay(f.durationMs.coerceAtLeast(Animation.MIN_FRAME_MS).toLong())
            }
        } while (request.loop && isActive)
        if (!isActive) return
        requests.remove(request.id)
        reschedule(force = true)
    }

    companion object {
        /** Re-send interval for the current frame while something is showing. */
        const val RESEND_MS = 3_000L
    }
}
