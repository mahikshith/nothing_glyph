package com.dotforge.engine

import com.dotforge.device.Animation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a notification alert shows. */
data class AlertGlyph(val animation: Animation, val label: String)

/**
 * Runs an [AlertQueue] on the arbiter: one notification request at a time, switched only at the end of an
 * animation cycle. The public calls are thread-safe (the notification listener and the unlock receiver call
 * them); the stepping runs on [scope], which must be the arbiter's single-threaded scope.
 */
class AlertPlayer(
    private val scope: CoroutineScope,
    private val arbiter: DisplayArbiter,
    private val queue: AlertQueue<AlertGlyph>,
) {
    private val lock = Any()
    private var cycle: Job? = null

    fun offer(alert: AlertQueue.Alert<AlertGlyph>): AlertQueue.Offer {
        val result = synchronized(lock) { queue.offer(alert) }
        if (result == AlertQueue.Offer.QUEUED) scope.launch { if (cycle == null) step() }
        return result
    }

    fun removed(key: String) {
        scope.launch { if (synchronized(lock) { queue.removed(key) }) step() }
    }

    fun unlocked() {
        scope.launch { if (synchronized(lock) { queue.unlocked() }) step() }
    }

    private fun step() {
        cycle?.cancel()
        cycle = null
        when (val s = synchronized(lock) { queue.advance() }) {
            AlertQueue.Step.Clear -> arbiter.remove(ID)
            AlertQueue.Step.Keep -> synchronized(lock) { queue.current }?.let(::endOfCycle)
            is AlertQueue.Step.Show -> {
                val glyph = s.alert.item
                arbiter.post(DisplayRequest(ID, Priority.NOTIFICATION, glyph.animation, loop = true, label = glyph.label))
                endOfCycle(s.alert)
            }
        }
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
