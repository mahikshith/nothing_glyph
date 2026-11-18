package com.dotforge.device

import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTest {
    private val p = DeviceProfile.Phone3

    @Test fun maskHas489Leds() {
        assertEquals(625, p.cellCount)
        assertEquals(489, p.ledCount)
        assertEquals(489, p.mask.count { it })
        assertFalse(p.isActive(0, 0))
        assertTrue(p.isActive(12, 0))
        assertTrue(p.isActive(0, 12))
        assertFalse(p.isActive(0, 8))
    }

    @Test fun maskIsSymmetric() {
        for (y in 0 until 25) for (x in 0 until 25) {
            assertEquals(p.isActive(x, y), p.isActive(24 - x, y))
            assertEquals(p.isActive(x, y), p.isActive(x, 24 - y))
            assertEquals(p.isActive(x, y), p.isActive(y, x))
        }
    }

    @Test fun ringHas68CellsClockwiseFromTwelve() {
        val ring = p.ring
        assertEquals(68, ring.size)
        assertEquals(68, ring.toSet().size)
        assertTrue(ring.all { p.mask[it] })
        // starts at 12 o'clock: top centre
        assertEquals(p.index(12, 0), ring.first())
        // 3 o'clock cell (24,12) is a quarter of the way round, 6 o'clock (12,24) half way
        assertTrue(ring.indexOf(p.index(24, 12)) in 15..19)
        assertTrue(ring.indexOf(p.index(12, 24)) in 32..36)
        // each consecutive pair (incl. wrap) is 8-connected
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            val dx = Math.abs(a % 25 - b % 25); val dy = Math.abs(a / 25 - b / 25)
            assertTrue("gap at $i", dx <= 1 && dy <= 1)
        }
        // clockwise: second cell is to the right of the first
        assertTrue(ring[1] % 25 > 12 || ring[1] / 25 > 0)
    }

    @Test fun realLedRoundTrip() {
        val frame = IntArray(625) { if (p.mask[it]) (it * 7) % 256 else 0 }
        val real = p.toRealLeds(frame)
        assertEquals(489, real.size)
        assertArrayEquals(frame, p.fromRealLeds(real))
    }

    @Test fun fromRealLedsPlacesRowMajor() {
        val real = IntArray(489) { it }
        val f = p.fromRealLeds(real)
        assertEquals(0, f[p.index(9, 0)])
        assertEquals(6, f[p.index(15, 0)])
        assertEquals(7, f[p.index(7, 1)])
    }

    @Test fun museumJsonRoundTrip() {
        val f1 = p.fromRealLeds(IntArray(489) { it % 256 })
        val f2 = IntArray(625)
        val json = GlyphMuseumJson.export(p, listOf(f1, f2), listOf(100, 250))
        assertTrue(json.contains("\"frames\""))
        val back = GlyphMuseumJson.import(p, json)
        assertEquals(2, back.size)
        assertArrayEquals(f1, back[0].first)
        assertEquals(250, back[1].second)
    }

    @Test fun museumJsonParsesExternalAndRejectsBadLength() {
        val vals = (0 until 489).joinToString(",")
        val ok = """{"version":1,"extra":true,"frames":[{"duration":40,"values":[$vals]}]}"""
        assertEquals(1, GlyphMuseumJson.import(p, ok).size)
        val bad = """{"version":1,"frames":[{"duration":40,"values":[1,2,3]}]}"""
        assertThrows(IllegalArgumentException::class.java) { GlyphMuseumJson.import(p, bad) }
    }

    @Test fun dotTextDrawsInsideMask() {
        val f = DotText.centered(25, "12")
        assertTrue(f.any { it > 0 })
        assertEquals(f.size, 625)
        assertTrue(DotText.width("AB") == 11)
    }
}

