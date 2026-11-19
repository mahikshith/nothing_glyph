package com.dotforge.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Nothing's own lights that cover ours for a while (row 50): the charging light when the charger goes in. The
 * firmware never says when it covers an app frame, so this is what we know from the phone's own events; Home
 * reads it ("The Glyph is busy (Nothing: charging)") and the arbiter re-sends afterwards.
 */
object NothingLights {
    private val _busy = MutableStateFlow<String?>(null)
    /** What Nothing is showing now ("charging"), or null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private var until = 0L

    /** Nothing shows [what] for about [ms] from [now]. */
    fun covered(what: String, ms: Long, now: Long = System.currentTimeMillis()) {
        until = now + ms
        _busy.value = what
    }

    /** Clears [busy] once its time has passed (Home checks each second while it shows). */
    fun expire(now: Long = System.currentTimeMillis()) {
        if (_busy.value != null && now >= until) _busy.value = null
    }

    /** Tests. */
    fun reset() { _busy.value = null; until = 0 }

    /** How long Nothing's charging light holds the Glyph after the charger goes in. */
    const val CHARGING_MS = 6_000L
}
