package com.dotforge.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.Timings
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a battery change should show (pure, unit tested). */
enum class BatteryEvent { PLUGGED, FULL, LOW }

object BatteryEvents {
    const val LOW = 15
    const val FULL = 100

    /** The event between two readings ([level] in percent); null for none. The first reading ([before] null) shows nothing. */
    fun between(before: Pair<Int, Boolean>?, level: Int, charging: Boolean): BatteryEvent? {
        val (was, wasCharging) = before ?: return null
        return when {
            charging && !wasCharging -> BatteryEvent.PLUGGED
            charging && level >= FULL && was < FULL -> BatteryEvent.FULL
            !charging && level <= LOW && was > LOW -> BatteryEvent.LOW
            else -> null
        }
    }

    /** A shake: the acceleration's change between two readings, in m/s². */
    fun isShake(last: FloatArray?, now: FloatArray, threshold: Float = 12f): Boolean {
        last ?: return false
        val d = sqrt((0..2).sumOf { ((now[it] - last[it]) * (now[it] - last[it])).toDouble() })
        return d > threshold
    }

    /** Two shakes within [windowMs] make a torch toggle; returns the new list of recent shake times. */
    fun shakeTimes(times: List<Long>, now: Long, windowMs: Long = 1_000): List<Long> = (times.filter { now - it <= windowMs } + now)

    fun isDoubleShake(times: List<Long>): Boolean = times.size >= 2

    /** The battery meter: the ring for [percent] with the number inside. */
    fun meter(p: DeviceProfile, percent: Int): Animation {
        val f = Effects.ring(p, percent / 100f).overlay(Effects.centredDigits(p, listOf("$percent")), 200)
        return Animation.single(f, 2500)
    }

    /** Full: the ring blinks bright three times. Low: a short ring blinks red-hot (dim, bright) four times. */
    fun alarm(p: DeviceProfile, event: BatteryEvent, percent: Int): Animation {
        val ring = Effects.ring(p, percent / 100f)
        val off = Frame.blank(p)
        val n = if (event == BatteryEvent.LOW) 4 else 3
        return Animation(p, List(n) { listOf(AnimFrame(ring, 250), AnimFrame(off, 200)) }.flatten() + AnimFrame(meter(p, percent).frames[0].frame, 1500))
    }

    /** The volume ring: [level] of [max] on the outer ring. */
    fun volume(p: DeviceProfile, level: Int, max: Int): Frame =
        Effects.ring(p, if (max <= 0) 0f else level.toFloat() / max, off = 25)

    /** 15 W or more counts as a fast charger. */
    const val FAST_WATTS = 15.0

    /**
     * A fast charger, from the battery broadcast's most current (µA) and voltage (µV) the charger offers; a
     * voltage of 0 (not reported) counts as 5 V.
     */
    fun isFast(maxCurrentUa: Int, maxVoltageUv: Int): Boolean {
        if (maxCurrentUa <= 0) return false
        val volts = (if (maxVoltageUv > 0) maxVoltageUv else 5_000_000) / 1e6
        return maxCurrentUa / 1e6 * volts >= FAST_WATTS
    }

    /** Steps of the ring filling up, and how long each shows at 1x. */
    const val FILL_STEPS = 12
    const val FILL_STEP_MS = 60
    /** How long the full design holds before it plays again. */
    const val HOLD_MS = 2_500

    /**
     * The charging design at 1x. [ChargingStyle.FILL]: the ring fills up to [percent], then holds.
     * [ChargingStyle.LEVEL]: the ring and the number. [ChargingStyle.MINE]: [mine] (your design) as it is, then its
     * fullest frame held; without one, the ring and the number. On a [fast] charger the design then pulses twice.
     */
    fun charging(p: DeviceProfile, style: ChargingStyle, percent: Int, fast: Boolean, mine: Animation? = null): Animation {
        val level = percent.coerceIn(0, 100) / 100f
        val own = mine?.takeIf { style == ChargingStyle.MINE }
        val full = when {
            own != null -> own.frames.maxBy { f -> f.frame.pixels.sum() }.frame
            style == ChargingStyle.FILL -> Effects.ring(p, level)
            else -> meter(p, percent).frames[0].frame
        }
        val frames = mutableListOf<AnimFrame>()
        if (own != null && own.frames.size > 1) frames += own.frames
        if (style == ChargingStyle.FILL) {
            for (i in 1 until FILL_STEPS) frames += AnimFrame(Effects.ring(p, level * i / FILL_STEPS), FILL_STEP_MS)
        }
        frames += AnimFrame(full, HOLD_MS)
        if (fast) repeat(2) {
            frames += AnimFrame(full.scaled(PULSE_LOW), 220)
            frames += AnimFrame(full, 220)
        }
        return Animation(p, frames)
    }

    private const val PULSE_LOW = 0.3f

    /** Steps of the volume ring sliding from the old level to the new one, each this long at 1x. */
    const val SLIDE_STEP_MS = 40

    /** The volume design at 1x: the ring slides from [from] to [to] (of [max]), then holds. */
    fun volumeSlide(p: DeviceProfile, from: Int, to: Int, max: Int): Animation {
        val a = from.coerceIn(0, max.coerceAtLeast(0))
        val b = to.coerceIn(0, max.coerceAtLeast(0))
        val steps = 4
        val slide = if (a == b) emptyList() else (1 until steps).map { i ->
            AnimFrame(Effects.ring(p, if (max <= 0) 0f else (a + (b - a) * i / steps.toFloat()) / max, off = 25), SLIDE_STEP_MS)
        }
        return Animation(p, slide + AnimFrame(volume(p, b, max), Animation.MAX_FRAME_MS))
    }
}

/** The switches on the Battery and volume page. All start off. */
class SystemGlyphSettings(private val prefs: SharedPreferences) {
    private fun flow(key: String) = MutableStateFlow(prefs.getBoolean(key, false))
    private val _charging = flow(KEY_CHARGING)
    private val _alarms = flow(KEY_ALARMS)
    private val _wiggle = flow(KEY_WIGGLE)
    private val _volume = flow(KEY_VOLUME)
    private val _torch = flow(KEY_TORCH)
    private val _torchFirm = flow(KEY_TORCH_FIRM)
    /** Shake twice to switch the glyph torch on or off. */
    val shakeTorch: StateFlow<Boolean> = _torch.asStateFlow()
    /** Needs a harder shake. */
    val shakeTorchFirm: StateFlow<Boolean> = _torchFirm.asStateFlow()
    /** Show the battery meter when the charger goes in. */
    val chargingMeter: StateFlow<Boolean> = _charging.asStateFlow()
    /** Full and low battery flashes. */
    val alarms: StateFlow<Boolean> = _alarms.asStateFlow()
    /** While charging, a shake shows the meter. */
    val wiggle: StateFlow<Boolean> = _wiggle.asStateFlow()
    val volume: StateFlow<Boolean> = _volume.asStateFlow()

    private fun set(f: MutableStateFlow<Boolean>, key: String, v: Boolean) {
        prefs.edit().putBoolean(key, v).apply()
        f.value = v
    }

    fun setChargingMeter(v: Boolean) = set(_charging, KEY_CHARGING, v)
    fun setAlarms(v: Boolean) = set(_alarms, KEY_ALARMS, v)
    fun setWiggle(v: Boolean) = set(_wiggle, KEY_WIGGLE, v)
    fun setVolume(v: Boolean) = set(_volume, KEY_VOLUME, v)
    fun setShakeTorch(v: Boolean) = set(_torch, KEY_TORCH, v)
    fun setShakeTorchFirm(v: Boolean) = set(_torchFirm, KEY_TORCH_FIRM, v)

    private companion object {
        const val KEY_CHARGING = "charging_meter"
        const val KEY_ALARMS = "battery_alarms"
        const val KEY_WIGGLE = "charging_wiggle"
        const val KEY_VOLUME = "volume_ring"
        const val KEY_TORCH = "shake_torch"
        const val KEY_TORCH_FIRM = "shake_torch_firm"
    }
}

/**
 * Battery and volume glyphs, run in the app process (kept alive by the notification listener): the meter
 * when the charger goes in or the phone is shaken while charging, full and low flashes, and a volume ring.
 */
class SystemGlyphs(private val context: Context, private val settings: SystemGlyphSettings) : SensorEventListener {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var last: Pair<Int, Boolean>? = null
    private var lastAccel: FloatArray? = null
    private var lastShakeAt = 0L
    private var listening = false
    private var lastVolume = -1
    private var torchListening = false
    private var torchLast: FloatArray? = null
    private var shakes = emptyList<Long>()
    private var lastToggle = 0L
    /** The charger's speed from the last battery broadcast. */
    private var fast = false
    /** The charging design shows until the charger comes out. */
    private var chargingHeld = false
    private val again = Runnable { if (last?.second == true) showCharging() }

    private val torchShake = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = e.values.copyOf()
            val threshold = if (settings.shakeTorchFirm.value) 20f else 14f
            val shook = BatteryEvents.isShake(torchLast, now, threshold)
            torchLast = now
            if (!shook) return
            val t = System.currentTimeMillis()
            if (t - lastToggle < 1_500) return // one shake is several readings
            shakes = BatteryEvents.shakeTimes(shakes, t)
            if (BatteryEvents.isDoubleShake(shakes) && shakes.last() - shakes.first() > 250) {
                shakes = emptyList()
                lastToggle = t
                com.dotforge.tile.TorchTileService.toggle()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val level = (i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100f / scale).roundToInt()
            val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            fast = charging && BatteryEvents.isFast(i.getIntExtra(EXTRA_MAX_CURRENT, 0), i.getIntExtra(EXTRA_MAX_VOLTAGE, 0))
            if (!charging && last?.second == true) unplugged()
            when (BatteryEvents.between(last, level, charging)) {
                BatteryEvent.PLUGGED -> {
                    // Nothing's charging light covers the Glyph for a few seconds; the arbiter re-sends after
                    com.dotforge.engine.NothingLights.covered("charging", com.dotforge.engine.NothingLights.CHARGING_MS)
                    AppGraph.arbiter.refreshAfter(com.dotforge.engine.NothingLights.CHARGING_MS)
                    last = level to charging
                    if (settings.chargingMeter.value) showCharging()
                }
                BatteryEvent.FULL -> if (settings.alarms.value) show(BatteryEvents.alarm(AppGraph.profile, BatteryEvent.FULL, level))
                BatteryEvent.LOW -> if (settings.alarms.value) show(BatteryEvents.alarm(AppGraph.profile, BatteryEvent.LOW, level))
                null -> Unit
            }
            last = level to charging
            listenForShake(charging && settings.wiggle.value)
        }
    }

    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (!settings.volume.value) return
            val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (v == lastVolume) return
            val before = lastVolume
            lastVolume = v
            val p = AppGraph.profile
            val t = AppGraph.features.timing(FeatureTiming.VOLUME).value
            val f = AppGraph.features
            val mine = if (f.volumeStyle.value == com.dotforge.system.VolumeStyle.MINE) AppGraph.designAnimation(f.volumeDesign.value) else null
            val plan = Timings.plan(mine ?: BatteryEvents.volumeSlide(p, before, v, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), t)
            AppGraph.arbiter.post(
                DisplayRequest(VOLUME_ID, Priority.NOTIFICATION, plan.animation, loop = false, timeoutMs = plan.showMs ?: (t.seconds * 1_000L), label = "Volume"),
            )
        }
    }

    fun start() {
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    /** Re-reads the switches (starts or stops the shake sensors). */
    fun refresh() {
        val charging = last?.second == true
        listenForShake(charging && settings.wiggle.value)
        val torch = settings.shakeTorch.value
        if (torch != torchListening) {
            torchListening = torch
            torchLast = null
            if (torch) sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(torchShake, it, SensorManager.SENSOR_DELAY_GAME) }
            else sensors?.unregisterListener(torchShake)
        }
    }

    private fun listenForShake(on: Boolean) {
        if (on == listening) return
        listening = on
        lastAccel = null
        if (on) sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        else sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = e.values.copyOf()
        val shook = BatteryEvents.isShake(lastAccel, now)
        lastAccel = now
        val t = System.currentTimeMillis()
        if (shook && abs(t - lastShakeAt) > 3_000) {
            lastShakeAt = t
            last?.let { show(BatteryEvents.meter(AppGraph.profile, it.first)) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * The charging design with the Charging page's timing: for a few seconds, or while plugged in; "Repeat"
     * shows it again every few minutes while charging.
     */
    private fun showCharging() {
        val (level, _) = last ?: return
        val f = AppGraph.features
        val t = f.timing(FeatureTiming.CHARGING).value
        val mine = if (f.chargingStyle.value == ChargingStyle.MINE) AppGraph.designAnimation(f.chargingDesign.value) else null
        val a = BatteryEvents.charging(AppGraph.profile, f.chargingStyle.value, level, fast && f.fastPulse.value, mine)
        // while plugged in it plays on (the fill comes round again) instead of resting on its first frame
        val plan = Timings.plan(a, t.copy(rest = false))
        chargingHeld = plan.untilChecked
        AppGraph.arbiter.post(DisplayRequest(BATTERY_ID, Priority.NOTIFICATION, plan.animation, loop = plan.loop, timeoutMs = plan.showMs, label = "Charging"))
        main.removeCallbacks(again)
        if (!plan.untilChecked && t.remindMinutes > 0) main.postDelayed(again, t.remindMinutes * 60_000L)
    }

    private fun unplugged() {
        main.removeCallbacks(again)
        if (chargingHeld) AppGraph.arbiter.remove(BATTERY_ID)
        chargingHeld = false
    }

    private fun show(a: Animation) {
        AppGraph.arbiter.post(DisplayRequest(BATTERY_ID, Priority.NOTIFICATION, a, timeoutMs = a.totalMs.toLong(), label = "Battery"))
    }

    companion object {
        const val BATTERY_ID = "battery"
        const val VOLUME_ID = "volume"
        /** Not in BatteryManager's public constants; most phones (Nothing's too) send them with ACTION_BATTERY_CHANGED. */
        private const val EXTRA_MAX_CURRENT = "max_charging_current"
        private const val EXTRA_MAX_VOLTAGE = "max_charging_voltage"
    }
}
