package com.dotforge.calls

/**
 * The calls DotForge silenced or declined in the last [windowMs] (10 min), in memory: a silenced call still
 * leaves a missed call behind, and the user chose not to hear it, so its missed call glyph is skipped
 * ([covers]). Safe to call from any thread.
 */
class ScreenedCalls(private val windowMs: Long = WINDOW_MS) {
    private data class Entry(val number: String?, val name: String?, val atMs: Long)

    private val entries = ArrayList<Entry>()

    /** Remembers a call DotForge stopped: its number (null when hidden) and the contact's name, if any. */
    fun note(number: String?, name: String?, atMs: Long) = synchronized(entries) {
        prune(atMs)
        entries += Entry(number?.takeIf { it.isNotBlank() }, name?.takeIf { it.isNotBlank() }, atMs)
    }

    /**
     * True when a missed call from [number] (or titled [title], as the phone app shows it: a name, a number, or
     * "Unknown" for a hidden one) at [atMs] is one DotForge stopped in the last 10 min.
     */
    fun covers(number: String?, title: String, atMs: Long): Boolean = synchronized(entries) {
        prune(atMs)
        val digits = tail(number) ?: tail(title.takeIf { t -> t.count(Char::isDigit) >= MIN_DIGITS })
        entries.any { e ->
            when {
                e.number != null -> digits != null && tail(e.number) == digits || (e.name != null && title.trim().equals(e.name, ignoreCase = true))
                // a hidden number: the missed call has no number either
                else -> digits == null && number.isNullOrBlank()
            }
        }
    }

    private fun prune(nowMs: Long) = entries.removeAll { nowMs - it.atMs > windowMs || it.atMs - nowMs > windowMs }

    /** The last 9 digits, which match across "+91 98…", "098…" and "98…". */
    private fun tail(n: String?): String? = n?.filter(Char::isDigit)?.takeIf { it.length >= MIN_DIGITS }?.takeLast(9)

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        private const val MIN_DIGITS = 6
    }
}
