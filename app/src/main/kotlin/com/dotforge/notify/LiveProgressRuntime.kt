package com.dotforge.notify

import android.content.SharedPreferences
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.live.Centre
import com.dotforge.live.LiveProgress
import com.dotforge.live.LiveRing
import com.dotforge.live.ProgressApp
import com.dotforge.live.ProgressApps
import com.dotforge.live.ProgressReading
import com.dotforge.live.ProgressSignals
import com.dotforge.live.ProgressSource
import com.dotforge.live.Stage
import com.dotforge.live.TripKind
import kotlinx.serialization.json.Json
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How long a ring shows: all the way, only the last 10 minutes, or 10 s each time it changes. */
enum class ProgressShowFor(val label: String) { WHOLE("Whole time"), LAST_10("Last 10 min"), ON_CHANGE("10 s on each change") }

/**
 * Features > Live progress settings: which known apps light a ring (all by default), what the middle shows
 * (minutes left or the stage), how long a ring shows, its brightness and the done animation, and the rings
 * before the next calendar event and the next alarm (minutes of lead, 0 = off). The main switch is
 * [AppGraph.progressRings]; other apps follow the "Show progress" rules (Any other app).
 */
class LiveProgressSettings(private val prefs: SharedPreferences) {
    private val _off = MutableStateFlow(prefs.getStringSet(KEY_OFF, emptySet()).orEmpty().toSet())
    /** Known apps switched off (stored as the ones off, so a new preset starts on). */
    val off: StateFlow<Set<String>> = _off.asStateFlow()

    private val _minutes = MutableStateFlow(prefs.getBoolean(KEY_MINUTES, true))
    val showMinutes: StateFlow<Boolean> = _minutes.asStateFlow()

    private val _showFor = MutableStateFlow(ProgressShowFor.entries.firstOrNull { it.name == prefs.getString(KEY_SHOW_FOR, null) } ?: ProgressShowFor.WHOLE)
    val showFor: StateFlow<ProgressShowFor> = _showFor.asStateFlow()

    private val _timing = MutableStateFlow(
        prefs.getString(KEY_TIMING, null)?.let { runCatching { json.decodeFromString(Timing.serializer(), it) }.getOrNull() } ?: DEFAULT_TIMING,
    )
    /** Brightness (every ring) and the done animation: once, 3 times or until you check. */
    val timing: StateFlow<Timing> = _timing.asStateFlow()

    private val _calendar = MutableStateFlow(prefs.getInt(KEY_CALENDAR, -1))
    /** Minutes before the next calendar event (0 = off; -1 = never set, see [migrate]). */
    val calendarLead: StateFlow<Int> = _calendar.asStateFlow()

    private val _alarm = MutableStateFlow(prefs.getInt(KEY_ALARM, -1))
    val alarmLead: StateFlow<Int> = _alarm.asStateFlow()

    fun appOn(id: String) = id !in _off.value

    fun setApp(id: String, on: Boolean) {
        _off.update { if (on) it - id else it + id }
        prefs.edit().putStringSet(KEY_OFF, _off.value).apply()
    }

    fun setShowMinutes(on: Boolean) {
        _minutes.value = on
        prefs.edit().putBoolean(KEY_MINUTES, on).apply()
    }

    fun setShowFor(v: ProgressShowFor) {
        _showFor.value = v
        prefs.edit().putString(KEY_SHOW_FOR, v.name).apply()
    }

    fun setTiming(t: Timing) {
        _timing.value = t.clamped()
        prefs.edit().putString(KEY_TIMING, json.encodeToString(Timing.serializer(), _timing.value)).apply()
    }

    fun setCalendarLead(min: Int) {
        _calendar.value = min
        prefs.edit().putInt(KEY_CALENDAR, min).apply()
    }

    fun setAlarmLead(min: Int) {
        _alarm.value = min
        prefs.edit().putInt(KEY_ALARM, min).apply()
    }

    /** The first time: the Reminders row's old "fill the ring before" choice carries over to both rings. */
    fun migrate(oldReminderLead: Int) {
        if (_calendar.value < 0) setCalendarLead(oldReminderLead.coerceAtLeast(0))
        if (_alarm.value < 0) setAlarmLead(oldReminderLead.coerceAtLeast(0))
    }

    /** Tests and screenshots: every app on, minutes shown, no event or alarm ring. */
    @androidx.annotation.VisibleForTesting
    fun reset() {
        _off.value = emptySet()
        prefs.edit().clear().apply()
        _minutes.value = true
        _showFor.value = ProgressShowFor.WHOLE
        _timing.value = DEFAULT_TIMING
        setCalendarLead(0)
        setAlarmLead(0)
    }

    companion object {
        /** Lead time chips on the page, in minutes (0 = off). */
        val LEADS = listOf(0, 5, 10, 15, 30)
        /** The done animation plays once, at full brightness. */
        val DEFAULT_TIMING = Timing(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE)
        /** "3 times" on the page. */
        const val DONE_TIMES = 3
        /** "Last 10 min": a ring with an arrival time shows from this many minutes before. */
        const val LAST_MINUTES = 10
        /** "10 s on each change". */
        const val CHANGE_MS = 10_000L
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private const val KEY_SHOW_FOR = "show_for"
        private const val KEY_TIMING = "timing"
        private const val KEY_OFF = "apps_off"
        private const val KEY_MINUTES = "show_minutes"
        private const val KEY_CALENDAR = "calendar_lead"
        private const val KEY_ALARM = "alarm_lead"
    }
}

/**
 * Turns notifications into Live progress rings: a progress bar, a countdown, the arrival time in words or the
 * order's stage (see [LiveProgress]). Known apps ([ProgressApps]) follow their own switch; other apps a "Show
 * progress" rule. The ring is the progress, the middle the minutes left or the stage's icon; about to arrive it
 * pulses; done, the done animation plays once. Minutes are worked out again every 30 s, so a countdown or an
 * "ETA 7:45 PM" keeps moving between the app's own updates.
 */
object LiveProgressRuntime {
    data class Item(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val app: ProgressApp?,
        val signals: ProgressSignals,
        val reading: ProgressReading,
        val track: LiveProgress.Track,
        /** When it arrived: the row stays under Now for [DONE_KEEP_MS] (or until its notification goes). */
        val doneAt: Long? = null,
    ) {
        val name: String get() = app?.name?.substringBefore(" /") ?: appLabel
        val done: Boolean get() = doneAt != null
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** What is in progress now, newest first (the page's "Now" rows). */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val doneShown = HashSet<String>()
    /** Rings you stopped showing ("Stop showing this"): left dark until their notification goes. */
    private val hidden = HashSet<String>()
    /** What each ring last showed, for "10 s on each change". */
    private val lastSeen = HashMap<String, String>()
    private var ticker: Job? = null
    var now: () -> Long = System::currentTimeMillis
    var zone: () -> ZoneId = ZoneId::systemDefault

    fun id(key: String) = "p:$key"
    private fun doneId(key: String) = "pd:$key"

    /**
     * A notification was posted or updated. [allowedByRule] says whether a "Show progress" rule wants an app we
     * don't know. Returns true when it is now a Live progress ring.
     */
    fun update(key: String, pkg: String, label: String, signals: (ProgressApp?) -> ProgressSignals, allowedByRule: () -> Boolean): Boolean {
        val app = ProgressApps.match(pkg, label)
        val on = AppGraph.progressRings.value && (if (app != null) AppGraph.liveProgress.appOn(app.id) else true)
        if (!on) return removed(key).let { false }
        val s = signals(app)
        val reading = LiveProgress.read(s, now(), zone())
        if (reading == null || reading.cancelled || (app == null && !allowedByRule())) return removed(key).let { false }
        if (key in hidden) return true
        val prev = _items.value.firstOrNull { it.key == key }
        val track = LiveProgress.track(prev?.track, reading, app?.kind ?: TripKind.DELIVERY, now())
        val item = Item(key, pkg, label, app, s, reading, track)
        if (reading.done) {
            AppGraph.arbiter.remove(id(key))
            if (doneShown.add(key)) {
                _items.update { list -> listOf(item.copy(doneAt = now())) + list.filterNot { it.key == key } }
                if (key !in hidden) postDone(item)
                keepTicking()
            }
            return true
        }
        doneShown.remove(key)
        _items.update { list -> listOf(item) + list.filterNot { it.key == key } }
        post(item)
        keepTicking()
        return true
    }

    /** The notification went (or stopped being progress); a done animation shown until you check goes too. */
    fun removed(key: String) {
        if (_items.value.any { it.key == key }) _items.update { list -> list.filterNot { it.key == key } }
        AppGraph.arbiter.remove(id(key))
        if (AppGraph.liveProgress.timing.value.untilChecked) AppGraph.arbiter.remove(doneId(key))
        hidden -= key
        lastSeen -= key
    }

    /** "Stop showing this" under Now: the ring goes dark until its notification goes. */
    fun hide(key: String) {
        hidden += key
        AppGraph.arbiter.remove(id(key))
        AppGraph.arbiter.remove(doneId(key))
        _items.update { list -> list.filterNot { it.key == key } }
    }

    fun isHidden(key: String) = key in hidden

    private fun postDone(item: Item) {
        val p = AppGraph.profile
        val plan = Timings.plan(LiveRing.done(p, icon(LiveProgress.icon(Stage.DONE, item.app, item.reading.vehicle))), AppGraph.liveProgress.timing.value)
        AppGraph.arbiter.post(
            DisplayRequest(doneId(item.key), Priority.PROGRESS, plan.animation, loop = plan.loop, timeoutMs = plan.showMs, label = "Done: ${item.name}"),
        )
    }

    /** Live progress was switched off, or a known app was: take the rings off. */
    fun refresh() {
        val keep = _items.value.filter { AppGraph.progressRings.value && (it.app == null || AppGraph.liveProgress.appOn(it.app.id)) }
        (_items.value - keep.toSet()).forEach { removed(it.key) }
        lastSeen.clear()
        keep.filterNot { it.done }.forEach(::post)
    }

    /**
     * Puts [item]'s ring on the Glyph as the page says: the whole time; only from [LiveProgressSettings.LAST_MINUTES]
     * before it arrives (a ring with no arrival time shows the whole time); or for 10 s each time what it shows
     * changes. Brightness applies to every ring.
     */
    private fun post(item: Item) {
        if (item.key in hidden || item.done) return
        val r = item.reading
        val seen = listOf(r.minutesLeft, r.stage, r.bar?.let { (it * 10).toInt() }, r.arriving).joinToString("|")
        val changed = lastSeen.put(item.key, seen) != seen
        val label = "${item.name} · " + LiveProgress.summary(r)
        when (AppGraph.liveProgress.showFor.value) {
            ProgressShowFor.WHOLE -> AppGraph.arbiter.post(DisplayRequest(id(item.key), Priority.PROGRESS, lit(animation(item)), loop = true, label = label))
            ProgressShowFor.LAST_10 -> {
                val m = r.minutesLeft
                if (m != null && m > LiveProgressSettings.LAST_MINUTES && !r.arriving) AppGraph.arbiter.remove(id(item.key))
                else AppGraph.arbiter.post(DisplayRequest(id(item.key), Priority.PROGRESS, lit(animation(item)), loop = true, label = label))
            }
            ProgressShowFor.ON_CHANGE -> if (changed) AppGraph.arbiter.post(
                DisplayRequest(id(item.key), Priority.PROGRESS, lit(animation(item)), loop = true, timeoutMs = LiveProgressSettings.CHANGE_MS, label = label),
            )
        }
    }

    /** [a] at the page's brightness. */
    private fun lit(a: Animation): Animation {
        val k = AppGraph.liveProgress.timing.value.brightness / 100f
        return if (k >= 1f) a else Animation(a.profile, a.frames.map { AnimFrame(it.frame.scaled(k), it.durationMs) })
    }

    /**
     * Try it: a delivery 12 minutes away, then about to arrive, then the done animation, with the page's
     * brightness and done animation (once through), on the Glyph.
     */
    fun tryIt() {
        val p = AppGraph.profile
        val frames = ArrayList<AnimFrame>()
        frames += AnimFrame(LiveRing.frame(p, Stage.ON_THE_WAY.fraction, centre(12, "g-scooter")), 1500)
        frames += LiveRing.animation(p, 0.92f, centre(1, "g-scooter"), arriving = true).frames
        frames += LiveRing.animation(p, 0.92f, centre(1, "g-scooter"), arriving = true).frames
        val t = AppGraph.liveProgress.timing.value
        frames += Timings.plan(LiveRing.done(p, icon("g-parcel-done")), t.copy(showFor = ShowFor.WHOLE, repeat = if (t.repeat == Repeat.TIMES && !t.untilChecked) Repeat.TIMES else Repeat.ONCE, brightness = 100)).animation.frames
        AppGraph.arbiter.post(DisplayRequest(TRY_ID, Priority.STUDIO_PREVIEW, lit(Animation(p, frames)), label = "Live progress: try it"))
    }

    /** The middle for an example: the minutes, or the stage's icon when the page says Stage. */
    fun centre(minutes: Int, iconId: String): Centre =
        if (AppGraph.liveProgress.showMinutes.value) Centre.Minutes(minutes) else icon(iconId)?.let { Centre.Icon(it) } ?: Centre.Minutes(minutes)

    const val TRY_ID = "progress-try"

    /** The ring for [item]: minutes in the middle when known (and wanted), else the stage's icon, else the percent. */
    fun animation(item: Item): Animation {
        val p = AppGraph.profile
        val r = item.reading
        if (r.source == ProgressSource.WAITING) return Progress.breathing(p)
        val minutes = r.minutesLeft?.takeIf { AppGraph.liveProgress.showMinutes.value && it in 1..999 }
        if (item.done) return LiveRing.done(p, icon(LiveProgress.icon(Stage.DONE, item.app, r.vehicle)))
        val iconId = LiveProgress.icon(r.stage, item.app, r.vehicle)
        val bar = r.bar
        val centre = when {
            minutes != null -> Centre.Minutes(minutes)
            bar != null && item.app == null -> Centre.Percent((bar * 100).toInt())
            iconId != null -> icon(iconId)?.let { Centre.Icon(it) } ?: Centre.None
            bar != null -> Centre.Percent((bar * 100).toInt())
            else -> Centre.None
        }
        return LiveRing.animation(p, item.track.fraction, centre, r.arriving)
    }

    private fun icon(id: String?): Frame? = LiveRing.icon(AppGraph.profile, id)

    /** Every 30 s while anything shows: read the stored words again (minutes move on with the clock). */
    private fun keepTicking() {
        if (ticker?.isActive == true) return
        ticker = MainScope().launch {
            while (isActive && _items.value.isNotEmpty()) {
                delay(TICK_MS)
                val old = _items.value.filter { it.doneAt != null && now() - it.doneAt >= DONE_KEEP_MS }
                if (old.isNotEmpty()) _items.update { list -> list - old.toSet() }
                for (item in _items.value) {
                    if (item.done) continue
                    val reading = LiveProgress.read(item.signals, now(), zone()) ?: continue
                    if (reading.done || reading.cancelled) continue
                    val track = LiveProgress.track(item.track, reading, item.app?.kind ?: TripKind.DELIVERY, now())
                    val next = item.copy(reading = reading, track = track)
                    _items.update { list -> list.map { if (it.key == item.key) next else it } }
                    post(next)
                }
            }
        }
    }

    /** Tests and Home's preview: a ring as it would show, without a notification. */
    @androidx.annotation.VisibleForTesting
    fun put(item: Item) {
        _items.update { list -> listOf(item) + list.filterNot { it.key == item.key } }
    }

    @androidx.annotation.VisibleForTesting
    fun reset() {
        ticker?.cancel()
        ticker = null
        _items.value = emptyList()
        doneShown.clear()
        hidden.clear()
        lastSeen.clear()
        now = System::currentTimeMillis
        zone = ZoneId::systemDefault
    }

    private const val TICK_MS = 30_000L
    /** A done row stays under Now this long. */
    const val DONE_KEEP_MS = 10 * 60_000L
}
