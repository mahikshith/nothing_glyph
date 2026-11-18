package com.dotforge.engine

/** How long a notification glyph stays on. */
enum class Until(val label: String, val hint: String) {
    /** Until you check: the notification is removed (read, opened, dismissed), or Stop on Home. */
    READ("read", "Until you check"),
    /** Until the phone is unlocked, even if the notification has gone (a missed call). */
    UNLOCK("unlock", "Until you unlock, even if it's gone"),
    /** One short showing. */
    TIMEOUT("timeout", "A few seconds, then it stops"),
}

/**
 * Which alert is on the Glyph, and what waits behind it. Pure logic with an injected clock; [AlertPlayer] runs
 * it.
 *
 * - Who wins: calls, then people, then apps ([Alert.rank], lower first; within a level the Who wins order).
 *   Within one rank the newest shows first. Nothing is cut mid-animation (the player only calls [advance] at
 *   the end of a cycle) and never in its first [MIN_SHOW_MS], except by a call.
 * - The queue is bounded and drops the oldest of the lowest rank.
 * - A conversation that alerted less than its cooldown ago does not alert again (re-posts, chat bursts). A
 *   re-post of a waiting or showing notification updates it in place.
 * - "Until you check" ([Until.READ]) alerts are held after their first showing and loop whenever nothing else
 *   waits; several held ones take turns every [TURN_MS]. [removed] and [stopAll] clear them; [unlocked] clears
 *   them only when unlocking counts as checking. They stop waiting after [capMs] (null = never).
 *   [Until.UNLOCK] alerts clear on unlock; [Until.TIMEOUT] alerts end after their show time.
 *
 * Not thread-safe. Queue bounding and the per-conversation cooldown follow Glyphifier's PlaybackQueue and
 * listener cooldown (Apache-2.0, see NOTICE).
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
         * until checked ("Remind me"), instead of staying on. Null = stay on.
         */
        val againMs: Long? = null,
        /** Who wins: lower first. [RANK_CALL], [RANK_PERSON] + order, [RANK_APP] + order. */
        val rank: Int = if (isCall) RANK_CALL else RANK_APP,
        /** This alert's own cooldown for its conversation (for "Don't repeat within 1 min"); null = the queue's. */
        val cooldownMs: Long? = null,
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

    private class Entry<T>(val alert: Alert<T>, val at: Long)

    private val queued = ArrayList<Entry<T>>()
    /** Alerts waiting until checked that have been shown, by key, with when they first arrived. */
    private val held = LinkedHashMap<String, Entry<T>>()
    private val lastAlert = HashMap<String, Long>()
    /** Held alerts resting until this time (their remind interval). */
    private val resting = HashMap<String, Long>()
    private var since = 0L

    /** How long a held alert waits before it gives up; null = never. */
    var capMs: Long? = CAP_MS

    var current: Alert<T>? = null
        private set

    val waiting: List<Alert<T>> get() = queued.map { it.alert }
    val holding: List<Alert<T>> get() = held.values.map { it.alert }

    private val order = compareBy<Entry<T>>({ it.alert.rank }, { -it.at })

    fun offer(alert: Alert<T>): Offer {
        val t = now()
        val i = queued.indexOfFirst { it.alert.key == alert.key }
        if (i >= 0) {
            queued[i] = Entry(alert.copy(isCall = queued[i].alert.isCall || alert.isCall), queued[i].at)
            return Offer.UPDATED
        }
        if (current?.key == alert.key) {
            current = alert
            held[alert.key]?.let { held[alert.key] = Entry(alert, it.at) }
            return Offer.UPDATED
        }
        val cool = alert.cooldownMs ?: cooldownMs
        val cooling = !alert.isCall && lastAlert[alert.conversation]?.let { t - it < cool } == true
        val wasHeld = held[alert.key]
        if (wasHeld != null) {
            if (alert.isCall || cooling) {
                held[alert.key] = Entry(alert, wasHeld.at)
                return if (cooling) Offer.COOLDOWN else Offer.UPDATED
            }
            held.remove(alert.key) // a new message in a held conversation: show it again
        } else if (cooling) {
            return Offer.COOLDOWN
        }
        lastAlert[alert.conversation] = t
        if (lastAlert.size > PRUNE_AT) lastAlert.values.removeAll { t - it > maxOf(cooldownMs, MAX_COOLDOWN_MS) }
        queued.add(Entry(alert, t))
        queued.sortWith(order)
        while (queued.size > maxQueued) {
            // the oldest of the lowest rank goes; never shown, but still unread: keep it glowing behind the others
            val worst = queued.maxOf { it.alert.rank }
            val victim = queued.filter { it.alert.rank == worst }.minBy { it.at }
            queued.remove(victim)
            if (victim.alert.until != Until.TIMEOUT) held[victim.alert.key] = victim
        }
        return Offer.QUEUED
    }

    /** The notification [key] was removed (checked). Returns true when what was showing must stop now. */
    fun removed(key: String): Boolean {
        queued.removeAll { it.alert.key == key && it.alert.until != Until.UNLOCK }
        if (held[key]?.alert?.until == Until.READ) held.remove(key)
        if (key !in held) resting.remove(key)
        val cur = current ?: return false
        if (cur.key != key || cur.until == Until.UNLOCK) return false
        current = null
        return true
    }

    /**
     * The phone was unlocked. Missed-call style alerts ([Until.UNLOCK]) always clear; alerts waiting until you
     * check clear only when [counts] ("Unlocking counts as checking"). Returns true when what was showing must stop.
     */
    fun unlocked(counts: Boolean = false): Boolean {
        fun clears(a: Alert<T>) = a.until == Until.UNLOCK || (counts && a.until == Until.READ)
        queued.removeAll { clears(it.alert) }
        held.values.removeAll { clears(it.alert) }
        resting.keys.retainAll(held.keys)
        val cur = current ?: return false
        if (!clears(cur)) return false
        current = null
        return true
    }

    /** Stop on Home: everything clears and counts as checked. Returns true when something was showing. */
    fun stopAll(): Boolean {
        queued.clear()
        held.clear()
        resting.clear()
        val was = current != null
        current = null
        return was
    }

    /** Called at the end of each cycle of the current alert, and when nothing is showing. */
    fun advance(): Step<T> {
        val t = now()
        expire(t)
        val cur = current
        if (queued.isNotEmpty()) {
            val next = queued.first()
            // never cut an alert in its first moments, unless a call rings
            if (cur == null || t - since >= MIN_SHOW_MS || (next.alert.isCall && !cur.isCall)) {
                queued.removeAt(0)
                return start(next, t)
            }
            return Step.Keep
        }
        if (cur != null && cur.until == Until.TIMEOUT) {
            if (t - since < (cur.showMs ?: showMs)) return Step.Keep
            current = null
        }
        if (cur != null && cur.until != Until.TIMEOUT && cur.againMs != null && cur.key in held) {
            if (t - since < (cur.showMs ?: 0L)) return Step.Keep
            resting[cur.key] = t + cur.againMs
            current = null
        }
        val awake = held.values.filter { (resting[it.alert.key] ?: 0L) <= t }.sortedWith(order)
        if (awake.isEmpty()) {
            current = null
            val wake = held.keys.mapNotNull { resting[it] }.minOrNull() ?: return Step.Clear
            return Step.Wait((wake - t).coerceAtLeast(1))
        }
        val curHeld = current?.key?.let { k -> awake.indexOfFirst { it.alert.key == k } } ?: -1
        if (curHeld >= 0) {
            // several waiting: take turns
            if (awake.size == 1 || t - since < TURN_MS) return Step.Keep
            return show(awake[(curHeld + 1) % awake.size].alert, t)
        }
        return show(awake.first().alert, t)
    }

    private fun show(next: Alert<T>, t: Long): Step<T> {
        resting.remove(next.key)
        current = next
        since = t
        return Step.Show(next)
    }

    private fun start(e: Entry<T>, t: Long): Step<T> {
        val alert = e.alert
        if (alert.until != Until.TIMEOUT) {
            held.remove(alert.key)
            held[alert.key] = e
        }
        return show(alert, t)
    }

    /** Held alerts past the cap stop waiting. */
    private fun expire(t: Long) {
        val cap = capMs ?: return
        val old = held.values.filter { it.alert.until == Until.READ && t - it.at >= cap }.map { it.alert.key }
        if (old.isEmpty()) return
        old.forEach { held.remove(it); resting.remove(it) }
        if (current?.key in old) current = null
    }

    companion object {
        const val MAX_QUEUED = 6
        const val COOLDOWN_MS = 10_000L
        private const val MAX_COOLDOWN_MS = 60_000L
        /** How long a [Until.TIMEOUT] glyph keeps showing when nothing waits (whole cycles, at least one). */
        const val SHOW_MS = 6_000L
        /** An alert is never cut sooner than this (except by a call). */
        const val MIN_SHOW_MS = 2_000L
        /** Several alerts waiting until checked take turns this often. */
        const val TURN_MS = 3_000L
        /** "Stop waiting after" default: 4 h. */
        const val CAP_MS = 4 * 60 * 60_000L
        const val RANK_CALL = 0
        const val RANK_PERSON = 1_000
        const val RANK_APP = 2_000
        private const val PRUNE_AT = 64
    }
}
