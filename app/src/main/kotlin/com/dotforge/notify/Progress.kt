package com.dotforge.notify

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Progress for the outer ring, from plain values (no Android types, so it is unit tested):
 *  - a notification's progress bar: the classic `setProgress` extras, or an Android 16 ProgressStyle, which
 *    saves its progress in the same extra and its length as segments (100 when it has none);
 *  - a media session's position.
 */
object Progress {
    const val PROGRESS = "android.progress"
    const val MAX = "android.progressMax"
    const val INDETERMINATE = "android.progressIndeterminate"
    const val TEMPLATE = "android.template"
    const val SEGMENTS = "android.progressSegments"
    /** Each segment's length, in a segment's bundle. */
    const val SEGMENT_LENGTH = "length"
    const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"
    private const val STYLE_DEFAULT_MAX = 100

    private fun int(v: Any?): Int? = (v as? Number)?.toInt()

    /** Determinate progress 0..1 from a notification's extras as plain values; null when there is none. */
    fun fraction(extras: Map<String, Any?>): Float? {
        if (extras[INDETERMINATE] == true) return null
        val progress = int(extras[PROGRESS]) ?: return null
        val max = int(extras[MAX])?.takeIf { it > 0 }
        val segments = (extras[SEGMENTS] as? List<*>).orEmpty()
        val style = extras[TEMPLATE] == PROGRESS_STYLE || segments.isNotEmpty()
        val total = when {
            max != null -> max
            style -> segments.sumOf { (it as? Map<*, *>)?.get(SEGMENT_LENGTH).let(::int)?.coerceAtLeast(0) ?: 0 }
                .takeIf { it > 0 } ?: STYLE_DEFAULT_MAX
            else -> return null
        }
        return (progress.toFloat() / total).coerceIn(0f, 1f)
    }

    /** A progress bar with no value yet ("waiting", "preparing"): indeterminate, classic or ProgressStyle. */
    fun waiting(extras: Map<String, Any?>): Boolean =
        extras[INDETERMINATE] == true && (extras.containsKey(PROGRESS) || extras.containsKey(MAX) || extras[TEMPLATE] == PROGRESS_STYLE)

    /** The waiting state: the whole ring breathes gently. */
    fun breathing(p: DeviceProfile, steps: Int = 30, periodMs: Int = 3000): Animation = Animation(p, List(steps) { i ->
        val level = (40 + 140 * (0.5 + 0.5 * sin(2 * PI * i / steps - PI / 2))).roundToInt()
        AnimFrame(Effects.ring(p, 1f, on = level), periodMs / steps)
    })

    /** How many of [ringSize] ring cells [fraction] lights (as [com.dotforge.device.Effects.ring] draws it). */
    fun litCells(ringSize: Int, fraction: Float): Int = (ringSize * fraction.coerceIn(0f, 1f)).roundToInt()

    /**
     * Where a media session is, 0..1: its last reported [position] moved on by the time since [updatedAt] at
     * [speed] while [playing]. Null for a stream with no length.
     */
    fun media(position: Long, duration: Long, updatedAt: Long, now: Long, speed: Float, playing: Boolean): Float? {
        if (duration <= 0 || position < 0) return null
        val moved = if (playing && updatedAt > 0) ((now - updatedAt).coerceAtLeast(0) * speed).toLong() else 0L
        return ((position + moved).toFloat() / duration).coerceIn(0f, 1f)
    }
}
