package com.dotforge.notify

import android.content.SharedPreferences
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Frame
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
import com.dotforge.live.TripKind
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

/**
 * Features > Live progress settings: which known apps light a ring (all by default), whether the middle shows
 * minutes left, and the rings before the next calendar event and the next alarm (minutes of lead, 0 = off).
 * The main switch is [AppGraph.progressRings]; other apps follow the "Show progress" rules (Any other app).
 */
class LiveProgressSettings(private val prefs: SharedPreferences) {
    private val _off = MutableStateFlow(prefs.getStringSet(KEY_OFF, emptySet()).orEmpty().toSet())
    /** Known apps switched off (stored as the ones off, so a new preset starts on). */
    val off: StateFlow<Set<String>> = _off.asStateFlow()

    private val _minutes = MutableStateFlow(prefs.getBoolean(KEY_MINUTES, true))
    val showMinutes: StateFlow<Boolean> = _minutes.asStateFlow()

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
        setCalendarLead(0)
        setAlarmLead(0)
    }

    companion object {
        /** Lead time chips on the page, in minutes (0 = off). */
        val LEADS = listOf(0, 5, 10, 15, 30)
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
    ) {
        val name: String get() = app?.name?.substringBefore(" /") ?: appLabel
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** What is in progress now, newest first (the page's "Now" rows). */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val doneShown = HashSet<String>()
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
        val prev = _items.value.firstOrNull { it.key == key }
        val track = LiveProgress.track(prev?.track, reading, app?.kind ?: TripKind.DELIVERY, now())
        val item = Item(key, pkg, label, app, s, reading, track)
        if (reading.done) {
            removed(key)
            if (doneShown.add(key)) AppGraph.arbiter.post(DisplayRequest(doneId(key), Priority.PROGRESS, LiveRing.done(AppGraph.profile, icon(LiveProgress.icon(reading.stage, app))), label = "Done: ${item.name}"))
            return true
        }
        doneShown.remove(key)
        _items.update { list -> listOf(item) + list.filterNot { it.key == key } }
        post(item)
        keepTicking()
        return true
    }

    /** The notification went (or stopped being progress). */
    fun removed(key: String) {
        if (_items.value.any { it.key == key }) _items.update { list -> list.filterNot { it.key == key } }
        AppGraph.arbiter.remove(id(key))
    }

    /** Live progress was switched off, or a known app was: take the rings off. */
    fun refresh() {
        val keep = _items.value.filter { AppGraph.progressRings.value && (it.app == null || AppGraph.liveProgress.appOn(it.app.id)) }
        (_items.value - keep.toSet()).forEach { removed(it.key) }
        keep.forEach(::post)
    }

    private fun post(item: Item) {
        AppGraph.arbiter.post(
            DisplayRequest(id(item.key), Priority.PROGRESS, animation(item), loop = true, label = "${item.name} · " + LiveProgress.summary(item.reading)),
        )
    }

    /** The ring for [item]: minutes in the middle when known (and wanted), else the stage's icon, else the percent. */
    fun animation(item: Item): Animation {
        val p = AppGraph.profile
        val r = item.reading
        if (r.source == ProgressSource.WAITING) return Progress.breathing(p)
        val minutes = r.minutesLeft?.takeIf { AppGraph.liveProgress.showMinutes.value && it in 1..999 }
        val iconId = LiveProgress.icon(r.stage, item.app)
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
                for (item in _items.value) {
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
        now = System::currentTimeMillis
        zone = ZoneId::systemDefault
    }

    private const val TICK_MS = 30_000L
}
