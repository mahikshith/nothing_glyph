package com.dotforge.timer

import android.content.Context
import android.content.SharedPreferences
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.TimerFaces
import com.dotforge.device.TimerStyle
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.live.ClockMirror
import com.dotforge.live.GlyphTimer
import com.dotforge.live.Stopwatch
import com.dotforge.live.TimerShown
import com.dotforge.live.TimerSource
import com.dotforge.live.Timers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Timer & stopwatch: DotForge's own timers and stopwatch, and the Clock app's (mirrored from its notifications).
 * They show on the Glyph as an app frame from our foreground service ([TimerService]), never as a toy (toys stop
 * after 30 s). Several timers can run; the Glyph shows the one that ends first, a timer that has ended bursts
 * until it is dismissed, and the last 10 s pulse. Our timers and stopwatch survive the process (saved in prefs).
 */
object GlyphTimers {
    /** Everything the page and the Glyph need. [clock] holds the Clock app's timers by notification key. */
    data class State(
        val timers: List<GlyphTimer> = emptyList(),
        val stopwatch: Stopwatch = Stopwatch(),
        val clock: Map<String, GlyphTimer> = emptyMap(),
        val clockWatch: Stopwatch? = null,
        /** Clock app items the user stopped showing (Stop on Home); they stay hidden until their notification goes. */
        val hidden: Set<String> = emptySet(),
    ) {
        /** Ours (newest first is not needed: by when they end), then the Clock app's. */
        val all: List<GlyphTimer> get() = timers + clock.values.filter { it.id !in hidden }
        val watch: Stopwatch? get() = stopwatch.takeIf { it.started } ?: clockWatch?.takeIf { "clock:watch" !in hidden }
        /** Our own work in progress: the service runs while there is any. */
        val ours: Boolean get() = timers.isNotEmpty() || stopwatch.started
    }

    @Serializable
    private data class Saved(val timers: List<GlyphTimer> = emptyList(), val stopwatch: Stopwatch = Stopwatch())

    const val ID = "timer"
    const val DONE_ID = "timer-done"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var prefs: SharedPreferences? = null
    private var app: Context? = null
    private var scope: CoroutineScope = MainScope()
    private var ticker: Job? = null
    private var seq = 0L
    /** The Clock app's stopwatch notification. */
    private var watchKey: String? = null

    /** The time, settable for tests. */
    var now: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _style = MutableStateFlow(TimerStyle.RING_DRAIN)
    val style: StateFlow<TimerStyle> = _style.asStateFlow()

    private val _mirrorClock = MutableStateFlow(true)
    /** Show the Clock app's timers and stopwatch too (on by default). */
    val mirrorClock: StateFlow<Boolean> = _mirrorClock.asStateFlow()

    /** Called by the service when the state changes (its notification). */
    var onChange: ((State) -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences("timers", Context.MODE_PRIVATE)
        prefs = p
        _style.value = TimerStyle.of(p.getString(KEY_STYLE, null))
        _mirrorClock.value = p.getBoolean(KEY_MIRROR, true)
        val saved = p.getString(KEY_SAVED, null)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
        _state.value = State(saved?.timers.orEmpty(), saved?.stopwatch ?: Stopwatch())
        if (_state.value.ours) changed(save = false)
    }

    fun setStyle(s: TimerStyle) {
        _style.value = s
        prefs?.edit()?.putString(KEY_STYLE, s.name)?.apply()
        tick()
    }

    fun setMirrorClock(on: Boolean) {
        _mirrorClock.value = on
        prefs?.edit()?.putBoolean(KEY_MIRROR, on)?.apply()
        if (!on) _state.update { it.copy(clock = emptyMap(), clockWatch = null, hidden = emptySet()) }
        changed(save = false)
    }

    // ---------------------------------------------------------------- our timers

    fun start(ms: Long, label: String = ""): GlyphTimer {
        val t = GlyphTimer.start("t${now()}-${seq++}", ms.coerceIn(1_000, Timers.MAX_MS), now(), label)
        _state.update { it.copy(timers = it.timers + t) }
        changed()
        return t
    }

    private fun edit(id: String, f: (GlyphTimer) -> GlyphTimer?) {
        _state.update { s -> s.copy(timers = s.timers.mapNotNull { if (it.id == id) f(it) else it }) }
        changed()
    }

    fun pause(id: String) = edit(id) { it.pause(now()) }
    fun resume(id: String) = edit(id) { it.resume(now()) }
    fun plusMinute(id: String) = edit(id) { it.plusMinute(now()) }
    fun cancel(id: String) = edit(id) { null }
    /** A timer that has ended goes away (the burst stops). */
    fun dismiss(id: String) {
        if (id.startsWith("clock:")) hide(id) else cancel(id)
    }

    fun dismissAllDone() {
        val n = now()
        _state.update { s -> s.copy(timers = s.timers.filterNot { it.over(n) }, hidden = s.hidden + s.clock.values.filter { it.over(n) }.map { it.id }) }
        changed()
    }

    /** Stop on Home for whatever timer shows: ends ours, hides the Clock app's. */
    fun stopShown() {
        when (val shown = shown()) {
            null -> Unit
            is TimerShown.Watch -> if (shown.stopwatch.source == TimerSource.CLOCK) hide("clock:watch") else stopwatchReset()
            is TimerShown.Done -> dismiss(shown.timer.id)
            is TimerShown.Running -> dismiss(shown.timer.id)
            is TimerShown.Paused -> dismiss(shown.timer.id)
        }
    }

    private fun hide(id: String) {
        _state.update { it.copy(hidden = it.hidden + id) }
        changed(save = false)
    }

    // ---------------------------------------------------------------- the stopwatch

    fun stopwatchStart() { _state.update { it.copy(stopwatch = it.stopwatch.start(now())) }; changed() }
    fun stopwatchPause() { _state.update { it.copy(stopwatch = it.stopwatch.pause(now())) }; changed() }
    fun stopwatchLap() { _state.update { it.copy(stopwatch = it.stopwatch.lap(now())) }; changed() }
    fun stopwatchReset() { _state.update { it.copy(stopwatch = Stopwatch()) }; changed() }

    // ---------------------------------------------------------------- the Clock app

    /** A Clock app notification: its timer or stopwatch, mirrored while the switch is on. */
    fun mirror(key: String, m: ClockMirror.Mirrored) {
        if (!_mirrorClock.value) return
        val n = now()
        when (m.kind) {
            ClockMirror.Kind.TIMER -> _state.update { s ->
                val t = ClockMirror.toTimer(key, m, n, s.clock[key]) ?: return@update s
                s.copy(clock = s.clock + (key to t))
            }
            ClockMirror.Kind.STOPWATCH -> {
                watchKey = key
                _state.update { s -> s.copy(clockWatch = ClockMirror.toStopwatch(m, n)) }
            }
        }
        changed(save = false)
    }

    /** The Clock app's notification went: so does its timer (or stopwatch). */
    fun unmirror(key: String) {
        val stopwatch = key == watchKey
        if (stopwatch) watchKey = null
        val s = _state.value
        if (key !in s.clock && !stopwatch) return
        _state.update {
            it.copy(
                clock = it.clock - key, hidden = it.hidden - "clock:$key" - if (stopwatch) "clock:watch" else "",
                clockWatch = if (stopwatch) null else it.clockWatch,
            )
        }
        changed(save = false)
    }

    // ---------------------------------------------------------------- what shows

    fun shown(at: Long = now()): TimerShown? {
        val s = _state.value
        return Timers.shown(s.all, s.watch, at)
    }

    private fun changed(save: Boolean = true) {
        if (save) prefs?.edit()?.putString(KEY_SAVED, json.encodeToString(Saved.serializer(), Saved(_state.value.timers, _state.value.stopwatch)))?.apply()
        app?.let { ctx -> if (_state.value.ours) TimerService.ensure(ctx) }
        tick()
        onChange?.invoke(_state.value)
    }

    /**
     * Marks timers that ran out as ended, puts what shows on the Glyph, and keeps a ticker going while anything
     * shows (once a second, on the second, so the digits change in step with the time).
     */
    fun tick() {
        val n = now()
        val (settled, ended) = Timers.settle(_state.value.timers, n)
        val (clockSettled, clockEnded) = Timers.settle(_state.value.clock.values.toList(), n)
        if (ended.isNotEmpty() || clockEnded.isNotEmpty()) {
            _state.update { it.copy(timers = settled, clock = it.clock.mapValues { (_, t) -> clockSettled.first { c -> c.id == t.id } }) }
            prefs?.edit()?.putString(KEY_SAVED, json.encodeToString(Saved.serializer(), Saved(_state.value.timers, _state.value.stopwatch)))?.apply()
            onChange?.invoke(_state.value)
        }
        show(shown(n), n)
        val anything = shown(n) != null
        if (anything && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    val shown = shown()
                    if (shown == null) break
                    // the next whole second of what shows
                    val wait = when (shown) {
                        is TimerShown.Running -> (shown.leftMs % 1000).let { if (it == 0L) 1000 else it }
                        is TimerShown.Watch -> 1000 - shown.elapsedMs % 1000
                        else -> 1000
                    }
                    delay(wait.coerceIn(50, 1000))
                    tick()
                }
            }
        } else if (!anything) {
            ticker?.cancel()
            ticker = null
        }
    }

    private var lastShown: String? = null

    private fun show(shown: TimerShown?, n: Long) {
        val arbiter = runCatching { AppGraph.arbiter }.getOrNull() ?: return
        val p = AppGraph.profile
        val style = _style.value
        if (shown !is TimerShown.Done) arbiter.remove(DONE_ID)
        when (shown) {
            null -> arbiter.remove(ID)
            is TimerShown.Done -> {
                arbiter.remove(ID)
                // posted once: re-posting would restart the burst every second
                if (lastShown != "done:" + shown.key || !arbiter.has(DONE_ID)) {
                    arbiter.post(DisplayRequest(DONE_ID, Priority.TIMER_DONE, TimerFaces.finished(p), loop = true, label = "Time's up · " + shown.timer.name))
                }
            }
            is TimerShown.Running -> arbiter.post(
                DisplayRequest(ID, Priority.TIMER, TimerFaces.second(p, style, shown.leftMs, shown.timer.totalMs), loop = true, label = label(shown)),
            )
            is TimerShown.Paused -> arbiter.post(
                DisplayRequest(ID, Priority.TIMER, TimerFaces.paused(p, TimerFaces.countdown(p, style, shown.leftMs, shown.timer.totalMs)), loop = true, label = label(shown)),
            )
            is TimerShown.Watch -> arbiter.post(
                DisplayRequest(ID, Priority.TIMER, if (shown.stopwatch.running) TimerFaces.stopwatchSecond(p, style, shown.elapsedMs) else TimerFaces.paused(p, TimerFaces.stopwatch(p, style, shown.elapsedMs)), loop = true, label = label(shown)),
            )
        }
        lastShown = when (shown) {
            null -> null
            is TimerShown.Done -> "done:" + shown.key
            else -> shown.key
        }
    }

    /** What Home and the page call it: "Timer · 4:12 left", "Clock timer · 0:42 left", "Stopwatch · 1:05". */
    fun label(shown: TimerShown): String = when (shown) {
        is TimerShown.Done -> "Time's up · " + shown.timer.name
        is TimerShown.Running -> who(shown.timer) + " · " + TimerFaces.clock(shown.leftMs) + " left"
        is TimerShown.Paused -> who(shown.timer) + " paused · " + TimerFaces.clock(shown.leftMs) + " left"
        is TimerShown.Watch -> (if (shown.stopwatch.source == TimerSource.CLOCK) "Clock stopwatch" else "Stopwatch") + " · " + TimerFaces.clock(shown.elapsedMs, roundUp = false)
    }

    /** The timer's own name when it has one ("Tea"), else "Timer" or "Clock timer". */
    private fun who(t: GlyphTimer) = t.label.ifBlank { if (t.source == TimerSource.CLOCK) "Clock timer" else "Timer" }

    /** A style's moving preview for the page and the style sheet. */
    fun preview(style: TimerStyle): Animation = TimerFaces.preview(AppGraph.profile, style)

    /** Tests: back to nothing, with no service. */
    @androidx.annotation.VisibleForTesting
    fun reset() {
        ticker?.cancel()
        ticker = null
        lastShown = null
        _state.value = State()
        prefs?.edit()?.clear()?.apply()
        _style.value = TimerStyle.RING_DRAIN
        _mirrorClock.value = true
        now = System::currentTimeMillis
    }

    private const val KEY_STYLE = "style"
    private const val KEY_MIRROR = "mirror_clock"
    private const val KEY_SAVED = "saved"
}
