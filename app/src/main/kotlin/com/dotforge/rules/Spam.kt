package com.dotforge.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * The spam alert: what plays when a call or message is marked as spam. By default a silent spam glyph; the
 * glyph and the sound can each be changed or switched off.
 *
 * The other fields are the version 2 spam filter's settings, read once to move them into spam rules (see
 * [RulesRepository]).
 */
@Serializable
data class SpamSettings(
    val glyphOn: Boolean = true,
    val glyph: GlyphStyle = GlyphStyle.SPAM,
    val designId: String? = null,
    /** A composition's or a system sound's URI; null = no sound. */
    val soundUri: String? = null,
    val soundName: String? = null,
    // ---- version 2, migrated into rules ----
    val enabled: Boolean = false,
    val keywords: List<String> = DEFAULT_KEYWORDS,
    val patterns: List<String> = emptyList(),
    val blockUnknown: Boolean = false,
    val allowContacts: Boolean = false,
    val allowStarred: Boolean = false,
    val allowedSenders: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_KEYWORDS = listOf("lottery", "you have won", "loan approved", "pre-approved", "claim your prize")
    }
}

/** One entry of the Spam log: a call or notification a rule silenced, hid, rejected or marked as spam. */
@Serializable
data class SpamCatch(
    val key: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val at: Long,
    val call: Boolean = false,
    val packageName: String = "",
    /** The sender's name (or the title when there is none). */
    val sender: String = "",
    /** The sender's normalised number, or empty. */
    val number: String = "",
    val action: Action = Action.SPAM,
    val rule: String? = null,
    val why: String = "",
) {
    /** What "Allow this sender" adds to the allowed list: the number, else the sender's name. */
    val senderKey: String get() = number.ifBlank { sender.ifBlank { title } }.trim()
}

object SpamFilter {
    fun blockedOn(day: LocalDate, catches: List<SpamCatch>, zone: ZoneId = ZoneId.systemDefault()): Int =
        catches.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }
}
