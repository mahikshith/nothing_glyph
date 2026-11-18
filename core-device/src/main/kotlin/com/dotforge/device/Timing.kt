package com.dotforge.device

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** How long a design shows when it is used. */
@Serializable
enum class ShowFor { WHOLE, SECONDS, UNTIL_CHECKED }

/** How often it plays within that time. */
@Serializable
enum class Repeat { ONCE, TIMES, FOREVER, UNTIL_CHECKED }

/** A still design can blink while it shows. */
@Serializable
enum class Blink(val onMs: Int, val offMs: Int) { NONE(0, 0), SLOW(700, 500), FAST(250, 200) }

@Serializable
enum class Fade(val ms: Int) { OFF(0), SHORT(150), LONG(500) }

/**
 * The timing panel's values: Show for, Speed, Repeat and Brightness, plus the extras of the spec's "Controls"
 * table. A design saves its own timing; a place that uses it (an app, a person) may override it without
 * changing the design. All values are clamped to the ranges the panel offers.
 */
@Serializable
data class Timing(
    val showFor: ShowFor = ShowFor.WHOLE,
    /** For [ShowFor.SECONDS]: 1-60. */
    val seconds: Int = 5,
    /** 0.25x-4x. */
    val speed: Float = 1f,
    val repeat: Repeat = Repeat.ONCE,
    /** For [Repeat.TIMES]: 2-10. */
    val times: Int = 2,
    /** 10-100 %. */
    val brightness: Int = 100,
    val blink: Blink = Blink.NONE,
    val fade: Fade = Fade.OFF,
    val backwards: Boolean = false,
    /** Forwards then backwards. */
    val bounce: Boolean = false,
    /** 0-5000 ms of dark before it starts. */
    val startDelayMs: Int = 0,
    /** For uses held until checked: show again every this many minutes (1, 5 or 15) instead of staying on; null = stay on. */
    val showAgainMinutes: Int? = null,
) {
    fun clamped() = copy(
        seconds = seconds.coerceIn(1, 60),
        speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        times = times.coerceIn(2, 10),
        brightness = brightness.coerceIn(10, 100),
        startDelayMs = startDelayMs.coerceIn(0, 5_000),
        showAgainMinutes = showAgainMinutes?.coerceIn(1, 60),
    )

    /** Shown until the user checks (the notification is read, or the phone unlocked). */
    val untilChecked: Boolean get() = showFor == ShowFor.UNTIL_CHECKED || repeat == Repeat.UNTIL_CHECKED

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        val SPEED_CHIPS = listOf(0.5f, 1f, 2f)
        val SECONDS_CHIPS = listOf(5, 10, 30)
        val AGAIN_CHIPS = listOf(1, 5, 15)
        val BRIGHTNESS_CHIPS = listOf(25, 50, 100)
    }
}

/**
 * What the engine plays for a design with a [Timing]: the frames to post, whether they loop, how long they
 * show ([showMs], null = one pass or until checked), and how often a held alert comes back ([againMs]).
 */
class TimingPlan(val animation: Animation, val loop: Boolean, val showMs: Long?, val untilChecked: Boolean, val againMs: Long?)

object Timings {
    /**
     * Applies [timing] to [source]: direction, speed, blink, repeats, fades, start delay and brightness become
     * frames; how long and how often become the plan's flags.
     */
    fun plan(source: Animation, timing: Timing): TimingPlan {
        val t = timing.clamped()
        val p = source.profile
        var frames = source.frames
        if (t.backwards) frames = frames.reversed()
        if (t.bounce && frames.size > 2) frames = frames + frames.subList(1, frames.size - 1).reversed()
        frames = frames.map { AnimFrame(it.frame, scaleMs(it.durationMs, t.speed)) }
        if (frames.size == 1 && t.blink != Blink.NONE) {
            frames = listOf(AnimFrame(frames[0].frame, t.blink.onMs), AnimFrame(Frame.blank(p), t.blink.offMs))
        }
        if (t.repeat == Repeat.TIMES) frames = List(t.times) { frames }.flatten()
        if (t.fade != Fade.OFF) {
            val first = frames.first().frame
            val last = frames.last().frame
            val steps = 3
            val inFrames = (1..steps).map { AnimFrame(first.scaled(it / (steps + 1f)), t.fade.ms / steps) }
            val outFrames = (steps downTo 1).map { AnimFrame(last.scaled(it / (steps + 1f)), t.fade.ms / steps) }
            frames = inFrames + frames + outFrames
        }
        if (t.startDelayMs > 0) frames = listOf(AnimFrame(Frame.blank(p), t.startDelayMs)) + frames
        if (t.brightness < 100) frames = frames.map { AnimFrame(it.frame.scaled(t.brightness / 100f), it.durationMs) }
        frames = frames.map { AnimFrame(it.frame, it.durationMs.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)) }
        val loop = t.repeat == Repeat.FOREVER || t.untilChecked || t.showFor == ShowFor.SECONDS
        val showMs = if (t.showFor == ShowFor.SECONDS) t.seconds * 1_000L else null
        val againMs = if (t.untilChecked) t.showAgainMinutes?.let { it * 60_000L } else null
        return TimingPlan(Animation(p, frames), loop, showMs, t.untilChecked, againMs)
    }

    private fun scaleMs(ms: Int, speed: Float) = (ms / speed).roundToInt().coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)
}
