package com.dotforge.engine

import com.dotforge.device.Animation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a notification alert shows; [label] names it on Home ("WhatsApp", "Mom"). */
data class AlertGlyph(val animation: Animation, val label: String)

/**
 * Runs an [AlertQueue] on the arbiter: one alert request at a time, switched only at the end of an animation
 * cycle. The public calls are thread-safe (the notification listener and the unlock receiver call them); the
 * stepping runs on [scope], which must be the arbiter's single-threaded scope.
 */
class AlertPlayer(
    private val scope: CoroutineScope,
    private val arbiter: DisplayArbiter,
    private val queue: AlertQueue<AlertGlyph>,
) {
    private val lock = Any()
    private var cycle: Job? = null

    private val _waiting = MutableStateFlow<List<String>>(emptyList())
    /** What is showing or waiting until checked, by label (Home: "2 waiting: Mom, WhatsApp"). */
    val waiting: StateFlow<List<String>> = _waiting.asStateFlow()

    private fun publish() {
        val labels = synchronized(lock) {
            (listOfNotNull(queue.current) + queue.holding + queue.waiting).distinctBy { it.key }.map { it.item.label }
        }
        _waiting.value = labels
    }

    fun offer(alert: AlertQueue.Alert<AlertGlyph>): AlertQueue.Offer {
        val result = synchronized(lock) { queue.offer(alert) }
        when (result) {
            AlertQueue.Offer.QUEUED -> scope.launch { if (cycle == null) step() }
            // a better caller name, a new message count: what is showing changes in place
            AlertQueue.Offer.UPDATED -> scope.launch {
                if (synchronized(lock) { queue.current?.key } == alert.key) {
                    arbiter.post(DisplayRequest(ID, Priority.NOTIFICATION, alert.item.animation, loop = true, label = alert.item.label))
                }
            }
            AlertQueue.Offer.COOLDOWN -> Unit
        }
        publish()
        return result
    }

    fun removed(key: String) {
        scope.launch { if (synchronized(lock) { queue.removed(key) }) step() else publish() }
    }

    /** The phone was unlocked; [counts] when "Unlocking counts as checking" is on. */
    fun unlocked(counts: Boolean = false) {
        scope.launch { if (synchronized(lock) { queue.unlocked(counts) }) step() else publish() }
    }

    /** Stop (all) on Home: clears the Glyph; the phone's notifications stay. */
    fun stopAll() {
        scope.launch {
            synchronized(lock) { queue.stopAll() }
            step()
        }
    }

    /** "Stop waiting after" (null = never). */
    fun setCap(ms: Long?) = synchronized(lock) { queue.capMs = ms }

    private fun step() {
        cycle?.cancel()
        cycle = null
        when (val s = synchronized(lock) { queue.advance() }) {
            AlertQueue.Step.Clear -> arbiter.remove(ID)
            is AlertQueue.Step.Wait -> {
                arbiter.remove(ID)
                cycle = scope.launch {
                    delay(s.ms)
                    cycle = null
                    step()
                }
            }
            AlertQueue.Step.Keep -> synchronized(lock) { queue.current }?.let(::endOfCycle)
            is AlertQueue.Step.Show -> {
                val glyph = s.alert.item
                arbiter.post(DisplayRequest(ID, Priority.NOTIFICATION, glyph.animation, loop = true, label = glyph.label))
                endOfCycle(s.alert)
            }
        }
        publish()
    }

    private fun endOfCycle(alert: AlertQueue.Alert<AlertGlyph>) {
        cycle = scope.launch {
            delay(alert.item.animation.totalMs.toLong().coerceAtLeast(MIN_CYCLE_MS))
            cycle = null
            step()
        }
    }

    companion object {
        const val ID = "alert"
        private const val MIN_CYCLE_MS = 800L
    }
}
