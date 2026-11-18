package com.dotforge.ringtones

import com.dotforge.composition.GlyphAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** "Lights earlier / later" moves the lights against the sound and keeps the length. */
class LightsSyncTest {
    private val rows = List(GlyphAuthor.FPS * 2) { i -> IntArray(4) { i + 1 } }

    @Test fun laterAddsDarkRowsAtTheStart() {
        val out = RingtoneImporter.shift(rows, 250)
        val n = GlyphAuthor.FPS / 4
        assertEquals(rows.size, out.size)
        assertEquals(0, out[n - 1][0])
        assertEquals(1, out[n][0])
    }

    @Test fun earlierDropsTheFirstRows() {
        val out = RingtoneImporter.shift(rows, -500)
        val n = GlyphAuthor.FPS / 2
        assertEquals(rows.size, out.size)
        assertEquals(n + 1, out[0][0])
        assertEquals(0, out.last()[0])
    }

    @Test fun inTimeIsUnchanged() {
        assertSame(rows, RingtoneImporter.shift(rows, 0))
    }
}
