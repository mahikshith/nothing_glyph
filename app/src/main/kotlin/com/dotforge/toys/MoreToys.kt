package com.dotforge.toys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.SystemClock
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.SnakeGame
import com.dotforge.device.ToyFaces
import com.dotforge.notify.GlyphNotificationListener
import com.dotforge.notify.Progress
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val INFO_MS = 3_000L

/** Delay to the next wall-clock minute. */
private fun toNextMinute(): Long = LocalDateTime.now().let { (60 - it.second) * 1000L - it.nano / 1_000_000 }

/** The playing (else latest) media session, read through DotForge's notification listener; empty without access. */
internal fun mediaController(ctx: Context): MediaController? = runCatching {
    val list = ctx.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(ctx, GlyphNotificationListener::class.java))
    list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
}.getOrNull()

internal fun mediaPosition(c: MediaController): Float? {
    val s = c.playbackState ?: return null
    return Progress.media(
        s.position, c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L, s.lastPositionUpdateTime,
        SystemClock.elapsedRealtime(), s.playbackSpeed, s.state == PlaybackState.STATE_PLAYING,
    )
}

/** Battery level 0..1 from the sticky battery broadcast. */
internal fun batteryLevel(ctx: Context): Float? {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    return if (level < 0 || scale <= 0) null else level.toFloat() / scale
}

/** Designs DF Gallery shows: the chosen ones, else all; each as its animation. */
internal fun galleryAnimations(): List<Animation> {
    val ids = AppGraph.toys.galleryIds
    val designs = AppGraph.designs.designs.value
    return (designs.filter { it.id in ids }.ifEmpty { designs }).mapNotNull(AppGraph.designs::animation)
}

/** Design carousel: plays the gallery designs in turn; on the always-on display, one design a minute. */
class GalleryToy : GlyphToyService() {
    private var index = 0
    private var paused = false
    private var cycle: Job? = null

    override fun onToyStart() = next(0)

    private fun next(step: Int) {
        val list = galleryAnimations()
        if (list.isEmpty()) return play(Effects.ringChase(profile), loop = true)
        index = Math.floorMod(index + step, list.size)
        val a = list[index]
        cycle?.cancel()
        // a design plays for at least 8 s (whole loops), then the next one
        val loops = (8_000 / a.totalMs.coerceAtLeast(1)).coerceAtLeast(1)
        play(Animation(profile, List(loops) { a.frames }.flatten()))
        cycle = toyScope.launch {
            delay(loops * a.totalMs.toLong())
            if (!paused) next(1)
        }
    }

    override fun onPress(press: Press) = when (press) {
        Press.SHORT -> next(1)
        Press.LONG -> { paused = !paused; if (!paused) next(1) else cycle?.cancel(); Unit }
        Press.DOUBLE -> next(-2) // the first press already went forward
    }

    override fun onAodTick() {
        stopAnimation()
        val list = galleryAnimations()
        if (list.isEmpty()) return
        index = (index + 1) % list.size
        show(list[index].frames.first().frame)
    }
}

/**
 * Always-on hub: one toy for the always-on display that shows a chosen face (clock with a battery ring,
 * gallery, moon, now playing). Long press, its settings or the Quick Settings tile switches the face.
 */
class HubToy : GlyphToyService() {
    private var stopListening: (() -> Unit)? = null
    private var tick: Job? = null
    private var galleryIndex = 0

    override fun onToyStart() {
        stopListening = AppGraph.toys.listen { toyScope.launch { render() } }
        tick = toyScope.launch {
            while (isActive) {
                render()
                delay(if (AppGraph.toys.hubFace == HubFace.NOW_PLAYING) 1_000 else toNextMinute())
            }
        }
    }

    override fun onToyStop() {
        stopListening?.invoke()
    }

    override fun onPress(press: Press) {
        if (press == Press.LONG) AppGraph.toys.hubFace = AppGraph.toys.hubFace.next() // the listener redraws
    }

    override fun onAodTick() {
        galleryIndex++
        render()
    }

    private fun clockFace(): Frame {
        val now = LocalDateTime.now()
        val f = ToyFaces.clock(profile, AppGraph.toys.clockStyle, now.hour, now.minute, AppGraph.toys.clock24h)
        if (AppGraph.toys.hubBattery) batteryLevel(this)?.let { level ->
            val ring = Effects.ring(profile, level, on = 110, off = 0)
            for (cell in profile.ring) if (f.pixels[cell] == 0) f.pixels[cell] = ring.pixels[cell]
        }
        return f
    }

    private fun render() {
        val frame = when (AppGraph.toys.hubFace) {
            HubFace.CLOCK -> clockFace()
            HubFace.MOON -> ToyFaces.moon(profile, ToyFaces.moonAge(LocalDate.now().toEpochDay() + 0.5))
            HubFace.GALLERY -> galleryAnimations().let { list ->
                if (list.isEmpty()) clockFace() else list[Math.floorMod(galleryIndex, list.size)].frames.first().frame
            }
            HubFace.NOW_PLAYING -> mediaController(this)?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?.let { ToyFaces.nowPlaying(profile, true, mediaPosition(it)) } ?: clockFace()
        }
        show(frame)
    }
}

/** Now playing: play / pause and the track position. Press: play or pause. Long press: next track. */
class NowPlayingToy : GlyphToyService() {
    override fun onToyStart() {
        toyScope.launch {
            while (isActive) {
                render()
                delay(1_000)
            }
        }
    }

    private fun render() {
        val c = mediaController(this)
        show(
            if (c == null) Frame.of(profile, com.dotforge.device.DotText.centered(profile.size, "--"))
            else ToyFaces.nowPlaying(profile, c.playbackState?.state == PlaybackState.STATE_PLAYING, mediaPosition(c)),
        )
    }

    override fun onPress(press: Press) {
        val c = mediaController(this) ?: return
        val t = c.transportControls
        when (press) {
            Press.SHORT -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            Press.LONG -> t.skipToNext()
            Press.DOUBLE -> t.skipToPrevious()
        }
        toyScope.launch {
            delay(300)
            render()
        }
    }

    override fun onAodTick() = render()
}

/** Stopwatch, or a countdown (minutes set in its settings). Press: start or pause. Long press: reset. */
class TimerToy : GlyphToyService() {
    private var elapsedMs = 0L
    private var startedAt: Long? = null
    private var done = false

    private val totalMs: Long get() = AppGraph.toys.timerMinutes * 60_000L

    override fun onToyStart() {
        toyScope.launch {
            while (isActive) {
                render()
                delay(200)
            }
        }
    }

    private fun now(): Long = elapsedMs + (startedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0)

    private fun render() {
        if (isPlaying) return
        val ms = now()
        val total = totalMs
        if (total > 0) {
            val left = (total - ms).coerceAtLeast(0)
            if (left == 0L && startedAt != null && !done) {
                done = true
                startedAt = null
                elapsedMs = total
                play(Animation(profile, List(4) { Effects.flash(profile).frames }.flatten()))
                return
            }
            show(ToyFaces.timer(profile, left.toFloat() / total, (left + 999) / 1000))
        } else {
            // stopwatch: the ring runs once a minute
            show(ToyFaces.timer(profile, (ms % 60_000) / 60_000f, ms / 1000))
        }
    }

    override fun onPress(press: Press) {
        when (press) {
            Press.SHORT, Press.DOUBLE -> if (startedAt == null) {
                if (done) { elapsedMs = 0; done = false }
                startedAt = SystemClock.elapsedRealtime()
            } else {
                elapsedMs = now()
                startedAt = null
            }
            Press.LONG -> { elapsedMs = 0; startedAt = null; done = false; stopAnimation() }
        }
        render()
    }
}

/** Compass, or a spirit level after a long press. Uses the rotation sensor only while the toy shows. */
class CompassToy : GlyphToyService(), SensorEventListener {
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var levelMode = false
    private var lastDraw = 0L
    private val rotation = FloatArray(9)
    private val angles = FloatArray(3)

    override fun onToyStart() {
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            ?: show(Frame.of(profile, com.dotforge.device.DotText.centered(profile.size, "N/A")))
    }

    override fun onToyStop() = sensors.unregisterListener(this)

    override fun onPress(press: Press) {
        if (press == Press.LONG) levelMode = !levelMode
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDraw < 50) return
        lastDraw = now
        SensorManager.getRotationMatrixFromVector(rotation, e.values)
        SensorManager.getOrientation(rotation, angles)
        val deg = angles.map { Math.toDegrees(it.toDouble()) }
        show(if (levelMode) ToyFaces.level(profile, -deg[1], deg[2]) else ToyFaces.compass(profile, (deg[0] + 360) % 360))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Snake: press turns right, long press turns left; after a crash the score shows and a press starts again. */
class SnakeToy : GlyphToyService() {
    private var game = SnakeGame(profile, seed = SystemClock.uptimeMillis().toInt())
    private var loop: Job? = null

    override fun onToyStart() = start()

    private fun start() {
        game = SnakeGame(profile, seed = SystemClock.uptimeMillis().toInt())
        loop?.cancel()
        loop = toyScope.launch {
            while (isActive && !game.over) {
                show(game.frame())
                delay((260 - game.score * 6L).coerceAtLeast(110))
                game.step()
            }
            play(Effects.flash(profile, times = 2))
            delay(700)
            show(Effects.centredDigits(profile, listOf("${game.score}")))
        }
    }

    override fun onPress(press: Press) {
        if (game.over) return start()
        when (press) {
            Press.SHORT -> game.turnRight()
            Press.LONG -> game.turnLeft()
            Press.DOUBLE -> game.turnRight() // the second press of a quick pair
        }
    }
}

/** Box breathing: press to start four breaths (in, hold, out, hold; 4 s each); press again to stop. */
class BreatheToy : GlyphToyService() {
    private var session: Job? = null

    override fun onToyStart() = idle()

    private fun idle() = show(ToyFaces.breath(profile, 0.5))

    override fun onPress(press: Press) {
        if (session?.isActive == true) {
            session?.cancel()
            return idle()
        }
        session = toyScope.launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val t = (SystemClock.elapsedRealtime() - start) / 1000.0
                if (t >= 64) break
                show(ToyFaces.breath(profile, t))
                delay(50)
            }
            idle()
        }
    }
}

/** Today's moon phase; long press shows the days to the next full moon. Works on the always-on display. */
class MoonToy : GlyphToyService() {
    private var info: Job? = null

    private fun age() = ToyFaces.moonAge(LocalDate.now().toEpochDay() + 0.5)

    override fun onToyStart() = render()

    override fun onAodTick() = render()

    private fun render() = show(ToyFaces.moon(profile, age()))

    override fun onPress(press: Press) {
        if (press != Press.LONG) return
        val days = (((0.5 - age() + 1) % 1) * 29.53).roundToInt()
        show(Effects.centredDigits(profile, listOf("$days")))
        info?.cancel()
        info = toyScope.launch {
            delay(INFO_MS)
            render()
        }
    }
}
