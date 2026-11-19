package com.dotforge.live

import kotlinx.serialization.Serializable

/** Where a timer comes from: started in DotForge, or mirrored from the Clock app's notification. */
@Serializable
enum class TimerSource { OURS, CLOCK }

/**
 * One countdown. While [running] it ends at [endsAt]; while paused [leftMs] is what is left. [doneAt] is set
 * when it ended and stays until it is dismissed (the burst repeats until then).
 */
@Serializable
data class GlyphTimer(
    val id: String,
    val totalMs: Long,
    val running: Boolean = true,
    val endsAt: Long = 0,
    val leftMs: Long = totalMs,
    val doneAt: Long = 0,
    val label: String = "",
    val source: TimerSource = TimerSource.OURS,
) {
    fun left(now: Long): Long = when {
        doneAt > 0 -> 0
        running -> (endsAt - now).coerceAtLeast(0)
        else -> leftMs.coerceAtLeast(0)
    }

    /** True once it has run out (whether or not the finish has been noticed yet). */
    fun over(now: Long): Boolean = doneAt > 0 || (running && now >= endsAt)

    fun pause(now: Long): GlyphTimer = if (!running || over(now)) this else copy(running = false, leftMs = left(now))
    fun resume(now: Long): GlyphTimer = if (running || doneAt > 0) this else copy(running = true, endsAt = now + leftMs)

    /** One more minute; a timer that has ended starts again with a minute. */
    fun plusMinute(now: Long): GlyphTimer = when {
        over(now) -> copy(running = true, doneAt = 0, endsAt = now + MINUTE, leftMs = MINUTE, totalMs = MINUTE)
        running -> copy(endsAt = endsAt + MINUTE, totalMs = totalMs + MINUTE)
        else -> copy(leftMs = leftMs + MINUTE, totalMs = totalMs + MINUTE)
    }

    /** "5 min timer", "1 h 30 min timer", "45 s timer", or its own label. */
    val name: String get() = label.ifBlank { Timers.lengthName(totalMs) + " timer" }

    companion object {
        const val MINUTE = 60_000L

        fun start(id: String, ms: Long, now: Long, label: String = ""): GlyphTimer =
            GlyphTimer(id, ms, running = true, endsAt = now + ms, leftMs = ms, label = label)
    }
}

/** The stopwatch: [accumulatedMs] before the current run, which started at [startedAt]; [laps] are total times. */
@Serializable
data class Stopwatch(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val accumulatedMs: Long = 0,
    val laps: List<Long> = emptyList(),
    val source: TimerSource = TimerSource.OURS,
    val label: String = "",
) {
    fun elapsed(now: Long): Long = accumulatedMs + if (running) (now - startedAt).coerceAtLeast(0) else 0

    val started: Boolean get() = running || accumulatedMs > 0

    fun start(now: Long): Stopwatch = if (running) this else copy(running = true, startedAt = now)
    fun pause(now: Long): Stopwatch = if (!running) this else copy(running = false, accumulatedMs = elapsed(now), startedAt = 0)
    fun lap(now: Long): Stopwatch = if (!running) this else copy(laps = laps + elapsed(now))
    fun reset(): Stopwatch = Stopwatch()

    /** Laps newest first: number, the lap's own time, and the total at that lap. */
    fun lapRows(): List<Lap> = laps.mapIndexed { i, total -> Lap(i + 1, total - (laps.getOrNull(i - 1) ?: 0L), total) }.reversed()
}

data class Lap(val number: Int, val splitMs: Long, val totalMs: Long)

/** What the Glyph shows of the timers right now. */
sealed interface TimerShown {
    val key: String

    /** A timer that has run out: the burst, until it is dismissed. */
    data class Done(val timer: GlyphTimer) : TimerShown { override val key get() = timer.id }
    data class Running(val timer: GlyphTimer, val leftMs: Long, val others: Int) : TimerShown { override val key get() = timer.id }
    data class Paused(val timer: GlyphTimer, val leftMs: Long) : TimerShown { override val key get() = timer.id }
    data class Watch(val stopwatch: Stopwatch, val elapsedMs: Long) : TimerShown { override val key get() = "stopwatch" }
}

object Timers {
    /** Presets on the Timer page, in minutes. */
    val PRESETS = listOf(1, 3, 5, 10, 15, 25)

    /** The longest timer: 24 h. */
    const val MAX_MS = 24 * 3_600_000L

    /**
     * What the Glyph shows: a timer that has ended (the newest) wins until dismissed; then the running timer that
     * ends first; then the running stopwatch; then a paused timer (the one with least left); else nothing.
     */
    fun shown(timers: List<GlyphTimer>, stopwatch: Stopwatch?, now: Long): TimerShown? {
        timers.filter { it.over(now) }.maxByOrNull { if (it.doneAt > 0) it.doneAt else it.endsAt }?.let { return TimerShown.Done(it) }
        val running = timers.filter { it.running }.sortedBy { it.endsAt }
        running.firstOrNull()?.let { return TimerShown.Running(it, it.left(now), running.size - 1) }
        stopwatch?.takeIf { it.running }?.let { return TimerShown.Watch(it, it.elapsed(now)) }
        timers.filter { !it.running }.minByOrNull { it.leftMs }?.let { return TimerShown.Paused(it, it.left(now)) }
        return null
    }

    /** Marks every timer that has run out as done at [now]; returns the list and the ones that just ended. */
    fun settle(timers: List<GlyphTimer>, now: Long): Pair<List<GlyphTimer>, List<GlyphTimer>> {
        val ended = ArrayList<GlyphTimer>()
        val out = timers.map { t ->
            if (t.doneAt == 0L && t.running && now >= t.endsAt) t.copy(doneAt = t.endsAt.coerceAtMost(now)).also { ended += it } else t
        }
        return out to ended
    }

    /** When the next timer ends (for the wake-up alarm), or null. */
    fun nextEnd(timers: List<GlyphTimer>): Long? = timers.filter { it.running && it.doneAt == 0L }.minOfOrNull { it.endsAt }

    /** "5 min", "1 h 30 min", "45 s", "2 min 30 s". */
    fun lengthName(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, sec.takeIf { it > 0 }?.let { "$it s" })
            .joinToString(" ").ifEmpty { "0 s" }
    }

    /** Minutes and seconds from the custom picker, kept to 1 s .. 24 h. */
    fun custom(hours: Int, minutes: Int, seconds: Int): Long =
        ((hours.coerceIn(0, 23) * 3600L + minutes.coerceIn(0, 59) * 60L + seconds.coerceIn(0, 59)) * 1000).coerceIn(1000, MAX_MS)
}
