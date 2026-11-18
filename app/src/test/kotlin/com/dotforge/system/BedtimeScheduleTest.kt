package com.dotforge.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class BedtimeScheduleTest {
    // 2026-11-16 is a Monday
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)

    @Test fun parsesNothingFormatMondayFirst() {
        val s = BedtimeSchedule.parse("22,30", "7,0", "1000001")!!
        assertEquals(LocalTime.of(22, 30), s.start)
        assertEquals(LocalTime.of(7, 0), s.end)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), s.days)
    }

    @Test fun rejectsMissingOrMalformedValues() {
        assertNull(BedtimeSchedule.parse(null, "7,0", "1111111"))
        assertNull(BedtimeSchedule.parse("25,0", "7,0", "1111111"))
        assertNull(BedtimeSchedule.parse("22", "7,0", "1111111"))
        assertNull(BedtimeSchedule.parse("22,0", "7,0", "111"))
        assertNull(BedtimeSchedule.parse("22,0", "7,0", "0000000"))
    }

    @Test fun overnightWindowBelongsToTheStartDay() {
        val s = BedtimeSchedule.parse("22,0", "7,0", "1000000")!! // Monday night only
        assertFalse(s.isActive(at(16, 21, 59)))
        assertTrue(s.isActive(at(16, 22)))
        assertTrue(s.isActive(at(17, 6, 59))) // Tuesday morning, started Monday
        assertFalse(s.isActive(at(17, 7)))
        assertFalse(s.isActive(at(17, 23))) // Tuesday night is not selected
    }

    @Test fun sameDayWindow() {
        val s = BedtimeSchedule.parse("13,0", "14,30", "1111111")!!
        assertTrue(s.isActive(at(18, 13, 15)))
        assertFalse(s.isActive(at(18, 14, 30)))
        assertFalse(s.isActive(at(18, 12, 59)))
    }
}
