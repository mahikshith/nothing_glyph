package com.dotforge.rules

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.engine.Until
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class KeywordMode(val label: String) { ANY("Any"), ALL("All"), NONE("None") }

/** What a rule shows on the matrix. */
@Serializable
enum class GlyphStyle(val label: String, val hint: String) {
    PULSE("Pulse", "Slow glow, in and out"),
    RING("Ring chase", "Three dots chase round the edge"),
    FLASH("Flash", "Three quick flashes"),
    SPARKLE("Sparkle", "Random twinkling dots"),
    COMET("Comet", "One bright comet, two laps"),
    LETTER("App letter", "The app's first letter blinks"),
    NAME("Sender name", "The sender's name scrolls by"),
    DESIGN("My design", "A design from Create"),
}

/**
 * One notification rule: "When [packages] from [people] mention [keywords], show [glyph] until [until]".
 * Empty slots mean any. The most specific matching rule wins (see [RuleEngine.tier]); [priority] only breaks
 * ties between equally specific rules, then list order does.
 */
@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New rule",
    val enabled: Boolean = true,
    /** Package names; empty = any app. */
    val packages: List<String> = emptyList(),
    /** Contact names, matched against the sender's contact name or the notification title. */
    val people: List<String> = emptyList(),
    /** Also match starred contacts. */
    val starred: Boolean = false,
    /** Also match people who are not in Contacts. */
    val unknown: Boolean = false,
    val keywords: List<String> = emptyList(),
    val keywordMode: KeywordMode = KeywordMode.ANY,
    /** Keywords are regular expressions (Advanced). */
    val regex: Boolean = false,
    val glyph: GlyphStyle = GlyphStyle.PULSE,
    /** The design for [GlyphStyle.DESIGN]; null = the newest design. */
    val designId: String? = null,
    val until: Until = Until.READ,
    /** 1 high, 0 normal, -1 low. */
    val priority: Int = 0,
) {
    val usesContacts: Boolean get() = people.any { it.isNotBlank() } || starred || unknown
    val words: List<String> get() = keywords.filter { it.isNotBlank() }
}

/** What Contacts knows about a notification's sender. */
enum class ContactStatus { UNKNOWN, CONTACT, STARRED }

/** The sender as found in Contacts; [name] is the contact's display name when found. */
data class Sender(val status: ContactStatus, val name: String? = null)

/** What we know about an incoming notification. */
data class NotificationInfo(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** Contact URIs the app attached (tel:, mailto: or content://). */
    val people: List<String> = emptyList(),
    /** A message or call from a person: the only kind "unknown senders" applies to. */
    val fromPerson: Boolean = false,
    /** A ringing call. */
    val isCall: Boolean = false,
    /** The conversation, for the re-post cooldown. */
    val conversation: String = key,
)

/** How specific a rule is; earlier entries win. */
enum class Tier { CONTACT, APP_AND_WORDS, WORDS, APP, ANY }

data class RuleMatch(val rule: Rule, val why: String)

object RuleEngine {

    fun tier(r: Rule): Tier = when {
        r.usesContacts -> Tier.CONTACT
        r.words.isNotEmpty() && r.packages.isNotEmpty() -> Tier.APP_AND_WORDS
        r.words.isNotEmpty() -> Tier.WORDS
        r.packages.isNotEmpty() -> Tier.APP
        else -> Tier.ANY
    }

    /**
     * The best rule for [info]: the most specific ([tier]), then the highest [Rule.priority], then the first in
     * the list. [sender] reads Contacts; it is called at most once, and only when a rule needs it.
     * Specificity tiers and the regex cache are adapted from Glyphifier's RuleMatcher (Apache-2.0, see NOTICE).
     */
    fun match(info: NotificationInfo, rules: List<Rule>, sender: () -> Sender? = { null }): RuleMatch? {
        val lookup = lazy(LazyThreadSafetyMode.NONE, sender)
        return rules.withIndex()
            .filter { it.value.enabled }
            .sortedWith(compareBy({ tier(it.value) }, { -it.value.priority }, { it.index }))
            .firstNotNullOfOrNull { (_, r) -> explain(r, info) { lookup.value }?.let { RuleMatch(r, it) } }
    }

    /** Why [rule] matches [info] in a few words, or null when it does not. Ignores [Rule.enabled]. */
    fun explain(rule: Rule, info: NotificationInfo, sender: () -> Sender? = { null }): String? {
        val why = mutableListOf<String>()
        if (rule.packages.isNotEmpty()) {
            if (rule.packages.none { it.equals(info.packageName, ignoreCase = true) }) return null
            why += info.appLabel
        }
        if (rule.usesContacts) why += people(rule, info, sender) ?: return null
        if (rule.words.isNotEmpty()) why += keywords(rule, "${info.title}\n${info.text}") ?: return null
        return why.joinToString(", ").ifEmpty { "any notification" }
    }

    private fun people(r: Rule, info: NotificationInfo, sender: () -> Sender?): String? {
        val names = r.people.filter { it.isNotBlank() }
        val title = info.title.trim()
        names.firstOrNull { title.contains(it.trim(), ignoreCase = true) }?.let { return "from $it" }
        val s = sender() ?: return null
        s.name?.let { n -> names.firstOrNull { it.trim().equals(n, ignoreCase = true) } }?.let { return "from $it" }
        if (r.starred && s.status == ContactStatus.STARRED) return "starred contact"
        if (r.unknown && info.fromPerson && s.status == ContactStatus.UNKNOWN) return "unknown sender"
        return null
    }

    private fun keywords(r: Rule, body: String): String? {
        val words = r.words
        val hit = { w: String -> if (r.regex) regex(w)?.containsMatchIn(body) == true else body.contains(w, ignoreCase = true) }
        val what = { w: String -> if (r.regex) "pattern" else "\"$w\"" }
        return when (r.keywordMode) {
            KeywordMode.ANY -> words.firstOrNull(hit)?.let { "mentions ${what(it)}" }
            KeywordMode.ALL -> if (words.all(hit)) "mentions " + if (words.size == 1) what(words[0]) else "all ${words.size}" else null
            KeywordMode.NONE -> if (words.none(hit)) "no " + if (words.size == 1) what(words[0]) else "listed words" else null
        }
    }

    private val regexCache = HashMap<String, Regex?>()

    /** Compiled once; null for an invalid pattern, which never matches. */
    fun regex(pattern: String): Regex? = synchronized(regexCache) {
        regexCache.getOrPut(pattern) { runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
    }

    /** True for a usable regular expression. */
    fun isValidPattern(p: String): Boolean = p.isNotBlank() && runCatching { Regex(p) }.isSuccess

    /** The animation [rule] shows for [info]. [design] returns a saved design's animation (null id = newest). */
    fun animation(rule: Rule, info: NotificationInfo, profile: DeviceProfile, design: (String?) -> Animation?): Animation =
        when (rule.glyph) {
            GlyphStyle.PULSE -> Effects.pulse(profile)
            GlyphStyle.RING -> Effects.chase(profile)
            GlyphStyle.FLASH -> Effects.flash(profile)
            GlyphStyle.SPARKLE -> Effects.sparkle(profile)
            GlyphStyle.COMET -> Effects.cometLaps(profile)
            GlyphStyle.LETTER -> Effects.blinkLetter(profile, info.appLabel.firstOrNull { it.isLetterOrDigit() } ?: '?')
            GlyphStyle.NAME -> Effects.marquee(profile, info.title.trim().ifBlank { info.appLabel }.take(24))
            GlyphStyle.DESIGN -> design(rule.designId) ?: Effects.pulse(profile)
        }

    /** Phone apps that post incoming and missed call notifications. */
    val CALL_APPS = listOf("com.google.android.dialer", "com.android.dialer", "com.android.server.telecom")

    val MESSAGE_APPS = listOf(
        "com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging", "org.thoughtcrime.securesms",
    )

    /** A 4-8 digit code near a word like OTP, code or verification, in either order. */
    const val OTP_PATTERN = """(otp|code|passcode|verif)\D{0,40}\b\d{4,8}\b|\b\d{4,8}\b\D{0,40}(otp|code|passcode|verif)"""

    val DELIVERY_WORDS = listOf(
        "out for delivery", "has been delivered", "was delivered", "arriving today", "your parcel", "your package", "courier",
    )

    /** Built-in rules. Fixed ids let them be added back after deletion. */
    fun presets() = listOf(
        Rule(id = "preset-calls", name = "Calls", packages = CALL_APPS, glyph = GlyphStyle.RING, until = Until.READ),
        Rule(id = "preset-messages", name = "Messages", packages = MESSAGE_APPS, glyph = GlyphStyle.NAME, until = Until.READ),
        Rule(
            id = "preset-otp", name = "OTP codes", keywords = listOf(OTP_PATTERN), regex = true,
            glyph = GlyphStyle.FLASH, until = Until.TIMEOUT,
        ),
        Rule(id = "preset-delivery", name = "Delivery", keywords = DELIVERY_WORDS, glyph = GlyphStyle.COMET, until = Until.TIMEOUT),
    )
}
