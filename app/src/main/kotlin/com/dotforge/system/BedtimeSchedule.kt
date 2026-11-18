package com.dotforge.system

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Nothing's Glyph bedtime schedule. The window opens at [start] on each of [days] and closes at the next
 * [end], on the following day when [end] is not after [start] (the same rule as Android's ScheduleCalendar,
 * which the firmware uses).
 */
data class BedtimeSchedule(val start: LocalTime, val end: LocalTime, val days: Set<DayOfWeek>) {

    fun isActive(now: LocalDateTime): Boolean {
        val t = now.toLocalTime()
        val today = now.dayOfWeek in days
        return if (end > start) today && t >= start && t < end
        else (today && t >= start) || (now.dayOfWeek.minus(1) in days && t < end)
    }

    companion object {
        /**
         * Parses the stored values: [start] and [end] as "hour,minute", [week] as seven '0'/'1' characters,
         * Monday first. Returns null when any part is missing, malformed or no day is selected.
         */
        fun parse(start: String?, end: String?, week: String?): BedtimeSchedule? {
            val s = parseTime(start) ?: return null
            val e = parseTime(end) ?: return null
            if (week == null || week.length != 7 || week.any { it != '0' && it != '1' }) return null
            val days = week.indices.filter { week[it] == '1' }.map { DayOfWeek.of(it + 1) }.toSet()
            return if (days.isEmpty()) null else BedtimeSchedule(s, e, days)
        }

        private fun parseTime(value: String?): LocalTime? {
            val parts = value?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
            if (parts.size != 2) return null
            val (h, m) = parts
            return if (h != null && m != null && h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        }
    }
}
