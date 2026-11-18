package com.dotforge.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * DotForge's own animation library: every animation is drawn by code here (no artwork from other apps).
 * Create > New design > From the library copies one into the user's designs to play, edit or use in rules.
 */
object AnimationLibrary {
    class Entry(val name: String, val hint: String, val make: (DeviceProfile) -> Animation)

    val entries: List<Entry> = listOf(
        Entry("Heartbeat", "A heart that beats twice", ::heartbeat),
        Entry("Ripple", "Rings spread from the middle", ::ripple),
        Entry("Rain", "Drops fall down the matrix", ::rain),
        Entry("Spiral", "A spinning spiral arm", ::spiral),
        Entry("Radar", "A sweep with a fading trail", ::radar),
        Entry("Loading", "A spinner arc", ::loading),
        Entry("Starfield", "Stars twinkle", ::starfield),
        Entry("Fireworks", "A burst of sparks", ::fireworks),
        Entry("Bounce", "A ball bounces", ::bounce),
        Entry("Wave", "A rolling wave", ::wave),
        Entry("Orbit", "Two moons circle", ::orbit),
        Entry("Fill up", "Fills like a glass, then empties", ::fillUp),
    )

    private fun c(p: DeviceProfile) = (p.size - 1) / 2.0

    private fun frame(p: DeviceProfile, value: (x: Int, y: Int) -> Int): Frame {
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) out[cell] = value(cell % p.size, cell / p.size).coerceIn(0, 255)
        return Frame(p, out)
    }

    private fun anim(p: DeviceProfile, n: Int, ms: Int, draw: (i: Int) -> Frame) = Animation(p, List(n) { AnimFrame(draw(it), ms) })

    private fun dist(p: DeviceProfile, x: Int, y: Int) = hypot(x - c(p), y - c(p))

    /** Brightness of a thin ring of radius [r] at distance [d]. */
    private fun ringAt(d: Double, r: Double, width: Double = 1.0) = (255 * (1 - abs(d - r) / width)).coerceAtLeast(0.0).roundToInt()

    fun heartbeat(p: DeviceProfile): Animation {
        // implicit heart: (x^2 + y^2 - 1)^3 - x^2 y^3 <= 0, scaled to the matrix
        fun heart(scale: Double, level: Int) = frame(p) { x, y ->
            val u = (x - c(p)) / (p.size * 0.32 * scale)
            val v = -(y - c(p) + 1) / (p.size * 0.32 * scale)
            val a = u * u + v * v - 1
            if (a * a * a - u * u * v * v * v <= 0) level else 0
        }
        val beats = listOf(1.0 to 255, 1.15 to 255, 1.0 to 200, 1.12 to 255, 1.0 to 160, 1.0 to 120)
        return Animation(p, beats.mapIndexed { i, (s, l) -> AnimFrame(heart(s, l), if (i == beats.lastIndex) 500 else 110) })
    }

    fun ripple(p: DeviceProfile) = anim(p, 24, 50) { i ->
        frame(p) { x, y ->
            val d = dist(p, x, y)
            (0 until 3).maxOf { k -> ringAt(d, ((i + k * 8) % 24) / 24.0 * p.size * 0.55, 1.4) * (24 - (i + k * 8) % 24) / 24 }
        }
    }

    fun rain(p: DeviceProfile): Animation {
        val rnd = Random(7)
        val drops = List(14) { Triple(rnd.nextInt(p.size), rnd.nextInt(p.size * 2), 1 + rnd.nextInt(2)) }
        val n = p.size * 2
        return anim(p, n, 50) { i ->
            val f = Frame.blank(p)
            for ((x, start, speed) in drops) {
                val head = (start + i * speed) % n - 4
                for (t in 0 until 4) f[x, head - t] = 255 - t * 60
            }
            f
        }
    }

    fun spiral(p: DeviceProfile) = anim(p, 24, 50) { i ->
        frame(p) { x, y ->
            val a = atan2(y - c(p), x - c(p)) + 2 * PI * i / 24
            val d = dist(p, x, y)
            val phase = ((a / (2 * PI) - d / 8) % 1 + 1) % 1
            (255 * (1 - phase * 3).coerceAtLeast(0.0)).roundToInt()
        }
    }

    fun radar(p: DeviceProfile) = anim(p, 36, 40) { i ->
        val sweep = 2 * PI * i / 36
        frame(p) { x, y ->
            val a = (atan2(x - c(p), -(y - c(p))) + 2 * PI) % (2 * PI)
            val behind = ((sweep - a) % (2 * PI) + 2 * PI) % (2 * PI)
            val trail = (255 * (1 - behind / 1.6)).coerceAtLeast(0.0).roundToInt()
            maxOf(trail, if (ringAt(dist(p, x, y), p.size * 0.47) > 0) 40 else 0)
        }
    }

    fun loading(p: DeviceProfile) = anim(p, 30, 33) { i ->
        val head = 2 * PI * i / 30
        frame(p) { x, y ->
            val a = (atan2(x - c(p), -(y - c(p))) + 2 * PI) % (2 * PI)
            val behind = ((head - a) % (2 * PI) + 2 * PI) % (2 * PI)
            if (behind < 2.2 && ringAt(dist(p, x, y), p.size * 0.3, 1.5) > 0) (255 * (1 - behind / 2.6)).roundToInt() else 0
        }
    }

    fun starfield(p: DeviceProfile): Animation {
        val rnd = Random(11)
        val stars = List(30) { p.ledCells[rnd.nextInt(p.ledCount)] to rnd.nextInt(16) }
        return anim(p, 16, 90) { i ->
            val f = Frame.blank(p)
            for ((cell, phase) in stars) f.pixels[cell] = (40 + 215 * (0.5 + 0.5 * sin(2 * PI * (i + phase) / 16))).roundToInt()
            f
        }
    }

    fun fireworks(p: DeviceProfile): Animation {
        val sparks = 12
        return anim(p, 20, 60) { i ->
            val f = Frame.blank(p)
            if (i < 5) {
                f[c(p).toInt(), p.size - 1 - i * 2] = 255 // the rocket
            } else {
                val r = (i - 5) * 0.8
                val level = (255 * (1 - (i - 5) / 15.0)).roundToInt()
                for (k in 0 until sparks) {
                    val a = 2 * PI * k / sparks
                    f[(c(p) + r * cos(a)).roundToInt(), (c(p) - 2 + r * sin(a)).roundToInt()] = level
                }
            }
            f
        }
    }

    fun bounce(p: DeviceProfile) = anim(p, 24, 45) { i ->
        val t = i / 24.0
        val bx = 4 + (p.size - 9) * t
        val by = p.size - 6 - (p.size - 12) * abs(sin(2 * PI * t))
        frame(p) { x, y -> if (hypot(x - bx, y - by) <= 2.2) 255 else 0 }
    }

    fun wave(p: DeviceProfile) = anim(p, 25, 50) { i ->
        frame(p) { x, y ->
            val wy = c(p) + 5 * sin(2 * PI * (x + i) / p.size)
            (255 * (1 - abs(y - wy) / 2)).coerceAtLeast(0.0).roundToInt()
        }
    }

    fun orbit(p: DeviceProfile) = anim(p, 36, 40) { i ->
        val a = 2 * PI * i / 36
        val moons = listOf(c(p) + 8 * cos(a) to c(p) + 8 * sin(a), c(p) + 5 * cos(-2 * a + 1) to c(p) + 5 * sin(-2 * a + 1))
        frame(p) { x, y ->
            val core = if (dist(p, x, y) <= 2.2) 160 else 0
            maxOf(core, if (moons.any { (mx, my) -> hypot(x - mx, y - my) <= 1.3 }) 255 else 0)
        }
    }

    fun fillUp(p: DeviceProfile): Animation {
        val n = p.size
        val levels = (0..n) + (n - 1 downTo 1)
        return Animation(p, levels.map { level ->
            AnimFrame(frame(p) { _, y -> if (y >= n - level) 255 else 0 }, 50)
        })
    }
}
