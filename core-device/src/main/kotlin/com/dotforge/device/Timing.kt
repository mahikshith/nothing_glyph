package com.dotforge.device

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** How long a design shows when it is used: once through, a number of seconds, or until you check. */
@Serializable
enum class ShowFor { WHOLE, SECONDS, UNTIL_CHECKED }

/** How often it plays within that time. ("Until I check" is a Show for choice only.) */
@Serializable
enum class Repeat { ONCE, TIMES, FOREVER }

/** Which way the frames play. */
@Serializable
enum class Direction(val label: String) { FORWARD("Forward"), BACKWARD("Backward"), BOUNCE("Back and forth") }

@Serializable
enum class Fade(val ms: Int, val label: String) { OFF(0, "Off"), SHORT(150, "Short"), LONG(500, "Long") }

/**
 * The timing panel's values. Always shown: Show for, Speed, Repeat and Brightness; under "More": Direction,
 * Start delay and Fade. A design saves its own timing; a place that uses it (an app, a person) overrides single
 * fields with a [TimingOverride]. All values are clamped to the ranges the panel offers.
 */
@Serializable
data class Timing(
    val showFor: ShowFor = ShowFor.WHOLE,
    /** For [ShowFor.SECONDS]: 1-60. */
    val seconds: Int = 5,
    /** 0.25x-4x: Slow 0.5x, Normal 1x, Fast 2x. */
    val speed: Float = 1f,
    val repeat: Repeat = Repeat.ONCE,
    /** For [Repeat.TIMES]: 2-10. */
    val times: Int = 2,
    /** 10-100 %, relative to the phone's own Glyph brightness. */
    val brightness: Int = 100,
    val direction: Direction = Direction.FORWARD,
    /** 0-5000 ms of dark before it starts. */
    val startDelayMs: Int = 0,
    val fade: Fade = Fade.OFF,
    /** For [ShowFor.UNTIL_CHECKED]: "Remind me" re-pulses every this many minutes (1, 5 or 15); 0 = off. */
    val remindMinutes: Int = 0,
    /**
     * For an animation shown [ShowFor.UNTIL_CHECKED]: play 3 loops, then hold the first frame (moving again
     * once a minute) instead of moving all the time. Saves battery and stops flicker on the desk.
     */
    val rest: Boolean = true,
) {
    fun clamped() = copy(
        seconds = seconds.coerceIn(1, 60),
        speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        times = times.coerceIn(2, 10),
        brightness = brightness.coerceIn(10, 100),
        startDelayMs = startDelayMs.coerceIn(0, 5_000),
        remindMinutes = remindMinutes.coerceIn(0, 60),
    )

    /** Shown until you check (the notification is gone, its app opened, or Stop on Home). */
    val untilChecked: Boolean get() = showFor == ShowFor.UNTIL_CHECKED

    /** True when anything under "More" differs from the default. */
    val moreChanged: Boolean get() = direction != Direction.FORWARD || startDelayMs != 0 || fade != Fade.OFF

    companion object {
        /** Loops an "until I check" animation plays before it rests. */
        const val REST_LOOPS = 3
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        /** Slow, Normal, Fast. */
        val SPEED_CHIPS = listOf(0.5f, 1f, 2f)
        val SPEED_NAMES = listOf("Slow", "Normal", "Fast")
        val SECONDS_CHIPS = listOf(5, 10, 30)
        val REMIND_CHIPS = listOf(1, 5, 15)
        val BRIGHTNESS_CHIPS = listOf(25, 50, 100)
    }
}

/**
 * What a place that uses a design (an app, a person) changed from the design's own timing: null fields follow
 * the design, so editing the design later still reaches every place except the fields changed there.
 */
@Serializable
data class TimingOverride(
    val showFor: ShowFor? = null,
    val seconds: Int? = null,
    val speed: Float? = null,
    val repeat: Repeat? = null,
    val times: Int? = null,
    val brightness: Int? = null,
    val direction: Direction? = null,
    val startDelayMs: Int? = null,
    val fade: Fade? = null,
    val remindMinutes: Int? = null,
    val rest: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == TimingOverride()

    /** [base] with these changes on top. */
    fun over(base: Timing): Timing = Timing(
        showFor ?: base.showFor, seconds ?: base.seconds, speed ?: base.speed, repeat ?: base.repeat, times ?: base.times,
        brightness ?: base.brightness, direction ?: base.direction, startDelayMs ?: base.startDelayMs, fade ?: base.fade,
        remindMinutes ?: base.remindMinutes, rest ?: base.rest,
    ).clamped()

    companion object {
        /** The fields where [edited] differs from [base]; equal fields stay null so they follow the design. */
        fun diff(base: Timing, edited: Timing): TimingOverride {
            val b = base.clamped()
            val e = edited.clamped()
            fun <T> d(x: T, y: T) = if (x == y) null else y
            return TimingOverride(
                d(b.showFor, e.showFor), d(b.seconds, e.seconds), d(b.speed, e.speed), d(b.repeat, e.repeat), d(b.times, e.times),
                d(b.brightness, e.brightness), d(b.direction, e.direction), d(b.startDelayMs, e.startDelayMs), d(b.fade, e.fade),
                d(b.remindMinutes, e.remindMinutes), d(b.rest, e.rest),
            )
        }
    }
}

/**
 * What the engine plays for a design with a [Timing]: the frames to post, whether they loop, how long they
 * show ([showMs], null = one pass or until checked), and how often a held alert reminds you ([againMs]).
 */
class TimingPlan(val animation: Animation, val loop: Boolean, val showMs: Long?, val untilChecked: Boolean, val againMs: Long?)

object Timings {
    /**
     * Applies [timing] to [source]: direction, speed, repeats, fades, start delay and brightness become
     * frames; how long and how often become the plan's flags.
     */
    fun plan(source: Animation, timing: Timing): TimingPlan {
        val t = timing.clamped()
        val p = source.profile
        var frames = source.frames
        if (t.direction == Direction.BACKWARD) frames = frames.reversed()
        if (t.direction == Direction.BOUNCE && frames.size > 2) frames = frames + frames.subList(1, frames.size - 1).reversed()
        frames = frames.map { AnimFrame(it.frame, scaleMs(it.durationMs, t.speed)) }
        if (t.repeat == Repeat.TIMES) frames = List(t.times) { frames }.flatten()
        if (t.untilChecked && t.rest && frames.size > 1) {
            frames = List(Timing.REST_LOOPS) { frames }.flatten() + AnimFrame(frames.first().frame, Animation.MAX_FRAME_MS)
        }
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
        val againMs = if (t.untilChecked && t.remindMinutes > 0) t.remindMinutes * 60_000L else null
        return TimingPlan(Animation(p, frames), loop, showMs, t.untilChecked, againMs)
    }

    private fun scaleMs(ms: Int, speed: Float) = (ms / speed).roundToInt().coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)
}
