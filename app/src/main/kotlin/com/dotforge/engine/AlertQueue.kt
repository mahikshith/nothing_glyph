package com.dotforge.engine

/** How long a notification glyph stays on. */
enum class Until(val label: String, val hint: String) {
    /** Until the notification is removed (read, opened, dismissed) or the phone is unlocked. */
    READ("read", "Until you open, dismiss or unlock"),
    /** Until the phone is unlocked, even if the notification has gone (a missed call). */
    UNLOCK("unlock", "Until you unlock, even if it's gone"),
    /** One short showing. */
    TIMEOUT("timeout", "A few seconds, then it stops"),
}

/**
 * Which notification glyph is on the matrix, and what waits behind it. Pure logic with an injected clock;
 * [AlertPlayer] runs it.
 *
 * - Nothing is cut mid-animation: the player only calls [advance] at the end of a cycle (or when idle), and
 *   an alert gives way to a waiting one only then.
 * - Calls queue ahead of everything else; the queue is bounded and drops the oldest non-call.
 * - A conversation that alerted less than [cooldownMs] ago does not alert again (re-posts, chat bursts).
 *   A re-post of a waiting or showing notification just updates it in place.
 * - [Until.READ] and [Until.UNLOCK] alerts are held after their first showing and loop whenever nothing else
 *   waits; [removed] clears READ ones, [unlocked] clears both. [Until.TIMEOUT] alerts end after [showMs].
 *
 * Not thread-safe. Queue ordering, bounding and the per-conversation cooldown follow Glyphifier's
 * PlaybackQueue and listener cooldown (Apache-2.0, see NOTICE).
 */
class AlertQueue<T>(
    private val now: () -> Long,
    private val maxQueued: Int = MAX_QUEUED,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val showMs: Long = SHOW_MS,
) {
    data class Alert<T>(
        val key: String,
        /** Re-posts and new messages in one conversation share this, for the cooldown. */
        val conversation: String,
        val isCall: Boolean,
        val until: Until,
        val item: T,
        /** How long a [Until.TIMEOUT] alert shows (whole cycles, at least one); null = the queue's default. */
        val showMs: Long? = null,
        /**
         * For held alerts: show once (for [showMs] or one cycle), then go dark and come back every [againMs]
         * until checked, instead of staying on. Null = stay on.
         */
        val againMs: Long? = null,
    )

    enum class Offer { QUEUED, UPDATED, COOLDOWN }

    sealed interface Step<out T> {
        /** Keep looping what is showing. */
        data object Keep : Step<Nothing>
        data class Show<T>(val alert: Alert<T>) : Step<T>
        /** Nothing left to show. */
        data object Clear : Step<Nothing>
        /** Nothing to show now; a held alert comes back in [ms] (call [advance] then). */
        data class Wait(val ms: Long) : Step<Nothing>
    }

    private val queued = ArrayList<Alert<T>>()
    /** READ / UNLOCK alerts that have been shown, oldest first. */
    private val held = LinkedHashMap<String, Alert<T>>()
    private val lastAlert = HashMap<String, Long>()
    /** Held alerts resting until this time (their show-again interval). */
    private val resting = HashMap<String, Long>()
    private var since = 0L

    var current: Alert<T>? = null
        private set

    val waiting: List<Alert<T>> get() = queued.toList()
    val holding: List<Alert<T>> get() = held.values.toList()

    fun offer(alert: Alert<T>): Offer {
        val t = now()
        val i = queued.indexOfFirst { it.key == alert.key }
        if (i >= 0) {
            queued[i] = alert.copy(isCall = queued[i].isCall || alert.isCall)
            return Offer.UPDATED
        }
        if (current?.key == alert.key) {
            current = alert
            if (alert.key in held) held[alert.key] = alert
            return Offer.UPDATED
        }
        val cooling = !alert.isCall && lastAlert[alert.conversation]?.let { t - it < cooldownMs } == true
        if (alert.key in held) {
            if (alert.isCall || cooling) {
                held[alert.key] = alert
                return if (cooling) Offer.COOLDOWN else Offer.UPDATED
            }
            held.remove(alert.key) // a new message in a held conversation: show it again
        } else if (cooling) {
            return Offer.COOLDOWN
        }
        lastAlert[alert.conversation] = t
        if (lastAlert.size > PRUNE_AT) lastAlert.values.removeAll { t - it > cooldownMs }
        val at = if (alert.isCall) queued.indexOfFirst { !it.isCall }.takeIf { it >= 0 } ?: queued.size else queued.size
        queued.add(at, alert)
        while (queued.size > maxQueued) {
            val victim = queued.removeAt(queued.indexOfFirst { !it.isCall }.coerceAtLeast(0))
            // never shown, but still unread: keep it glowing behind the others
            if (victim.until != Until.TIMEOUT) held[victim.key] = victim
        }
        return Offer.QUEUED
    }

    /** The notification [key] was removed. Returns true when what was showing must stop now. */
    fun removed(key: String): Boolean {
        queued.removeAll { it.key == key && it.until != Until.UNLOCK }
        if (held[key]?.until == Until.READ) held.remove(key)
        if (key !in held) resting.remove(key)
        val cur = current ?: return false
        if (cur.key != key || cur.until == Until.UNLOCK) return false
        current = null
        return true
    }

    /** The phone was unlocked. Returns true when what was showing must stop now. */
    fun unlocked(): Boolean {
        queued.removeAll { it.until != Until.TIMEOUT }
        held.clear()
        resting.clear()
        val cur = current ?: return false
        if (cur.until == Until.TIMEOUT) return false
        current = null
        return true
    }

    /** Called at the end of each cycle of the current alert, and when nothing is showing. */
    fun advance(): Step<T> {
        val t = now()
        val cur = current
        if (queued.isNotEmpty()) return start(queued.removeAt(0), t)
        if (cur != null && cur.until == Until.TIMEOUT) {
            if (t - since < (cur.showMs ?: showMs)) return Step.Keep
            current = null
        }
        if (cur != null && cur.until != Until.TIMEOUT && cur.againMs != null && cur.key in held) {
            if (t - since < (cur.showMs ?: 0L)) return Step.Keep
            resting[cur.key] = t + cur.againMs
            current = null
        }
        val next = held.values.lastOrNull { (resting[it.key] ?: 0L) <= t }
        if (next == null) {
            current = null
            val wake = held.keys.mapNotNull { resting[it] }.minOrNull() ?: return Step.Clear
            return Step.Wait((wake - t).coerceAtLeast(1))
        }
        if (next.key == cur?.key) return Step.Keep
        resting.remove(next.key)
        current = next
        since = t
        return Step.Show(next)
    }

    private fun start(alert: Alert<T>, t: Long): Step<T> {
        current = alert
        since = t
        resting.remove(alert.key)
        if (alert.until != Until.TIMEOUT) {
            held.remove(alert.key)
            held[alert.key] = alert
        }
        return Step.Show(alert)
    }

    companion object {
        const val MAX_QUEUED = 6
        const val COOLDOWN_MS = 10_000L
        /** How long a [Until.TIMEOUT] glyph keeps showing when nothing waits (whole cycles, at least one). */
        const val SHOW_MS = 6_000L
        private const val PRUNE_AT = 64
    }
}
