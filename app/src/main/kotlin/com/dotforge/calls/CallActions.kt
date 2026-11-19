package com.dotforge.calls

import com.dotforge.device.ShowFor
import com.dotforge.device.TimingOverride
import com.dotforge.engine.Until
import com.dotforge.rules.Action
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.Rule

/**
 * What a screened call gets, as CallScreeningService flags, and the glyphs for missed and screened calls.
 *
 * Android's rules for [android.telecom.CallScreeningService.CallResponse]: "silence" lets the call through
 * without ringing (it stays in the call log as missed); "disallow" stops it, and only then can it also be
 * rejected (the caller hears it declined), kept out of the call log, or kept out of the missed-call
 * notification.
 */
object CallActions {
    data class Flags(
        val silence: Boolean = false,
        val disallow: Boolean = false,
        val reject: Boolean = false,
        val skipLog: Boolean = false,
        val skipNotification: Boolean = false,
    )

    /** What a call matching [rule] gets: a block rule's own choice for calls (Silence by default), else its action. */
    fun of(rule: Rule): Action = if (rule.action == Action.SPAM) rule.callAction ?: Action.SPAM else rule.action

    fun flags(action: Action): Flags = when (action) {
        Action.SILENCE, Action.SPAM, Action.HIDE -> Flags(silence = true)
        Action.REJECT -> Flags(disallow = true, reject = true)
        Action.REJECT_QUIET -> Flags(disallow = true, reject = true, skipLog = true, skipNotification = true)
        Action.ALLOW, Action.SHOW, Action.PROGRESS -> Flags()
    }

    /** The "What to do" chips for calls in Add a rule: label, the rule's [Rule.callAction], and what it means. */
    val CHOICES: List<Triple<String, Action?, String>> = listOf(
        Triple("Silence", null, "It doesn't ring and stays in your call log."),
        Triple("Decline", Action.REJECT, "Declined. It stays in your call log."),
        Triple("Decline, no log", Action.REJECT_QUIET, "Declined, with no call log entry or missed-call notification."),
    )

    // ---------------------------------------------------------------- missed and screened calls

    const val MISSED_ID = "missed-call"
    const val BUILT_IN = "built-in"

    /** A missed call waits until you check it (the notification is opened or dismissed) and lights again every 5 min. */
    val MISSED_TIMING = TimingOverride(showFor = ShowFor.UNTIL_CHECKED, remindMinutes = 5)

    /**
     * The missed call glyph as an ordinary rule, so the shared timing controls and alert code run it: the glyph
     * picked (or the built-in missed call glyph, [GlyphStyle.DESIGN] with [BUILT_IN]), until you check, again
     * every few minutes.
     */
    fun missedRule(s: CallSettings) = Rule(
        id = MISSED_ID, name = "Missed call",
        glyph = s.missedStyle ?: GlyphStyle.DESIGN, designId = if (s.missedStyle == null) BUILT_IN else s.missedDesign,
        until = Until.READ, timing = s.missedTiming ?: MISSED_TIMING,
    )
}
