package com.dotforge.rules

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.GlyphLibrary
import com.dotforge.engine.Until
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.FilterEngine
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import com.dotforge.filter.Subject
import java.util.UUID
import kotlinx.serialization.Serializable

/** Keyword modes of version 2 rules (read when migrating). */
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
    SPAM("Spam", "A short warning: ! in a ring"),
    DESIGN("My design", "A design from Create"),
    /** A glyph from [com.dotforge.device.GlyphLibrary]; [Rule.designId] holds its id. */
    ICON("Glyph", "A glyph from the library"),
    /** The sending app's icon in dots (the default for a new app). */
    APP_ICON("App icon", "The app's own icon in dots"),
    /** A bell that swings (Reminders & to-dos). */
    BELL("Bell", "A bell swings while a dot runs round"),
    NONE("No glyph", "Only the sound"),
}

/**
 * What a rule does with the sound of what it lights for. The app's own sound is the default (we add nothing);
 * the other two silence the notification (a quiet copy stays in the shade) so there is never a double sound.
 */
@Serializable
enum class SoundMode(val label: String, val hint: String) {
    APP("App's own sound", "We add nothing"),
    NONE("No sound", "The notification is silenced; the glyph still shows"),
    CUSTOM("Custom tune", "The app's sound is silenced and your tune plays"),
}

/** What a ringing call shows: the caller's name, the glyph, or the glyph then the name. */
@Serializable
enum class CallShow(val label: String) { NAME("Name"), GLYPH("Glyph"), BOTH("Both") }

/** Moments on the phone itself that can light a design (Alerts > Apps > This phone). */
@Serializable
enum class Moment(val label: String, val sentence: String, val hint: String, val lasts: Boolean) {
    UNLOCK("Unlock", "you unlock the phone", "Plays as the phone unlocks", lasts = false),
    SHAKE("Shake", "you shake the phone", "Two quick shakes", lasts = false),
    CAMERA("Camera", "the camera is open", "While any app uses the camera", lasts = true),
    ALARM("Alarm or timer", "an alarm rings", "While an alarm or timer rings", lasts = true),
    HEADPHONES("Headphones", "headphones connect", "When wired or Bluetooth headphones connect", lasts = false),
}

/** What a rule applies to: notifications from apps (all, or [Rule.packages]), phone calls, or both. */
@Serializable
enum class Source { APPS, CALLS, EVERYTHING }

/**
 * What a rule does. [blocks] actions keep the item from lighting up with other rules and put it in the Spam
 * log; [ALLOW] keeps it out of every blocking rule. [PROGRESS] stands apart: it only picks which progress bars
 * reach the ring, and never decides whether a notification alerts.
 */
@Serializable
enum class Action(
    val label: String,
    val hint: String,
    val done: String,
    val calls: Boolean,
    val apps: Boolean,
    val blocks: Boolean,
) {
    SHOW("show a glyph", "Lights up, with an optional sound and vibration", "Shown", calls = false, apps = true, blocks = false),
    ALLOW("always allow", "Never treated as spam or blocked", "Allowed", calls = true, apps = true, blocks = false),
    SILENCE("silence it", "No sound or vibration; it stays in the shade (a call doesn't ring)", "Silenced", calls = true, apps = true, blocks = true),
    HIDE("hide it", "Removes the notification", "Hidden", calls = false, apps = true, blocks = true),
    SPAM("mark as spam", "Silenced, logged in Spam, with the spam glyph and sound", "Spam", calls = true, apps = true, blocks = true),
    REJECT("reject it", "Hangs up; it stays in the call log", "Rejected", calls = true, apps = false, blocks = true),
    REJECT_QUIET("reject it, no call log", "Hangs up and leaves no trace in the call log", "Rejected, no call log", calls = true, apps = false, blocks = true),
    /** A notification's progress bar (classic or Android 16 ProgressStyle) on the outer ring, while it lasts. */
    PROGRESS("show its progress", "Its progress bar fills the outer ring", "Progress", calls = false, apps = true, blocks = false),
    ;

    fun availableFor(source: Source): Boolean = when (source) {
        Source.APPS -> apps
        Source.CALLS -> calls
        Source.EVERYTHING -> apps && calls
    }
}

/**
 * A rule: "When [source] and [conditions], [action]" (for [Action.SHOW]: "show [glyph] until [until]", with an
 * optional sound and vibration). One engine ([FilterEngine]) tests notifications and calls alike.
 */
@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New rule",
    val enabled: Boolean = true,
    val source: Source = Source.APPS,
    /** Package names for [Source.APPS] and [Source.EVERYTHING]; empty = any app. */
    val packages: List<String> = emptyList(),
    val match: Match = Match(),
    val action: Action = Action.SHOW,
    val glyph: GlyphStyle = GlyphStyle.PULSE,
    /** The design for [GlyphStyle.DESIGN] (null = the newest design), or the library glyph's id for [GlyphStyle.ICON]. */
    val designId: String? = null,
    val until: Until = Until.READ,
    /** 1 high, 0 normal, -1 low. */
    val priority: Int = 0,
    /** A sound played with the glyph (a DotForge ringtone or a system sound), or null for none. */
    val soundUri: String? = null,
    /** The sound's name, for the rule sentence. */
    val soundName: String? = null,
    val vibrate: Boolean = false,
    /** What this rule changes from the design's own timing (null fields follow the design). */
    val timing: com.dotforge.device.TimingOverride? = null,
    /** The sound: the app's own (default), none, or [soundUri] instead of the app's. */
    val sound: SoundMode = SoundMode.APP,
    /** Phone calls: what a ringing call shows. */
    val callShow: CallShow = CallShow.BOTH,
    /** "Don't repeat for the same chat within 1 min". */
    val noRepeat: Boolean = false,
    /** A person: also light for their messages in group chats. */
    val groupChats: Boolean = false,
    /** A person: when they call, ring with this Glyph ringtone (its URI) instead of showing their glyph. */
    val callRingtone: String? = null,
    /**
     * A block rule ([Action.SPAM]) on calls: what a matching call gets. Null is Silence (it doesn't ring and
     * stays in the call log); [Action.REJECT] declines it; [Action.REJECT_QUIET] declines it with no call log
     * entry. Messages are hidden either way.
     */
    val callAction: Action? = null,
    val callRingtoneName: String? = null,
    /** Set for a phone moment (This phone); such a rule never matches a notification. */
    val moment: Moment? = null,
    /** Reminders: fill the ring this many minutes before the next event or alarm (0 = off). */
    val approachMinutes: Int = 0,
) {
    val usesContacts: Boolean get() = match.allConditions.any { it.field == Field.CONTACT || it.field == Field.STARRED }

    fun appliesTo(s: Subject): Boolean = moment == null && !(id.startsWith(PERSON_ID) && !groupChats && s.groupChat) && when (source) {
        Source.CALLS -> s.isCall
        Source.APPS -> !s.isCall && appOk(s)
        Source.EVERYTHING -> s.isCall || appOk(s)
    } && (if (s.isCall) action.calls else action.apps)

    private fun appOk(s: Subject) = packages.isEmpty() || packages.any { it.equals(s.app, ignoreCase = true) }
}

private const val PERSON_ID = "person-"

/** What Contacts knows about a notification's sender. */
enum class ContactStatus { UNKNOWN, CONTACT, STARRED }

/** The sender as found in Contacts; [name] is the contact's display name when found. */
/** A notification sender as Contacts knows them; [groups] are their Contacts groups (labels). */
data class Sender(val status: ContactStatus, val name: String? = null, val groups: Set<String> = emptySet())

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
    /** A message or call from a person: the only kind "not in Contacts" applies to. */
    val fromPerson: Boolean = false,
    /** A ringing call. */
    val isCall: Boolean = false,
    /** The conversation, for the re-post cooldown. */
    val conversation: String = key,
    val channel: String = "",
    val category: String = "",
    val bigText: String = "",
    val subText: String = "",
    val summary: String = "",
    /** Who sent it (see [com.dotforge.filter.Senders]); empty = unknown. */
    val sender: String = "",
    val email: String = "",
    /** The sender's number, normalised; null when there is none. */
    val number: String? = null,
    val groupChat: Boolean = false,
    val ongoing: Boolean = false,
)

/** How specific a rule is; earlier entries win. */
enum class Tier { EXACT, PERSON, APP_AND_WORDS, WORDS, APP, CALLER, ANY }

data class RuleMatch(val rule: Rule, val why: String)

/** The outcome for one item: the allow or blocking rule that decided (if any), else the rule that shows. */
data class Decision(val gate: RuleMatch? = null, val show: RuleMatch? = null) {
    val blocked: Boolean get() = gate?.rule?.action?.blocks == true
}

object RuleEngine {

    private fun positive(c: Condition) = !c.not && (c.field.kind != com.dotforge.filter.FieldKind.FLAG || c.cleanValues.firstOrNull()?.lowercase() != "no")

    fun tier(r: Rule): Tier {
        val cs = r.match.allConditions.filter { !it.not }
        val person = cs.filter { it.field in Field.PERSON }
        return when {
            person.any { it.op == Op.IS || it.op == Op.IN_LIST } -> Tier.EXACT
            // a Contacts group is people too, but a person named exactly (above) beats their group
            cs.any { it.field == Field.CONTACT_GROUP && it.op != Op.IS_NOT && it.op != Op.NOT_CONTAINS } ||
                person.any { it.op != Op.IS_NOT && it.op != Op.NOT_CONTAINS } ||
                cs.any { positive(it) && (it.field == Field.CONTACT || it.field == Field.STARRED || it.field == Field.REPEATED) } -> Tier.PERSON
            cs.any { it.field in Field.WORDS } && (r.packages.isNotEmpty() || cs.any { it.field == Field.APP }) -> Tier.APP_AND_WORDS
            cs.any { it.field in Field.WORDS } -> Tier.WORDS
            r.packages.isNotEmpty() || cs.any { it.field == Field.APP || it.field == Field.CHANNEL || it.field == Field.CATEGORY } -> Tier.APP
            cs.any { it.field in setOf(Field.CONTACT, Field.HIDDEN, Field.UNVERIFIED, Field.SPAM_LIST, Field.GROUP_CHAT, Field.ONGOING) } -> Tier.CALLER
            else -> Tier.ANY
        }
    }

    private val timed = setOf(Field.TIME, Field.DAY, Field.QUIET)

    /**
     * The fixed order rules are tried in; there is no user priority to set. Blocking and allow rules (spam) are
     * decided first, in [decide]; of the rules that show, the most specific wins: a person named exactly, then
     * a Contacts group, then an app with words, words, an app, and anything else ([tier]). Within a tier people
     * and groups beat apps, an allow rule beats a block, then rules with a time beat those without, then the
     * Alerts order.
     */
    private fun order(rules: List<IndexedValue<Rule>>) = rules.sortedWith(
        compareBy<IndexedValue<Rule>>(
            { tier(it.value) },
            // People win over apps: Mom's glyph shows, not WhatsApp's, even when the WhatsApp row names her too
            { if (SimpleRules.isPersonRule(it.value)) 0 else 1 },
            { if (it.value.action == Action.ALLOW) 0 else 1 },
            { -it.value.priority },
            { if (it.value.match.allConditions.any { c -> c.field in timed }) 0 else 1 },
            { it.index },
        ),
    )

    /**
     * Decides [s]: first the allow and blocking rules (spam first), the most specific one deciding, an allow
     * winning a tie; then, unless blocked, the best rule that shows a glyph. Specificity, then priority, then a
     * time limit, then list order. Tiers and the regex cache follow Glyphifier's RuleMatcher (Apache-2.0, NOTICE).
     */
    fun decide(s: Subject, rules: List<Rule>, lists: List<NamedList> = emptyList()): Decision {
        val live = rules.withIndex().filter { it.value.enabled && it.value.action != Action.PROGRESS && it.value.appliesTo(s) }
        fun first(cands: List<IndexedValue<Rule>>) =
            order(cands).firstNotNullOfOrNull { (_, r) -> explain(r, s, lists)?.let { RuleMatch(r, it) } }
        val gate = first(live.filter { it.value.action != Action.SHOW })
        if (gate?.rule?.action?.blocks == true) return Decision(gate, null)
        return Decision(gate, first(live.filter { it.value.action == Action.SHOW }))
    }

    /** The "Show progress" rule for [s] (most specific first), or null when no rule asks for its progress bar. */
    fun progress(s: Subject, rules: List<Rule>, lists: List<NamedList> = emptyList()): RuleMatch? =
        order(rules.withIndex().filter { it.value.enabled && it.value.action == Action.PROGRESS && it.value.appliesTo(s) })
            .firstNotNullOfOrNull { (_, r) -> explain(r, s, lists)?.let { RuleMatch(r, it) } }

    /** Why [rule] matches [s] in a few words, or null when it does not. Ignores [Rule.enabled]. */
    fun explain(rule: Rule, s: Subject, lists: List<NamedList> = emptyList()): String? {
        if (!rule.appliesTo(s)) return null
        val why = FilterEngine.explain(rule.match, s, lists) ?: return null
        val app = if (!s.isCall && rule.packages.isNotEmpty()) s.appLabel else null
        return listOfNotNull(app, why.takeIf { it != "anything" || app == null }?.let { if (it == "anything") if (s.isCall) "any call" else "any notification" else it })
            .joinToString(", ")
    }

    private val regexCache = HashMap<String, Regex?>()

    /** Compiled once; null for an invalid pattern, which never matches. */
    fun regex(pattern: String): Regex? = synchronized(regexCache) {
        regexCache.getOrPut(pattern) { runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
    }

    /** True for a usable regular expression. */
    fun isValidPattern(p: String): Boolean = p.isNotBlank() && runCatching { Regex(p) }.isSuccess

    /** The animation [style] shows for [info]. [design] returns a saved design's animation (null id = newest). */
    fun animation(
        style: GlyphStyle, designId: String?, info: NotificationInfo, profile: DeviceProfile, design: (String?) -> Animation?,
        appIcon: (String) -> Animation? = { null },
    ): Animation =
        when (style) {
            GlyphStyle.PULSE, GlyphStyle.NONE -> Effects.pulse(profile)
            GlyphStyle.RING -> Effects.chase(profile)
            GlyphStyle.FLASH -> Effects.flash(profile)
            GlyphStyle.SPARKLE -> Effects.sparkle(profile)
            GlyphStyle.COMET -> Effects.cometLaps(profile)
            GlyphStyle.LETTER -> Effects.blinkLetter(profile, info.appLabel.firstOrNull { it.isLetterOrDigit() } ?: '?')
            GlyphStyle.NAME -> com.dotforge.device.AlertEffects.name(profile, info.sender.ifBlank { info.title }.trim().ifBlank { info.appLabel })
            GlyphStyle.SPAM -> Effects.spam(profile)
            GlyphStyle.DESIGN -> design(designId) ?: Effects.pulse(profile)
            GlyphStyle.ICON -> GlyphLibrary.alert(profile, designId)
            GlyphStyle.APP_ICON -> appIcon(info.packageName) ?: GlyphLibrary.alert(profile, "chat")
            GlyphStyle.BELL -> com.dotforge.device.AlertEffects.reminder(profile)
        }

    fun animation(rule: Rule, info: NotificationInfo, profile: DeviceProfile, design: (String?) -> Animation?, appIcon: (String) -> Animation? = { null }): Animation =
        animation(rule.glyph, rule.designId, info, profile, design, appIcon)

    /**
     * The alert a showing rule posts: its glyph with the rule's timing (or, for a design, the design's own),
     * held until checked when the rule or the timing says so.
     */
    fun alert(
        rule: Rule, info: NotificationInfo, profile: DeviceProfile, design: (String?) -> Animation?,
        designTiming: (String?) -> com.dotforge.device.Timing? = { null }, isCall: Boolean = info.isCall,
        rank: Int = if (isCall) com.dotforge.engine.AlertQueue.RANK_CALL else com.dotforge.engine.AlertQueue.RANK_APP,
        dim: Boolean = false,
        appIcon: (String) -> Animation? = { null },
    ): com.dotforge.engine.AlertQueue.Alert<com.dotforge.engine.AlertGlyph> {
        val label = "${rule.name}: ${info.sender.ifBlank { info.title }.ifBlank { info.appLabel }}"
        val anim = animation(rule, info, profile, design, appIcon)
        val cooldown = if (rule.noRepeat) NO_REPEAT_MS else null
        val own = designTiming(rule.designId).takeIf { rule.glyph == GlyphStyle.DESIGN }
        var timing = rule.timing?.over(own ?: com.dotforge.device.Timing()) ?: own
        if (dim) timing = (timing ?: com.dotforge.device.Timing(showFor = if (rule.until == Until.TIMEOUT) com.dotforge.device.ShowFor.WHOLE else com.dotforge.device.ShowFor.UNTIL_CHECKED))
            .let { it.copy(brightness = minOf(it.brightness, DIM_PERCENT)) }
        if (timing == null) {
            return com.dotforge.engine.AlertQueue.Alert(info.key, info.conversation, isCall, rule.until, com.dotforge.engine.AlertGlyph(anim, label), rank = rank, cooldownMs = cooldown)
        }
        val plan = com.dotforge.device.Timings.plan(anim, timing)
        val until = if (plan.untilChecked && rule.until == Until.TIMEOUT) Until.READ else if (!plan.untilChecked && timing.showFor != com.dotforge.device.ShowFor.UNTIL_CHECKED && rule.timing?.showFor != null) Until.TIMEOUT else rule.until
        return com.dotforge.engine.AlertQueue.Alert(
            info.key, info.conversation, isCall, until, com.dotforge.engine.AlertGlyph(plan.animation, label),
            showMs = plan.showMs, againMs = plan.againMs, rank = rank, cooldownMs = cooldown,
        )
    }

    /** "Don't repeat for the same chat within 1 min". */
    const val NO_REPEAT_MS = 60_000L

    /** "During Quiet: Dim to 20%". */
    const val DIM_PERCENT = 20

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

    const val ALLOWED = "Allowed senders"
    const val BLOCKED = "Blocked senders"
    const val SPAM_WORDS = "Spam words"
    const val SPAM_PREFIXES = "Spam prefixes"

    private fun senderIn(list: String) = Match(
        all = false,
        conditions = listOf(Condition(Field.SENDER, Op.IN_LIST, list = list), Condition(Field.NUMBER, Op.IN_LIST, list = list)),
    )

    /** Built-in rules. Fixed ids let them be added back after deletion. */
    fun presets() = listOf(
        SimpleRules.phoneCalls(),
        Rule(id = "preset-messages", name = "Messages", packages = MESSAGE_APPS, glyph = GlyphStyle.NAME, until = Until.READ),
        Rule(
            id = "preset-otp", name = "OTP codes", match = Match(conditions = listOf(Condition(Field.MESSAGE, Op.REGEX, listOf(OTP_PATTERN)))),
            glyph = GlyphStyle.FLASH, until = Until.TIMEOUT,
        ),
        Rule(
            id = "preset-delivery", name = "Delivery", match = Match(conditions = listOf(Condition(Field.MESSAGE, Op.CONTAINS, DELIVERY_WORDS))),
            glyph = GlyphStyle.COMET, until = Until.TIMEOUT,
        ),
        progressPreset(),
        SimpleRules.reminders(),
    ) + SimpleRules.moments() + spamPresets()

    const val PROGRESS_PRESET = "preset-progress"

    /** Progress bars from any app on the ring (what the "Progress rings" switch did before rules could pick). */
    fun progressPreset() = Rule(id = PROGRESS_PRESET, name = "Progress bars", action = Action.PROGRESS)

    /** The spam filter's built-in rules, for calls and messages alike. */
    fun spamPresets(spamWords: Boolean = false, unknown: Boolean = false, patterns: List<String> = emptyList()) = listOf(
        Rule(id = "spam-allowed", name = ALLOWED, source = Source.EVERYTHING, match = senderIn(ALLOWED), action = Action.ALLOW),
        Rule(id = "spam-contacts", name = "Contacts", source = Source.EVERYTHING, match = Match(conditions = listOf(Condition.flag(Field.CONTACT))), action = Action.ALLOW),
        Rule(id = "spam-repeated", name = "Repeated callers", source = Source.CALLS, match = Match(conditions = listOf(Condition.flag(Field.REPEATED))), action = Action.ALLOW),
        Rule(id = "spam-blocked", name = BLOCKED, source = Source.EVERYTHING, match = senderIn(BLOCKED), action = Action.SPAM),
        Rule(
            id = "spam-prefixes", name = SPAM_PREFIXES, source = Source.EVERYTHING,
            match = Match(conditions = listOf(Condition(Field.NUMBER, Op.STARTS, list = SPAM_PREFIXES))), action = Action.SPAM,
        ),
        Rule(id = "spam-list", name = "Spam list", source = Source.EVERYTHING, match = Match(conditions = listOf(Condition.flag(Field.SPAM_LIST))), action = Action.SPAM),
        Rule(
            id = "spam-words", name = SPAM_WORDS, enabled = spamWords, source = Source.APPS,
            match = Match(
                all = false,
                conditions = listOf(Condition(Field.MESSAGE, Op.CONTAINS, list = SPAM_WORDS)) +
                    listOfNotNull(patterns.takeIf { it.isNotEmpty() }?.let { Condition(Field.MESSAGE, Op.REGEX, it) }),
            ),
            action = Action.SPAM,
        ),
        Rule(id = "spam-unknown", name = "Unknown senders", enabled = unknown, source = Source.APPS, match = Match(conditions = listOf(Condition.flag(Field.CONTACT, yes = false))), action = Action.SPAM),
        Rule(id = "spam-hidden", name = "Hidden numbers", enabled = false, source = Source.CALLS, match = Match(conditions = listOf(Condition.flag(Field.HIDDEN))), action = Action.SILENCE),
        // calls from numbers not in Contacts don't ring (Contacts and saved numbers still do)
        Rule(id = UNKNOWN_CALLS, name = "Unknown numbers", enabled = false, source = Source.CALLS, match = Match(conditions = listOf(Condition.flag(Field.CONTACT, yes = false))), action = Action.SILENCE),
        Rule(id = "spam-faked", name = "Faked numbers", enabled = false, source = Source.CALLS, match = Match(conditions = listOf(Condition.flag(Field.UNVERIFIED))), action = Action.REJECT),
    )

    fun presetLists(allowed: List<String> = emptyList(), words: List<String> = SpamSettings.DEFAULT_KEYWORDS) = listOf(
        NamedList(ALLOWED, allowed),
        NamedList(BLOCKED),
        NamedList(SPAM_WORDS, words),
        NamedList(SPAM_PREFIXES),
    )

    const val UNKNOWN_CALLS = "spam-unknown-calls"
    const val HIDDEN_CALLS = "spam-hidden"
    const val FAKED_CALLS = "spam-faked"
    const val SPAM_WORDS_RULE = "spam-words"
    const val UNKNOWN_SENDERS = "spam-unknown"

    /** Spam protections that are on by default: the community spam list, contacts and repeat callers allowed. */
    val DEFAULT_PROTECTIONS = setOf("spam-list", "spam-contacts", "spam-repeated")

    /** Call or spam protection that only works with the call screening role: an enabled blocking rule for calls. */
    fun needsCallScreening(rules: List<Rule>): Boolean =
        rules.any { it.enabled && it.source != Source.APPS && it.action.blocks }

    /** A spam-filter rule: anything that allows or blocks rather than shows a glyph or progress. */
    fun isSpamRule(r: Rule) = r.action != Action.SHOW && r.action != Action.PROGRESS

}
