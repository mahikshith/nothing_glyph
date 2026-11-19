package com.dotforge.toys

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import com.dotforge.AppGraph
import com.dotforge.device.DayDots
import com.dotforge.device.Frame
import com.dotforge.device.OpenMeteo
import com.dotforge.device.TiltMaze
import com.dotforge.device.WeatherFace
import com.dotforge.device.WeatherNow
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DF Weather (parity row 89): the sky's icon over the temperature, from Open-Meteo (no key, no server of ours).
 * The place is where the phone roughly is (coarse location, at most once an hour) or a typed place. Press: today's
 * chance of rain for 3 s, when "Show rain chance" is on. Long press: fetch again. Shows on always-on too.
 */
class WeatherToy : GlyphToyService() {
    private var rain: Job? = null

    override fun onToyStart() {
        render()
        toyScope.launch { refresh(force = false) }
    }

    override fun onAodTick() {
        render()
        toyScope.launch { refresh(force = false) }
    }

    override fun onPress(press: Press) {
        when (press) {
            Press.SHORT, Press.DOUBLE -> {
                val w = Weather.cached() ?: return
                if (!AppGraph.toys.weatherRain || w.rainPct == null) return
                show(WeatherFace.rain(profile, w.rainPct!!))
                rain?.cancel()
                rain = toyScope.launch {
                    delay(3_000)
                    render()
                }
            }
            Press.LONG -> toyScope.launch { refresh(force = true) }
        }
    }

    private fun render() {
        show(Weather.cached()?.let { WeatherFace.frame(profile, it, AppGraph.toys.weatherFahrenheit) } ?: WeatherFace.waiting(profile))
    }

    private suspend fun refresh(force: Boolean) {
        if (Weather.fetch(this, force)) render()
    }
}

/** Fetching and caching DF Weather's reading (an hour at most between fetches). */
object Weather {
    const val MAX_AGE_MS = 60 * 60_000L
    /** After a try without a connection, the next try waits this long (a press of Long press forces one). */
    const val RETRY_MS = 10 * 60_000L

    fun cached(): WeatherNow? = AppGraph.toys.weatherJson?.let(OpenMeteo::parse)

    /** True when a new reading came in. */
    suspend fun fetch(ctx: Context, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        val toys = AppGraph.toys
        val now = System.currentTimeMillis()
        if (!force && now - toys.weatherAt < MAX_AGE_MS && toys.weatherJson != null) return@withContext false
        // offline a moment ago: wait a little before trying again
        if (!force && now - toys.weatherOfflineAt < RETRY_MS) return@withContext false
        val (lat, lon) = place(ctx) ?: return@withContext false
        val body = get(OpenMeteo.forecastUrl(lat, lon))
        if (body == null) {
            toys.weatherOfflineAt = System.currentTimeMillis()
            return@withContext false
        }
        if (OpenMeteo.parse(body) == null) return@withContext false
        toys.weatherJson = body
        toys.weatherAt = System.currentTimeMillis()
        toys.weatherOfflineAt = 0L
        true
    }

    /** The typed place (looked up once), else the phone's last coarse location. */
    private fun place(ctx: Context): Pair<Double, Double>? {
        val toys = AppGraph.toys
        toys.weatherPlace?.takeIf { it.isNotBlank() }?.let { typed ->
            toys.weatherPlaceAt?.let { return it }
            val found = get(OpenMeteo.geocodeUrl(typed))?.let(OpenMeteo::parsePlace) ?: return null
            toys.weatherPlaceAt = found.first to found.second
            return found.first to found.second
        }
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = runCatching {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return loc.latitude to loc.longitude
    }

    private fun get(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000
            readTimeout = 8_000
            try {
                if (responseCode != 200) null else inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }.getOrNull()
}

/**
 * DF Day dots (parity row 90): one dot for every 10 minutes the screen was on today, the goal's dots faint.
 * Needs Usage access, asked on the toy's page when it is first switched on. A still frame, redrawn each minute.
 */
class DayDotsToy : GlyphToyService() {
    override fun onToyStart() {
        toyScope.launch {
            while (isActive) {
                render()
                delay(60_000)
            }
        }
    }

    override fun onAodTick() {
        toyScope.launch { render() }
    }

    private suspend fun render() {
        val minutes = withContext(Dispatchers.IO) { DayUsage.minutesToday(this@DayDotsToy) }
        show(if (minutes == null) noAccess() else DayDots.frame(profile, minutes, AppGraph.toys.dayGoalMinutes))
    }

    /** Without Usage access: the goal's dots, faint, and nothing lit. */
    private fun noAccess(): Frame = DayDots.frame(profile, 0, AppGraph.toys.dayGoalMinutes)
}

/** Screen time today from Android's usage events. */
object DayUsage {
    fun allowed(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** The day starts at the reset hour (midnight by default); before it, yesterday's day is still running. */
    fun dayStart(now: LocalDateTime, resetHour: Int): LocalDateTime {
        val today = now.toLocalDate().atTime(resetHour.coerceIn(0, 23), 0)
        return if (now.isBefore(today)) today.minusDays(1) else today
    }

    /** Minutes the screen was on since the day started; null without Usage access. */
    fun minutesToday(ctx: Context): Int? {
        if (!allowed(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val start = dayStart(LocalDateTime.now(), AppGraph.toys.dayResetHour).atZone(zone).toInstant().toEpochMilli()
        val events = mutableListOf<Pair<Long, Boolean>>()
        val list = runCatching { usm.queryEvents(start, now) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        while (list.hasNextEvent()) {
            list.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> events += e.timeStamp to true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> events += e.timeStamp to false
            }
        }
        return (DayDots.screenOnMs(events, start, now) / 60_000).toInt()
    }
}

/**
 * DF Tilt maze (parity row 91): tilt the phone to roll the ball to the blinking goal. Easy or Hard. A round
 * lasts at most [ROUND_MS] (tilting doesn't keep a toy alive, so it never meets the 30 s toy timeout); a
 * press starts again.
 */
class TiltMazeToy : GlyphToyService(), SensorEventListener {
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var maze = TiltMaze(AppGraph.toys.mazeLevel)
    @Volatile private var tx = 0f
    @Volatile private var ty = 0f
    private var round: Job? = null

    override fun onToyStart() {
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        start()
    }

    override fun onToyStop() {
        sensors?.unregisterListener(this)
    }

    override fun onPress(press: Press) = start()

    private fun start() {
        maze = TiltMaze(AppGraph.toys.mazeLevel)
        round?.cancel()
        round = toyScope.launch {
            val began = SystemClock.uptimeMillis()
            var tick = 0
            while (isActive) {
                // the Glyph faces away from the screen: seen from the back, the phone's x axis runs the other way,
                // which the accelerometer's sign already gives (the high side reads positive)
                maze.step(tx, ty)
                show(maze.frame(profile, blink = tick++ / 3 % 2 == 0))
                if (maze.won) {
                    repeat(4) {
                        show(maze.frame(profile, blink = true).also { it.overlay(com.dotforge.device.Effects.ring(profile, 1f), 255) })
                        delay(200)
                        show(maze.frame(profile, blink = true))
                        delay(200)
                    }
                    start()
                    return@launch
                } else if (SystemClock.uptimeMillis() - began > ROUND_MS) {
                    // out of time: the ball goes back to the start
                    show(com.dotforge.device.Effects.ring(profile, 1f, on = 90))
                    delay(400)
                    start()
                    return@launch
                }
                delay(STEP_MS)
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        tx = e.values[0]
        ty = e.values[1]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val ROUND_MS = 25_000L
        const val STEP_MS = 140L
    }
}
