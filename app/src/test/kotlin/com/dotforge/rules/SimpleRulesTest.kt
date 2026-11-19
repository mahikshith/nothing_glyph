package com.dotforge.rules

import com.dotforge.engine.Until
import com.dotforge.filter.Field
import com.dotforge.filter.Op
import com.dotforge.filter.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleRulesTest {
    private fun note(app: String, sender: String, number: String? = null) = Subject(
        isCall = false, app = app, appLabel = app, title = sender, text = "hi", sender = sender, number = number,
    )

    private fun call(number: String) = Subject(isCall = true, app = "", appLabel = "Phone", title = number, text = "", sender = number, number = number)

    @Test fun anAppSheetWritesAShowRuleForThatApp() {
        var r = SimpleRules.newApp("com.whatsapp", "WhatsApp")
        assertEquals(Action.SHOW, r.action)
        assertEquals(Source.APPS, r.source)
        assertEquals(listOf("com.whatsapp"), r.packages)
        assertEquals(GlyphStyle.APP_ICON, r.glyph) // row 55: the app's icon in dots
        assertEquals(null, r.designId)
        assertTrue(SimpleRules.staysOn(r)) // until you check
        assertTrue(SimpleRules.isSimpleApp(r))

        r = SimpleRules.withStays(r, true)
        r = SimpleRules.withGlyph(r, GlyphChoice.icon("heart"))
        r = SimpleRules.withSound(r, "content://tick", "Soft tick")
        assertEquals(Until.READ, r.until)
        assertEquals("heart", r.designId)
        assertEquals("Soft tick", r.soundName)
        assertEquals("Everyone · Heart · Stays on · 1 blocked", SimpleRules.summary(r, "Heart", 1))

        val decision = RuleEngine.decide(note("com.whatsapp", "Anyone"), listOf(r))
        assertEquals(r.id, decision.show?.rule?.id)
        assertNull(RuleEngine.decide(note("org.telegram.messenger", "Anyone"), listOf(r)).show)
        assertEquals("Off", SimpleRules.summary(r.copy(enabled = false), "Heart", 0))
    }

    @Test fun onlyPeopleIPickAddsAndRemovesOneSenderCondition() {
        val base = SimpleRules.newApp("com.whatsapp", "WhatsApp")
        val picked = SimpleRules.withPeople(base, listOf("Mom", " Dad ", "Mom"))
        assertEquals(listOf("Mom", "Dad"), SimpleRules.people(picked))
        assertTrue(SimpleRules.isSimpleApp(picked))
        assertEquals(picked.id, RuleEngine.decide(note("com.whatsapp", "Mom"), listOf(picked)).show?.rule?.id)
        assertNull(RuleEngine.decide(note("com.whatsapp", "Boss"), listOf(picked)).show)
        val everyone = SimpleRules.withPeople(picked, emptyList())
        assertTrue(everyone.match.conditions.isEmpty())
        assertEquals("Mom", SimpleRules.summary(SimpleRules.withPeople(base, listOf("Mom")), "Chat", 0).substringBefore(" ·"))
    }

    @Test fun advancedConditionsMakeTheAppRuleNotSimpleButKeepThem() {
        val base = SimpleRules.newApp("com.whatsapp", "WhatsApp")
        val words = base.copy(match = base.match.copy(conditions = listOf(com.dotforge.filter.Condition(Field.MESSAGE, Op.CONTAINS, listOf("urgent")))))
        assertFalse(SimpleRules.isSimpleApp(words))
        val picked = SimpleRules.withPeople(words, listOf("Mom"))
        assertEquals(2, picked.match.conditions.size)
        assertEquals(1, SimpleRules.withPeople(picked, emptyList()).match.conditions.size)
    }

    @Test fun aPersonLightsUpFromAnyAppByNameOrNumberAndBeatsTheAppRule() {
        val mom = SimpleRules.newPerson("Mom", listOf("+44 7700 900123"), GlyphChoice.icon("heart"))
        assertTrue(SimpleRules.isPersonRule(mom))
        assertFalse(SimpleRules.isAppRule(mom))
        val app = SimpleRules.newApp("com.whatsapp", "WhatsApp")
        val rules = listOf(app, mom)
        assertEquals(mom.id, RuleEngine.decide(note("com.whatsapp", "Mom"), rules).show?.rule?.id)
        assertEquals(mom.id, RuleEngine.decide(note("com.google.android.apps.messaging", "+447700900123", "+447700900123"), rules).show?.rule?.id)
        assertEquals(app.id, RuleEngine.decide(note("com.whatsapp", "Someone"), rules).show?.rule?.id)
        assertEquals(listOf("+44 7700 900123"), SimpleRules.personNumbers(mom))
        assertEquals("Mum", SimpleRules.renamePerson(mom, "Mum").match.conditions.first().values.single())
    }

    @Test fun aBlockRuleForCallsAndSmsBlocksBothAndReadsBack() {
        val p = BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS, Where.SMS))
        val r = SimpleRules.blockRule(p)
        assertEquals(Action.SPAM, r.action)
        assertEquals(Source.EVERYTHING, r.source)
        assertTrue(r.packages.containsAll(SimpleRules.SMS_APPS))
        assertEquals(p, SimpleRules.pattern(r))
        assertEquals("Starts with 140", r.name)
        assertTrue(RuleEngine.decide(call("1401234567"), listOf(r)).blocked)
        assertTrue(RuleEngine.decide(note("com.google.android.apps.messaging", "1409876", "1409876"), listOf(r)).blocked)
        assertFalse(RuleEngine.decide(note("com.whatsapp", "1409876", "1409876"), listOf(r)).blocked)
        assertFalse(RuleEngine.decide(call("5551401234"), listOf(r)).blocked)
        assertEquals("Calls, SMS", SimpleRules.whereText(p) { it })
        // "Other apps" has no app list, so it covers SMS and WhatsApp too: the row says so
        assertEquals("All apps", SimpleRules.whereText(p.copy(where = setOf(Where.SMS, Where.ANY_APP))) { it })
        assertEquals("Calls, all apps", SimpleRules.whereText(p.copy(where = setOf(Where.CALLS, Where.ANY_APP))) { it })
    }

    @Test fun whereChipsMapToSourceAndPackages() {
        fun rule(vararg w: Where) = SimpleRules.blockRule(BlockPattern(PatternType.CONTAINS, "loan", w.toSet()))
        assertEquals(Source.CALLS, rule(Where.CALLS).source)
        assertTrue(rule(Where.CALLS).packages.isEmpty())
        rule(Where.ANY_APP).let { assertEquals(Source.APPS, it.source); assertTrue(it.packages.isEmpty()) }
        rule(Where.CALLS, Where.ANY_APP).let { assertEquals(Source.EVERYTHING, it.source); assertTrue(it.packages.isEmpty()) }
        rule(Where.WHATSAPP).let { assertEquals(Source.APPS, it.source); assertEquals(SimpleRules.WHATSAPP_APPS, it.packages) }
        for (w in listOf(setOf(Where.CALLS), setOf(Where.ANY_APP), setOf(Where.SMS, Where.WHATSAPP), setOf(Where.CALLS, Where.WHATSAPP))) {
            assertEquals(w, SimpleRules.pattern(SimpleRules.blockRule(BlockPattern(PatternType.IS, "x", w)))?.where)
        }
        assertTrue(RuleEngine.decide(note("com.whatsapp", "Quick loan offer"), listOf(rule(Where.ANY_APP))).blocked)
    }

    @Test fun appSheetBlocksAreRulesForThatAppOnly() {
        val wa = SimpleRules.blockRule(BlockPattern(PatternType.STARTS, "+92", emptySet(), listOf("com.example.chat")))
        val pat = SimpleRules.pattern(wa)
        assertNotNull(pat)
        assertEquals(listOf("com.example.chat"), pat!!.apps)
        assertEquals(Source.APPS, wa.source)
        val calls = SimpleRules.blockRule(BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS)))
        val any = SimpleRules.blockRule(BlockPattern(PatternType.STARTS, "999", setOf(Where.ANY_APP)))
        val blocks = SimpleRules.appBlocks(listOf(wa, calls, any), "com.example.chat")
        assertEquals(listOf(wa.id), blocks.map { it.id })
        assertTrue(RuleEngine.decide(note("com.example.chat", "+92300", "+92300"), listOf(wa)).blocked)
    }

    @Test fun otherSpamRulesAreNotPatterns() {
        // the starter spam words are the one exception: a block rule like any the user adds
        for (r in RuleEngine.spamPresets().filterNot { it.id == RuleEngine.SPAM_WORDS_RULE }) assertNull(r.name, SimpleRules.pattern(r))
        org.junit.Assert.assertNotNull(SimpleRules.pattern(RuleEngine.spamPresets().first { it.id == RuleEngine.SPAM_WORDS_RULE }))
        assertNull(SimpleRules.pattern(SimpleRules.newApp("a", "A")))
    }
}
