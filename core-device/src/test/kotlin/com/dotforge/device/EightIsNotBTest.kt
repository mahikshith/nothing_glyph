package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden check: an 8 never reads as a B, in every font and size the Glyph draws digits with. An 8 is notched
 * at its four corners and at its waist on both sides (so it is left-right symmetric); a B is flat on the left.
 */
class EightIsNotBTest {
    private val p = DeviceProfile.Phone3

    /** Rows of '#' and '.' for a glyph drawn into a frame, cropped to its lit box. */
    private fun rows(f: Frame): List<String> {
        val n = p.size
        val lit = f.pixels.indices.filter { f.pixels[it] > 0 }
        val x0 = lit.minOf { it % n }
        val x1 = lit.maxOf { it % n }
        val y0 = lit.minOf { it / n }
        val y1 = lit.maxOf { it / n }
        return (y0..y1).map { y -> (x0..x1).joinToString("") { x -> if (f.pixels[y * n + x] > 0) "#" else "." } }
    }

    private fun rows(s: Strip) = (0 until s.h).map { y -> (0 until s.w).joinToString("") { x -> if (s.lit(x, y)) "#" else "." } }

    /** The checks every 8 must pass: notched corners and waist on both sides, and symmetric. */
    private fun assertEight(name: String, g: List<String>) {
        val h = g.size
        val w = g[0].length
        val mid = h / 2
        val left = g.map { it.first() }
        val right = g.map { it.last() }
        assertEquals("$name top-left corner notched\n${g.joinToString("\n")}", '.', left[0])
        assertEquals("$name bottom-left corner notched", '.', left[h - 1])
        assertEquals("$name top-right corner notched", '.', right[0])
        assertEquals("$name bottom-right corner notched", '.', right[h - 1])
        // the waist: some row near the middle is open on both sides
        assertTrue("$name waist notched on both sides\n${g.joinToString("\n")}", (mid - 1..mid + 1).any { y -> g[y].first() == '.' && g[y].last() == '.' })
        assertEquals("$name is symmetric", g, g.map { it.reversed() })
        assertTrue(w >= 3)
    }

    @Test fun bigDigitsAtEverySize() {
        // 1x (m:ss), 2x (the big seconds and minutes) and 3x; 4x (20 dots tall) doesn't fit the round Glyph
        for (scale in 1..3) {
            val f = Frame.blank(p)
            BigDigits.draw(f, "8", (p.size - 3 * scale) / 2, (p.size - 5 * scale) / 2, scale)
            assertEight("BigDigits x$scale", rows(f))
        }
        // scaled up, the 8 keeps the digits' stroke: two closed loops, not a chain of single dots
        val two = Frame.blank(p).also { BigDigits.draw(it, "8", 9, 7, 2) }
        assertEquals(listOf(".####.", ".####.", "##..##", "##..##", ".####.", ".####.", "##..##", "##..##", ".####.", ".####."), rows(two))
    }

    @Test fun everyDotFont() {
        for (font in DotFont.entries) {
            val eight = rows(DotFonts.glyph(font, '8'))
            val b = rows(DotFonts.glyph(font, 'B'))
            assertNotEquals("${font.label}: 8 and B", b, eight)
            // the B's left side is solid; the 8's isn't
            assertTrue("${font.label}: B is flat on the left", b.all { it.contains('#') })
            val eightCore = if (font == DotFont.SERIF) eight.map { it.substring(1, it.length - 1) } else eight
            assertEight(font.label, eightCore)
        }
    }

    @Test fun pixelFont() {
        val g = PixelFont.getGlyph('8').map { r -> r.joinToString("") { if (it != 0) "#" else "." } }
        assertEight("PixelFont", g)
    }

    @Test fun theCalendarsYearDigits() {
        assertEight("GlyphArt year", com.dotforge.device.library.GlyphArt.digit('8')!!)
    }

    @Test fun theTimerFaces() {
        // the small m:ss font, and the big seconds
        assertEight("small", rows(TimerFaces.small(p, "8")))
        assertEight("big seconds", rows(TimerFaces.digits(p, "8")))
        assertFalse(rows(TimerFaces.small(p, "8")) == rows(TimerFaces.small(p, "0")))
    }
}
