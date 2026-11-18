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
    MARQUEE("Scroll sender"), SPARKLE("Sparkle"), CUSTOM("My design"),
}

/**
 * One notification rule: "when [packages] from [senders] mention [keywords], light up [effect]". Rules are
 * checked top to bottom and the first match wins, so list order is priority.
 */
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
    /** Spam: matching notifications never light up and stop lower rules. Only the spam filter makes these. */
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
    /** Contact URIs the app attached (tel:, mailto: or content://), for the spam filter's allow list. */
    val people: List<String> = emptyList(),
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

    /** Phone apps that post incoming and missed call notifications. */
    val CALL_APPS = listOf("com.google.android.dialer", "com.android.dialer", "com.android.server.telecom")

    val MESSAGE_APPS = listOf(
        "com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging", "org.thoughtcrime.securesms",
    )

    /** A 4-8 digit code near a word like OTP, code or verification, in either order. */
    const val OTP_PATTERN = """(otp|code|passcode|verif)\D{0,40}\b\d{4,8}\b|\b\d{4,8}\b\D{0,40}(otp|code|passcode|verif)"""

    /** Built-in rules, so the list never starts empty. Fixed ids let them be added back after deletion. */
    fun presets() = listOf(
        Rule(id = "preset-calls", name = "Calls", packages = CALL_APPS, effect = Effect.RING, holdUntilDismissed = true),
        Rule(
            id = "preset-otp", name = "OTP", keywords = listOf(OTP_PATTERN), keywordMode = KeywordMode.REGEX,
            effect = Effect.FLASH,
        ),
        Rule(
            id = "preset-messages", name = "Messages", packages = MESSAGE_APPS, effect = Effect.LETTER,
            holdUntilDismissed = true,
        ),
    )
}

/** Rules saved as JSON in app storage. Call from one thread (the UI thread). */
class RulesRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val serializer = ListSerializer(Rule.serializer())

    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    // Spam rules from earlier versions are dropped: the spam filter has its own settings now.
    private fun load(): List<Rule> =
        if (!file.exists()) RuleEngine.presets()
        else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { RuleEngine.presets() }
            .filterNot { it.suppress }

    private fun save(list: List<Rule>) {
        _rules.value = list
        file.writeText(json.encodeToString(serializer, list))
    }

    /** Replaces the rule with the same id, or adds a new one at the top (most specific rules usually win). */
    fun upsert(rule: Rule) {
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == rule.id }
        if (i >= 0) list[i] = rule else list.add(0, rule)
        save(list)
    }

    fun delete(id: String) = save(_rules.value.filterNot { it.id == id })

    /** Adds back any built-in preset that is missing, at the end. */
    fun addPresets() {
        val have = _rules.value.map { it.id }.toSet()
        save(_rules.value + RuleEngine.presets().filterNot { it.id in have })
    }
}
