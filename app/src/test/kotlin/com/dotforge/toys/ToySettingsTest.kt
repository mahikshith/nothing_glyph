package com.dotforge.toys

import org.junit.Assert.assertEquals
import org.junit.Test

class ToySettingsTest {
    @Test fun twelveHourClock() {
        assertEquals(listOf(12, 1, 11, 12, 1, 11), listOf(0, 1, 11, 12, 13, 23).map { ToySettings.displayHour(it, use24h = false) })
        assertEquals(listOf(0, 13, 23), listOf(0, 13, 23).map { ToySettings.displayHour(it, use24h = true) })
    }
}
