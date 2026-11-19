package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weather, Day dots and Tilt maze (parity rows 89-91). */
class ParityToysTest {
    private val p = DeviceProfile.Phone3

    @Test fun openMeteoAnswersAreRead() {
        val body = """{"latitude":51.5,"current":{"time":"2026-11-19T10:00","temperature_2m":7.6,"weather_code":61},"daily":{"precipitation_probability_max":[80]}}"""
        val w = OpenMeteo.parse(body)!!
        assertEquals(7.6, w.tempC, 0.001)
        assertEquals(Sky.RAIN, w.sky)
        assertEquals(80, w.rainPct)
        // no daily block: no rain chance; no current temperature: nothing
        assertNull(OpenMeteo.parse("""{"current":{"temperature_2m":20,"weather_code":0}}""")!!.rainPct)
        assertNull(OpenMeteo.parse("""{"current":{"weather_code":0}}"""))
        assertNull(OpenMeteo.parse("not json"))
        val place = OpenMeteo.parsePlace("""{"results":[{"name":"Leeds","latitude":53.8,"longitude":-1.55}]}""")!!
        assertEquals("Leeds", place.third)
        assertEquals(-1.55, place.second, 0.001)
        assertNull(OpenMeteo.parsePlace("""{"generationtime_ms":0.2}"""))
        assertTrue(OpenMeteo.forecastUrl(53.8, -1.55).contains("latitude=53.800&longitude=-1.550"))
        assertTrue(OpenMeteo.geocodeUrl("New York").endsWith("name=New+York"))
    }

    @Test fun skyAndDegrees() {
        assertEquals(Sky.CLEAR, Sky.of(0))
        assertEquals(Sky.SNOW, Sky.of(73))
        assertEquals(Sky.STORM, Sky.of(95))
        assertEquals(Sky.FOG, Sky.of(45))
        assertEquals(45, OpenMeteo.degrees(7.2, fahrenheit = true))
        assertEquals(-3, OpenMeteo.degrees(-2.6, fahrenheit = false))
    }

    @Test fun weatherFacesFitInsideTheRing() {
        val ring = p.ring.toSet()
        for (sky in Sky.entries) for (t in listOf(-12.0, 3.0, 24.0, 104.0)) {
            val f = WeatherFace.frame(p, WeatherNow(t, sky, 30), fahrenheit = false)
            val lit = p.ledCells.filter { f.pixels[it] > 0 }
            assertTrue("$sky $t", lit.size > 10)
            assertTrue("$sky $t touches the ring", lit.none { it in ring })
        }
        assertNotEquals(WeatherFace.rain(p, 80).pixels.toList(), WeatherFace.rain(p, 20).pixels.toList())
        assertTrue(WeatherFace.waiting(p).pixels.any { it > 0 })
    }

    private fun assertNotEquals(a: Any, b: Any) = assertFalse(a == b)

    @Test fun oneDotPerTenMinutesAndTheGoalFaint() {
        val cells = DayDots.cells(p)
        assertTrue("room for a long day", cells.size >= 40)
        val f = DayDots.frame(p, 95, 180)
        assertEquals(9, cells.count { f.pixels[it] == 200 })
        assertEquals(9, cells.count { f.pixels[it] == 45 })
        assertTrue(p.ring.all { f.pixels[it] == 0 })
        // over the goal: the extra dots bright and the ring lit
        val over = DayDots.frame(p, 200, 120)
        assertEquals(8, cells.count { over.pixels[it] == 255 })
        assertTrue(p.ring.all { over.pixels[it] == 90 })
    }

    @Test fun screenTimeAddsTheOnSpells() {
        val start = 0L
        // on 10-20, on 30-35, on from 50 to now (60)
        val events = listOf(10L to true, 20L to false, 30L to true, 35L to false, 50L to true)
        assertEquals(25L, DayDots.screenOnMs(events, start, 60))
        // already on when the day started
        assertEquals(15L, DayDots.screenOnMs(listOf(15L to false), start, 60))
        assertEquals(0L, DayDots.screenOnMs(emptyList(), start, 60))
    }

    /** Every maze can be finished: a path of open cells joins the start to the goal. */
    @Test fun bothMazesCanBeFinished() {
        for (level in TiltMaze.Level.entries) {
            val m = TiltMaze(level)
            assertTrue(level.name, level.rows.all { it.length == p.size } && level.rows.size == p.size)
            val seen = mutableSetOf(m.start)
            val queue = ArrayDeque(listOf(m.start))
            while (queue.isNotEmpty()) {
                val (x, y) = queue.removeFirst()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val n = x + dx to y + dy
                    if (!m.isWall(n.first, n.second) && p.isActive(n.first, n.second) && seen.add(n)) queue += n
                }
            }
            assertTrue("$level goal reachable", m.goal in seen)
            // and the open cells are all on the matrix
            assertTrue(p.isActive(m.start.first, m.start.second) && p.isActive(m.goal.first, m.goal.second))
        }
    }

    @Test fun theBallRollsTheWayThePhoneTilts() {
        val m = TiltMaze(TiltMaze.Level.EASY)
        val (sx, sy) = m.start
        // a small tilt does nothing
        assertFalse(m.step(1f, 0.5f))
        assertEquals(m.start, m.ball)
        // right is open from the start on Easy
        assertTrue(m.step(5f, 0f))
        assertEquals(sx + 1 to sy, m.ball)
        // a wall stops it
        val stuck = TiltMaze(TiltMaze.Level.EASY)
        assertFalse(stuck.isWall(sx + 1, sy))
        assertTrue(stuck.isWall(sx - 1, sy))
        assertFalse(stuck.step(-5f, 0f))
        m.restart()
        assertEquals(m.start, m.ball)
        val f = m.frame(p, blink = true)
        assertEquals(255, f.pixels[p.index(sx, sy)])
        assertEquals(160, f.pixels[p.index(m.goal.first, m.goal.second)])
        assertNotNull(f)
    }
}
