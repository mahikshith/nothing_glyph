package com.dotforge.calls

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.TelecomManager
import com.dotforge.AppGraph
import com.dotforge.device.Effects
import com.dotforge.engine.AlertGlyph
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.Until
import com.dotforge.filter.Caller
import com.dotforge.filter.FilterActions
import com.dotforge.filter.PhoneState
import com.dotforge.filter.Subject
import com.dotforge.rules.Action
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SpamCatch

/**
 * Screens incoming calls with the rules for calls, the same engine and spam rules as notifications (DotForge
 * must hold the call screening role). Lets a call ring, silences or rejects it; blocked calls go to the Spam
 * log with a short glyph. Android only passes calls from saved contacts to a screening app that can read
 * Contacts.
 */
class GlyphCallScreeningService : CallScreeningService() {
    private val lookup by lazy { CallerLookup(this) }
    private val main = Handler(Looper.getMainLooper())

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        Thread({ screen(details) }, "DotForgeScreen").start()
    }

    private fun screen(details: Call.Details) {
        val calls = AppGraph.calls
        val raw = details.handle?.schemeSpecificPart.orEmpty()
        val hidden = details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || raw.isBlank()
        val number = if (hidden) null else lookup.key(raw)
        val nowMs = System.currentTimeMillis()
        val window = calls.settings.value.repeatMinutes * 60_000L
        val conditions = FilterActions.conditions(this)
        var caller: Caller? = null
        val subject = Subject(
            isCall = true,
            app = "phone",
            appLabel = "Phone",
            number = number,
            fromPerson = !hidden,
            hidden = hidden,
            unverified = details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED,
            at = conditions.now,
            region = lookup.region(),
            contact = { if (hidden) null else lookup.caller(raw).also { caller = it } },
            repeated = { isRepeatedCall(number, nowMs, calls.recent, window) },
            spamList = AppGraph.spamList::contains,
            state = { PhoneState(conditions.locked, conditions.dnd, conditions.charging, AppGraph.filters.filters.value.quiet.isActive(conditions.now)) },
        )
        val gate = runCatching { RuleEngine.decide(subject, AppGraph.rules.rules.value, AppGraph.rules.lists.value).gate }.getOrNull()
        val action = gate?.rule?.action?.takeIf { it.blocks } ?: Action.ALLOW
        respondToCall(details, response(action))
        calls.noteCall(number, nowMs)
        if (action == Action.ALLOW) return

        val who = caller?.name ?: number ?: "Hidden number"
        AppGraph.filters.record(
            SpamCatch(
                key = "call:$nowMs", appLabel = "Phone", title = who, text = "", at = nowMs, call = true,
                sender = caller?.name ?: number.orEmpty(), number = number.orEmpty(), action = action,
                rule = gate?.rule?.name, why = gate?.why.orEmpty(),
            ),
        )
        main.post {
            when {
                action == Action.SPAM -> FilterActions.spamAlert(this, who, key = "call:$nowMs", call = true)
                calls.settings.value.screenedGlyph -> {
                    val p = AppGraph.profile
                    val (anim, verb) = if (action == Action.SILENCE) Effects.callSilenced(p) to "Silenced" else Effects.callBlocked(p) to "Blocked"
                    AppGraph.alerts.offer(AlertQueue.Alert("call:$nowMs", "screened:$who", isCall = true, until = Until.UNLOCK, item = AlertGlyph(anim, "$verb: $who")))
                }
            }
        }
    }

    private fun response(action: Action): CallResponse {
        val b = CallResponse.Builder()
        when (action) {
            Action.SILENCE, Action.SPAM, Action.HIDE -> b.setSilenceCall(true)
            Action.REJECT -> b.setDisallowCall(true).setRejectCall(true)
            Action.REJECT_QUIET -> b.setDisallowCall(true).setRejectCall(true).setSkipCallLog(true).setSkipNotification(true)
            Action.ALLOW, Action.SHOW, Action.PROGRESS -> Unit
        }
        return b.build()
    }
}
