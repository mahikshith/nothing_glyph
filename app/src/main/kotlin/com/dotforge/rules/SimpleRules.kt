package com.dotforge.rules

import com.dotforge.engine.Until
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Match
import com.dotforge.filter.Op
import java.util.UUID

/** "Block senders that …": how the text is compared. */
enum class PatternType(val label: String, val op: Op) {
    STARTS("Start with", Op.STARTS),
    ENDS("End with", Op.ENDS),
    CONTAINS("Contain", Op.CONTAINS),
    IS("Are exactly", Op.IS),
}

/** Where a block rule applies, as chips. */
enum class Where(val label: String) { CALLS("Calls"), SMS("SMS"), WHATSAPP("WhatsApp"), ANY_APP("Any app") }

/**
 * A simple block rule: senders that [type] [text], in [where] (and in [apps], app packages from an app's
 * sheet that no chip covers).
 */
data class BlockPattern(val type: PatternType, val text: String, val where: Set<Where>, val apps: List<String> = emptyList()) {
    val label: String get() = "${type.label} “$text”"
}

/** The glyph a simple sheet picks: a library glyph ([GlyphStyle.ICON] + id), a design, or another style. */
data class GlyphChoice(val style: GlyphStyle, val id: String? = null) {
    companion object {
        fun icon(id: String) = GlyphChoice(GlyphStyle.ICON, id)
        fun of(r: Rule) = GlyphChoice(r.glyph, r.designId.takeIf { r.glyph == GlyphStyle.ICON || r.glyph == GlyphStyle.DESIGN })
    }
}

/**
 * What the simple Alerts sheets show and write. Every sheet writes a normal [Rule] the filter engine runs, so
 * the full editor (Advanced) can open the same rule.
 * - An app: a [Action.SHOW] rule for the app's package, optionally only from senders picked by name.
 * - A person: a [Action.SHOW] rule for any app whose sender is the person (by name or number).
 * - A block rule: a [Action.SPAM] rule whose sender or number starts with, ends with, contains or is the text.
 */
object SimpleRules {
    val SMS_APPS = listOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.messaging", "com.android.mms",
    )
    val WHATSAPP_APPS = listOf("com.whatsapp", "com.whatsapp.w4b")
    const val PERSON_PREFIX = "person-"
    const val BLOCK_PREFIX = "block-"

    // ---- apps ----

    /** A new app row: the app lights up with [glyph] for a few seconds, from everyone, with no sound. */
    fun newApp(pkg: String, label: String, glyph: GlyphChoice = GlyphChoice.icon("chat")) = Rule(
        id = UUID.randomUUID().toString(),
        name = label,
        source = Source.APPS,
        packages = listOf(pkg),
        action = Action.SHOW,
        glyph = glyph.style,
        designId = glyph.id,
        until = Until.TIMEOUT,
    )

    /** A rule shown as a row under Apps: anything that shows a glyph and isn't a person's. */
    fun isAppRule(r: Rule) = r.action == Action.SHOW && !isPersonRule(r)

    /** True when the app sheet's fields describe [r] fully (otherwise Advanced has more, and the sheet says so). */
    fun isSimpleApp(r: Rule): Boolean =
        isAppRule(r) && r.source == Source.APPS && r.packages.isNotEmpty() && r.match.groups.isEmpty() &&
            r.match.conditions.all { it == peopleCondition(r) }

    private fun peopleCondition(r: Rule): Condition? =
        r.match.conditions.firstOrNull { it.field == Field.SENDER && it.op == Op.IS && !it.not && it.list == null }

    /** Senders an app rule is limited to; empty = everyone. */
    fun people(r: Rule): List<String> = peopleCondition(r)?.cleanValues.orEmpty()

    /** Everyone ([names] empty) or only the senders named; other conditions stay. */
    fun withPeople(r: Rule, names: List<String>): Rule {
        val rest = r.match.conditions.filterNot { it == peopleCondition(r) }
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val conditions = if (clean.isEmpty()) rest else listOf(Condition(Field.SENDER, Op.IS, clean)) + rest
        return r.copy(match = r.match.copy(all = true, conditions = conditions))
    }

    /** "Stay on until I check": the glyph stays until the notification is read, else a few seconds. */
    fun staysOn(r: Rule) = r.until != Until.TIMEOUT

    fun withStays(r: Rule, on: Boolean) = r.copy(until = if (on) Until.READ else Until.TIMEOUT)

    fun withGlyph(r: Rule, g: GlyphChoice) = r.copy(glyph = g.style, designId = g.id)

    fun withSound(r: Rule, uri: String?, name: String?) = r.copy(soundUri = uri, soundName = name?.takeIf { uri != null })

    /** "Everyone · Chat · Stays on · 1 blocked", or "Off". */
    fun summary(r: Rule, glyphName: String, blocked: Int): String {
        if (!r.enabled) return "Off"
        val who = if (people(r).isEmpty()) "Everyone" else if (people(r).size == 1) people(r).first() else "${people(r).size} people"
        return listOfNotNull(who, glyphName, "Stays on".takeIf { staysOn(r) }, "$blocked blocked".takeIf { blocked > 0 }).joinToString(" · ")
    }

    // ---- people ----

    /** A person lights up from any app, by name (as apps show it) or by any of their [numbers]. */
    fun newPerson(name: String, numbers: List<String> = emptyList(), glyph: GlyphChoice = GlyphChoice.icon("heart")) = Rule(
        id = PERSON_PREFIX + UUID.randomUUID(),
        name = name,
        source = Source.APPS,
        match = personMatch(name, numbers),
        action = Action.SHOW,
        glyph = glyph.style,
        designId = glyph.id,
        until = Until.READ,
    )

    private fun personMatch(name: String, numbers: List<String>) = Match(
        all = false,
        conditions = listOf(Condition(Field.SENDER, Op.IS, listOf(name.trim()))) +
            listOfNotNull(numbers.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { Condition(Field.NUMBER, Op.IS, it) }),
    )

    fun isPersonRule(r: Rule) = r.action == Action.SHOW && r.id.startsWith(PERSON_PREFIX)

    fun personNumbers(r: Rule): List<String> = r.match.conditions.firstOrNull { it.field == Field.NUMBER }?.cleanValues.orEmpty()

    fun renamePerson(r: Rule, name: String, numbers: List<String> = personNumbers(r)) = r.copy(name = name, match = personMatch(name, numbers))

    // ---- block rules ----

    /** A rule that marks senders matching [p] as spam. */
    fun blockRule(p: BlockPattern, id: String = BLOCK_PREFIX + UUID.randomUUID()): Rule {
        val calls = Where.CALLS in p.where
        val anyApp = Where.ANY_APP in p.where
        val packages = if (anyApp) emptyList() else buildList {
            if (Where.SMS in p.where) addAll(SMS_APPS)
            if (Where.WHATSAPP in p.where) addAll(WHATSAPP_APPS)
            addAll(p.apps.filterNot { it in this })
        }
        val apps = anyApp || packages.isNotEmpty()
        val source = when {
            calls && apps -> Source.EVERYTHING
            apps -> Source.APPS
            else -> Source.CALLS
        }
        val text = p.text.trim()
        return Rule(
            id = id,
            name = p.label,
            source = source,
            packages = if (source == Source.CALLS) emptyList() else packages,
            match = Match(all = false, conditions = listOf(Condition(Field.SENDER, p.type.op, listOf(text)), Condition(Field.NUMBER, p.type.op, listOf(text)))),
            action = Action.SPAM,
        )
    }

    /** The simple block pattern [r] was written as, or null when it is something else. */
    fun pattern(r: Rule): BlockPattern? {
        if (r.action != Action.SPAM || r.match.groups.isNotEmpty() || r.match.all) return null
        val cs = r.match.conditions
        if (cs.size != 2 || cs.any { it.not || it.list != null }) return null
        val type = PatternType.entries.firstOrNull { it.op == cs[0].op } ?: return null
        val text = cs[0].cleanValues.singleOrNull() ?: return null
        if (cs.map { it.field }.toSet() != setOf(Field.SENDER, Field.NUMBER) || cs[1].op != cs[0].op || cs[1].cleanValues != listOf(text)) return null
        val where = mutableSetOf<Where>()
        if (r.source != Source.APPS) where += Where.CALLS
        val others = mutableListOf<String>()
        if (r.source != Source.CALLS) {
            if (r.packages.isEmpty()) where += Where.ANY_APP
            if (r.packages.containsAll(SMS_APPS)) where += Where.SMS
            if (r.packages.containsAll(WHATSAPP_APPS)) where += Where.WHATSAPP
            others += r.packages.filter { pkg ->
                !(Where.SMS in where && pkg in SMS_APPS) && !(Where.WHATSAPP in where && pkg in WHATSAPP_APPS)
            }
        }
        return BlockPattern(type, text, where, others)
    }

    /** Block rules that apply to the app [pkg] on its own sheet ("Block senders in WhatsApp"). */
    fun appBlocks(rules: List<Rule>, pkg: String): List<Rule> =
        rules.filter { r -> pattern(r) != null && r.source != Source.CALLS && (r.packages.isEmpty() || pkg in r.packages) && r.packages.isNotEmpty() }

    /** "Calls, SMS", or the app names for packages no chip covers. */
    fun whereText(p: BlockPattern, label: (String) -> String): String =
        (Where.entries.filter { it in p.where }.map { it.label } + p.apps.map(label)).joinToString(", ").ifEmpty { "Nowhere" }
}
