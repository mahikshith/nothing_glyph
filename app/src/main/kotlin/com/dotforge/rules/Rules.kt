package com.dotforge.rules

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class KeywordMode(val label: String) { ANY("Any word"), ALL("All words"), NONE("None of the words"), REGEX("Regex") }

@Serializable
enum class Effect(val label: String) {
    PULSE("Pulse"), RING("Ring chase"), LETTER("Sender letter"), FLASH("Flash"),
    MARQUEE("Scroll sender"), SPARKLE("Sparkle"), CUSTOM("My Studio animation"),
}

/** One notification rule. Rules are checked top to bottom and the first match wins, so list order is priority. */
@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New rule",
    val enabled: Boolean = true,
    /** Package names; empty = any app. */
    val packages: List<String> = emptyList(),
    /** Sender / conversation names matched against the notification title; empty = anyone. */
    val senders: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val keywordMode: KeywordMode = KeywordMode.ANY,
    val effect: Effect = Effect.PULSE,
    /** Keep showing until the notification is dismissed. */
    @SerialName("hold") val holdUntilDismissed: Boolean = false,
    /** Stop showing as soon as the phone is unlocked. */
    val clearOnUnlock: Boolean = true,
    /** Spam / ignore: matching notifications never light up and stop lower rules. */
    val suppress: Boolean = false,
    /** Still light up during Do Not Disturb. */
    val breakDnd: Boolean = false,
)

/** What we know about an incoming notification. */
data class NotificationInfo(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

sealed interface MatchResult {
    data class Show(val rule: Rule) : MatchResult
    data class Suppressed(val rule: Rule) : MatchResult
    data object NoMatch : MatchResult
}

object RuleEngine {

    fun match(info: NotificationInfo, rules: List<Rule>): MatchResult {
        val body = "${info.title}\n${info.text}"
        for (r in rules) {
            if (!r.enabled) continue
            if (r.packages.isNotEmpty() && r.packages.none { it.equals(info.packageName, ignoreCase = true) }) continue
            if (r.senders.isNotEmpty() && r.senders.none { info.title.contains(it, ignoreCase = true) }) continue
            if (!keywordsMatch(r, body)) continue
            return if (r.suppress) MatchResult.Suppressed(r) else MatchResult.Show(r)
        }
        return MatchResult.NoMatch
    }

    private fun keywordsMatch(r: Rule, body: String): Boolean {
        val words = r.keywords.filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        return when (r.keywordMode) {
            KeywordMode.ANY -> words.any { body.contains(it, ignoreCase = true) }
            KeywordMode.ALL -> words.all { body.contains(it, ignoreCase = true) }
            KeywordMode.NONE -> words.none { body.contains(it, ignoreCase = true) }
            // an invalid pattern simply never matches
            KeywordMode.REGEX -> words.any { p ->
                runCatching { Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(body) }.getOrDefault(false)
            }
        }
    }

    fun animationFor(rule: Rule, info: NotificationInfo, profile: DeviceProfile, custom: Animation?): Animation {
        val who = info.title.ifBlank { info.appLabel }
        return when (rule.effect) {
            Effect.PULSE -> Effects.pulse(profile)
            Effect.RING -> Effects.ringChase(profile)
            Effect.LETTER -> Effects.blinkLetter(profile, who.firstOrNull { it.isLetterOrDigit() } ?: '?')
            Effect.FLASH -> Effects.flash(profile)
            Effect.MARQUEE -> Effects.marquee(profile, who.take(24))
            Effect.SPARKLE -> Effects.sparkle(profile)
            Effect.CUSTOM -> custom ?: Effects.pulse(profile)
        }
    }

    /** The three rules a fresh install starts with. */
    fun starterRules() = listOf(
        Rule(
            name = "Spam filter",
            keywords = listOf("otp expired", "lottery", "you have won", "loan approved"),
            suppress = true,
        ),
        Rule(
            name = "Messages",
            packages = listOf("com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging"),
            effect = Effect.LETTER,
            holdUntilDismissed = true,
        ),
        Rule(name = "Everything else", effect = Effect.PULSE),
    )
}

/** Rules saved as JSON in app storage. Call from one thread (the UI thread). */
class RulesRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val serializer = ListSerializer(Rule.serializer())

    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private fun load(): List<Rule> =
        if (!file.exists()) RuleEngine.starterRules()
        else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { RuleEngine.starterRules() }

    private fun save(list: List<Rule>) {
        _rules.value = list
        file.writeText(json.encodeToString(serializer, list))
    }

    fun upsert(rule: Rule) {
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == rule.id }
        if (i >= 0) list[i] = rule else list += rule
        save(list)
    }

    fun delete(id: String) = save(_rules.value.filterNot { it.id == id })

    /** Moves a rule up (negative [delta]) or down the list; out-of-range moves are ignored. */
    fun move(id: String, delta: Int) {
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(list)
    }
}
