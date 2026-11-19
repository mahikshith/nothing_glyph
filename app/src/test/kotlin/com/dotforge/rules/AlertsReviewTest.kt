package com.dotforge.rules

import com.dotforge.calls.CallerLookup
import com.dotforge.calls.ScreenedCalls
import com.dotforge.filter.Caller
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Group
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import com.dotforge.filter.Subject
import com.dotforge.rules.MoreConditions.Pick
import com.dotforge.rules.MoreConditions.Test as T
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Alerts additions review: lists still in use, missed calls after a call DotForge stopped, honest counts,
 * Contacts groups, restoring starter rules, calls-only block rules and the More conditions group.
 */
class AlertsReviewTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp")
    private val sms = "com.google.android.apps.messaging"

    private fun msg(sender: String, text: String = "", app: String = sms) = Subject(
        isCall = false, app = app, appLabel = "Messages", sender = sender, title = sender, text = text, fromPerson = true,
    )

    // ---------------------------------------------------------------- P0: a list still in use

    @Test fun aMissingListMatchesNothingSoNoRuleWidens() {
        val banks = NamedList("Banks", listOf("HDFC", "ICICI"))
        val notBank = MoreConditions.condition(Pick.SENDER, T.NOT_IN_LIST, list = "Banks")
        val inBank = MoreConditions.condition(Pick.SENDER, T.IN_LIST, list = "Banks")
        val block = SimpleRules.blockRule(BlockPattern(PatternType.WORDS, "loan", setOf(Where.SMS)), id = "block-loan", more = Group(true, listOf(notBank)))
        // with the list: a loan message from a stranger is blocked, from the bank it isn't
        assertNotNull(RuleEngine.explain(block, msg("AX-LOANS", "a loan"), listOf(banks)))
        assertNull(RuleEngine.explain(block, msg("HDFC", "a loan"), listOf(banks)))
        // the list deleted: "isn't in list" fails rather than becoming always true, so nothing more is blocked
        assertNull(RuleEngine.explain(block, msg("AX-LOANS", "a loan"), emptyList()))
        assertNull(RuleEngine.explain(block, msg("HDFC", "a loan"), emptyList()))
        // and "is in list" matches nothing either
        val light = MoreConditions.with(wa, Group(true, listOf(inBank)))
        assertNull(RuleEngine.explain(light, msg("HDFC", app = "com.whatsapp"), emptyList()))
        assertEquals(listOf("Banks"), MoreConditions.missingLists(MoreConditions.of(block), emptyList()))
        assertEquals(emptyList<String>(), MoreConditions.missingLists(MoreConditions.of(block), listOf(banks)))
    }

    @Test fun aListSaysWhoUsesItAndUndoPutsItBackExactly() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        val banks = NamedList("Banks", listOf("HDFC", "ICICI", "SBI"))
        repo.putList(banks)
        val a = MoreConditions.with(wa, Group(true, listOf(MoreConditions.condition(Pick.SENDER, T.NOT_IN_LIST, list = "Banks"))))
        val b = SimpleRules.blockRule(BlockPattern(PatternType.WORDS, "loan", setOf(Where.SMS)), id = "block-b", more = Group(true, listOf(MoreConditions.condition(Pick.SENDER, T.IN_LIST, list = "banks"))))
        repo.upsert(a)
        repo.upsert(b)
        assertEquals(setOf(a.id, b.id), repo.listUsers("Banks").map { it.id }.toSet())
        assertEquals(emptyList<Rule>(), repo.listUsers("Loans"))

        // deleted at once; the rules still point at it
        val gone = repo.deleteList("Banks")
        assertEquals(banks, gone)
        assertNull(repo.list("Banks"))
        assertEquals(2, repo.listUsers("Banks").size)
        assertNull(RuleEngine.explain(repo.rules.value.first { it.id == a.id }, msg("Mom", app = "com.whatsapp"), repo.lists.value))
        // Undo: the same name and entries, in the same order, and the rule works again
        repo.putList(gone!!)
        assertEquals(banks, repo.list("Banks"))
        assertNotNull(RuleEngine.explain(repo.rules.value.first { it.id == a.id }, msg("Mom", app = "com.whatsapp"), repo.lists.value))
    }

    // ---------------------------------------------------------------- P1: missed calls after a call DotForge stopped

    @Test fun aStoppedCallsMissedCallDoesntLight() {
        val s = ScreenedCalls()
        val t0 = 1_000_000L
        s.note("+919810011111", "Ravi", t0)
        // the phone app's missed call, by number, by a formatted title, or by the contact's name
        assertTrue(s.covers("+919810011111", "Ravi", t0 + 20_000))
        assertTrue(s.covers(null, "098100 11111", t0 + 20_000))
        assertTrue(s.covers(null, "Ravi", t0 + 20_000))
        // another caller still lights
        assertFalse(s.covers("+447700900123", "Mom", t0 + 20_000))
        // after 10 min it is an ordinary missed call again
        assertFalse(s.covers("+919810011111", "Ravi", t0 + ScreenedCalls.WINDOW_MS + 1))

        // a hidden number DotForge silenced: the missed call has no number either
        val h = ScreenedCalls()
        h.note(null, null, t0)
        assertTrue(h.covers(null, "Unknown", t0 + 5_000))
        assertFalse(h.covers("+447700900123", "Mom", t0 + 5_000))
        // nothing stopped: nothing skipped
        assertFalse(ScreenedCalls().covers(null, "Unknown", t0))
    }

    // ---------------------------------------------------------------- P1: honest counts

    private val zone = ZoneOffset.UTC
    private fun at(day: Int, h: Int) = LocalDateTime.of(2026, 11, 16 + day, h, 0).toInstant(zone).toEpochMilli() // 16 Nov 2026 is a Monday

    private fun lit(key: String, at: Long, sender: String = "Ravi", channel: String = "", locked: Boolean? = null, text: String = "hi") = HistoryEntry(
        key, at, Outcome.LIT, appLabel = "WhatsApp", packageName = "com.whatsapp", title = sender, text = text, sender = sender,
        channel = channel, locked = locked,
    )

    @Test fun countsUseEachEntrysOwnTimeAndLockState() {
        val history = listOf(
            lit("mon-10", at(0, 10), locked = true),
            lit("mon-22", at(0, 22), locked = false),
            lit("sat-10", at(5, 10), locked = null),
        )
        // weekdays 09:00 to 18:00: only Monday 10:00, whatever day it is now
        val office = SimpleRules.withTimes(wa, listOf(1, 2, 3, 4, 5), "09:00-18:00")
        assertEquals(Counts.Count(1, partial = false), Counts.count(office, history, null, zone = zone))
        // only when locked: the stored state where there is one; the older entry skips that part
        val locked = SimpleRules.withLocked(wa, true)
        assertEquals(Counts.Count(2, partial = false), Counts.count(locked, history, null, zone = zone))
        assertEquals("Would have lit 2 times in the last 30 days", Counts.litText(Counts.count(locked, history, null, zone = zone)))
    }

    @Test fun aNotificationTypeCountsOnlyWhereItWasStored() {
        val promo = MoreConditions.with(wa, Group(true, listOf(MoreConditions.condition(Pick.CHANNEL, T.IS, "Promotions"))))
        val stored = listOf(lit("a", at(0, 10), channel = "Promotions"), lit("b", at(0, 11), channel = "Chats"))
        assertEquals(Counts.Count(1, partial = false), Counts.count(promo, stored, null, zone = zone))
        // older entries didn't keep it: never "0", it says it can't count yet
        val old = listOf(lit("c", at(0, 10)), lit("d", at(0, 11)))
        val c = Counts.count(promo, old, null, zone = zone)
        assertTrue(c.unknown)
        assertEquals("Can't count this part yet", Counts.litText(c))
        assertEquals("Can't count this part yet", Counts.blockedText(c))
        // some counted, some not: "at least"
        assertEquals("Would have lit at least 1 time in the last 30 days", Counts.litText(Counts.count(promo, stored + old, null, zone = zone)))
    }

    @Test fun contactsAreLookedUpAtCountTimeOrNotCountedAtAll() {
        val strangers = SimpleRules.blockRule(
            BlockPattern(PatternType.WORDS, "loan", setOf(Where.ANY_APP)), id = "block-s",
            more = Group(true, listOf(MoreConditions.condition(Pick.CONTACTS, T.NOT_IN_CONTACTS))),
        )
        val history = listOf(lit("a", at(0, 10), sender = "AX-LOANS", text = "a loan"), lit("b", at(0, 11), sender = "Mom", text = "the loan"))
        // without Contacts: it can't say who is a stranger, so it says so instead of "blocked nothing"
        assertEquals("Can't count this part yet", Counts.blockedText(Counts.count(strangers, history, null, zone = zone)))
        // with Contacts, looked up now
        val lookup = { e: HistoryEntry -> Caller(contact = e.sender == "Mom") }
        val c = Counts.count(strangers, history, null, contacts = lookup, zone = zone)
        assertEquals(Counts.Count(1, partial = false), c)
        assertEquals("Would have blocked 1 in the last 30 days", Counts.blockedText(c))
        assertEquals("Would have blocked nothing in the last 30 days", Counts.blockedText(Counts.Count(0, false)))
        assertEquals("Wouldn't have lit in the last 30 days", Counts.litText(Counts.Count(0, false)))
        assertEquals("Would have lit 1 time in the last 30 days", Counts.litText(Counts.Count(1, false)))
    }

    // ---------------------------------------------------------------- P2: Contacts groups

    @Test fun onlyVisibleGroupsAPersonMadeAreOffered() {
        assertTrue(CallerLookup.isUserGroup("Family", systemId = null, visible = true, autoAdd = false, favorites = false))
        // Google's own Family label is a system group a person uses: kept
        assertTrue(CallerLookup.isUserGroup("Family", systemId = "Family", visible = true, autoAdd = false, favorites = false))
        assertFalse(CallerLookup.isUserGroup("My Contacts", systemId = "Contacts", visible = true, autoAdd = true, favorites = false))
        assertFalse(CallerLookup.isUserGroup("My Contacts", systemId = null, visible = true, autoAdd = false, favorites = false))
        assertFalse(CallerLookup.isUserGroup("Starred in Android", systemId = "Starred", visible = true, autoAdd = false, favorites = true))
        assertFalse(CallerLookup.isUserGroup("Old", systemId = null, visible = false, autoAdd = false, favorites = false))
        assertFalse(CallerLookup.isUserGroup(" ", systemId = null, visible = true, autoAdd = false, favorites = false))
    }

    @Test fun aGroupCoversItsPeoplesCallsAndAPersonStillWins() {
        val family = SimpleRules.newGroup("Family")
        val mom = SimpleRules.newPerson("Mom", listOf("+44 7700 900123"))
        val phone = SimpleRules.phoneCalls()
        val rules = listOf(phone, family, mom)
        assertEquals(mom.id, SimpleRules.callGlyphRule(rules, "Mom", "+447700900123", setOf("Family"))?.id)
        assertEquals(mom.id, SimpleRules.callGlyphRule(rules, null, "07700 900123", setOf("Family"))?.id)
        assertEquals(family.id, SimpleRules.callGlyphRule(rules, "Dad", "+447700900456", setOf("family"))?.id)
        assertNull(SimpleRules.callGlyphRule(rules, "Sam", "+447700900789", setOf("Work")))
        assertNull(SimpleRules.callGlyphRule(listOf(phone, family.copy(enabled = false)), "Dad", null, setOf("Family")))
        // its sentence says so
        val words = AlertSentence.parts(family, family.name).joinToString("") { p -> (p as? SentencePart.Text)?.text ?: "…" }
        assertTrue(words, words.startsWith("When someone in Family calls or messages, the "))
    }

    // ---------------------------------------------------------------- P2: restoring starter rules

    @Test fun restoringSpamOrAppRulesNamesThemAndUndoTakesTheirListsToo() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        repo.delete("spam-allowed")
        repo.delete("preset-otp")
        repo.deleteList(RuleEngine.ALLOWED)
        val spam = repo.missingPresets().filter(RuleEngine::isSpamRule)
        assertEquals(listOf("spam-allowed"), spam.map { it.id })
        val r = repo.restorePresets(spam)
        assertEquals(listOf("spam-allowed"), r.rules.map { it.id })
        // the list the rule uses came back with it, and only that one
        assertEquals(listOf(RuleEngine.ALLOWED), r.lists.map { it.name })
        assertNotNull(repo.list(RuleEngine.ALLOWED))
        // the app rules weren't touched
        assertFalse(repo.rules.value.any { it.id == "preset-otp" })
        repo.undoRestore(r)
        assertFalse(repo.rules.value.any { it.id == "spam-allowed" })
        assertNull(repo.list(RuleEngine.ALLOWED))
        assertEquals("Restored OTP codes", com.dotforge.ui.alerts.restoredText(listOf("OTP codes")))
        assertEquals("Restored A, B and 2 more", com.dotforge.ui.alerts.restoredText(listOf("A", "B", "C", "D")))
    }

    // ---------------------------------------------------------------- P2: calls only, and the More conditions group

    @Test fun turningARuleToCallsOnlyTakesOutWhatACallCantHave() {
        val g = Group(
            true,
            listOf(
                MoreConditions.condition(Pick.TITLE, T.CONTAINS, "OTP"),
                MoreConditions.condition(Pick.NUMBER, T.STARTS, "140"),
                MoreConditions.condition(Pick.TEXT, T.CONTAINS, "loan"),
                MoreConditions.condition(Pick.CHANNEL, T.IS, "Promotions"),
                MoreConditions.condition(Pick.CONTACTS, T.NOT_IN_CONTACTS),
            ),
            more = true,
        )
        val (kept, gone) = MoreConditions.forCalls(g)
        assertEquals(listOf(Field.NUMBER, Field.CONTACT), kept.conditions.map { it.field })
        assertEquals(listOf(Field.TITLE, Field.TEXT, Field.CHANNEL), gone.map { it.field })
    }

    @Test fun aMigratedRulesPeopleAreNeverItsMoreConditions() {
        // version 2 kept people, starred and unknown senders as one "any" group
        val old = RuleV2(id = "v2", packages = listOf("com.whatsapp"), people = listOf("Mom"), starred = true).migrate()
        assertEquals(1, old.match.groups.size)
        assertTrue(MoreConditions.of(old).conditions.isEmpty())
        // adding more conditions keeps the people group as it was, beside a marked group
        val more = Group(true, listOf(MoreConditions.condition(Pick.TITLE, T.CONTAINS, "OTP")))
        val r = MoreConditions.with(old, more)
        assertEquals(old.match.groups[0], r.match.groups[0])
        assertTrue(r.match.groups[1].more)
        assertEquals(more.conditions, MoreConditions.of(r).conditions)
        // and taking them away leaves the people group alone
        assertEquals(old.match, MoreConditions.with(r, Group()).match)
    }

    @Test fun aBlockRuleWrappedBeforeGroupsWereMarkedStillReads() {
        val p = BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS))
        val plain = SimpleRules.blockRule(p, id = "block-old")
        val unknown = MoreConditions.condition(Pick.CONTACTS, T.NOT_IN_CONTACTS)
        val legacy = plain.copy(match = Match(all = true, groups = listOf(Group(all = false, conditions = plain.match.conditions), Group(true, listOf(unknown)))))
        assertEquals(listOf(unknown), MoreConditions.of(legacy).conditions)
        assertEquals(p, SimpleRules.pattern(legacy))
        assertEquals(plain.match, MoreConditions.with(legacy, Group()).match)
        // a condition on no list at all is never "missing"
        assertFalse(com.dotforge.filter.FilterEngine.missingList(Condition(Field.TITLE, Op.CONTAINS, listOf("x")), emptyList()))
    }
}
