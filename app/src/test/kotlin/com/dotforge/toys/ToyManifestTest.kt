package com.dotforge.toys

import android.content.pm.PackageManager
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every DotForge toy follows the firmware's meta-data rules (see the Phone (3) firmware notes). */
class ToyManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun toys(): List<Element> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/AndroidManifest.xml").exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(root, "app/src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        return (0 until services.length).map { services.item(it) as Element }.filter { s ->
            val actions = s.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(android, "name") == ToyMeta.ACTION }
        }
    }

    private fun meta(service: Element): Map<String, Element> {
        val list = service.getElementsByTagName("meta-data")
        return (0 until list.length).map { list.item(it) as Element }.associateBy { it.getAttributeNS(android, "name") }
    }

    @Test fun everyToyDeclaresNameImageAndSummaryAsResources() {
        val toys = toys()
        assertEquals(com.dotforge.ui.Toy.entries.size, toys.size)
        assertEquals(17, toys.size)
        for (t in toys) {
            val name = t.getAttributeNS(android, "name")
            val m = meta(t)
            assertEquals(name, "true", t.getAttributeNS(android, "exported"))
            assertTrue(name, m.getValue(ToyMeta.NAME).getAttributeNS(android, "resource").startsWith("@string/"))
            assertTrue(name, m.getValue(ToyMeta.IMAGE).getAttributeNS(android, "resource").startsWith("@drawable/"))
            assertTrue(name, m.getValue(ToyMeta.SUMMARY).getAttributeNS(android, "resource").startsWith("@string/"))
            for (flag in listOf(ToyMeta.LONG_PRESS, ToyMeta.AOD)) m[flag]?.let { assertEquals(name, "1", it.getAttributeNS(android, "value")) }
        }
    }

    @Test fun onlyClockBatteryAndNowPlayingStartOn() {
        val on = toys().filter { it.getAttributeNS(android, "enabled") != "false" }.map { it.getAttributeNS(android, "name").substringAfterLast('.') }
        assertEquals(ToyComponents.DEFAULT_ON, on.toSet())
    }

    @Test fun toggledStatesFollowTheDefault() {
        val default = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        assertTrue(ToyComponents.isOn(default, "ClockToy"))
        assertFalse(ToyComponents.isOn(default, "SnakeToy"))
        assertTrue(ToyComponents.isOn(enabled, "SnakeToy"))
        assertFalse(ToyComponents.isOn(disabled, "ClockToy"))
        // switching back to the manifest's value writes DEFAULT
        assertEquals(enabled, ToyComponents.stateFor(true, "SnakeToy"))
        assertEquals(default, ToyComponents.stateFor(false, "SnakeToy"))
        assertEquals(disabled, ToyComponents.stateFor(false, "ClockToy"))
        assertEquals(default, ToyComponents.stateFor(true, "ClockToy"))
        for ((s, name) in listOf(enabled to "SnakeToy", disabled to "ClockToy", default to "MoonToy")) {
            assertEquals(ToyComponents.isOn(s, name), ToyComponents.isOn(ToyComponents.stateFor(ToyComponents.isOn(s, name), name), name))
        }
    }

    @Test fun hubFacesCycle() {
        assertEquals(HubFace.GALLERY, HubFace.CLOCK.next())
        assertEquals(HubFace.CLOCK, HubFace.entries.last().next())
    }
}
