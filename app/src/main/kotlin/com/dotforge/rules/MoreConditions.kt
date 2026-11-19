package com.dotforge.rules

import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Group
import com.dotforge.filter.Match
import com.dotforge.filter.Op

/**
 * "More conditions": tests on any field (who sent it, title, message, number, notification type, app, Contacts)
 * that a rule's sheet or a block rule adds on top of what its own rows set. They are one [Group] in the rule's
 * [Match], marked [Group.more], so the filter engine runs them like any other condition: no new kind of rule.
 *
 * - A rule whose match is "all" (an app's row: its words, chats, times and people are all-conditions) keeps
 *   them as its marked group, beside any group of its own.
 * - A rule whose match is "any" (a block rule: sender or number) is wrapped once there are any: all of
 *   (any of its own conditions) and (the more conditions).
 */
object MoreConditions {
    /** "What to check", in plain words; [noun] is how a sentence names it. [CONTACTS] is the "is a contact" flag. */
    enum class Pick(val label: String, val field: Field, val noun: String) {
        SENDER("Who sent it", Field.SENDER, "sender"),
        TITLE("Title", Field.TITLE, "title"),
        TEXT("Message", Field.TEXT, "message"),
        NUMBER("Number", Field.NUMBER, "number"),
        CHANNEL("Notification type", Field.CHANNEL, "notification type"),
        APP("App", Field.APP, "app"),
        CONTACTS("Contacts", Field.CONTACT, "sender"),
        ;

        /** The tests this field takes. */
        val tests: List<Test> get() = if (this == CONTACTS) listOf(Test.IN_CONTACTS, Test.NOT_IN_CONTACTS) else Test.entries.filter { !it.flag }

        companion object {
            fun of(f: Field): Pick? = entries.firstOrNull { it.field == f }

            /** The fields that mean something for a rule on calls and/or app notifications. */
            fun available(calls: Boolean, apps: Boolean): List<Pick> = entries.filter { it.field.availableFor(calls, apps) }
        }
    }

    /** "How" a field is compared, with one verb ("has") throughout. [list] tests take a named list. */
    enum class Test(val label: String, val op: Op, val not: Boolean = false, val list: Boolean = false, val flag: Boolean = false) {
        CONTAINS("has", Op.CONTAINS),
        NOT_CONTAINS("doesn't have", Op.NOT_CONTAINS),
        STARTS("starts with", Op.STARTS),
        ENDS("ends with", Op.ENDS),
        IS("is", Op.IS),
        IS_NOT("isn't", Op.IS_NOT),
        IN_LIST("is in list", Op.IN_LIST, list = true),
        NOT_IN_LIST("isn't in list", Op.IN_LIST, not = true, list = true),
        IN_CONTACTS("is a contact", Op.IS, flag = true),
        NOT_IN_CONTACTS("isn't a contact", Op.IS, not = true, flag = true),
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

    /**
     * The rule's more conditions (an empty "all" group when it has none): the group marked [Group.more]. Other
     * groups, like the people group a version 2 rule was migrated with, are the rule's own and never shown here.
     * A block rule wrapped before groups were marked ("any" of its own, then the more conditions) is read too.
     */
    fun of(r: Rule): Group {
        val m = r.match
        m.groups.firstOrNull { it.more }?.let { return it }
        if (r.action == Action.SPAM && wrapped(m)) return m.groups[1].copy(more = true)
        return Group(more = true)
    }

    private fun wrapped(m: Match) = m.all && m.conditions.isEmpty() && m.groups.size == 2 && !m.groups[0].all && !m.groups[0].not && !m.groups[0].more

    /** [r] with [g] as its more conditions; an empty [g] takes them away. */
    fun with(r: Rule, g: Group): Rule {
        val clean = g.copy(conditions = g.conditions.filter(::complete), more = true).takeIf { it.conditions.isNotEmpty() }
        val m = r.match
        if (!m.all) {
            // an "any" match (a block rule): wrap its own conditions so the more conditions add to them
            if (clean == null) return r
            return r.copy(match = Match(all = true, groups = listOf(Group(all = false, conditions = m.conditions), clean)))
        }
        val legacy = r.action == Action.SPAM && wrapped(m) && m.groups.none { it.more }
        val rest = if (legacy) m.groups.take(1) else m.groups.filterNot { it.more }
        if (clean != null) return r.copy(match = m.copy(groups = rest + clean))
        // taken away from a wrapped block rule: back to its own "any" match
        val only = rest.singleOrNull()
        if (r.action == Action.SPAM && m.conditions.isEmpty() && only != null && !only.all && !only.not) return r.copy(match = Match(all = false, conditions = only.conditions))
        return r.copy(match = m.copy(groups = rest))
    }

    /** A block rule's own (sender or number) conditions, with any more conditions set apart. */
    fun base(r: Rule): Match {
        val m = r.match
        val own = m.groups.filterNot { it.more }
        val wrappedNow = r.action == Action.SPAM && m.all && m.conditions.isEmpty() && own.size == 1 && m.groups.size == 2 && !own[0].all && !own[0].not
        return when {
            wrappedNow -> Match(all = false, conditions = own[0].conditions)
            r.action == Action.SPAM && wrapped(m) -> Match(all = false, conditions = m.groups[0].conditions)
            else -> m
        }
    }

    /** The lists [g]'s conditions use that are gone ("List removed · Fix"). */
    fun missingLists(g: Group, lists: List<com.dotforge.filter.NamedList>): List<String> =
        g.conditions.filter { com.dotforge.filter.FilterEngine.missingList(it, lists) }.mapNotNull { it.list }.distinct()

    /**
     * When a block rule turns to calls only: the conditions a call can never meet (title, message, notification
     * type, app) are taken out. Returns what stays and what was taken out, for the note and its Undo.
     */
    fun forCalls(g: Group): Pair<Group, List<Condition>> {
        val gone = g.conditions.filterNot { it.field.availableFor(calls = true, notifications = false) }
        return g.copy(conditions = g.conditions - gone.toSet()) to gone
    }

    // ---------------------------------------------------------------- words

    private fun quoted(v: String) = "“$v”"

    private fun values(c: Condition): String {
        val v = c.cleanValues
        return when (v.size) {
            0 -> "…"
            1, 2 -> v.joinToString(" or ") { quoted(it) }
            else -> "${quoted(v[0])} or ${v.size - 1} more"
        }
    }

    /**
     * One condition as a short sentence, in lower case: "title has “OTP”", "not from “Bank”", "sender isn't a
     * contact", "message is in list “Loans”". Callers capitalise it where it starts a sentence.
     */
    fun sentence(c: Condition): String {
        val pick = Pick.of(c.field)
        val test = Test.of(c)
        val list = c.list?.let { "list ${quoted(it)}" }
        if (pick == null || test == null) return com.dotforge.filter.FilterEngine.describe(c)
        if (pick == Pick.CONTACTS) return if (test == Test.IN_CONTACTS) "sender is a contact" else "sender isn't a contact"
        val v = values(c)
        if (pick == Pick.SENDER) when (test) {
            Test.IS -> return "from $v"
            Test.IS_NOT -> return "not from $v"
            Test.IN_LIST -> return "from ${list ?: "a list"}"
            Test.NOT_IN_LIST -> return "not from ${list ?: "a list"}"
            else -> Unit
        }
        val name = pick.noun
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
     * The row's subtitle: "Title has “OTP” and not from “Bank”", "Sender is “A” or title has “B”", the first two
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
