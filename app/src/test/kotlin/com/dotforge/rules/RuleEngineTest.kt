package com.dotforge.rules

import com.dotforge.calls.RecentCall
import com.dotforge.calls.isRepeatedCall
import com.dotforge.filter.Caller
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import com.dotforge.filter.Subject
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val noon = LocalDateTime.of(2026, 11, 16, 12, 0) // a Monday

    private fun note(
        pkg: String = "com.example", title: String = "Alex", text: String = "hello", sender: String = "", number: String? = null,
        person: Boolean = true, contact: Caller? = null,
    ) = Subject(
        isCall = false, app = pkg, appLabel = "Example", sender = sender.ifBlank { title }, number = number, title = title, text = text,
        fromPerson = person, at = noon, region = "GB", contact = { contact },
    )

    private fun call(
        number: String? = "+447700900123", contact: Caller? = Caller(contact = false), repeated: Boolean = false,
        spam: Boolean = false, hidden: Boolean = false, unverified: Boolean = false,
    ) = Subject(
        isCall = true, app = "phone", number = number, fromPerson = !hidden, hidden = hidden, unverified = unverified, at = noon,
        region = "GB", contact = { contact }, repeated = { repeated }, spamList = { spam },
    )

    private fun cond(field: Field, op: Op, vararg v: String) = Condition(field, op, v.toList())
    private fun rule(name: String, vararg c: Condition, source: Source = Source.APPS, action: Action = Action.SHOW, packages: List<String> = emptyList(), priority: Int = 0) =
        Rule(id = name, name = name, source = source, packages = packages, match = Match(conditions = c.toList()), action = action, priority = priority)

    private fun withList(name: String, vararg entries: String) =
        RuleEngine.presetLists().map { if (it.name == name) it.copy(entries = entries.toList()) else it }

    private fun decide(s: Subject, vararg rules: Rule, lists: List<NamedList> = emptyList()) = RuleEngine.decide(s, rules.toList(), lists)
    private fun shown(s: Subject, vararg rules: Rule) = decide(s, *rules).show?.rule?.name
    private fun gate(s: Subject, vararg rules: Rule, lists: List<NamedList> = emptyList()) = decide(s, *rules, lists = lists).gate?.rule?.name

    @Test fun emptyRuleMatchesEveryNotificationButNoCall() {
        val r = rule("all")
        assertEquals("all", shown(note(), r))
        assertEquals("any notification", RuleEngine.explain(r, note()))
        assertNull(shown(call(), r))
    }

    @Test fun scopePerChannel() {
        val whatsapp = rule("wa", source = Source.APPS, action = Action.SPAM, packages = listOf("COM.WhatsApp"))
        val calls = rule("calls", source = Source.CALLS, action = Action.SPAM)
        val both = rule("both", source = Source.EVERYTHING, action = Action.SPAM)
        assertTrue(whatsapp.appliesTo(note(pkg = "com.whatsapp")))
        assertFalse(whatsapp.appliesTo(note(pkg = "com.google.android.apps.messaging")))
        assertFalse(whatsapp.appliesTo(call()))
        assertTrue(calls.appliesTo(call()))
        assertFalse(calls.appliesTo(note()))
        assertTrue(both.appliesTo(call()))
        assertTrue(both.appliesTo(note(pkg = "anything")))
        // "Calls and WhatsApp": calls, plus that app only
        val callsAndWa = both.copy(packages = listOf("com.whatsapp"))
        assertTrue(callsAndWa.appliesTo(call()))
        assertFalse(callsAndWa.appliesTo(note(pkg = "org.telegram.messenger")))
        // actions that make no sense somewhere don't apply there
        assertFalse(rule("hide", source = Source.EVERYTHING, action = Action.HIDE).appliesTo(call()))
        assertFalse(rule("reject", source = Source.EVERYTHING, action = Action.REJECT).appliesTo(note()))
        assertFalse(rule("show", source = Source.EVERYTHING).appliesTo(call()))
    }

    @Test fun moreSpecificRulesWin() {
        val any = rule("any")
        val app = rule("app", packages = listOf("com.example"))
        val words = rule("words", cond(Field.MESSAGE, Op.CONTAINS, "hello"))
        val appWords = rule("appWords", cond(Field.MESSAGE, Op.CONTAINS, "hello"), packages = listOf("com.example"))
        val person = rule("person", cond(Field.SENDER, Op.STARTS, "Al"))
        val exact = rule("exact", cond(Field.SENDER, Op.IS, "alex"))
        assertEquals("exact", shown(note(), any, app, words, appWords, person, exact))
        assertEquals("person", shown(note(), any, app, words, appWords, person))
        assertEquals("appWords", shown(note(), any, app, words, appWords))
        assertEquals("words", shown(note(), any, app, words))
        assertEquals("app", shown(note(), any, app))
        assertEquals(Tier.EXACT, RuleEngine.tier(exact))
        assertEquals(Tier.PERSON, RuleEngine.tier(rule("c", Condition.flag(Field.CONTACT))))
        assertEquals(Tier.CALLER, RuleEngine.tier(rule("h", Condition.flag(Field.HIDDEN))))
        assertEquals(Tier.ANY, RuleEngine.tier(rule("t", Condition(Field.TIME, Op.IS, listOf("22:00-07:00")))))
        // negated person conditions don't make a rule specific
        assertEquals(Tier.ANY, RuleEngine.tier(rule("n", cond(Field.SENDER, Op.IS, "x").copy(not = true))))
    }

    @Test fun tiesGoToPriorityThenTimeLimitsThenOrder() {
        val a = rule("a", packages = listOf("com.example"))
        val b = rule("b", packages = listOf("com.example"), priority = 1)
        assertEquals("b", shown(note(), a, b))
        val night = rule("night", Condition(Field.DAY, Op.IS, listOf("1")), packages = listOf("com.example"))
        assertEquals("night", shown(note(), a, night))
        assertEquals("a", shown(note(), a, rule("c", packages = listOf("com.example"))))
    }

    @Test fun disabledRulesAreSkipped() {
        assertNull(shown(note(), rule("off").copy(enabled = false)))
    }

    @Test fun spamComesBeforeShowing() {
        val show = rule("show", cond(Field.SENDER, Op.IS, "alex"))
        val spam = rule("spam", cond(Field.TEXT, Op.CONTAINS, "loan"), action = Action.SPAM)
        val d = decide(note(text = "loan approved"), show, spam)
        assertTrue(d.blocked)
        assertEquals("spam", d.gate?.rule?.name)
        assertNull(d.show)
        // an allow rule lets the best showing rule through
        val allow = rule("allow", cond(Field.SENDER, Op.IS, "alex"), action = Action.ALLOW)
        val ok = decide(note(text = "loan approved"), show, spam, allow)
        assertFalse(ok.blocked)
        assertEquals("allow", ok.gate?.rule?.name)
        assertEquals("show", ok.show?.rule?.name)
    }

    @Test fun defaultSpamRules() {
        val rules = RuleEngine.presets()
        val lists = withList(RuleEngine.BLOCKED, "+44 7700 900999", "Promo Bot")
        fun gateOf(s: Subject, l: List<NamedList> = lists) = RuleEngine.decide(s, rules, l).gate?.rule?.id

        // contacts and repeated callers are always allowed, even when on the spam list
        assertEquals("spam-contacts", gateOf(call(contact = Caller(contact = true), spam = true)))
        assertEquals("spam-repeated", gateOf(call(repeated = true, spam = true)))
        assertEquals("spam-list", gateOf(call(spam = true)))
        assertNull(gateOf(call()))
        // the blocked list, by number in any format or by name
        assertEquals("spam-blocked", gateOf(call(number = "+447700900999")))
        assertEquals("spam-blocked", gateOf(note(sender = "promo bot", person = false)))
        // a blocked contact stays blocked: the list names them exactly
        assertEquals("spam-blocked", gateOf(call(number = "+447700900999", contact = Caller(contact = true))))
        // the allowed list beats the blocked list
        val both = lists.map { if (it.name == RuleEngine.ALLOWED) it.copy(entries = listOf("07700900999")) else it }
        assertEquals("spam-allowed", gateOf(call(number = "+447700900999"), both))
        // hidden and faked numbers are off by default
        assertNull(gateOf(call(number = null, hidden = true, contact = null)))
        assertNull(gateOf(call(unverified = true)))
    }

    @Test fun spamPrefixesMatchAnyFormat() {
        val rules = RuleEngine.presets()
        val lists = withList(RuleEngine.SPAM_PREFIXES, "0800")
        assertEquals("spam-prefixes", RuleEngine.decide(call(number = "+448001234567"), rules, lists).gate?.rule?.id)
        assertEquals("spam-prefixes", RuleEngine.decide(note(number = "+448001234567", person = true), rules, lists).gate?.rule?.id)
        assertNull(RuleEngine.decide(call(number = "+447700900123"), rules, lists).gate)
    }

    @Test fun callRulesSilenceAndReject() {
        val night = rule("night", Condition(Field.TIME, Op.IS, listOf("22:00-07:00")), source = Source.CALLS, action = Action.SILENCE)
        val intl = rule("intl", cond(Field.NUMBER, Op.STARTS, "+1"), source = Source.CALLS, action = Action.REJECT)
        val faked = rule("faked", Condition.flag(Field.UNVERIFIED), source = Source.CALLS, action = Action.REJECT_QUIET)
        val late = Subject(isCall = true, number = "+12025550123", fromPerson = true, at = noon.withHour(23))
        assertEquals("intl", gate(late, night, intl)) // a number beats a time
        assertEquals("night", gate(Subject(isCall = true, number = "+447700900123", fromPerson = true, at = noon.withHour(23)), night, intl))
        assertNull(gate(call(), night, intl))
        assertEquals("faked", gate(call(unverified = true), faked))
    }

    @Test fun repeatedCallerWindow() {
        val recent = listOf(RecentCall("+447700900123", 1_000), RecentCall("+15550100", 50_000))
        val window = 5 * 60_000L
        assertTrue(isRepeatedCall("+447700900123", 1_000 + window, recent, window)) // edge included
        assertFalse(isRepeatedCall("+447700900123", 1_001 + window, recent, window))
        assertFalse(isRepeatedCall("+447700900123", 1_000, recent, window)) // the same call is not a repeat
        assertTrue(isRepeatedCall("+447700900123", 2_000, recent, window))
        assertFalse(isRepeatedCall("+447700900124", 2_000, recent, window))
        assertFalse(isRepeatedCall(null, 2_000, recent, window)) // hidden numbers never repeat
        assertFalse(isRepeatedCall("", 2_000, recent, window))
    }

    @Test fun explainSaysWhy() {
        val r = rule("wa", cond(Field.SENDER, Op.STARTS, "+44"), packages = listOf("com.whatsapp"))
        val s = note(pkg = "com.whatsapp", title = "+44 7700 900123")
        assertEquals("Example, sender starts with \"+44\"", RuleEngine.explain(r, s))
        assertNull(RuleEngine.explain(r, note(pkg = "com.whatsapp", title = "+1 555")))
        assertEquals("any call", RuleEngine.explain(rule("c", source = Source.CALLS, action = Action.SILENCE), call()))
    }

    @Test fun contactsAreOnlyLookedUpWhenARuleNeedsThem() {
        var lookups = 0
        val s = Subject(isCall = false, app = "x", title = "Alex", fromPerson = true, contact = { lookups++; null })
        decide(s, rule("a", cond(Field.TEXT, Op.CONTAINS, "loan"), action = Action.SPAM), rule("b"))
        assertEquals(0, lookups)
        decide(s, rule("c", Condition.flag(Field.CONTACT, yes = false), action = Action.SPAM), rule("d", Condition.flag(Field.STARRED)))
        assertEquals(1, lookups)
    }

    @Test fun validPatterns() {
        assertTrue(RuleEngine.isValidPattern("""\d{6}"""))
        assertFalse(RuleEngine.isValidPattern("(unclosed"))
        assertFalse(RuleEngine.isValidPattern(" "))
        assertSame(RuleEngine.regex("abc"), RuleEngine.regex("abc"))
    }

    @Test fun progressRulesPickProgressBarsAndNeverDecideAlerts() {
        val progress = rule("Downloads", packages = listOf("com.example"), action = Action.PROGRESS)
        val any = rule("Any progress", action = Action.PROGRESS)
        val show = rule("Show all")
        // a progress rule neither shows nor blocks
        assertEquals(Decision(), decide(note(), progress))
        assertEquals("Show all", shown(note(), progress, show))
        assertNull(gate(note(), progress, show))
        // the most specific progress rule wins; other apps fall back to the any-app rule
        assertEquals("Downloads", RuleEngine.progress(note(), listOf(any, progress))?.rule?.name)
        assertEquals("Any progress", RuleEngine.progress(note(pkg = "com.other"), listOf(any, progress))?.rule?.name)
        assertNull(RuleEngine.progress(note(pkg = "com.other"), listOf(progress, show)))
        assertNull(RuleEngine.progress(note(), listOf(progress.copy(enabled = false))))
        // conditions apply as in any rule
        val words = rule("Delivery bar", cond(Field.MESSAGE, Op.CONTAINS, "parcel"), action = Action.PROGRESS)
        assertNull(RuleEngine.progress(note(text = "your download"), listOf(words)))
        assertEquals("Delivery bar", RuleEngine.progress(note(text = "your parcel is near"), listOf(words))?.rule?.name)
        // never for calls, never a spam rule
        assertFalse(Action.PROGRESS.availableFor(Source.CALLS))
        assertFalse(RuleEngine.isSpamRule(progress))
        assertTrue(RuleEngine.presets().any { it.id == RuleEngine.PROGRESS_PRESET && it.enabled && it.action == Action.PROGRESS })
    }
}
