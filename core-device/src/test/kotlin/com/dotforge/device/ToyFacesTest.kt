package com.dotforge.device

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToyFacesTest {
    private val p = DeviceProfile.Phone3
    private val c = (p.size - 1) / 2
    private fun Frame.at(x: Int, y: Int) = pixels[p.index(x, y)]
    private fun Frame.lit() = p.ledCells.count { pixels[it] > 0 }

    @Test fun everyClockStyleDrawsAndChangesWithTheTime() {
        for (style in ClockStyle.entries) {
            val a = ToyFaces.clock(p, style, 10, 8)
            val b = ToyFaces.clock(p, style, 10, 9)
            assertTrue(style.name, a.lit() > 5)
            assertNotEquals(style.name, a.pixels.toList(), b.pixels.toList())
            assertTrue(style.name, a.pixels.indices.all { p.mask[it] || a.pixels[it] == 0 })
        }
        // 12-hour clocks show 1 for 13:00
        assertEquals(ToyFaces.clock(p, ClockStyle.DIGITAL, 1, 0).pixels.toList(), ToyFaces.clock(p, ClockStyle.DIGITAL, 13, 0, use24h = false).pixels.toList())
    }

    @Test fun analogHandsPoint() {
        // 3:00: the hour hand runs right of the centre, the minute hand up
        val f = ToyFaces.analog(p, 3, 0)
        assertEquals(255, f.at(c + 3, c))
        assertEquals(180, f.at(c, c - 6))
        assertEquals(0, f.at(c - 3, c))
    }

    @Test fun binaryColumnsCountUp() {
        val f = ToyFaces.binary(p, 0, 1)
        val g = ToyFaces.binary(p, 0, 0)
        assertEquals(4, p.ledCells.count { f.pixels[it] == 255 }) // one bit: a 2x2 block
        assertEquals(0, p.ledCells.count { g.pixels[it] == 255 })
    }

    @Test fun moonPhases() {
        // 2000-01-21 was a full moon, 2000-01-06 a new one
        val full = ToyFaces.moonAge(LocalDate.of(2000, 1, 21).toEpochDay() + 0.2)
        assertEquals(0.5, full, 0.03)
        assertEquals(0.0, ToyFaces.moonAge(LocalDate.of(2000, 1, 6).toEpochDay() + 0.76).let { if (it > 0.5) it - 1 else it }, 0.01)
        val bright = ToyFaces.moon(p, 0.5).pixels.count { it == 230 }
        val dark = ToyFaces.moon(p, 0.0).pixels.count { it == 230 }
        assertTrue(bright > 300)
        assertTrue(dark < 20)
        // waxing quarter: the right half lit
        val quarter = ToyFaces.moon(p, 0.25)
        assertEquals(230, quarter.at(c + 5, c))
        assertEquals(18, quarter.at(c - 5, c))
    }

    @Test fun compassAndLevel() {
        assertEquals(255, ToyFaces.compass(p, 0.0).at(c, c - 5)) // facing north: needle up
        assertEquals(255, ToyFaces.compass(p, 90.0).at(c - 5, c)) // facing east: north is to the left
        val flat = ToyFaces.level(p, 0.0, 0.0)
        assertEquals(255, flat.at(c, c))
        val tilted = ToyFaces.level(p, 0.0, 45.0)
        assertEquals(0, tilted.at(c, c))
        assertEquals(255, tilted.at(c + 8, c))
    }

    @Test fun snakeMovesEatsAndCrashes() {
        val g = SnakeGame(p, seed = 1)
        val head = g.body.first()
        g.placeFood(head + 1)
        g.step()
        assertEquals(SnakeGame.START + 1, g.body.size)
        assertEquals(1, g.score)
        g.step()
        assertEquals(SnakeGame.START + 1, g.body.size) // no food: same length
        g.turnRight()
        assertEquals(SnakeGame.Dir.DOWN, g.dir)
        g.turnLeft(); g.turnLeft()
        assertEquals(SnakeGame.Dir.UP, g.dir)
        repeat(30) { g.step() }
        assertTrue(g.over) // ran off the top
        val frame = g.frame()
        assertTrue(frame.lit() >= g.body.size)
        assertFalse(SnakeGame(p).over)
    }

    @Test fun timerBreathAndPlayer() {
        val t = ToyFaces.timer(p, 0.5f, 125)
        assertEquals(p.ring.size / 2, p.ring.count { t.pixels[it] == 255 })
        val inhale = ToyFaces.breath(p, 1.0).lit()
        val full = ToyFaces.breath(p, 5.0).lit()
        val empty = ToyFaces.breath(p, 14.0).lit()
        assertTrue(inhale in (empty + 1) until full)
        assertNotEquals(ToyFaces.nowPlaying(p, true, 0.3f).pixels.toList(), ToyFaces.nowPlaying(p, false, 0.3f).pixels.toList())
        assertEquals(0, ToyFaces.nowPlaying(p, true, null).let { f -> p.ring.count { f.pixels[it] > 0 && f.pixels[it] != 255 } })
    }
}
