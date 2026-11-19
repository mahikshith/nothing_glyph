package com.dotforge.rules

import com.dotforge.filter.Caller
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Group
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import com.dotforge.filter.Subject
import com.dotforge.rules.MoreConditions.Pick
import com.dotforge.rules.MoreConditions.Test as T
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** More conditions: the sentences, all/any/not through the engine, block rules with them, and named lists. */
class MoreConditionsTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private val otp = MoreConditions.condition(Pick.TITLE, T.CONTAINS, "OTP")
    private val notBank = MoreConditions.condition(Pick.SENDER, T.IS_NOT, "Bank")
    private val loans = MoreConditions.condition(Pick.TEXT, T.IN_LIST, list = "Loans")
    private val unknown = MoreConditions.condition(Pick.CONTACTS, T.NOT_IN_CONTACTS)

    private fun msg(sender: String, title: String, text: String = "", contact: Boolean? = null, app: String = "com.whatsapp", channel: String = "") = Subject(
        isCall = false, app = app, appLabel = "WhatsApp", channel = channel, sender = sender, title = title, text = text, fromPerson = true,
        contact = { contact?.let { Caller(it) } },
    )

    // ---------------------------------------------------------------- the sentence summary

    @Test fun theSummaryReadsAsASentence() {
        assertEquals("Title has 'OTP' and not from 'Bank'", MoreConditions.summary(Group(true, listOf(otp, notBank))))
        assertEquals("Title has 'OTP' or not from 'Bank'", MoreConditions.summary(Group(false, listOf(otp, notBank))))
        assertEquals("None", MoreConditions.summary(Group()))
        assertEquals("Title has 'OTP' and not from 'Bank', +2 more", MoreConditions.summary(Group(true, listOf(otp, notBank, loans, unknown))))
        assertEquals("Sender isn't in my contacts", MoreConditions.summary(Group(true, listOf(unknown))))
        assertEquals("Text is in list 'Loans'", MoreConditions.summary(Group(true, listOf(loans))))
    }

    @Test fun everyTestHasItsOwnWords() {
        fun s(p: Pick, t: T, v: String = "x") = MoreConditions.sentence(MoreConditions.condition(p, t, v, list = "L"))
        assertEquals("Title doesn't have 'x'", s(Pick.TITLE, T.NOT_CONTAINS))
        assertEquals("Number starts with '140'", s(Pick.NUMBER, T.STARTS, "140"))
        assertEquals("Channel ends with 'x'", s(Pick.CHANNEL, T.ENDS))
        assertEquals("App is 'x'", s(Pick.APP, T.IS))
        assertEquals("Text isn't 'x'", s(Pick.TEXT, T.IS_NOT))
        assertEquals("from 'x'", s(Pick.SENDER, T.IS))
        assertEquals("sender has 'x'".replaceFirstChar { it.uppercase() }, s(Pick.SENDER, T.CONTAINS))
        assertEquals("not from list 'L'", s(Pick.SENDER, T.NOT_IN_LIST))
        assertEquals("Title isn't in list 'L'", s(Pick.TITLE, T.NOT_IN_LIST))
        assertEquals("sender is in my contacts", s(Pick.CONTACTS, T.IN_CONTACTS))
        // several values, separated with commas
        assertEquals("Text has 'loan' or 'credit'", s(Pick.TEXT, T.CONTAINS, "loan, credit"))
    }

    @Test fun aConditionReadsBackAsTheTestItWasBuiltWith() {
        Pick.entries.forEach { p ->
            p.tests.forEach { t -> assertEquals("$p $t", t, T.of(MoreConditions.condition(p, t, "x", list = "L"))) }
        }
        // a pattern the builder doesn't offer still reads, in the engine's words
        assertNull(T.of(Condition(Field.TITLE, Op.REGEX, listOf("a+"))))
        assertEquals("title matches regex \"a+\"", MoreConditions.sentence(Condition(Field.TITLE, Op.REGEX, listOf("a+"))))
    }

    @Test fun callRulesOfferOnlyFieldsACallHas() {
        assertEquals(listOf(Pick.SENDER, Pick.NUMBER, Pick.CONTACTS), Pick.available(calls = true, apps = false))
        assertEquals(Pick.entries, Pick.available(calls = true, apps = true))
    }

    // ---------------------------------------------------------------- all, any and not, through the engine

    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp")

    @Test fun matchAllNeedsEveryCondition() {
        val r = MoreConditions.with(wa, Group(true, listOf(otp, notBank)))
        assertNotNull(RuleEngine.explain(r, msg("HDFC", "Your OTP is 1234")))
        assertNull(RuleEngine.explain(r, msg("Bank", "Your OTP is 1234")))
        assertNull(RuleEngine.explain(r, msg("HDFC", "Hello")))
    }

    @Test fun matchAnyNeedsOne() {
        val r = MoreConditions.with(wa, Group(false, listOf(otp, notBank)))
        assertNotNull(RuleEngine.explain(r, msg("Bank", "Your OTP is 1234")))
        assertNotNull(RuleEngine.explain(r, msg("HDFC", "Hello")))
        assertNull(RuleEngine.explain(r, msg("Bank", "Hello")))
    }

    @Test fun notTestsAndListsWork() {
        val lists = listOf(NamedList("Loans", listOf("loan", "credit")))
        val notIn = MoreConditions.condition(Pick.TEXT, T.NOT_IN_LIST, list = "Loans")
        // "is in list" compares whole values: a text that is exactly an entry
        val r = MoreConditions.with(wa, Group(true, listOf(MoreConditions.condition(Pick.SENDER, T.IN_LIST, list = "Loans"))))
        assertNotNull(RuleEngine.explain(r, msg("credit", "x"), lists))
        assertNull(RuleEngine.explain(r, msg("Mom", "x"), lists))
        val n = MoreConditions.with(wa, Group(true, listOf(notIn)))
        assertNull(RuleEngine.explain(n, msg("x", "x", text = "loan"), lists))
        assertNotNull(RuleEngine.explain(n, msg("x", "x", text = "hello"), lists))
        val dc = MoreConditions.with(wa, Group(true, listOf(MoreConditions.condition(Pick.TEXT, T.NOT_CONTAINS, "loan"))))
        assertNull(RuleEngine.explain(dc, msg("x", "x", text = "a loan offer")))
        assertNotNull(RuleEngine.explain(dc, msg("x", "x", text = "dinner")))
        val ch = MoreConditions.with(wa, Group(true, listOf(MoreConditions.condition(Pick.CHANNEL, T.IS, "Promotions"))))
        assertNotNull(RuleEngine.explain(ch, msg("x", "x", channel = "Promotions")))
        assertNull(RuleEngine.explain(ch, msg("x", "x", channel = "Chats")))
    }

    @Test fun anAppsOwnRowsKeepWorkingAlongside() {
        val r = SimpleRules.withWords(MoreConditions.with(wa, Group(true, listOf(notBank))), listOf("OTP"))
        assertEquals(listOf("OTP"), SimpleRules.words(r))
        assertEquals(Group(true, listOf(notBank)), MoreConditions.of(r))
        assertNull(RuleEngine.explain(r, msg("Bank", "x", text = "OTP 1")))
        assertNotNull(RuleEngine.explain(r, msg("HDFC", "x", text = "OTP 1")))
        // taking them away leaves the words
        val back = MoreConditions.with(r, Group())
        assertTrue(back.match.groups.isEmpty())
        assertEquals(listOf("OTP"), SimpleRules.words(back))
    }

    // ---------------------------------------------------------------- block rules

    @Test fun aBlockRuleCanUseAnyField() {
        val p = BlockPattern(PatternType.WORDS, "loan", setOf(Where.SMS))
        val sms = "com.google.android.apps.messaging"
        val r = SimpleRules.blockRule(p, id = "block-x", more = Group(true, listOf(unknown)))
        // still the same simple rule in the Spam list, with its More conditions apart
        assertEquals(p, SimpleRules.pattern(r))
        assertEquals(Group(true, listOf(unknown)), MoreConditions.of(r))
        assertEquals("Mentions “loan”, sender isn't in my contacts", r.name)
        assertNotNull(RuleEngine.explain(r, msg("AX-LOANS", "x", text = "Pre-approved loan", contact = false, app = sms)))
        assertNull(RuleEngine.explain(r, msg("Mom", "x", text = "the loan", contact = true, app = sms)))
        assertNull(RuleEngine.explain(r, msg("AX-LOANS", "x", text = "hello", contact = false, app = sms)))
        // taking them away gives back the plain rule
        assertEquals(SimpleRules.blockRule(p, id = "block-x").match, MoreConditions.with(r, Group()).match)
    }

    @Test fun aNumberBlockRuleWithMoreConditions() {
        val p = BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS))
        val r = SimpleRules.blockRule(p, id = "block-y", more = Group(true, listOf(unknown)))
        assertEquals(p, SimpleRules.pattern(r))
        fun call(contact: Boolean) = Subject(isCall = true, number = "+911405550101", fromPerson = true, region = "IN", contact = { Caller(contact) })
        assertNotNull(RuleEngine.explain(r, call(false)))
        assertNull(RuleEngine.explain(r, call(true)))
    }

    @Test fun unknownAndHiddenNumbersAreCallRules() {
        val unknownCalls = RuleEngine.presets().first { it.id == RuleEngine.UNKNOWN_CALLS }
        val hidden = RuleEngine.presets().first { it.id == RuleEngine.HIDDEN_CALLS }
        assertFalse(unknownCalls.enabled)
        fun call(contact: Boolean?, hiddenNumber: Boolean = false) = Subject(
            isCall = true, number = if (hiddenNumber) null else "+447700900123", hidden = hiddenNumber, fromPerson = !hiddenNumber,
            contact = { contact?.let { Caller(it) } },
        )
        assertNotNull(RuleEngine.explain(unknownCalls, call(false)))
        assertNull(RuleEngine.explain(unknownCalls, call(true)))
        // without Contacts access nobody is called unknown
        assertNull(RuleEngine.explain(unknownCalls, call(null)))
        assertNotNull(RuleEngine.explain(hidden, call(null, hiddenNumber = true)))
        assertNull(RuleEngine.explain(hidden, call(true)))
        // a notification is never a call rule's business
        assertNull(RuleEngine.explain(unknownCalls, msg("x", "x", contact = false)))
    }

    // ---------------------------------------------------------------- lists round-trip

    @Test fun listsAndConditionsRoundTrip() {
        val r = MoreConditions.with(wa, Group(false, listOf(otp, loans, MoreConditions.condition(Pick.SENDER, T.NOT_IN_LIST, list = "Loans"))))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(r, json.decodeFromString(Rule.serializer(), json.encodeToString(Rule.serializer(), r)))

        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file)
        repo.putList(NamedList("Loans", listOf("loan", "credit")))
        repo.upsert(r)
        val again = RulesRepository(file)
        assertEquals(listOf("loan", "credit"), again.list("Loans")?.entries)
        assertEquals(MoreConditions.of(r), MoreConditions.of(again.rules.value.first { it.id == r.id }))
        // renaming the list keeps the rule pointing at it
        again.putList(NamedList("Credit", listOf("loan", "credit")), oldName = "Loans")
        val renamed = MoreConditions.of(again.rules.value.first { it.id == r.id }).conditions
        assertTrue(renamed.filter { it.list != null }.all { it.list == "Credit" })
    }
}
