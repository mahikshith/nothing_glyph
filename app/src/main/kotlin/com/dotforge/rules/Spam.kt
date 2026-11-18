package com.dotforge.rules

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class SpamSettings(
    val enabled: Boolean = false,
    /** Plain words: any one of them marks a notification as spam. */
    val keywords: List<String> = DEFAULT_KEYWORDS,
    /** Regular expressions (Advanced). */
    val patterns: List<String> = emptyList(),
    /** Messages and calls from people who are not in Contacts. */
    val blockUnknown: Boolean = false,
    /** Never block people saved in Contacts. */
    val allowContacts: Boolean = false,
    /** Never block starred contacts. */
    val allowStarred: Boolean = false,
    /** Senders (notification titles) the user allowed from a catch. */
    val allowedSenders: List<String> = emptyList(),
) {
    val needsContacts: Boolean get() = enabled && (blockUnknown || allowContacts || allowStarred)

    companion object {
        val DEFAULT_KEYWORDS = listOf("lottery", "you have won", "loan approved", "pre-approved", "claim your prize")
    }
}

/** A notification the spam filter kept off the Glyphs. */
@Serializable
data class SpamCatch(
    val key: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val at: Long,
)

object SpamFilter {

    /**
     * Why [info] is spam ("spam word "lottery"", "unknown sender"), or null when it is not or the allow list
     * covers it. [sender] reads Contacts and is only called when a setting needs it.
     */
    fun check(info: NotificationInfo, s: SpamSettings, sender: () -> Sender?): String? {
        if (!s.enabled) return null
        val body = "${info.title}\n${info.text}"
        val reason = s.keywords.firstOrNull { it.isNotBlank() && body.contains(it, ignoreCase = true) }?.let { "spam word \"$it\"" }
            ?: s.patterns.firstOrNull { it.isNotBlank() && RuleEngine.regex(it)?.containsMatchIn(body) == true }?.let { "spam pattern" }
            ?: if (s.blockUnknown && info.fromPerson && sender()?.status == ContactStatus.UNKNOWN) "unknown sender" else null
        if (reason == null) return null
        if (s.allowedSenders.any { it.equals(info.title.trim(), ignoreCase = true) }) return null
        if (s.allowContacts || s.allowStarred) {
            val c = sender()?.status
            if (s.allowContacts && (c == ContactStatus.CONTACT || c == ContactStatus.STARRED)) return null
            if (s.allowStarred && c == ContactStatus.STARRED) return null
        }
        return reason
    }

    fun blockedOn(day: LocalDate, catches: List<SpamCatch>, zone: ZoneId = ZoneId.systemDefault()): Int =
        catches.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }
}
