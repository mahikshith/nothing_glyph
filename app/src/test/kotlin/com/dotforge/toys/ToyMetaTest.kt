package com.dotforge.toys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToyMetaTest {
    @Test fun resourceIdsAsTheFirmwareReadsThem() {
        val m = ToyMeta.parse(
            mapOf(
                ToyMeta.NAME to 0x7f120001, ToyMeta.IMAGE to 0x7f080002, ToyMeta.SUMMARY to 0x7f120003,
                ToyMeta.INTRODUCTION to "Shake to roll", ToyMeta.LONG_PRESS to "1", ToyMeta.AOD to 1,
                "com.nothing.glyph.toy.matrix_support" to "true", // not read by the firmware
            ),
        )!!
        assertEquals(0x7f120001, m.nameRes)
        assertNull(m.name)
        assertEquals(0x7f080002, m.imageRes)
        assertEquals(0x7f120003, m.summaryRes)
        assertEquals("Shake to roll", m.introduction)
        assertTrue(m.longPress)
        assertTrue(m.aod)
    }

    @Test fun aToyWithoutANameIsSkipped() {
        assertNull(ToyMeta.parse(mapOf(ToyMeta.IMAGE to 0x7f080002)))
        assertNull(ToyMeta.parse(mapOf(ToyMeta.NAME to -1)))
        assertNull(ToyMeta.parse(mapOf(ToyMeta.NAME to "  ")))
        assertNull(ToyMeta.parse(emptyMap()))
    }

    @Test fun plainStringValuesAreKept() {
        val m = ToyMeta.parse(mapOf(ToyMeta.NAME to "Compass", ToyMeta.SUMMARY to " Points north ", ToyMeta.LONG_PRESS to true, ToyMeta.AOD to "0"))!!
        assertNull(m.nameRes)
        assertEquals("Compass", m.name)
        assertEquals("Points north", m.summary)
        assertNull(m.imageRes)
        assertTrue(m.longPress)
        assertFalse(m.aod)
    }

    @Test fun oddFlagValuesAreOff() {
        val m = ToyMeta.parse(mapOf(ToyMeta.NAME to 5, ToyMeta.LONG_PRESS to 2, ToyMeta.AOD to 1.0f))!!
        assertFalse(m.longPress)
        assertFalse(m.aod)
    }
}
