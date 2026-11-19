package com.dotforge.device

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the sky is doing, from a WMO weather code (Open-Meteo's `weather_code`). */
enum class Sky {
    CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM;

    companion object {
        fun of(code: Int): Sky = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> STORM
            else -> CLOUDY
        }
    }
}

/** The weather now: temperature in °C, the sky, and today's chance of rain (null when not given). */
data class WeatherNow(val tempC: Double, val sky: Sky, val rainPct: Int?)

/** Open-Meteo answers (no key needed). Pure; tested. */
object OpenMeteo {
    private val json = Json { ignoreUnknownKeys = true }

    fun forecastUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&daily=precipitation_probability_max&forecast_days=1&timezone=auto"
            .format(java.util.Locale.ROOT, lat, lon)

    fun geocodeUrl(place: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + java.net.URLEncoder.encode(place.trim(), "UTF-8")

    /** The weather in a forecast answer; null when it has no current temperature. */
    fun parse(body: String): WeatherNow? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val current = o["current"]?.jsonObject ?: return null
        val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: return null
        val code = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 3
        val rain = (o["daily"] as? JsonObject)?.get("precipitation_probability_max")?.let { it as? JsonArray }?.firstOrNull()?.jsonPrimitive?.intOrNull
        WeatherNow(temp, Sky.of(code), rain)
    }.getOrNull()

    /** The first place in a geocoding answer: latitude, longitude and its name. */
    fun parsePlace(body: String): Triple<Double, Double, String>? = runCatching {
        val r = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        Triple(r["latitude"]!!.jsonPrimitive.doubleOrNull!!, r["longitude"]!!.jsonPrimitive.doubleOrNull!!, r["name"]?.jsonPrimitive?.content.orEmpty())
    }.getOrNull()

    /** Whole degrees in the unit asked for. */
    fun degrees(tempC: Double, fahrenheit: Boolean): Int = (if (fahrenheit) tempC * 9 / 5 + 32 else tempC).roundToInt()
}

/** The Weather toy's faces: the sky's icon over the temperature; a long press shows the chance of rain. */
object WeatherFace {
    private val ICONS = mapOf(
        Sky.CLEAR to listOf("....#....", ".#.....#.", "...###...", "#.#####.#", "...###...", ".#.....#.", "....#...."),
        Sky.PARTLY to listOf("#.#......", ".##......", "##..###..", "...#...#.", "..#.....#", "..#.....#", "...#####."),
        Sky.CLOUDY to listOf("..###....", ".#...##..", "#......#.", "#.......#", "#.......#", ".#######.", "........."),
        Sky.FOG to listOf("#######", ".......", ".######", ".......", "######.", ".......", ".######"),
        Sky.RAIN to listOf("..###..", ".#...#.", "#.....#", ".#####.", ".......", "#.#.#..", ".#.#.#."),
        Sky.SNOW to listOf("...#...", "#..#..#", ".#.#.#.", "...#...", ".#.#.#.", "#..#..#", "...#..."),
        Sky.STORM to listOf("..###..", ".#...#.", "#.....#", ".#####.", "...#...", "..##...", "..#...."),
    )

    fun frame(p: DeviceProfile, w: WeatherNow, fahrenheit: Boolean): Frame {
        val f = Frame.blank(p)
        val icon = ICONS.getValue(w.sky)
        val iw = icon.maxOf { it.length }
        val x0 = (p.size - iw) / 2
        icon.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') f[x0 + x, 4 + y] = 255 } }
        val t = OpenMeteo.degrees(w.tempC, fahrenheit)
        val text = (if (t < 0) "-" else "") + abs(t).toString()
        drawSmall(f, text, 14)
        // the degree mark, top right of the number
        f[(p.size + TimerFaces.smallWidth(text.replace("-", ""))) / 2 + 1, 14] = 255
        return f
    }

    /** The chance of rain today: the number over a rain icon. */
    fun rain(p: DeviceProfile, pct: Int): Frame {
        val f = Frame.blank(p)
        val icon = ICONS.getValue(Sky.RAIN)
        val x0 = (p.size - 7) / 2
        icon.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') f[x0 + x, 4 + y] = 255 } }
        drawSmall(f, pct.coerceIn(0, 100).toString(), 14)
        return f
    }

    /** "No weather yet": the cloud over "--" where the temperature goes, never a made-up number. */
    fun waiting(p: DeviceProfile): Frame {
        val f = Frame.blank(p)
        val icon = ICONS.getValue(Sky.CLOUDY)
        val x0 = (p.size - 9) / 2
        icon.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') f[x0 + x, 4 + y] = 255 } }
        // two dashes, each as wide as a digit, where the number sits
        val w = BigDigits.GLYPH_WIDTH * 2 + 1
        val dx = (p.size - w) / 2
        for (x in 0 until w) if (x != BigDigits.GLYPH_WIDTH) f[dx + x, 16] = 255
        return f
    }

    private fun drawSmall(f: Frame, text: String, y: Int) {
        val digits = text.filter { it.isDigit() }
        var x = (f.profile.size - TimerFaces.smallWidth(digits)) / 2
        if (text.startsWith("-")) {
            f[x - 3, y + 2] = 255
            f[x - 2, y + 2] = 255
        }
        BigDigits.draw(f, digits, x, y, 1)
    }
}

/** Day dots: one dot for every 10 minutes of screen time today, the day's goal shown faint. Pure; tested. */
object DayDots {
    const val MINUTES_PER_DOT = 10
    /** The goal's dots still to fill: faint, but clear of the off dots. */
    const val GOAL_DOT = 80

    /** The dot cells, every other cell inside the ring, from the top left, row by row. */
    fun cells(p: DeviceProfile): List<Int> {
        val out = ArrayList<Int>()
        val ring = p.ring.toSet()
        for (y in 1 until p.size step 2) for (x in 1 until p.size step 2) {
            val i = p.index(x, y)
            if (p.mask[i] && i !in ring && neighboursClear(p, x, y, ring)) out += i
        }
        return out
    }

    private fun neighboursClear(p: DeviceProfile, x: Int, y: Int, ring: Set<Int>): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx !in 0 until p.size || ny !in 0 until p.size) return false
            val i = p.index(nx, ny)
            if (!p.mask[i] || i in ring) return false
        }
        return true
    }

    fun frame(p: DeviceProfile, minutes: Int, goalMinutes: Int): Frame {
        val f = Frame.blank(p)
        val cells = cells(p)
        val lit = (minutes / MINUTES_PER_DOT).coerceIn(0, cells.size)
        val goal = (goalMinutes / MINUTES_PER_DOT).coerceIn(0, cells.size)
        cells.forEachIndexed { i, cell ->
            f.pixels[cell] = when {
                i < lit -> if (i >= goal && goal > 0) 255 else 200
                i < goal -> GOAL_DOT
                else -> 0
            }
        }
        // over the goal: the ring says so
        if (goal in 1..<lit) for (cell in p.ring) f.pixels[cell] = 90
        return f
    }

    /**
     * Screen-on time from [events] (time, true = screen on) between [start] and [now]: the screen counts as on at
     * [start] when the first event is "off".
     */
    fun screenOnMs(events: List<Pair<Long, Boolean>>, start: Long, now: Long): Long {
        val sorted = events.filter { it.first in start..now }.sortedBy { it.first }
        var on = sorted.firstOrNull()?.second == false
        var since = start
        var total = 0L
        for ((t, state) in sorted) {
            if (on && !state) total += t - since
            if (!on && state) since = t
            on = state
        }
        if (on) total += now - since
        return total
    }
}

/**
 * Tilt maze: a ball rolls one dot at a time the way the phone tilts, through walls drawn on the matrix, to the
 * blinking goal. Pure; tested.
 */
class TiltMaze(val level: Level) {
    enum class Level(val label: String, val rows: List<String>) {
        EASY(
            "Easy",
            listOf(
                ".........................",
                ".........................",
                ".........................",
                "........#########........",
                "......##S......#.##......",
                ".....#.........#...#.....",
                "....#..####....#....#....",
                "....#.....#.........#....",
                "...#......#....######.#..",
                "...#......#...........#..",
                "...#..#####...........#..",
                "...#..........#####...#..",
                "...#..............#...#..",
                "...#####..........#...#..",
                "...#......#.......#...#..",
                "...#......#....####...#..",
                "...#......#...........#..",
                "....#.....######.....#...",
                "....#...............G#...",
                ".....#.............#.....",
                "......##.........##......",
                "........#########........",
                ".........................",
                ".........................",
                ".........................",
            ),
        ),
        HARD(
            "Hard",
            listOf(
                ".........................",
                ".........................",
                ".........................",
                "........#########........",
                "......##S#.....#.##......",
                ".....#...#.###.#...#.....",
                "....#....#...#.#.#..#....",
                "....#.####.#.#...#..#....",
                "...#.......#.#####.##.#..",
                "...#.#######.....#....#..",
                "...#.#.....#####.#.##.#..",
                "...#.#.###.....#.#..#.#..",
                "...#...#.#.###.#.##.#.#..",
                "...#####.#...#.#....#.#..",
                "...#.....###.#.######.#..",
                "...#.###.....#........#..",
                "...#...#######.######.#..",
                "....#..#.....#......#....",
                "....##.#.###.######G#....",
                ".....#...#.........#.....",
                "......##.#.......##......",
                "........#########........",
                ".........................",
                ".........................",
                ".........................",
            ),
        ),
    }

    private val size = level.rows.size
    private val walls: Set<Pair<Int, Int>> = buildSet {
        level.rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == '#') add(x to y) } }
    }
    val start: Pair<Int, Int> = find('S')
    val goal: Pair<Int, Int> = find('G')
    var ball: Pair<Int, Int> = start
        private set
    val won: Boolean get() = ball == goal

    private fun find(ch: Char): Pair<Int, Int> {
        level.rows.forEachIndexed { y, r -> val x = r.indexOf(ch); if (x >= 0) return x to y }
        error("no $ch in the maze")
    }

    fun isWall(x: Int, y: Int): Boolean = (x to y) in walls || x !in 0 until size || y !in 0 until size

    /**
     * One step for a tilt: [tx] right is positive, [ty] towards you is positive (m/s², the accelerometer's
     * pull). The steeper axis wins; a tilt under [THRESHOLD] does nothing. Returns true when the ball moved.
     */
    fun step(tx: Float, ty: Float): Boolean {
        if (won) return false
        val (dx, dy) = when {
            abs(tx) < THRESHOLD && abs(ty) < THRESHOLD -> return false
            abs(tx) >= abs(ty) -> (if (tx > 0) 1 else -1) to 0
            else -> 0 to (if (ty > 0) 1 else -1)
        }
        val next = ball.first + dx to ball.second + dy
        if (isWall(next.first, next.second)) {
            // the other axis, when it is tilted too, can still move the ball round a corner
            val (ax, ay) = if (dx != 0) 0 to (if (ty > 0) 1 else -1) else (if (tx > 0) 1 else -1) to 0
            val alt = ball.first + ax to ball.second + ay
            if ((dx != 0 && abs(ty) >= THRESHOLD) || (dy != 0 && abs(tx) >= THRESHOLD)) {
                if (!isWall(alt.first, alt.second)) { ball = alt; return true }
            }
            return false
        }
        ball = next
        return true
    }

    fun restart() { ball = start }

    /** Walls at [WALL] (clear, but under the ball), the ball full, the goal blinking ([blink] on or off). */
    fun frame(p: DeviceProfile, blink: Boolean): Frame {
        val f = Frame.blank(p)
        for ((x, y) in walls) if (p.isActive(x, y)) f[x, y] = WALL
        if (blink || won) f[goal.first, goal.second] = GOAL
        f[ball.first, ball.second] = 255
        return f
    }

    companion object {
        const val WALL = 140
        const val GOAL = 200
        /** About 12° of tilt. */
        const val THRESHOLD = 2f
    }
}
