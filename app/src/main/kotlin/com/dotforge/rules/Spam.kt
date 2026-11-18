package com.dotforge.rules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
    /** Never block people saved in Contacts. */
    val allowContacts: Boolean = false,
    /** Never block starred contacts. */
    val allowStarred: Boolean = false,
    /** Senders (notification titles) the user allowed from a catch. */
    val allowedSenders: List<String> = emptyList(),
) {
    val needsContacts: Boolean get() = allowContacts || allowStarred

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

/** What Contacts knows about a notification's sender. */
enum class ContactStatus { UNKNOWN, CONTACT, STARRED }

object SpamFilter {

    /** The filter as suppress rules for [RuleEngine]: one for the words, one for the patterns. */
    fun rules(s: SpamSettings): List<Rule> {
        if (!s.enabled) return emptyList()
        val words = s.keywords.filter { it.isNotBlank() }
        val patterns = s.patterns.filter { it.isNotBlank() }
        return listOfNotNull(
            Rule(id = "spam-words", name = "Spam filter", keywords = words, suppress = true).takeIf { words.isNotEmpty() },
            Rule(id = "spam-patterns", name = "Spam filter", keywords = patterns, keywordMode = KeywordMode.REGEX, suppress = true)
                .takeIf { patterns.isNotEmpty() },
        )
    }

    /**
     * True when [info] is spam and nothing on the allow list covers it. [contact] is only asked when needed,
     * because it reads Contacts.
     */
    fun isSpam(info: NotificationInfo, s: SpamSettings, contact: () -> ContactStatus): Boolean {
        if (RuleEngine.match(info, rules(s)) !is MatchResult.Suppressed) return false
        if (s.allowedSenders.any { it.equals(info.title.trim(), ignoreCase = true) }) return false
        if (s.needsContacts) {
            val c = contact()
            if (s.allowContacts && c != ContactStatus.UNKNOWN) return false
            if (s.allowStarred && c == ContactStatus.STARRED) return false
        }
        return true
    }

    fun blockedOn(day: LocalDate, catches: List<SpamCatch>, zone: ZoneId = ZoneId.systemDefault()): Int =
        catches.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }

    /** True for a usable regular expression. */
    fun isValidPattern(p: String): Boolean = p.isNotBlank() && runCatching { Regex(p) }.isSuccess
}

@Serializable
private data class SpamState(val settings: SpamSettings = SpamSettings(), val catches: List<SpamCatch> = emptyList())

/** Spam filter settings and recent catches, saved as JSON. Call from one thread (the main thread). */
class SpamRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private var state = if (!file.exists()) SpamState()
    else runCatching { json.decodeFromString(SpamState.serializer(), file.readText()) }.getOrElse { SpamState() }

    private val _settings = MutableStateFlow(state.settings)
    val settings: StateFlow<SpamSettings> = _settings.asStateFlow()

    private val _catches = MutableStateFlow(state.catches)
    /** Newest first. */
    val catches: StateFlow<List<SpamCatch>> = _catches.asStateFlow()

    private fun save(next: SpamState) {
        state = next
        _settings.value = next.settings
        _catches.value = next.catches
        file.writeText(json.encodeToString(SpamState.serializer(), next))
    }

    fun update(transform: (SpamSettings) -> SpamSettings) = save(state.copy(settings = transform(state.settings)))

    fun record(info: NotificationInfo) {
        val c = SpamCatch(info.key, info.appLabel, info.title, info.text, info.postedAt)
        save(state.copy(catches = (listOf(c) + state.catches.filterNot { it.key == c.key }).take(MAX_CATCHES)))
    }

    /** Never block this sender again, and forget their catches. */
    fun allow(c: SpamCatch) {
        val who = c.title.trim()
        if (who.isEmpty()) {
            save(state.copy(catches = state.catches - c))
            return
        }
        val s = state.settings
        val allowed = if (s.allowedSenders.any { it.equals(who, ignoreCase = true) }) s.allowedSenders else s.allowedSenders + who
        save(
            SpamState(
                settings = s.copy(allowedSenders = allowed),
                catches = state.catches.filterNot { it.title.trim().equals(who, ignoreCase = true) },
            ),
        )
    }

    private companion object {
        const val MAX_CATCHES = 50
    }
}
