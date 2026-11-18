package com.dotforge.toys

enum class Press { SHORT, LONG, DOUBLE }

/**
 * Turns Glyph Button events into presses. Nothing documents EVENT_CHANGE as the long press; action_down /
 * action_up give the raw button, from which short, double and (if no change event came) long presses are
 * derived. A long press is reported once even when both sources describe it. A double press is reported
 * after the short press that started it.
 */
class PressDecoder(
    private val longPressMs: Long = 500,
    private val doublePressMs: Long = 350,
    private val longDedupeMs: Long = 1_000,
) {
    private var downAt: Long? = null
    private var longDuringPress = false
    private var lastShortUp: Long? = null
    private var lastLong: Long? = null

    fun down(now: Long) {
        downAt = now
        longDuringPress = false
    }

    fun up(now: Long): Press? {
        val start = downAt ?: return null
        downAt = null
        val hadLong = longDuringPress
        longDuringPress = false
        if (hadLong) return null
        if (now - start >= longPressMs) return long(now)
        val previous = lastShortUp
        if (previous != null && now - previous <= doublePressMs) {
            lastShortUp = null
            return Press.DOUBLE
        }
        lastShortUp = now
        return Press.SHORT
    }

    /** EVENT_CHANGE from the firmware. */
    fun change(now: Long): Press? {
        if (downAt != null) longDuringPress = true
        return long(now)
    }

    private fun long(now: Long): Press? {
        val duplicate = lastLong?.let { now - it < longDedupeMs } == true
        lastLong = now
        lastShortUp = null
        return if (duplicate) null else Press.LONG
    }
}
