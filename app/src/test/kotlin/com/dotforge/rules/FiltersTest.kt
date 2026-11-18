package com.dotforge.rules

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

    private val calls = Rule(name = "Calls", packages = listOf("dialer"))
    private fun note(text: String = "Incoming call") = NotificationInfo("k", "dialer", "Phone", "Sam", text, 0, fromPerson = true)
    private fun cond(h: Int = 12, dnd: Boolean = false, locked: Boolean = true, faceDown: Boolean? = null) =
        { Conditions(at(0, h), dnd, locked, faceDown) }

    private fun decide(filters: FilterSettings = FilterSettings(), spam: SpamSettings = SpamSettings(), c: () -> Conditions = cond(), text: String = "Incoming call") =
        Delivery.decide(note(text), listOf(calls), spam, filters, c) { null }

    @Test fun showsWhenNothingHoldsItBack() = assertEquals(Verdict.Show(RuleMatch(calls, "Phone")), decide())

    @Test fun spamComesFirstThenRulesThenFilters() {
        val spam = SpamSettings(enabled = true)
        assertEquals(Verdict.Blocked("spam word \"lottery\""), decide(spam = spam, text = "lottery win"))
        assertEquals(Verdict.NoRule, Delivery.decide(note().copy(packageName = "other"), listOf(calls), spam, FilterSettings(), cond()) { null })
    }

    @Test fun filtersHoldBackAMatch() {
        fun reason(v: Verdict) = (v as? Verdict.Quiet)?.reason
        assertEquals("quiet hours", reason(decide(FilterSettings(quiet = night), c = cond(h = 23))))
        assertEquals("Do Not Disturb", reason(decide(c = cond(dnd = true))))
        assertTrue(decide(FilterSettings(respectDnd = false), c = cond(dnd = true)) is Verdict.Show)
        assertEquals("phone unlocked", reason(decide(FilterSettings(onlyLocked = true), c = cond(locked = false))))
        assertEquals("not face down", reason(decide(FilterSettings(onlyFaceDown = true), c = cond(faceDown = false))))
        assertTrue(decide(FilterSettings(onlyFaceDown = true), c = cond(faceDown = true)) is Verdict.Show)
        assertTrue(decide(FilterSettings(onlyFaceDown = true), c = cond(faceDown = null)) is Verdict.Show) // no reading yet
    }

    @Test fun conditionsAreOnlyReadForAMatch() {
        var reads = 0
        Delivery.decide(note().copy(packageName = "other"), listOf(calls), SpamSettings(), FilterSettings(), { reads++; cond()() }) { null }
        assertEquals(0, reads)
    }
}
