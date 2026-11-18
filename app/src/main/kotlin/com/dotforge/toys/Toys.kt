package com.dotforge.toys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.ToyFaces
import com.dotforge.visualiser.VisualiserEngine
import com.dotforge.visualiser.next
import java.time.LocalDateTime
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val INFO_MS = 3_000L

/** Big two-line dot clock (12 or 24 hour), redrawn each minute and on AOD ticks. Long press shows the date for 3 s. */
class ClockToy : GlyphToyService() {
    private var showDate = false
    private var revert: Job? = null

    override fun onToyStart() {
        toyScope.launch {
            while (isActive) {
                render()
                val now = LocalDateTime.now()
                delay((60 - now.second) * 1000L - now.nano / 1_000_000)
            }
        }
    }

    override fun onAodTick() = render()

    override fun onPress(press: Press) {
        if (press != Press.LONG) return
        showDate = true
        render()
        revert?.cancel()
        revert = toyScope.launch {
            delay(INFO_MS)
            showDate = false
            render()
        }
    }

    private fun render() {
        val now = LocalDateTime.now()
        show(
            if (showDate) Effects.clock(profile, now.dayOfMonth, now.monthValue)
            else ToyFaces.clock(profile, AppGraph.toys.clockStyle, now.hour, now.minute, AppGraph.toys.clock24h),
        )
    }
}

/** Battery ring, with a dot running along it while charging (optional). Long press shows the percentage for 3 s. */
class BatteryToy : GlyphToyService() {
    private var level = 0f
    private var charging = false
    private var showPercent = false
    private var phase = 0
    private var revert: Job? = null
    private var chargeAnim: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / scale.toFloat()
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            updateChargeAnimation()
            render()
        }
    }

    override fun onToyStart() {
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onToyStop() = unregisterReceiver(receiver)

    override fun onAodTick() = render()

    override fun onPress(press: Press) {
        if (press != Press.LONG) return
        showPercent = true
        render()
        revert?.cancel()
        revert = toyScope.launch {
            delay(INFO_MS)
            showPercent = false
            render()
        }
    }

    private fun updateChargeAnimation() {
        val animate = charging && AppGraph.toys.batteryChargeAnimation
        if (animate == (chargeAnim?.isActive == true)) return
        chargeAnim?.cancel()
        chargeAnim = if (!animate) null else toyScope.launch {
            while (isActive) {
                delay(120)
                phase++
                render()
            }
        }
    }

    private fun render() {
        val f = Effects.ring(profile, level)
        if (chargeAnim?.isActive == true) {
            val filled = (profile.ring.size * level).toInt().coerceAtLeast(1)
            for (i in 0 until filled) f.pixels[profile.ring[i]] = 140
            f.pixels[profile.ring[phase % filled]] = 255
        }
        if (showPercent) f.overlay(Effects.centredDigits(profile, listOf("${(level * 100).toInt()}")))
        show(f)
    }
}

/** Dice: the Glyph Button or (optional) a shake rolls it. */
class DiceToy : GlyphToyService(), SensorEventListener {
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var lastShake = 0L

    override fun onToyStart() {
        show(Effects.dice(profile, 6))
        if (!AppGraph.toys.diceShake) return
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onToyStop() = sensors.unregisterListener(this)

    override fun onPress(press: Press) {
        if (press != Press.DOUBLE) roll() // a double press already rolled on its first press
    }

    private fun roll() {
        val tumble = (0 until 8).map { AnimFrame(Effects.dice(profile, Random.nextInt(1, 7)), 60 + it * 15) }
        play(Animation(profile, tumble + AnimFrame(Effects.dice(profile, Random.nextInt(1, 7)), 400)))
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = e.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > SHAKE_G && now - lastShake > SHAKE_GAP_MS) {
            lastShake = now
            roll()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SHAKE_G = 2.2f
        const val SHAKE_GAP_MS = 1_200L
    }
}

/** Loops the chosen design from the Create tab (a ring chase until one is saved). Long press pauses, double press restarts. */
class PlayerToy : GlyphToyService() {
    private var paused = false

    override fun onToyStart() = start()

    private fun start() = play(AppGraph.playerAnimation() ?: Effects.ringChase(profile), loop = true)

    override fun onPress(press: Press) {
        when (press) {
            Press.LONG -> {
                paused = !paused
                if (paused) stopAnimation() else start()
            }
            Press.DOUBLE -> {
                paused = false
                start()
            }
            Press.SHORT -> Unit
        }
    }

    override fun onAodTick() {
        if (!paused && !isPlaying) start()
    }
}

/**
 * Music visualiser toy: bars, a ring pulse or a radial spectrum from what is playing (long press: next style).
 * Needs the microphone permission, given in DotForge; until then it scrolls a hint.
 */
class MusicToy : GlyphToyService() {
    private var engine: VisualiserEngine? = null

    override fun onToyStart() {
        val e = VisualiserEngine(this, profile, { AppGraph.toys.visualStyle }) { pixels ->
            toyScope.launch { show(Frame.of(profile, pixels)) }
        }
        if (!e.hasPermission() || !e.start()) {
            e.close()
            play(Effects.marquee(profile, "ALLOW MICROPHONE IN DOTFORGE"), loop = true)
            return
        }
        engine = e
    }

    override fun onPress(press: Press) {
        if (press == Press.LONG) AppGraph.toys.visualStyle = AppGraph.toys.visualStyle.next()
    }

    override fun onToyStop() {
        engine?.close()
        engine = null
    }
}
