package com.dotforge.system

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.dotforge.AppGraph
import com.dotforge.device.AlertEffects
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.engine.Until
import com.dotforge.notify.AlertRuntime
import com.dotforge.rules.Moment
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import kotlin.math.sqrt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Alerts > Apps > Phone moments: the five moments (unlock, shake, camera, alarm, headphones), and
 * the approaching ring before the next calendar event or alarm (Reminders & to-dos). Each source is listened
 * to only while its row is on. None needs a new permission (the calendar is read only when already allowed).
 */
class PhoneMoments(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var on: Set<Moment> = emptySet()

    fun start() {
        MainScope().launch {
            AppGraph.rules.rules.collect { rules ->
                val now = rules.mapNotNull { r -> r.moment?.takeIf { r.enabled } }.toSet()
                if (now != on) update(now)
            }
        }
        MainScope().launch {
            kotlinx.coroutines.flow.combine(AppGraph.liveProgress.calendarLead, AppGraph.liveProgress.alarmLead, AppGraph.progressRings) { a, b, c -> Triple(a, b, c) }
                .collect { approach() }
        }
    }

    /** Live progress was switched on or off: the event and alarm ring follows. */
    fun refreshApproach() {
        main.post { approach() }
    }

    private fun rule(m: Moment): Rule? = AppGraph.rules.rules.value.firstOrNull { it.moment == m && it.enabled }

    /** [m] happened ([active] false: a moment that lasts has ended). */
    fun fire(m: Moment, active: Boolean = true) {
        val id = "moment:${m.name}"
        if (!active) {
            AppGraph.arbiter.remove(id)
            return
        }
        val r = rule(m) ?: return
        val info = NotificationInfo(id, "", m.label, m.label, "", System.currentTimeMillis())
        if (m.lasts && r.until != Until.TIMEOUT) {
            val anim = RuleEngine.animation(r, info, AppGraph.profile, AppGraph::designAnimation, AlertRuntime::appIcon)
            AppGraph.arbiter.post(DisplayRequest(id, Priority.NOTIFICATION, anim, loop = true, timeoutMs = LASTS_MAX_MS, label = m.label))
        } else {
            AppGraph.alerts.offer(RuleEngine.alert(r, info.copy(key = "$id:${info.postedAt}"), AppGraph.profile, AppGraph::designAnimation, AppGraph::designTiming, appIcon = AlertRuntime::appIcon).copy(until = Until.TIMEOUT))
        }
    }

    private fun update(now: Set<Moment>) {
        val was = on
        on = now
        fun changed(m: Moment) = (m in now) != (m in was)
        if (changed(Moment.HEADPHONES)) if (Moment.HEADPHONES in now) audio.registerAudioDeviceCallback(devices, main) else audio.unregisterAudioDeviceCallback(devices)
        if (changed(Moment.ALARM)) if (Moment.ALARM in now) audio.registerAudioPlaybackCallback(playback, main) else audio.unregisterAudioPlaybackCallback(playback)
        if (changed(Moment.CAMERA)) {
            val cm = ctx.getSystemService(CameraManager::class.java)
            if (Moment.CAMERA in now) cm.registerAvailabilityCallback(camera, main) else cm.unregisterAvailabilityCallback(camera)
        }
        if (changed(Moment.SHAKE)) {
            val sm = ctx.getSystemService(SensorManager::class.java)
            if (Moment.SHAKE in now) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shake, it, SensorManager.SENSOR_DELAY_UI) }
            else sm.unregisterListener(shake)
        }
        (was - now).filter { it.lasts }.forEach { fire(it, active = false) }
    }

    private val devices = object : AudioDeviceCallback() {
        private var first = true
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            // the callback reports what is already connected when it is registered: not a moment
            if (first) { first = false; return }
            if (added.any { it.isSink && it.type in HEADPHONES }) fire(Moment.HEADPHONES)
        }
    }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            fire(Moment.ALARM, configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM })
        }
    }

    private val camera = object : CameraManager.AvailabilityCallback() {
        private val open = HashSet<String>()
        override fun onCameraUnavailable(cameraId: String) {
            if (open.isEmpty()) fire(Moment.CAMERA)
            open += cameraId
        }
        override fun onCameraAvailable(cameraId: String) {
            open -= cameraId
            if (open.isEmpty()) fire(Moment.CAMERA, active = false)
        }
    }

    /** Two jolts over 2.5 g within 800 ms. */
    private val shake = object : SensorEventListener {
        private var last = 0L
        private var quietUntil = 0L
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
            if (g < 2.5f) return
            val t = System.currentTimeMillis()
            if (t < quietUntil) return
            if (t - last in 120..800) {
                quietUntil = t + 2_000
                last = 0
                fire(Moment.SHAKE)
            } else last = t
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ---------------------------------------------------------------- the approaching ring

    private var approachTick: Runnable? = null

    /**
     * Features > Live progress > Next calendar event and Next alarm: the ring fills over the lead time before
     * whichever comes first, minutes in the middle, and breathes in the last minute (the clock icon then).
     */
    private fun approach() {
        approachTick?.let(main::removeCallbacks)
        approachTick = null
        val calendar = AppGraph.liveProgress.calendarLead.value.coerceAtLeast(0) * 60_000L
        val alarm = AppGraph.liveProgress.alarmLead.value.coerceAtLeast(0) * 60_000L
        if (!AppGraph.progressRings.value || (calendar == 0L && alarm == 0L)) {
            AppGraph.arbiter.remove(APPROACH_ID)
            return
        }
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val next = listOfNotNull(
                    nextAlarm()?.takeIf { alarm > 0 }?.let { Triple(it, alarm, "alarm") },
                    nextEvent(now)?.takeIf { calendar > 0 }?.let { Triple(it, calendar, "event") },
                ).filter { it.first > now }.mapNotNull { (at, lead, what) -> AlertEffects.approach(at - now, lead)?.let { Triple(at, it, what) } }
                    .minByOrNull { it.first }
                if (next == null) AppGraph.arbiter.remove(APPROACH_ID)
                else {
                    val (at, a, what) = next
                    val minutes = kotlin.math.ceil((at - now) / 60_000.0).toInt()
                    val icon = com.dotforge.live.LiveRing.icon(AppGraph.profile, if (what == "alarm") "g-alarm" else "g-calendar")
                    val label = (if (what == "alarm") "Alarm" else "Next event") + " in $minutes min"
                    AppGraph.arbiter.post(DisplayRequest(APPROACH_ID, Priority.PROGRESS, com.dotforge.live.LiveRing.approaching(AppGraph.profile, a.fraction, minutes, icon), loop = true, label = label))
                }
                main.postDelayed(this, if (next?.second?.pulse == true) 5_000 else 30_000)
            }
        }
        approachTick = tick
        main.post(tick)
    }

    private fun nextAlarm(): Long? = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime

    private fun nextEvent(now: Long): Long? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now)
            android.content.ContentUris.appendId(it, now + 2 * 3_600_000L)
        }.build()
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                var found: Long? = null
                while (c.moveToNext() && found == null) if (c.getInt(1) == 0 && c.getLong(0) > now) found = c.getLong(0)
                found
            }
        }.getOrNull()
    }

    private companion object {
        const val APPROACH_ID = "approach"
        const val LASTS_MAX_MS = 30 * 60_000L
        val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
