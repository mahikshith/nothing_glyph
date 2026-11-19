package com.dotforge.rules

import com.dotforge.filter.Caller
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.PhoneState
import com.dotforge.filter.Subject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Would have blocked 3" and "Would have lit 2 times": a rule run against the last 30 days of History, each entry
 * at its own time. What History doesn't know is never guessed as "no":
 * - "Only when locked" uses the lock state stored with the entry; older entries without one skip that part.
 * - Contacts are looked up now ([contacts]), when Contacts can be read; without it, a Contacts part can't count.
 * - The notification type is stored from now on; an entry without one can't count a notification type part.
 * - Do Not Disturb, charging, quiet hours, repeat callers, the spam list and group chats aren't stored.
 * An entry that needs a part it can't count isn't counted, and the result says so ([Count.partial]).
 */
object Counts {
    data class Count(val n: Int, val partial: Boolean) {
        /** Nothing counted, and only because a part couldn't be: "Can't count this part yet", never 0. */
        val unknown: Boolean get() = n == 0 && partial
    }

    private val CONTACT_FIELDS = setOf(Field.CONTACT, Field.STARRED, Field.CONTACT_GROUP)
    private val NEVER_STORED = setOf(
        Field.DND, Field.CHARGING, Field.QUIET, Field.REPEATED, Field.SPAM_LIST, Field.UNVERIFIED, Field.GROUP_CHAT, Field.ONGOING,
        Field.EMAIL, Field.BIG_TEXT, Field.SUB_TEXT, Field.SUMMARY, Field.CATEGORY,
    )

    fun count(
        rule: Rule, history: List<HistoryEntry>, region: String?, lists: List<NamedList> = emptyList(),
        contacts: ((HistoryEntry) -> Caller?)? = null, zone: ZoneId = ZoneId.systemDefault(),
    ): Count {
        val fields = rule.match.allConditions.map { it.field }.toSet()
        val noLock = rule.copy(match = without(rule.match, Field.LOCKED))
        var n = 0
        var partial = false
        for (e in history) {
            val unknown = when {
                fields.any { it in NEVER_STORED } -> true
                fields.any { it in CONTACT_FIELDS } && contacts == null -> true
                Field.CHANNEL in fields && !e.call && e.channel.isBlank() -> true
                else -> false
            }
            if (unknown) { partial = true; continue }
            val r = if (Field.LOCKED in fields && e.locked == null) noLock else rule
            val hidden = e.call && e.number.isBlank()
            val s = Subject(
                isCall = e.call, app = e.packageName, appLabel = e.appLabel, channel = e.channel, sender = e.sender,
                number = e.number.ifBlank { null }, title = e.title, text = e.text, fromPerson = !hidden, hidden = hidden,
                at = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.at), zone), region = region,
                contact = { contacts?.invoke(e) }, state = { PhoneState(locked = e.locked) },
            )
            if (RuleEngine.explain(r, s, lists) != null) n++
        }
        return Count(n, partial)
    }

    /** [m] without the conditions on [f] ("skip that part"). */
    private fun without(m: Match, f: Field): Match {
        fun keep(c: Condition) = c.field != f
        return m.copy(conditions = m.conditions.filter(::keep), groups = m.groups.map { g -> g.copy(conditions = g.conditions.filter(::keep)) })
    }

    /** "Would have blocked 3 in the last 30 days", "at least 3", or "Can't count this part yet". */
    fun blockedText(c: Count): String = when {
        c.unknown -> "Can't count this part yet"
        c.n == 0 -> "Would have blocked nothing in the last 30 days"
        c.partial -> "Would have blocked at least ${c.n} in the last 30 days"
        else -> "Would have blocked ${c.n} in the last 30 days"
    }

    /** "Would have lit 2 times in the last 30 days", "Wouldn't have lit…", or "Can't count this part yet". */
    fun litText(c: Count): String {
        val times = if (c.n == 1) "1 time" else "${c.n} times"
        return when {
            c.unknown -> "Can't count this part yet"
            c.n == 0 -> "Wouldn't have lit in the last 30 days"
            c.partial -> "Would have lit at least $times in the last 30 days"
            else -> "Would have lit $times in the last 30 days"
        }
    }
}
