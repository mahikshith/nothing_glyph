package com.dotforge.rules

import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FiltersTest {
    // 2026-11-16 is a Monday
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 16 + day, h, m)

    private val night = QuietHours(enabled = true, start = 22 * 60, end = 7 * 60)

    @Test fun quietHoursRunAcrossMidnight() {
        assertFalse(night.isActive(at(0, 21, 59)))
        assertTrue(night.isActive(at(0, 22, 0)))
        assertTrue(night.isActive(at(1, 3, 0)))
        assertTrue(night.isActive(at(1, 6, 59)))
        assertFalse(night.isActive(at(1, 7, 0)))
        assertFalse(night.isActive(at(1, 12, 0)))
    }

    @Test fun anOvernightRangeBelongsToTheDayItStarts() {
        val fridayNight = night.copy(days = listOf(5))
        assertTrue(fridayNight.isActive(at(4, 23, 0))) // Friday 23:00
        assertTrue(fridayNight.isActive(at(5, 2, 0))) // Saturday 02:00, still Friday's night
        assertFalse(fridayNight.isActive(at(5, 23, 0))) // Saturday night
        assertFalse(fridayNight.isActive(at(4, 2, 0))) // Friday 02:00 is Thursday's night
    }

    @Test fun sameDayRangeAndSwitches() {
        val lunch = QuietHours(enabled = true, start = 12 * 60, end = 13 * 60, days = listOf(1, 2, 3, 4, 5))
        assertTrue(lunch.isActive(at(0, 12, 30)))
        assertFalse(lunch.isActive(at(0, 13, 0)))
        assertFalse(lunch.isActive(at(5, 12, 30))) // Saturday
        assertFalse(lunch.copy(enabled = false).isActive(at(0, 12, 30)))
        assertFalse(lunch.copy(days = emptyList()).isActive(at(0, 12, 30)))
    }

    private val calls = Rule(id = "calls", name = "Calls", packages = listOf("dialer"))
    private val spamWords = Rule(
        id = "spam", name = "Spam words", match = Match(conditions = listOf(Condition(Field.MESSAGE, Op.CONTAINS, list = "Spam words"))),
        action = Action.SPAM,
    )
    private val lists = listOf(NamedList("Spam words", listOf("lottery")))
    private fun note(text: String = "Incoming call") = NotificationInfo("k", "dialer", "Phone", "Sam", text, 0, fromPerson = true)
    private fun cond(h: Int = 12, dnd: Boolean = false, locked: Boolean = true, faceDown: Boolean? = null) =
        { Conditions(at(0, h), dnd, locked, faceDown) }

    private fun decide(filters: FilterSettings = FilterSettings(), c: () -> Conditions = cond(), info: NotificationInfo = note()) =
        Delivery.decide(info, listOf(calls, spamWords), lists, filters, c, { null })

    @Test fun showsWhenNothingHoldsItBack() = assertEquals(Verdict.Show(RuleMatch(calls, "Phone")), decide())

    @Test fun spamComesFirstThenRulesThenFilters() {
        val v = decide(info = note("lottery win"))
        assertTrue(v is Verdict.Blocked)
        assertEquals("spam", (v as Verdict.Blocked).match.rule.id)
        assertEquals("title or text contains list \"Spam words\"", v.match.why)
        assertEquals(Verdict.NoRule, decide(info = note().copy(packageName = "other")))
        // quiet hours hold back a glyph, never the spam filter
        assertTrue(decide(FilterSettings(quiet = night), cond(h = 23), note("lottery")) is Verdict.Blocked)
    }

    @Test fun filtersHoldBackAMatch() {
        fun reason(v: Verdict) = (v as? Verdict.Quiet)?.reason
        assertEquals("Quiet", reason(decide(FilterSettings(quiet = night), c = cond(h = 23))))
        assertEquals("Do Not Disturb", reason(decide(c = cond(dnd = true))))
        assertTrue(decide(FilterSettings(respectDnd = false), c = cond(dnd = true)) is Verdict.Show)
        assertEquals("phone in use", reason(decide(c = cond(locked = false))))
        assertTrue(decide(FilterSettings(whileUsing = false), c = cond(locked = false)) is Verdict.Show)
        // the firmware hides app lights face down: History says so
        assertEquals("phone face down", reason(decide(c = cond(faceDown = true))))
        assertTrue(decide(c = cond(faceDown = false)) is Verdict.Show)
        assertTrue(decide(c = cond(faceDown = null)) is Verdict.Show) // no reading yet
    }

    @Test fun subjectCarriesTheNotificationsFields() {
        val info = note().copy(sender = "", number = "+447700900123", channel = "calls", groupChat = true)
        val s = Delivery.subject(info, FilterSettings(), cond(), { Sender(ContactStatus.STARRED, "Sam") }, "GB") { it == "+447700900123" }
        assertEquals("Sam", s.sender) // the title, as it comes from a person
        assertEquals("calls", s.channel)
        assertTrue(s.groupChat)
        assertTrue(s.onSpamList)
        assertEquals(true, s.caller?.starred)
    }
}
