package com.dotforge.filter

import com.dotforge.calls.PhoneNumbers
import com.dotforge.rules.RuleEngine
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.Serializable

/** What a field holds, which decides its operators and value editor. */
enum class FieldKind { TEXT, NUMBER, FLAG, TIME, DAYS }

/** Where a field means something. */
enum class FieldScope { BOTH, NOTIFICATIONS, CALLS }

/**
 * Something a condition can test. Flags read as a phrase ([yes] / [no]); the others as "[label] [operator]
 * [values]".
 */
@Serializable
enum class Field(
    val label: String,
    val kind: FieldKind,
    val scope: FieldScope,
    val yes: String = "",
    val no: String = "",
) {
    SENDER("sender", FieldKind.TEXT, FieldScope.BOTH),
    NUMBER("phone number", FieldKind.NUMBER, FieldScope.BOTH),
    EMAIL("sender email", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    CONTACT("in Contacts", FieldKind.FLAG, FieldScope.BOTH, "the sender is in Contacts", "the sender is not in Contacts"),
    STARRED("starred", FieldKind.FLAG, FieldScope.BOTH, "the sender is starred", "the sender is not starred"),
    MESSAGE("title or text", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    TITLE("title", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    TEXT("text", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    BIG_TEXT("long text", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    SUB_TEXT("subtext", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    SUMMARY("summary", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    APP("app", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    CHANNEL("notification channel", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    CATEGORY("category", FieldKind.TEXT, FieldScope.NOTIFICATIONS),
    GROUP_CHAT("group chat", FieldKind.FLAG, FieldScope.NOTIFICATIONS, "it's a group chat", "it's a direct chat"),
    ONGOING("ongoing", FieldKind.FLAG, FieldScope.NOTIFICATIONS, "it's ongoing", "it's new"),
    REPEATED("calling again", FieldKind.FLAG, FieldScope.CALLS, "they call again soon", "it's their first call"),
    HIDDEN("hidden number", FieldKind.FLAG, FieldScope.CALLS, "the number is hidden", "the number is shown"),
    UNVERIFIED("caller ID check", FieldKind.FLAG, FieldScope.CALLS, "the caller ID check fails", "the caller ID check doesn't fail"),
    /** The sender's Contacts groups (labels), as Contacts shows them: "Family", "Work". */
    CONTACT_GROUP("Contacts group", FieldKind.TEXT, FieldScope.BOTH),
    SPAM_LIST("spam list", FieldKind.FLAG, FieldScope.BOTH, "the number is on the spam list", "the number isn't on the spam list"),
    TIME("time", FieldKind.TIME, FieldScope.BOTH),
    DAY("day", FieldKind.DAYS, FieldScope.BOTH),
    LOCKED("phone locked", FieldKind.FLAG, FieldScope.BOTH, "the phone is locked", "the phone is unlocked"),
    DND("Do Not Disturb", FieldKind.FLAG, FieldScope.BOTH, "Do Not Disturb is on", "Do Not Disturb is off"),
    CHARGING("charging", FieldKind.FLAG, FieldScope.BOTH, "the phone is charging", "the phone isn't charging"),
    QUIET("quiet hours", FieldKind.FLAG, FieldScope.BOTH, "it's quiet hours", "it's not quiet hours"),
    ;

    fun availableFor(calls: Boolean, notifications: Boolean): Boolean = when (scope) {
        FieldScope.BOTH -> true
        FieldScope.NOTIFICATIONS -> notifications
        FieldScope.CALLS -> calls
    }

    companion object {
        val PERSON = setOf(SENDER, NUMBER, EMAIL)
        val WORDS = setOf(MESSAGE, TITLE, TEXT, BIG_TEXT, SUB_TEXT, SUMMARY)
    }
}

/** How a condition compares. Matching is case-insensitive. */
@Serializable
enum class Op(val label: String, val advanced: Boolean = false) {
    IS("is"),
    IS_NOT("is not"),
    STARTS("starts with"),
    ENDS("ends with"),
    CONTAINS("contains"),
    NOT_CONTAINS("does not contain"),
    IN_LIST("is in list"),
    REGEX("matches regex", advanced = true),
    ;

    companion object {
        fun forKind(kind: FieldKind): List<Op> = when (kind) {
            FieldKind.TEXT, FieldKind.NUMBER -> entries
            FieldKind.FLAG, FieldKind.TIME, FieldKind.DAYS -> listOf(IS)
        }
    }
}

/**
 * One test: "[field] [op] [values]". Several values mean any of them ("is not" and "does not contain": none of
 * them). [list] adds a named list's entries to the values. A flag's value is "yes" or "no"; a time is
 * "22:00-07:00"; days are ISO numbers (1 = Monday). [not] negates the result.
 */
@Serializable
data class Condition(
    val field: Field = Field.MESSAGE,
    val op: Op = Op.CONTAINS,
    val values: List<String> = emptyList(),
    val list: String? = null,
    val not: Boolean = false,
) {
    val cleanValues: List<String> get() = values.map { it.trim() }.filter { it.isNotEmpty() }

    /** True when the condition has something to compare with. */
    val isComplete: Boolean get() = this.field.kind != FieldKind.TEXT && this.field.kind != FieldKind.NUMBER || cleanValues.isNotEmpty() || list != null

    companion object {
        fun flag(field: Field, yes: Boolean = true) = Condition(field, Op.IS, listOf(if (yes) "yes" else "no"))
    }
}

/**
 * A nested group (one level only): all or any of its conditions, optionally negated. [more] marks the group the
 * More conditions builder writes, so other groups (like a migrated rule's people) are never shown as its.
 */
@Serializable
data class Group(val all: Boolean = true, val conditions: List<Condition> = emptyList(), val not: Boolean = false, val more: Boolean = false)

/** What a rule tests: all (AND) or any (OR) of its conditions and groups. Empty matches everything. */
@Serializable
data class Match(val all: Boolean = true, val conditions: List<Condition> = emptyList(), val groups: List<Group> = emptyList()) {
    val isEmpty: Boolean get() = conditions.isEmpty() && groups.isEmpty()

    /** Every condition, top level and inside groups. */
    val allConditions: List<Condition> get() = conditions + groups.flatMap { it.conditions }
}

/** A list the user keeps ("Spam prefixes", "VIP senders") that several rules can share. */
@Serializable
data class NamedList(val name: String, val entries: List<String> = emptyList())

/** What Contacts knows about a sender or caller. */
data class Caller(val contact: Boolean, val starred: Boolean = false, val name: String? = null, val groups: Set<String> = emptySet())

/** The phone's state; null when unknown. */
data class PhoneState(val locked: Boolean? = null, val dnd: Boolean? = null, val charging: Boolean? = null, val quiet: Boolean? = null)

/**
 * The thing being tested: a notification or an incoming call. Contacts, repeated calls, the spam list and the
 * phone's state are read lazily, at most once, and only when a condition needs them.
 */
class Subject(
    val isCall: Boolean,
    val app: String = "",
    val appLabel: String = "",
    val channel: String = "",
    val category: String = "",
    val sender: String = "",
    val email: String = "",
    /** E.164 (or digits for odd formats); null when there is none or it is hidden. */
    val number: String? = null,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val subText: String = "",
    val summary: String = "",
    /** A message or call from a person (not a news app or a download). */
    val fromPerson: Boolean = false,
    val groupChat: Boolean = false,
    val ongoing: Boolean = false,
    val hidden: Boolean = false,
    val unverified: Boolean = false,
    val at: LocalDateTime = LocalDateTime.now(),
    /** The phone's region, for national numbers in conditions. */
    val region: String? = null,
    contact: () -> Caller? = { null },
    repeated: () -> Boolean = { false },
    spamList: (String) -> Boolean = { false },
    state: () -> PhoneState = { PhoneState() },
) {
    val caller: Caller? by lazy(LazyThreadSafetyMode.NONE, contact)
    val isRepeated: Boolean by lazy(LazyThreadSafetyMode.NONE, repeated)
    val onSpamList: Boolean by lazy(LazyThreadSafetyMode.NONE) { number?.let(spamList) == true }
    val phone: PhoneState by lazy(LazyThreadSafetyMode.NONE, state)
}

/** The filter engine shared by notification rules, call rules and spam rules. */
object FilterEngine {
    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun matches(m: Match, s: Subject, lists: List<NamedList> = emptyList()): Boolean {
        if (m.isEmpty) return true
        val tests = m.conditions.asSequence().map { { test(it, s, lists) } } + m.groups.asSequence().map { { group(it, s, lists) } }
        return if (m.all) tests.all { it() } else tests.any { it() }
    }

    /** Why [m] matches [s] in a few words, or null when it doesn't. */
    fun explain(m: Match, s: Subject, lists: List<NamedList> = emptyList()): String? {
        if (!matches(m, s, lists)) return null
        val hits = m.conditions.filter { test(it, s, lists) }.map(::describe) +
            m.groups.filter { group(it, s, lists) }.map { g -> describe(g) }
        return (if (m.all) hits else hits.take(1)).joinToString(", ").ifEmpty { "anything" }
    }

    private fun group(g: Group, s: Subject, lists: List<NamedList>): Boolean {
        val r = if (g.conditions.isEmpty()) true
        else if (g.all) g.conditions.all { test(it, s, lists) } else g.conditions.any { test(it, s, lists) }
        return r != g.not
    }

    /**
     * A condition on a list that no longer exists matches nothing, whatever its test: "isn't in list" fails
     * too, so deleting a list never widens a rule.
     */
    fun test(c: Condition, s: Subject, lists: List<NamedList>): Boolean {
        if (c.list != null && missingList(c, lists)) return false
        return raw(c, s, lists) != c.not
    }

    /** True when [c] points at a list that isn't in [lists]. */
    fun missingList(c: Condition, lists: List<NamedList>): Boolean =
        c.list != null && lists.none { it.name.equals(c.list, ignoreCase = true) }

    private fun valuesOf(c: Condition, lists: List<NamedList>): List<String> {
        val listed = c.list?.let { name -> lists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.entries.orEmpty() }.orEmpty()
        return (c.cleanValues + listed.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
    }

    private fun raw(c: Condition, s: Subject, lists: List<NamedList>): Boolean = when (c.field.kind) {
        FieldKind.FLAG -> flag(c.field, s)?.let { it == (c.cleanValues.firstOrNull()?.lowercase() != "no") } ?: false
        FieldKind.TIME -> c.cleanValues.firstOrNull()?.let { inRange(it, s.at.toLocalTime()) } ?: false
        FieldKind.DAYS -> s.at.dayOfWeek.value.toString() in c.cleanValues
        FieldKind.TEXT, FieldKind.NUMBER -> {
            val values = valuesOf(c, lists)
            val op = if (c.op == Op.IN_LIST) Op.IS else c.op
            when (op) {
                Op.IS_NOT -> values.none { compare(c.field, Op.IS, s, it) }
                Op.NOT_CONTAINS -> values.none { compare(c.field, Op.CONTAINS, s, it) }
                else -> values.any { compare(c.field, op, s, it) }
            }
        }
    }

    private fun flag(f: Field, s: Subject): Boolean? = when (f) {
        // "not in Contacts" needs a person we could look up; without Contacts access we don't know
        Field.CONTACT -> if (!s.fromPerson || s.hidden) (if (s.hidden) false else null) else s.caller?.contact
        Field.STARRED -> if (!s.fromPerson || s.hidden) false else s.caller?.starred
        Field.GROUP_CHAT -> s.groupChat
        Field.ONGOING -> s.ongoing
        Field.REPEATED -> s.isRepeated
        Field.HIDDEN -> s.hidden
        Field.UNVERIFIED -> s.unverified
        Field.SPAM_LIST -> s.onSpamList
        Field.LOCKED -> s.phone.locked
        Field.DND -> s.phone.dnd
        Field.CHARGING -> s.phone.charging
        Field.QUIET -> s.phone.quiet
        else -> null
    }

    /** The texts a field compares against (an app is its package or its name). */
    private fun texts(f: Field, s: Subject): List<String> = when (f) {
        Field.SENDER -> listOf(s.sender, s.caller.takeIf { s.fromPerson && !s.isCall && s.sender.isBlank() }?.name.orEmpty())
        Field.EMAIL -> listOf(s.email)
        Field.MESSAGE -> listOf(listOf(s.title, s.text, s.bigText).filter { it.isNotBlank() }.joinToString("\n"))
        Field.TITLE -> listOf(s.title)
        Field.TEXT -> listOf(s.text)
        Field.BIG_TEXT -> listOf(s.bigText)
        Field.SUB_TEXT -> listOf(s.subText)
        Field.SUMMARY -> listOf(s.summary)
        Field.APP -> listOf(s.app, s.appLabel)
        Field.CHANNEL -> listOf(s.channel)
        Field.CATEGORY -> listOf(s.category)
        Field.CONTACT_GROUP -> if (!s.fromPerson || s.hidden) emptyList() else s.caller?.groups?.toList().orEmpty()
        else -> emptyList()
    }.filter { it.isNotBlank() }

    private fun compare(f: Field, op: Op, s: Subject, value: String): Boolean {
        if (f == Field.NUMBER) return number(op, s, value)
        val v = value.trim()
        return texts(f, s).any { t0 ->
            val t = t0.trim()
            when (op) {
                Op.IS, Op.IN_LIST -> t.equals(v, ignoreCase = true)
                Op.STARTS -> t.startsWith(v, ignoreCase = true)
                Op.ENDS -> t.endsWith(v, ignoreCase = true)
                Op.CONTAINS -> t.contains(v, ignoreCase = true)
                Op.REGEX -> RuleEngine.regex(v)?.containsMatchIn(t) == true
                Op.IS_NOT, Op.NOT_CONTAINS -> false
            }
        }
    }

    /**
     * Numbers compare normalised: "+91 98…", "0098…", "098…" and "98…" (in India) all mean the same start;
     * ends with and contains compare digits only.
     */
    private fun number(op: Op, s: Subject, value: String): Boolean {
        val n = s.number?.takeIf { it.isNotBlank() } ?: return false
        val digits = n.filter { it.isDigit() }
        val vd = value.filter { it.isDigit() }
        return when (op) {
            Op.IS, Op.IN_LIST -> PhoneNumbers.key(value, s.region) == n || (vd.isNotEmpty() && vd == digits)
            Op.STARTS -> PhoneNumbers.startsWith(n, value, s.region)
            Op.ENDS -> vd.isNotEmpty() && digits.endsWith(vd)
            Op.CONTAINS -> vd.isNotEmpty() && digits.contains(vd)
            Op.REGEX -> RuleEngine.regex(value.trim())?.let { it.containsMatchIn(n) || it.containsMatchIn(digits) } == true
            Op.IS_NOT, Op.NOT_CONTAINS -> false
        }
    }

    /** "22:00-07:00" (the end is not included; a range past midnight wraps). */
    fun inRange(range: String, t: LocalTime): Boolean {
        val (a, b) = parseRange(range) ?: return false
        return if (b > a) t >= a && t < b else t >= a || t < b
    }

    fun parseRange(range: String): Pair<LocalTime, LocalTime>? {
        val parts = range.split('-', '–').map { it.trim() }
        if (parts.size != 2) return null
        fun time(p: String): LocalTime? {
            val hm = p.split(':').map { it.toIntOrNull() }
            if (hm.size != 2 || hm.any { it == null }) return null
            val (h, m) = hm.map { it!! }
            return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        }
        return time(parts[0])?.let { a -> time(parts[1])?.let { b -> a to b } }
    }

    // ---- text -----------------------------------------------------------------------------------

    private fun quoted(v: String) = "\"$v\""

    /** "sender starts with "+44" or "+1"", "the sender is in Contacts", "title or text is in list "Spam words"". */
    fun describe(c: Condition, label: (String) -> String = { it }): String {
        val body = when (c.field.kind) {
            FieldKind.FLAG -> if (c.cleanValues.firstOrNull()?.lowercase() == "no") c.field.no else c.field.yes
            FieldKind.TIME -> "the time is " + (c.cleanValues.firstOrNull()?.replace("-", "–") ?: "…")
            FieldKind.DAYS -> "the day is " + c.cleanValues.mapNotNull { it.toIntOrNull()?.takeIf { d -> d in 1..7 } }.sorted()
                .joinToString(", ") { DAY_NAMES[it - 1] }.ifEmpty { "…" }
            else -> {
                val vals = c.cleanValues.map { if (c.field == Field.APP) label(it) else it }
                val shown = when (vals.size) {
                    0 -> ""
                    1, 2 -> vals.joinToString(" or ") { quoted(it) }
                    else -> "${quoted(vals[0])} or ${vals.size - 1} more"
                }
                val list = c.list?.let { "list ${quoted(it)}" }
                val what = listOfNotNull(shown.ifEmpty { null }, list).joinToString(" or ").ifEmpty { "…" }
                val op = if (c.op == Op.IN_LIST) "is in" else c.op.label
                "${c.field.label} $op $what"
            }
        }
        return if (c.not) "not ($body)" else body
    }

    fun describe(g: Group, label: (String) -> String = { it }): String {
        val inner = g.conditions.joinToString(if (g.all) " and " else " or ") { describe(it, label) }.ifEmpty { "anything" }
        return (if (g.not) "not " else "") + "($inner)"
    }

    /** The whole match as words: "sender starts with "+44" and title or text contains "loan"". */
    fun describe(m: Match, label: (String) -> String = { it }): String {
        val parts = m.conditions.map { describe(it, label) } + m.groups.map { describe(it, label) }
        return parts.joinToString(if (m.all) " and " else " or ")
    }

    fun dayName(d: DayOfWeek) = DAY_NAMES[d.value - 1]
}
