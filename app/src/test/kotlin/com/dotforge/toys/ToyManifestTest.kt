package com.dotforge.toys

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
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
        assertEquals(13, toys.size)
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

    @Test fun hubFacesCycle() {
        assertEquals(HubFace.GALLERY, HubFace.CLOCK.next())
        assertEquals(HubFace.CLOCK, HubFace.entries.last().next())
    }
}
