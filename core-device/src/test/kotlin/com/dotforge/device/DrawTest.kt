package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawTest {
    private val p = DeviceProfile.Phone3
    private fun lit(f: Frame) = p.ledCells.count { f.pixels[it] > 0 }

    @Test fun fillStopsAtLinesAndTheMask() {
        val f = Frame.blank(p)
        assertEquals(p.ledCount, Frame.blank(p).also { Draw.fill(it, 12, 12, 255) }.let(::lit))
        Draw.line(f, 0, 12, 24, 12, 255) // splits the disc
        val before = lit(f)
        Draw.fill(f, 12, 5, 100)
        val top = p.ledCells.count { f.pixels[it] == 100 }
        assertEquals(p.ledCells.count { it / p.size < 12 }, top)
        assertEquals(before + top, lit(f))
        Draw.fill(f, 12, 5, 100) // same value: nothing changes
        Draw.fill(f, 0, 0, 255) // outside the mask: nothing
        assertEquals(before + top, lit(f))
    }

    @Test fun linesRectsAndCircles() {
        val f = Frame.blank(p)
        Draw.line(f, 5, 5, 10, 10, 255)
        assertEquals(6, lit(f))
        val r = Frame.blank(p).also { Draw.rect(it, 8, 8, 16, 14, 255) }
        assertEquals(2 * 9 + 2 * 5, lit(r))
        val c = Frame.blank(p).also { Draw.circle(it, 12, 12, 17, 12, 255) }
        assertEquals(255, c.pixels[p.index(17, 12)])
        assertEquals(255, c.pixels[p.index(12, 7)])
        assertEquals(0, c.pixels[p.index(12, 12)])
        val dot = Frame.blank(p).also { Draw.circle(it, 12, 12, 12, 12, 200) }
        assertEquals(1, lit(dot))
    }

    @Test fun onionShowsThePreviousFrameFaintly() {
        val prev = Frame.blank(p).also { it[3, 12] = 250 }
        val cur = Frame.blank(p).also { it[4, 12] = 200 }
        val shown = Draw.onion(cur, prev)
        assertEquals(50, shown[p.index(3, 12)])
        assertEquals(200, shown[p.index(4, 12)])
        assertEquals(cur.pixels.toList(), Draw.onion(cur, null).toList())
    }
}
