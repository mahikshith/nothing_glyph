package com.dotforge.toys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Features > Toys: "Added in Glyph Toys", from Nothing's toy list. */
class ToyListTest {
    @Test fun readsOurToysFromTheList() {
        val rows = listOf(
            mapOf("_id" to "1", "package_name" to "com.dotforge", "service_name" to "com.dotforge.toys.ClockToy", "is_active" to "1"),
            mapOf("_id" to "2", "package_name" to "com.dotforge", "service_name" to "com.dotforge.toys.SnakeToy", "is_active" to "0"),
            // a component string, and a service kept relative to its package
            mapOf("component" to "ComponentInfo{com.dotforge/com.dotforge.toys.WeatherToy}", "is_active" to "1"),
            mapOf("pkg" to "com.dotforge", "service" to ".toys.TiltMazeToy", "IS_ACTIVE" to "true"),
            // someone else's toy is left out
            mapOf("package_name" to "com.other", "service_name" to "com.other.Toy", "is_active" to "1"),
        )
        assertEquals(mapOf("ClockToy" to true, "SnakeToy" to false, "WeatherToy" to true, "TiltMazeToy" to true), ToyList.parse(rows))
        assertEquals(emptyMap<String, Boolean>(), ToyList.parse(emptyList()))
    }

    @Test fun statusLines() {
        assertEquals("In Glyph Toys", ToyStatus.of(active = true).label)
        assertEquals("Not added", ToyStatus.of(active = false).label)
        // the list can't be read: the one instruction, never "On"
        assertEquals("Add it in Glyph Toys", ToyStatus.of(active = null).label)
        // a toy missing from a list that was read is not added
        assertEquals(ToyStatus.NOT_ADDED, ToyStatus.of(mapOf("ClockToy" to true), "SnakeToy"))
        assertEquals(ToyStatus.IN, ToyStatus.of(mapOf("ClockToy" to true), "ClockToy"))
        assertEquals(ToyStatus.UNKNOWN, ToyStatus.of(null, "ClockToy"))
        assertTrue(ToyStatus.entries.none { it.label.startsWith("On") })
    }
}
