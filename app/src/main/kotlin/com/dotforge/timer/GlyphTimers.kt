package com.dotforge.timer

import android.content.Context
import android.content.SharedPreferences
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.StopwatchStyle
import com.dotforge.device.TimerFaces
import com.dotforge.device.TimerStyle
import com.dotforge.device.Timing
import com.dotforge.device.Timings
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
        /** The timer you chose to show on the Glyph ("Show on Glyph"), instead of the one that ends first. */
        val focus: String? = null,
    ) {
        /** Ours (newest first is not needed: by when they end), then the Clock app's. */
        val all: List<GlyphTimer> get() = timers + clock.values.filter { it.id !in hidden }
        val watch: Stopwatch? get() = stopwatch.takeIf { it.started } ?: clockWatch?.takeIf { "clock:watch" !in hidden }
        /** Our own work in progress: the service runs while there is any. */
        val ours: Boolean get() = timers.isNotEmpty() || stopwatch.started
    }

    @Serializable
    private data class Saved(val timers: List<GlyphTimer> = emptyList(), val stopwatch: Stopwatch = Stopwatch(), val focus: String? = null)

    /** The finish's default: plays until dismissed, at full speed and brightness, never resting. */
    val DEFAULT_TIMING = Timing(showFor = ShowFor.UNTIL_CHECKED, rest = false)
    /** "Runs for" on the page: 10 s, 30 s, 1 min, or until dismissed. */
    val RUN_CHIPS = listOf(10, 30, 60)
    /** Saved custom times (newest first), at most this many. */
    const val MAX_PRESETS = 4

    const val ID = "timer"
    const val DONE_ID = "timer-done"
    const val TRY_ID = "timer-try"

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

    private val _watchStyle = MutableStateFlow(StopwatchStyle.BIG_DIGITS)
    val watchStyle: StateFlow<StopwatchStyle> = _watchStyle.asStateFlow()

    private val _timing = MutableStateFlow(DEFAULT_TIMING)
    /** On the Glyph: brightness (everything), and the finish's speed, how long it runs and flashing again. */
    val timing: StateFlow<Timing> = _timing.asStateFlow()

    private val _lastTen = MutableStateFlow(true)
    /** The last 10 seconds count down big, a ring segment ticking off each second. */
    val lastTen: StateFlow<Boolean> = _lastTen.asStateFlow()

    private val _sound = MutableStateFlow(true)
    val sound: StateFlow<Boolean> = _sound.asStateFlow()
    private val _vibrate = MutableStateFlow(true)
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    private val _presets = MutableStateFlow<List<Long>>(emptyList())
    /** Custom times saved as presets (ms), newest first. */
    val presets: StateFlow<List<Long>> = _presets.asStateFlow()

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
        _watchStyle.value = StopwatchStyle.of(p.getString(KEY_WATCH_STYLE, null))
        _mirrorClock.value = p.getBoolean(KEY_MIRROR, true)
        _timing.value = p.getString(KEY_TIMING, null)?.let { runCatching { json.decodeFromString(Timing.serializer(), it) }.getOrNull() } ?: DEFAULT_TIMING
        _lastTen.value = p.getBoolean(KEY_LAST_TEN, true)
        _sound.value = p.getBoolean(KEY_SOUND, true)
        _vibrate.value = p.getBoolean(KEY_VIBRATE, true)
        _presets.value = p.getString(KEY_PRESETS, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val saved = p.getString(KEY_SAVED, null)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
        _state.value = State(saved?.timers.orEmpty(), saved?.stopwatch ?: Stopwatch(), focus = saved?.focus)
        if (_state.value.ours) changed(save = false)
    }

    fun setStyle(s: TimerStyle) {
        _style.value = s
        prefs?.edit()?.putString(KEY_STYLE, s.name)?.apply()
        lastShown = null
        tick()
    }

    fun setWatchStyle(s: StopwatchStyle) {
        _watchStyle.value = s
        prefs?.edit()?.putString(KEY_WATCH_STYLE, s.name)?.apply()
        lastShown = null
        tick()
    }

    fun setTiming(t: Timing) {
        // the finish never rests: it keeps going for as long as it is set to run
        _timing.value = t.clamped().copy(rest = false)
        prefs?.edit()?.putString(KEY_TIMING, json.encodeToString(Timing.serializer(), _timing.value))?.apply()
        lastShown = null
        tick()
    }

    fun setLastTen(on: Boolean) {
        _lastTen.value = on
        prefs?.edit()?.putBoolean(KEY_LAST_TEN, on)?.apply()
        tick()
    }

    fun setSound(on: Boolean) { _sound.value = on; prefs?.edit()?.putBoolean(KEY_SOUND, on)?.apply() }
    fun setVibrate(on: Boolean) { _vibrate.value = on; prefs?.edit()?.putBoolean(KEY_VIBRATE, on)?.apply() }

    /** Saves a custom time as a preset chip (newest first; the oldest goes past [MAX_PRESETS]). */
    fun savePreset(ms: Long) {
        if (ms in Timers.PRESETS.map { it * 60_000L }) return
        _presets.value = (listOf(ms) + _presets.value.filter { it != ms }).take(MAX_PRESETS)
        prefs?.edit()?.putString(KEY_PRESETS, _presets.value.joinToString(","))?.apply()
    }

    fun removePreset(ms: Long) {
        _presets.value = _presets.value - ms
        prefs?.edit()?.putString(KEY_PRESETS, _presets.value.joinToString(","))?.apply()
    }

    /** Brightness for everything the timer draws, 0.1..1. */
    private val level: Float get() = _timing.value.brightness / 100f

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

    /** Undo for Stop: the timer comes back as it was (a running one has kept counting). */
    fun restore(t: GlyphTimer) {
        if (_state.value.timers.any { it.id == t.id }) return
        _state.update { it.copy(timers = it.timers + t) }
        changed()
    }

    /** "Show on Glyph": this timer shows instead of the one that ends first (until it goes). */
    fun showOnGlyph(id: String?) {
        _state.update { it.copy(focus = id) }
        changed()
    }
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
        return Timers.shown(s.all, s.watch, at, s.focus)
    }

    /** The finish for a timer that ended [sinceMs] ago: how much longer it shows (null: dark for now). */
    fun finishLeft(sinceMs: Long): Long? {
        val plan = Timings.plan(TimerFaces.finished(AppGraph.profile), _timing.value)
        return Timers.finishShowing(sinceMs, plan.showMs.takeUnless { plan.untilChecked }, if (_timing.value.remindMinutes > 0) _timing.value.remindMinutes * 60_000L else null)
    }

    /** How long the phone rings and buzzes when a timer ends: as long as the finish first runs (at most 10 min). */
    fun ringMs(): Long {
        val t = _timing.value
        return if (t.showFor == ShowFor.SECONDS) t.seconds * 1000L else 10 * 60_000L
    }

    private fun saved() = json.encodeToString(Saved.serializer(), Saved(_state.value.timers, _state.value.stopwatch, _state.value.focus))

    private fun changed(save: Boolean = true) {
        _state.value.focus?.let { f -> if (_state.value.all.none { it.id == f }) _state.update { it.copy(focus = null) } }
        if (save) prefs?.edit()?.putString(KEY_SAVED, saved())?.apply()
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
            prefs?.edit()?.putString(KEY_SAVED, saved())?.apply()
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
        val k = level
        if (shown !is TimerShown.Done) arbiter.remove(DONE_ID)
        var key: String? = shown?.key
        when (shown) {
            null -> arbiter.remove(ID)
            is TimerShown.Done -> {
                arbiter.remove(ID)
                val since = n - shown.timer.doneAt.takeIf { it > 0 }.let { it ?: shown.timer.endsAt }
                val left = finishLeft(since)
                if (left == null) {
                    arbiter.remove(DONE_ID)
                    key = "done-dark:" + shown.key
                } else {
                    // posted once per run or flash: re-posting would restart the burst every second
                    val window = if (left == Long.MAX_VALUE) "all" else ((since + left) / 1000).toString()
                    key = "done:" + shown.key + ":" + window
                    if (lastShown != key || !arbiter.has(DONE_ID)) {
                        val plan = Timings.plan(TimerFaces.finished(p), _timing.value.copy(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE))
                        arbiter.post(
                            DisplayRequest(
                                DONE_ID, Priority.TIMER_DONE, plan.animation, loop = true,
                                timeoutMs = left.takeIf { it != Long.MAX_VALUE }, label = "Time's up · " + shown.timer.name,
                            ),
                        )
                    }
                }
            }
            is TimerShown.Running -> arbiter.post(
                DisplayRequest(ID, Priority.TIMER, TimerFaces.second(p, style, shown.leftMs, shown.timer.totalMs, k, _lastTen.value), loop = true, label = label(shown)),
            )
            is TimerShown.Paused -> {
                // posted once while it stays paused: re-posting every second would restart the blink
                key = "paused:" + shown.key + ":" + shown.leftMs / 1000
                if (lastShown != key || !arbiter.has(ID)) arbiter.post(
                    DisplayRequest(ID, Priority.TIMER, TimerFaces.paused(p, TimerFaces.countdown(p, style, shown.leftMs, shown.timer.totalMs), k), loop = true, label = label(shown)),
                )
            }
            is TimerShown.Watch -> {
                val ws = _watchStyle.value
                if (shown.stopwatch.running) arbiter.post(
                    DisplayRequest(ID, Priority.TIMER, TimerFaces.stopwatchSecond(p, ws, shown.elapsedMs, k), loop = true, label = label(shown)),
                ) else {
                    key = "paused:" + shown.key + ":" + shown.elapsedMs / 1000
                    if (lastShown != key || !arbiter.has(ID)) arbiter.post(
                        DisplayRequest(ID, Priority.TIMER, TimerFaces.paused(p, TimerFaces.stopwatch(p, ws, shown.elapsedMs), k), loop = true, label = label(shown)),
                    )
                }
            }
        }
        lastShown = key
    }

    /** Try it: the last three seconds (if on) and one finish, with the current settings, on the Glyph. */
    fun tryFinish() {
        val p = AppGraph.profile
        val k = level
        val frames = ArrayList<AnimFrame>()
        if (_lastTen.value) for (s in 3 downTo 1) frames += TimerFaces.lastSeconds(p, s * 1000L, k).frames
        else frames += TimerFaces.second(p, _style.value, 3_000, 60_000, k, lastTen = false).frames
        frames += Timings.plan(TimerFaces.finished(p), _timing.value.copy(showFor = ShowFor.WHOLE, repeat = Repeat.TIMES, times = 2)).animation.frames
        runCatching { AppGraph.arbiter }.getOrNull()?.post(DisplayRequest(TRY_ID, Priority.STUDIO_PREVIEW, Animation(p, frames), label = "Timer: try it"))
    }

    /** The finish as it plays with the current settings (the page's preview). */
    fun finishPreview(): Animation = Timings.plan(TimerFaces.finished(AppGraph.profile), _timing.value.copy(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE)).animation

    /** What Home and the page call it: "Timer · 4:12 left", "Clock timer · 0:42 left", "Stopwatch · 1:05". */
    fun label(shown: TimerShown): String = when (shown) {
        is TimerShown.Done -> "Time's up · " + shown.timer.name
        is TimerShown.Running -> who(shown.timer) + " · " + TimerFaces.clock(shown.leftMs) + " left"
        is TimerShown.Paused -> who(shown.timer) + " paused · " + TimerFaces.clock(shown.leftMs) + " left"
        is TimerShown.Watch -> (if (shown.stopwatch.source == TimerSource.CLOCK) "Clock stopwatch" else "Stopwatch") +
            (if (shown.stopwatch.running) "" else " paused") + " · " + TimerFaces.clock(shown.elapsedMs, roundUp = false)
    }

    /** The timer's own name when it has one ("Tea"), else "Timer" or "Clock timer". */
    private fun who(t: GlyphTimer) = t.label.ifBlank { if (t.source == TimerSource.CLOCK) "Clock timer" else "Timer" }

    /** A style's moving preview for the page and the style sheet. */
    fun preview(style: TimerStyle): Animation = TimerFaces.preview(AppGraph.profile, style)
    fun preview(style: StopwatchStyle): Animation = TimerFaces.preview(AppGraph.profile, style)

    /** Tests: back to nothing, with no service. */
    @androidx.annotation.VisibleForTesting
    fun reset() {
        ticker?.cancel()
        ticker = null
        lastShown = null
        _state.value = State()
        prefs?.edit()?.clear()?.apply()
        _style.value = TimerStyle.RING_DRAIN
        _watchStyle.value = StopwatchStyle.BIG_DIGITS
        _timing.value = DEFAULT_TIMING
        _lastTen.value = true
        _sound.value = true
        _vibrate.value = true
        _presets.value = emptyList()
        _mirrorClock.value = true
        now = System::currentTimeMillis
    }

    private const val KEY_STYLE = "style"
    private const val KEY_MIRROR = "mirror_clock"
    private const val KEY_SAVED = "saved"
    private const val KEY_WATCH_STYLE = "watch_style"
    private const val KEY_TIMING = "timing"
    private const val KEY_LAST_TEN = "last_ten"
    private const val KEY_SOUND = "sound"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_PRESETS = "presets"
}
