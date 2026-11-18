package com.dotforge.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamListTest {
    private val sample = """
        # CallShield spam numbers, database version 68 (updated 2026-11-16)
        # One E.164 number per line.
        +12002662278
        +12012000014
        +447700900123

        +12012000014
        12025550199
        +0123456789
        +1 202 555 0100
        not a number
        +1234567890123456
    """.trimIndent()

    @Test fun parsesHeaderNumbersAndSkipsJunk() {
        val list = SpamList.parse(sample)
        assertEquals(68, list.version)
        assertEquals("2026-11-16", list.updated)
        assertEquals(3, list.size) // duplicate dropped; no "+", leading 0, spaces and 16 digits refused
        assertTrue("+12002662278" in list)
        assertTrue("+447700900123" in list)
        assertFalse("+12025550199" in list)
        assertFalse("12002662278" in list)
        assertFalse("" in list)
    }

    @Test fun emptyAndHeaderlessLists() {
        assertEquals(0, SpamList.EMPTY.size)
        assertFalse("+12002662278" in SpamList.EMPTY)
        val bare = SpamList.parse("+15551234567\n+15551234568")
        assertNull(bare.version)
        assertEquals(2, bare.size)
    }

    @Test fun lookupsWorkOnALargeUnsortedList() {
        val numbers = (0 until 50_000).map { "+1" + (2_000_000_000L + it * 7919L % 1_000_000_000L) }.shuffled(kotlin.random.Random(1))
        val list = SpamList.parse(numbers.asSequence())
        assertEquals(numbers.toSet().size, list.size)
        assertTrue(numbers.take(500).all { it in list })
        assertFalse("+19999999999" in list)
    }

    @Test fun e164ToLong() {
        assertEquals(447700900123L, SpamList.toLong("+447700900123"))
        assertNull(SpamList.toLong("+44770"))
        assertNull(SpamList.toLong("447700900123"))
    }
}
