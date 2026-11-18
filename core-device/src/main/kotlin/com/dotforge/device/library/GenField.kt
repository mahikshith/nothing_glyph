package com.dotforge.device.library

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Field-style effects: every cell is a function of position and loop time. All loops are seamless. */
internal object GenField {
    fun plasma(p: DeviceProfile) = p.frames(48, 50) { _, t ->
        p.field { u, v ->
            val a = sin(u * 5 + TAU * t) + sin(v * 4 - TAU * t) + sin((u + v) * 3.5 + TAU * t) + sin(hypot(u, v) * 6 - 2 * TAU * t)
            0.5 + a / 8
        }
    }

    fun noiseClouds(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        p.field { u, v -> ((fbm(u * 2.2 + 5, v * 2.2 + 5, t, 3) - 0.25) * 1.9).coerceIn(0.0, 1.0).pow(1.3) }
    }

    fun swirl(p: DeviceProfile) = p.frames(48, 45) { _, t ->
        p.field { u, v ->
            val d = hypot(u, v)
            val a = atan2(v, u) + d * 4 + TAU * t
            (0.5 + 0.5 * sin(a * 3)).pow(1.5) * (1 - d * 0.35)
        }
    }

    fun kaleido(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        p.field { u, v ->
            val a = abs(((atan2(v, u) + TAU * t / 6) % (PI / 3)) - PI / 6)
            val d = hypot(u, v)
            val x = cos(a) * d; val y = sin(a) * d
            (0.5 + 0.5 * sin(x * 9 + TAU * t * 2) * cos(y * 9 - TAU * t)).pow(1.4)
        }
    }

    fun rings(p: DeviceProfile) = p.frames(40, 50) { _, t ->
        p.field { u, v -> (0.5 + 0.5 * sin(hypot(u, v) * 14 - TAU * t * 2)).pow(2.0) * (1 - hypot(u, v) * 0.25) }
    }

    fun silk(p: DeviceProfile) = p.frames(60, 55) { _, t ->
        p.field { u, v ->
            val w = sin(u * 3 + TAU * t) * 0.35 + sin(u * 7 - 2 * TAU * t) * 0.1
            (0.5 + 0.5 * sin((v + w) * 11)).pow(3.0)
        }
    }

    fun wash(p: DeviceProfile) = p.frames(48, 50) { _, t ->
        p.field { u, v -> 0.5 + 0.5 * sin((u * cos(TAU * t / 4 * 0) + v * 0.6) * 2.4 - TAU * t) }
    }

    fun metaballs(p: DeviceProfile) = p.frames(60, 45) { _, t ->
        val balls = List(4) { k -> Triple(0.55 * cos(TAU * (t * (1 + k % 2) + k * 0.27)), 0.55 * sin(TAU * (t * (1 + (k + 1) % 2) + k * 0.41)), 0.22 + 0.04 * k) }
        p.field { u, v ->
            val s = balls.sumOf { (bx, by, r) -> r * r / ((u - bx).pow(2) + (v - by).pow(2) + 0.002) }
            smooth((s - 0.9) * 1.2)
        }
    }

    fun tunnel(p: DeviceProfile) = p.frames(36, 40) { _, t ->
        p.field { u, v ->
            val d = max(abs(u), abs(v)).coerceAtLeast(0.05)
            (0.5 + 0.5 * sin(1.5 / d - TAU * t * 2)).pow(2.0) * min(1.0, d * 2.2)
        }
    }

    fun voronoi(p: DeviceProfile): Animation {
        val rnd = Random(21)
        val seeds = List(7) { doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextInt(1, 3).toDouble(), rnd.nextDouble() * TAU) }
        return p.frames(48, 55) { _, t ->
            val pts = seeds.map { s -> (s[0] + 0.25 * sin(TAU * t * s[2] + s[3])) to (s[1] + 0.25 * cos(TAU * t * s[2] + s[3])) }
            p.field { u, v ->
                val ds = pts.map { (x, y) -> hypot(u - x, v - y) }.sorted()
                val edge = (ds[1] - ds[0]).coerceAtLeast(0.0)
                (1 - edge * 7).coerceIn(0.0, 1.0) * 0.9 + (if (ds[0] < 0.08) 0.1 else 0.0)
            }
        }
    }

    fun interference(p: DeviceProfile) = p.frames(48, 50) { _, t ->
        val ax = 0.5 * cos(TAU * t); val ay = 0.5 * sin(TAU * t)
        p.field { u, v ->
            val w = sin(hypot(u - ax, v - ay) * 16 - TAU * t * 2) + sin(hypot(u + ax, v + ay) * 16 - TAU * t * 2)
            (0.5 + w / 4).pow(2.0)
        }
    }

    fun lissajous(p: DeviceProfile) = p.frames(60, 40) { i, _ ->
        val f = Frame.blank(p)
        val r = p.mid() - 3
        for (k in 0..14) {
            val a = TAU * (i - k * 0.6) / 60
            f.dot(p.mid() + r * sin(3 * a + 1.2), p.mid() + r * sin(2 * a), br(1 - k / 15.0), 0.6)
        }
        f
    }

    fun blackHole(p: DeviceProfile): Animation {
        val rnd = Random(5)
        val bits = List(26) { doubleArrayOf(rnd.nextDouble() * TAU, rnd.nextDouble(), rnd.nextDouble(0.5, 1.0)) }
        return p.frames(60, 45) { i, t ->
            val f = Frame.blank(p)
            for (b in bits) {
                val phase = (b[1] + t * b[2] * 1.0) % 1.0
                val rad = (1 - phase).pow(1.5) * (p.mid() - 1)
                val ang = b[0] + phase * phase * 9
                f.dot(p.mid() + rad * cos(ang), p.mid() + rad * sin(ang), br(min(1.0, phase * 4) * 0.9 + 0.1), 0.5)
            }
            f.disc(p.mid(), p.mid(), 1.0, 40)
            f.ring(p.mid(), p.mid(), 2.6, 0.5, br(0.5 + 0.3 * sin(TAU * t * 3)))
            f
        }
    }

    fun galaxy(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        p.field { u, v ->
            val d = hypot(u, v)
            val a = atan2(v, u) - d * 3.4 + TAU * t
            val arms = (0.5 + 0.5 * cos(a * 2)).pow(2.2) * (1 - d).coerceAtLeast(0.0)
            max(arms, (0.9 - d * 3.2).coerceAtLeast(0.0))
        }
    }

    fun lattice(p: DeviceProfile) = p.frames(40, 50) { _, t ->
        p.field { u, v ->
            val x = sin(u * 9 + TAU * t) ; val y = sin(v * 9 - TAU * t)
            (0.5 + 0.5 * x * y).pow(2.0)
        }
    }

    fun caustics(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        p.field { u, v ->
            val a = noiseLoop(u * 3 + 9, v * 3 + 9, t, 1.2, 31)
            val b = noiseLoop(u * 3 + 20, v * 3 + 3, t, 1.2, 37)
            (1 - abs(a - b) * 5).coerceIn(0.0, 1.0).pow(2.0)
        }
    }

    fun opArt(p: DeviceProfile) = p.frames(48, 45) { _, t ->
        p.field { u, v ->
            val s = sin((hypot(u - 0.3 * cos(TAU * t), v - 0.3 * sin(TAU * t)) * 18)) + sin(hypot(u + 0.3 * cos(TAU * t), v) * 18)
            if (s > 0.2) 1.0 else 0.0
        }
    }

    fun disco(p: DeviceProfile): Animation = p.frames(32, 90) { i, _ ->
        p.cells { x, y -> if (hash(x / 4, y / 4, i / 2) > 0.55) 255 else 30 }
    }

    fun pendulum(p: DeviceProfile) = p.frames(60, 40) { i, t ->
        val f = Frame.blank(p)
        val rows = 9
        for (k in 0 until rows) {
            val y = p.mid() - (rows - 1) + 2.0 * k
            val x = p.mid() + (p.mid() - 3) * sin(TAU * t * (6 + k))
            f.dot(x, y, 255, 0.7)
            f.put((p.mid()).toInt(), y.roundToInt(), 40)
        }
        f
    }

    fun spirograph(p: DeviceProfile) = p.frames(60, 45) { i, t ->
        val f = Frame.blank(p)
        val r = p.mid() - 3
        for (k in 0 until 22) {
            val a = TAU * (t - k * 0.012)
            val x = (cos(5 * a) * 0.6 + cos(2 * a) * 0.4) * r
            val y = (sin(5 * a) * 0.6 - sin(2 * a) * 0.4) * r
            f.dot(p.mid() + x, p.mid() + y, br(1 - k / 22.0), 0.5)
        }
        f
    }

    fun comets(p: DeviceProfile) = p.frames(60, 40) { _, t ->
        val f = Frame.blank(p)
        for (c in 0 until 3) for (k in 0 until 10) {
            val a = TAU * (t * (c + 1) * (if (c == 1) -1 else 1) - k * 0.018) + c * 2.1
            val rad = p.mid() - 2 - c * 3.5
            f.dot(p.mid() + rad * cos(a), p.mid() + rad * sin(a), br(1 - k / 10.0), 0.5)
        }
        f
    }

    fun wireframe(p: DeviceProfile) = p.frames(60, 45) { _, t ->
        val f = Frame.blank(p)
        val a = TAU * t; val b = TAU * t * 0.5 + 0.6
        val pts = (0 until 8).map { k ->
            val x = if (k and 1 == 0) -1.0 else 1.0; val y = if (k and 2 == 0) -1.0 else 1.0; val z = if (k and 4 == 0) -1.0 else 1.0
            val x1 = x * cos(a) - z * sin(a); val z1 = x * sin(a) + z * cos(a)
            val y2 = y * cos(b) - z1 * sin(b); val z2 = y * sin(b) + z1 * cos(b)
            val s = (p.mid() - 4.5) / 1.5 / (1 + z2 * 0.12)
            (p.mid() + x1 * s) to (p.mid() + y2 * s)
        }
        for (k in 0 until 8) for (bit in listOf(1, 2, 4)) if (k and bit == 0) {
            val q = k or bit
            f.line(pts[k].first, pts[k].second, pts[q].first, pts[q].second, 220)
        }
        f
    }

    fun glitch(p: DeviceProfile): Animation {
        val base = p.field { u, v -> if (hypot(u, v) < 0.7 && (abs(u) < 0.12 || abs(v) < 0.12 || hypot(u, v) > 0.55)) 1.0 else 0.0 }
        return p.frames(40, 60) { i, _ ->
            val out = Frame.blank(p)
            val tear = hash(i, 1, 4) > 0.45
            for (y in 0 until p.size) {
                val shift = if (tear && hash(y, i, 9) > 0.8) ((hash(y, i, 3) - 0.5) * 10).roundToInt() else 0
                for (x in 0 until p.size) {
                    val sx = x - shift
                    if (sx in 0 until p.size) {
                        val v = base.pixels[p.index(sx, y)]
                        if (v > 0) out.put(x, y, if (hash(x, y, i) > 0.93) 120 else v)
                    }
                }
            }
            out
        }
    }
}
