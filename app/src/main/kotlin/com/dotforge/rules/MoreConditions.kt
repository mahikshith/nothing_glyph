package com.dotforge.rules

import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Group
import com.dotforge.filter.Match
import com.dotforge.filter.Op

/**
 * "More conditions": tests on any field (sender, title, text, number, channel, app, Contacts) that a rule's sheet
 * or a block rule adds on top of what its own rows set. They are one [Group] in the rule's [Match], so the
 * filter engine runs them like any other condition: no new kind of rule.
 *
 * - A rule whose match is "all" (an app's row: its words, chats, times and people are all-conditions) keeps
 *   them as its last group.
 * - A rule whose match is "any" (a block rule: sender or number) is wrapped once there are any: all of
 *   (any of its own conditions) and (the more conditions).
 */
object MoreConditions {
    /** The fields offered, in plain words. [CONTACTS] is the "in my contacts" flag. */
    enum class Pick(val label: String, val field: Field) {
        SENDER("Sender", Field.SENDER),
        TITLE("Title", Field.TITLE),
        TEXT("Text", Field.TEXT),
        NUMBER("Number", Field.NUMBER),
        CHANNEL("Channel", Field.CHANNEL),
        APP("App", Field.APP),
        CONTACTS("Contacts", Field.CONTACT),
        ;

        /** The tests this field takes. */
        val tests: List<Test> get() = if (this == CONTACTS) listOf(Test.IN_CONTACTS, Test.NOT_IN_CONTACTS) else Test.entries.filter { !it.flag }

        companion object {
            fun of(f: Field): Pick? = entries.firstOrNull { it.field == f }

            /** The fields that mean something for a rule on calls and/or app notifications. */
            fun available(calls: Boolean, apps: Boolean): List<Pick> = entries.filter { it.field.availableFor(calls, apps) }
        }
    }

    /** How a field is compared. The "not" tests read as their own words. [list] tests take a named list. */
    enum class Test(val label: String, val op: Op, val not: Boolean = false, val list: Boolean = false, val flag: Boolean = false) {
        CONTAINS("contains", Op.CONTAINS),
        NOT_CONTAINS("doesn't contain", Op.NOT_CONTAINS),
        STARTS("starts with", Op.STARTS),
        ENDS("ends with", Op.ENDS),
        IS("is", Op.IS),
        IS_NOT("is not", Op.IS_NOT),
        IN_LIST("is in list", Op.IN_LIST, list = true),
        NOT_IN_LIST("isn't in list", Op.IN_LIST, not = true, list = true),
        IN_CONTACTS("is in my contacts", Op.IS, flag = true),
        NOT_IN_CONTACTS("isn't in my contacts", Op.IS, not = true, flag = true),
        ;

        companion object {
            /** The test a condition was written with (null for one the builder doesn't offer, like a pattern). */
            fun of(c: Condition): Test? = when {
                c.field == Field.CONTACT -> if (contactYes(c)) IN_CONTACTS else NOT_IN_CONTACTS
                c.op == Op.IN_LIST || (c.list != null && c.op == Op.IS) -> if (c.not) NOT_IN_LIST else IN_LIST
                c.not -> when (c.op) {
                    Op.CONTAINS -> NOT_CONTAINS
                    Op.IS -> IS_NOT
                    Op.NOT_CONTAINS -> CONTAINS
                    Op.IS_NOT -> IS
                    else -> null
                }
                else -> entries.firstOrNull { it.op == c.op && !it.not && !it.list && !it.flag }
            }
        }
    }

    private fun contactYes(c: Condition) = (c.cleanValues.firstOrNull()?.lowercase() != "no") != c.not

    /**
     * A condition from the builder's three choices: "Title" "contains" "OTP", or "Sender" "isn't in list" "Banks".
     * [value] may hold several, separated by commas.
     */
    fun condition(pick: Pick, test: Test, value: String = "", list: String? = null): Condition = when {
        test.flag -> Condition.flag(Field.CONTACT, yes = test == Test.IN_CONTACTS)
        test.list -> Condition(pick.field, Op.IN_LIST, list = list, not = test.not)
        else -> Condition(pick.field, test.op, split(value))
    }

    /** "a, b" as values: several mean any of them ("is not" and "doesn't contain": none of them). */
    fun split(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** True when the builder can save [c]: something to compare with. */
    fun complete(c: Condition): Boolean = c.isComplete

    // ---------------------------------------------------------------- reading and writing a rule

    /** The rule's more conditions (an empty "all" group when it has none). */
    fun of(r: Rule): Group = if (r.match.all) r.match.groups.lastOrNull() ?: Group() else Group()

    /** [r] with [g] as its more conditions; an empty [g] takes them away. */
    fun with(r: Rule, g: Group): Rule {
        val clean = g.copy(conditions = g.conditions.filter(::complete)).takeIf { it.conditions.isNotEmpty() }
        val m = r.match
        if (!m.all) {
            // an "any" match (a block rule): wrap its own conditions so the more conditions add to them
            if (clean == null) return r
            return r.copy(match = Match(all = true, groups = listOf(Group(all = false, conditions = m.conditions), clean)))
        }
        val rest = if (m.groups.isEmpty()) emptyList() else m.groups.dropLast(1)
        if (clean != null) return r.copy(match = m.copy(groups = rest + clean))
        // taken away from a wrapped block rule: back to its own "any" match
        val only = rest.singleOrNull()
        if (m.conditions.isEmpty() && only != null && !only.all && !only.not) return r.copy(match = Match(all = false, conditions = only.conditions))
        return r.copy(match = m.copy(groups = rest))
    }

    /** A block rule's own (sender or number) conditions, with any more conditions set apart. */
    fun base(r: Rule): Match {
        val m = r.match
        val g = m.groups
        return if (m.all && m.conditions.isEmpty() && g.size == 2 && !g[0].all && !g[0].not) Match(all = false, conditions = g[0].conditions) else m
    }

    // ---------------------------------------------------------------- words

    private fun quoted(v: String) = "'$v'"

    private fun values(c: Condition): String {
        val v = c.cleanValues
        return when (v.size) {
            0 -> "…"
            1, 2 -> v.joinToString(" or ") { quoted(it) }
            else -> "${quoted(v[0])} or ${v.size - 1} more"
        }
    }

    /**
     * One condition as a short sentence: "Title has 'OTP'", "not from 'Bank'", "sender isn't in my contacts",
     * "Text is in list 'Loans'". Starts in lower case unless it starts with a field name.
     */
    fun sentence(c: Condition): String {
        val pick = Pick.of(c.field)
        val test = Test.of(c)
        val list = c.list?.let { "list ${quoted(it)}" }
        if (pick == null || test == null) return com.dotforge.filter.FilterEngine.describe(c)
        if (pick == Pick.CONTACTS) return if (test == Test.IN_CONTACTS) "sender is in my contacts" else "sender isn't in my contacts"
        val v = values(c)
        if (pick == Pick.SENDER) when (test) {
            Test.IS -> return "from $v"
            Test.IS_NOT -> return "not from $v"
            Test.IN_LIST -> return "from ${list ?: "a list"}"
            Test.NOT_IN_LIST -> return "not from ${list ?: "a list"}"
            else -> Unit
        }
        val name = pick.label
        return when (test) {
            Test.CONTAINS -> "$name has $v"
            Test.NOT_CONTAINS -> "$name doesn't have $v"
            Test.STARTS -> "$name starts with $v"
            Test.ENDS -> "$name ends with $v"
            Test.IS -> "$name is $v"
            Test.IS_NOT -> "$name isn't $v"
            Test.IN_LIST -> "$name is in ${list ?: "a list"}"
            Test.NOT_IN_LIST -> "$name isn't in ${list ?: "a list"}"
            Test.IN_CONTACTS, Test.NOT_IN_CONTACTS -> com.dotforge.filter.FilterEngine.describe(c)
        }
    }

    /**
     * The row's subtitle: "Title has 'OTP' and not from 'Bank'", "Sender is 'A' or Title has 'B'", the first two
     * and "+N more" past that, or "None".
     */
    fun summary(g: Group): String {
        val parts = g.conditions.map(::sentence)
        if (parts.isEmpty()) return "None"
        val join = if (g.all) " and " else " or "
        val shown = parts.take(2).joinToString(join)
        val text = if (parts.size > 2) "$shown, +${parts.size - 2} more" else shown
        return text.replaceFirstChar { it.uppercase() }
    }
}
