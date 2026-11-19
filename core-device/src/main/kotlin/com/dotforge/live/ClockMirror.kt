package com.dotforge.live

/**
 * Reads the Clock app's timer and stopwatch notifications (plain values, unit tested), so they show on the
 * Glyph like DotForge's own. A running timer is a countdown chronometer ending at `when`; a running stopwatch
 * counts up from `when`; paused ones show their time as text; "Time's up" is a timer that has ended.
 */
object ClockMirror {
    /** Clock apps we know by package; any other package with "clock" in its name also counts. */
    val CLOCK_APPS = setOf(
        "com.google.android.deskclock", "com.android.deskclock", "com.nothing.deskclock", "com.nothing.clock",
        "com.sec.android.app.clockpackage", "com.oneplus.deskclock", "com.coloros.alarmclock", "com.android.BBKClock",
        "com.miui.clock", "com.huawei.deskclock", "com.motorola.timeweatherwidget",
    )

    enum class Kind { TIMER, STOPWATCH }

    data class Notice(
        val packageName: String,
        val category: String? = null,
        val chronometer: Boolean = false,
        val countDown: Boolean = false,
        val whenMs: Long = 0,
        val title: String = "",
        val text: String = "",
        val ongoing: Boolean = true,
    )

    data class Mirrored(
        val kind: Kind,
        val running: Boolean,
        /** Timer: when it ends (running) — stopwatch: when it started counting (running). */
        val atMs: Long = 0,
        /** Paused: what is left (timer) or how long it has run (stopwatch), when the text says. */
        val shownMs: Long? = null,
        val done: Boolean = false,
        val label: String = "",
    )

    fun isClock(pkg: String): Boolean = pkg in CLOCK_APPS || pkg.lowercase().let { "deskclock" in it || ".clock" in it || "alarmclock" in it }

    /** The Clock app's own words for an unnamed timer, which say nothing a name would. */
    private val GENERIC = Regex("""(?i)(timer|stopwatch|timers|countdown)(\s+\d+)?""")
    private val DONE = Regex("""(?i)\b(time'?s up|times up|timer (?:done|finished|expired|complete)|timer ended)\b""")
    private val STOPWATCH = Regex("""(?i)\bstop ?watch\b""")
    private val TIMER = Regex("""(?i)\btimer\b""")
    private val PAUSED = Regex("""(?i)\bpaused\b""")
    /** "4:32", "1:02:03", "-0:05" in the text of a paused one. */
    private val CLOCK_TEXT = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?::(\d{2}))?(?![\d:])""")

    /** A Clock timer or stopwatch, or null when [n] is something else (an alarm, a bedtime reminder). */
    fun read(n: Notice, now: Long): Mirrored? {
        if (!isClock(n.packageName)) return null
        val words = n.title + " " + n.text
        val label = n.title.trim().takeUnless { DONE.containsMatchIn(it) || it.isBlank() || GENERIC.matches(it) }.orEmpty()
        if (DONE.containsMatchIn(words)) return Mirrored(Kind.TIMER, running = false, done = true, label = label)
        val stopwatch = n.category == "stopwatch" || STOPWATCH.containsMatchIn(words)
        if (n.chronometer && n.whenMs > 0) {
            return if (n.countDown && !stopwatch) {
                // a countdown that has passed its end is a timer that has ended (Time's up comes next)
                if (n.whenMs <= now) Mirrored(Kind.TIMER, running = false, done = true, label = label)
                else Mirrored(Kind.TIMER, running = true, atMs = n.whenMs, label = label)
            } else if (stopwatch) Mirrored(Kind.STOPWATCH, running = true, atMs = n.whenMs.coerceAtMost(now), label = label)
            else null
        }
        // paused (or a Clock app that writes the time as text)
        val time = parseClock(n.text) ?: parseClock(n.title)
        return when {
            stopwatch -> Mirrored(Kind.STOPWATCH, running = false, shownMs = time, label = label).takeIf { time != null || PAUSED.containsMatchIn(words) }
            TIMER.containsMatchIn(words) && time != null -> Mirrored(Kind.TIMER, running = false, shownMs = time, label = label)
            else -> null
        }
    }

    /** "4:32" → 272 000 ms; "1:02:03" → 3 723 000 ms; null when there is no time in [text]. */
    fun parseClock(text: String): Long? {
        val m = CLOCK_TEXT.find(text) ?: return null
        val a = m.groupValues[1].toLong()
        val b = m.groupValues[2].toLong()
        val c = m.groupValues[3].ifEmpty { null }?.toLong()
        if (b > 59 || (c != null && c > 59)) return null
        return if (c != null) (a * 3600 + b * 60 + c) * 1000 else (a * 60 + b) * 1000
    }

    /** A mirrored notice as a timer or stopwatch the Glyph can show (id from the notification's key). */
    fun toTimer(key: String, m: Mirrored, now: Long, seen: GlyphTimer?): GlyphTimer? {
        if (m.kind != Kind.TIMER) return null
        val id = "clock:$key"
        return when {
            m.done -> (seen ?: GlyphTimer(id, 60_000, running = false, leftMs = 0)).copy(doneAt = seen?.doneAt?.takeIf { it > 0 } ?: now, running = false, label = m.label.ifBlank { seen?.label.orEmpty() }, source = TimerSource.CLOCK)
            m.running -> {
                val left = m.atMs - now
                // the total is unknown: the longest left we have seen (the first sighting), so the ring drains from full
                val total = maxOf(seen?.totalMs ?: 0, left, 1_000)
                GlyphTimer(id, total, running = true, endsAt = m.atMs, leftMs = left, label = m.label, source = TimerSource.CLOCK)
            }
            m.shownMs != null -> GlyphTimer(id, maxOf(seen?.totalMs ?: 0, m.shownMs, 1_000), running = false, leftMs = m.shownMs, label = m.label, source = TimerSource.CLOCK)
            else -> null
        }
    }

    fun toStopwatch(m: Mirrored, now: Long): Stopwatch? {
        if (m.kind != Kind.STOPWATCH) return null
        return if (m.running) Stopwatch(running = true, startedAt = m.atMs, source = TimerSource.CLOCK, label = m.label)
        else Stopwatch(running = false, accumulatedMs = m.shownMs ?: 0, source = TimerSource.CLOCK, label = m.label)
    }
}
