package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotArtTest {
    private val p = DeviceProfile.Phone3
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A 100x50 picture: left half white, right half black (the centre square is x 25..74). */
    private fun halves() = IntArray(100 * 50) { i -> if (i % 100 < 50) white else black }

    @Test fun centreSquareIsCroppedAndHalvesStayHalves() {
        for (mode in DotArtMode.entries) {
            val f = DotArt.frame(p, 100, 50, halves(), mode)
            assertEquals(mode.name, 255, f.pixels[p.index(3, 12)])
            assertEquals(mode.name, 0, f.pixels[p.index(21, 12)])
            assertTrue(mode.name, f.pixels.indices.all { p.mask[it] || f.pixels[it] == 0 })
        }
        val inverted = DotArt.frame(p, 100, 50, halves(), DotArtMode.GREY, invert = true)
        assertEquals(0, inverted.pixels[p.index(3, 12)])
    }

    @Test fun greysBecomeLevelsOrPatterns() {
        // a horizontal ramp from black to white
        val ramp = IntArray(250 * 250) { i -> val v = (i % 250) * 255 / 249; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val post = DotArt.frame(p, 250, 250, ramp, DotArtMode.POSTERISE)
        assertEquals(setOf(0, 110, 255), p.ledCells.map { post.pixels[it] }.toSet())
        val dither = DotArt.frame(p, 250, 250, ramp, DotArtMode.DITHER)
        assertEquals(setOf(0, 255), p.ledCells.map { dither.pixels[it] }.toSet())
        val row = (0 until 25).map { dither.pixels[p.index(it, 12)] }
        assertTrue(row.take(8).count { it > 0 } < row.takeLast(8).count { it > 0 })
        val grey = DotArt.frame(p, 250, 250, ramp, DotArtMode.GREY)
        assertTrue(p.ledCells.map { grey.pixels[it] }.toSet().size > 10)
    }

    @Test fun flatPicturesDoNotCrash() {
        val f = DotArt.frame(p, 10, 10, IntArray(100) { white }, DotArtMode.DITHER)
        assertTrue(f.pixels.all { it in 0..255 })
    }
}
