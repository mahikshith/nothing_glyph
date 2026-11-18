package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphLibraryTest {
    private val p = DeviceProfile.Phone3

    @Test fun atLeastFortyStaticGlyphsAndFiveAnimated() {
        assertTrue(GlyphLibrary.all.count { !it.animated } >= 40)
        assertEquals(setOf("pulse", "ripple", "spinner", "blink", "wave"), GlyphLibrary.all.filter { it.animated }.map { it.id }.toSet())
    }

    @Test fun idsAreUnique() = assertEquals(GlyphLibrary.all.size, GlyphLibrary.all.map { it.id }.toSet().size)

    @Test fun thePrototypeTenAndTheDigitsAreThere() {
        for (id in listOf("chat", "heart", "mail", "star", "bell", "ring", "pulse", "check", "phone", "spam")) assertNotNull(id, GlyphLibrary.get(id))
        for (d in '0'..'9') assertNotNull(GlyphLibrary.get("digit-$d"))
    }

    @Test fun everyGlyphDrawsSomethingInsideTheMaskAndNotEverything() {
        for (g in GlyphLibrary.all) {
            for (af in g.animation(p).frames) {
                val px = af.frame.pixels
                assertTrue(g.id, px.indices.all { px[it] == 0 || p.mask[it] })
            }
            val lit = g.still(p).pixels.count { it > 0 }
            assertTrue("${g.id} lights $lit", lit in 8 until p.ledCount)
        }
    }

    @Test fun stillGlyphsDifferFromEachOther() {
        val stills = GlyphLibrary.all.filter { !it.animated }.map { it.still(p).pixels.toList() }
        assertEquals(stills.size, stills.toSet().size)
    }

    @Test fun alertFadesInAStillGlyphAndPlaysAnAnimatedOne() {
        val a = GlyphLibrary.alert(p, "heart")
        val full = GlyphLibrary.get("heart")!!.still(p).pixels
        assertTrue(a.frames.any { it.frame.pixels.contentEquals(full) })
        assertTrue(a.frames.first().frame.pixels.max() < 255)
        assertEquals(GlyphLibrary.get("ripple")!!.animation(p).frames.size, GlyphLibrary.alert(p, "ripple").frames.size)
        assertNotNull(GlyphLibrary.alert(p, "no-such-glyph"))
    }
}
