package com.dotforge.device.library

import com.dotforge.device.AnimFrame
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

/** Simulation and particle effects: fixed seeds, the same frames on every run. */
internal object GenSim {
    private fun List<Frame>.anim(p: DeviceProfile, ms: Int) = Animation(p, map { AnimFrame(it, ms) })

    fun fire(p: DeviceProfile): Animation {
        val n = p.size
        val rnd = Random(3)
        var heat = Array(n) { DoubleArray(n) }
        val out = ArrayList<Frame>()
        for (step in 0 until 40 + 48) {
            val next = Array(n) { DoubleArray(n) }
            for (y in 0 until n - 1) for (x in 0 until n) {
                val below = heat[y + 1][x] * 2 + heat[y + 1][(x + 1) % n] + heat[y + 1][(x + n - 1) % n] + (if (y + 2 < n) heat[y + 2][x] else 0.0)
                next[y][x] = (below / 5.0 - 0.028).coerceAtLeast(0.0)
            }
            for (x in 0 until n) next[n - 1][x] = if (rnd.nextDouble() < 0.8) 0.7 + rnd.nextDouble() * 0.3 else rnd.nextDouble() * 0.4
            heat = next
            if (step >= 40) out += p.cells { x, y -> br(heat[y][x].pow(0.8) * 1.15) }
        }
        return out.anim(p, 55)
    }

    fun matrixRain(p: DeviceProfile): Animation {
        val n = p.size
        val rnd = Random(8)
        val cols = List(n) { doubleArrayOf(rnd.nextDouble() * 2 * n, 0.5 + rnd.nextInt(3) * 0.5, 4.0 + rnd.nextInt(8)) }
        return p.frames(60, 55) { i, _ ->
            p.cells { x, y ->
                val (start, speed, len) = cols[x]
                val head = (start + i * speed) % (2.0 * n) - 4
                val d = head - y
                if (d < 0 || d > len) 0 else if (d < 1) 255 else (190 * (1 - d / len)).toInt()
            }
        }
    }

    fun life(p: DeviceProfile): Animation {
        val n = p.size
        val rnd = Random(12)
        fun seed() = Array(n) { y -> BooleanArray(n) { x -> p.isActive(x, y) && rnd.nextDouble() < 0.33 } }
        var g = seed()
        var prev: Array<BooleanArray>? = null
        var prev2: Array<BooleanArray>? = null
        val age = Array(n) { IntArray(n) }
        val out = ArrayList<Frame>()
        repeat(96) {
            out += p.cells { x, y -> if (g[y][x]) (150 + min(age[y][x], 5) * 21) else 0 }
            val next = Array(n) { y -> BooleanArray(n) { x ->
                var c = 0
                for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && g[(y + dy + n) % n][(x + dx + n) % n]) c++
                if (g[y][x]) c == 2 || c == 3 else c == 3
            } }
            for (y in 0 until n) for (x in 0 until n) age[y][x] = if (next[y][x] && g[y][x]) age[y][x] + 1 else 0
            val stagnant = next.contentDeepEquals(g) || next.contentDeepEquals(prev) || next.contentDeepEquals(prev2)
            prev2 = prev; prev = g
            g = if (stagnant) seed() else next
            if (stagnant) for (row in age) row.fill(0)
        }
        return out.anim(p, 120)
    }

    fun sand(p: DeviceProfile): Animation {
        val n = p.size
        val grid = Array(n) { y -> BooleanArray(n) { x -> !p.isActive(x, y) } } // true = blocked
        val sand = Array(n) { BooleanArray(n) }
        val rnd = Random(14)
        val out = ArrayList<Frame>()
        repeat(100) { i ->
            val sx = (p.mid() + 3 * sin(i * 0.31)).roundToInt()
            if (!grid[3][sx]) { sand[3][sx] = true; grid[3][sx] = true }
            for (y in n - 2 downTo 0) for (x in 0 until n) if (sand[y][x]) {
                val dir = if (rnd.nextBoolean()) 1 else -1
                val moves = listOf(0 to 1, dir to 1, -dir to 1)
                for ((dx, dy) in moves) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until n && ny in 0 until n && !grid[ny][nx]) {
                        sand[y][x] = false; grid[y][x] = false; sand[ny][nx] = true; grid[ny][nx] = true; break
                    }
                }
            }
            out += p.cells { x, y -> if (sand[y][x]) 210 else 0 }
        }
        return out.anim(p, 60)
    }

    fun boids(p: DeviceProfile): Animation {
        val rnd = Random(2)
        val m = p.mid()
        val px = DoubleArray(14) { m + rnd.nextDouble(-4.0, 4.0) }; val py = DoubleArray(14) { m + rnd.nextDouble(-4.0, 4.0) }
        val vx = DoubleArray(14) { rnd.nextDouble(-1.0, 1.0) }; val vy = DoubleArray(14) { rnd.nextDouble(-1.0, 1.0) }
        val out = ArrayList<Frame>()
        repeat(96) {
            for (b in px.indices) {
                var ax = 0.0; var ay = 0.0; var cx = 0.0; var cy = 0.0; var cnt = 0
                for (o in px.indices) if (o != b) {
                    val dx = px[o] - px[b]; val dy = py[o] - py[b]; val d = hypot(dx, dy)
                    if (d < 9) { ax += vx[o]; ay += vy[o]; cx += dx; cy += dy; cnt++ }
                    if (d < 1.8 && d > 0) { vx[b] -= dx / d * 0.08; vy[b] -= dy / d * 0.08 }
                }
                if (cnt > 0) { vx[b] += (ax / cnt - vx[b]) * 0.08 + cx / cnt * 0.01; vy[b] += (ay / cnt - vy[b]) * 0.08 + cy / cnt * 0.01 }
                val dm = hypot(px[b] - m, py[b] - m)
                if (dm > 9) { vx[b] -= (px[b] - m) / dm * 0.12; vy[b] -= (py[b] - m) / dm * 0.12 }
                val sp = hypot(vx[b], vy[b]).coerceAtLeast(0.01)
                val k = 0.9 / sp
                vx[b] *= k; vy[b] *= k
            }
            for (b in px.indices) { px[b] += vx[b]; py[b] += vy[b] }
            val f = Frame.blank(p)
            for (b in px.indices) { f.dot(px[b], py[b], 255, 0.5); f.dot(px[b] - vx[b], py[b] - vy[b], 110, 0.3) }
            out += f
        }
        return out.anim(p, 70)
    }

    fun ant(p: DeviceProfile): Animation {
        val n = p.size
        val cell = Array(n) { BooleanArray(n) }
        var x = n / 2; var y = n / 2; var dir = 0
        val dx = intArrayOf(0, 1, 0, -1); val dy = intArrayOf(-1, 0, 1, 0)
        val out = ArrayList<Frame>()
        repeat(100) {
            repeat(5) {
                dir = if (cell[y][x]) (dir + 3) % 4 else (dir + 1) % 4
                cell[y][x] = !cell[y][x]
                x = (x + dx[dir] + n) % n; y = (y + dy[dir] + n) % n
            }
            val f = p.cells { cx, cy -> if (cell[cy][cx]) 120 else 0 }
            f.put(x, y, 255)
            out += f
        }
        return out.anim(p, 70)
    }

    fun reaction(p: DeviceProfile): Animation {
        val n = p.size
        var u = Array(n) { DoubleArray(n) { 1.0 } }
        var v = Array(n) { DoubleArray(n) }
        val rnd = Random(30)
        repeat(6) { val cx = rnd.nextInt(3, n - 3); val cy = rnd.nextInt(3, n - 3)
            for (dy in -1..1) for (dx in -1..1) { u[cy + dy][cx + dx] = 0.5; v[cy + dy][cx + dx] = 0.25 } }
        fun step() {
            val nu = Array(n) { DoubleArray(n) }; val nv = Array(n) { DoubleArray(n) }
            for (y in 0 until n) for (x in 0 until n) {
                var lu = -u[y][x]; var lv = -v[y][x]
                for ((ox, oy, w) in LAP) {
                    val xx = (x + ox + n) % n; val yy = (y + oy + n) % n
                    lu += u[yy][xx] * w; lv += v[yy][xx] * w
                }
                val uvv = u[y][x] * v[y][x] * v[y][x]
                nu[y][x] = (u[y][x] + 0.2097 * lu - uvv + 0.037 * (1 - u[y][x])).coerceIn(0.0, 1.0)
                nv[y][x] = (v[y][x] + 0.105 * lv + uvv - (0.06 + 0.037) * v[y][x]).coerceIn(0.0, 1.0)
            }
            u = nu; v = nv
        }
        repeat(300) { step() }
        val out = ArrayList<Frame>()
        repeat(60) { repeat(6) { step() }; out += p.cells { x, y -> br(v[y][x] * 3.2) } }
        return out.anim(p, 70)
    }

    private val LAP = listOf(Triple(-1, 0, 0.2), Triple(1, 0, 0.2), Triple(0, -1, 0.2), Triple(0, 1, 0.2),
        Triple(-1, -1, 0.05), Triple(1, -1, 0.05), Triple(-1, 1, 0.05), Triple(1, 1, 0.05))

    fun flowField(p: DeviceProfile): Animation {
        val rnd = Random(6)
        val m = p.mid()
        val px = DoubleArray(34) { m + rnd.nextDouble(-8.0, 8.0) }; val py = DoubleArray(34) { m + rnd.nextDouble(-8.0, 8.0) }
        val buf = DoubleArray(p.cellCount)
        val out = ArrayList<Frame>()
        repeat(96) { i ->
            for (c in buf.indices) buf[c] *= 0.86
            for (b in px.indices) {
                val a = noise(px[b] * 0.17, py[b] * 0.17 + i * 0.012, 5) * TAU * 2
                px[b] += cos(a) * 0.7; py[b] += sin(a) * 0.7
                if (hypot(px[b] - m, py[b] - m) > p.size / 2.0 - 0.5) { px[b] = m + rnd.nextDouble(-8.0, 8.0); py[b] = m + rnd.nextDouble(-8.0, 8.0) }
                val xi = px[b].roundToInt(); val yi = py[b].roundToInt()
                if (p.isActive(xi, yi)) buf[p.index(xi, yi)] = 1.0
            }
            out += p.cells { x, y -> br(buf[p.index(x, y)]) }
        }
        return out.anim(p, 55)
    }

    fun maze(p: DeviceProfile): Animation {
        val n = p.size
        val cellsN = n / 2 + 1
        val rnd = Random(40)
        val carved = Array(n) { BooleanArray(n) }
        val order = ArrayList<IntArray>()
        val seen = Array(cellsN) { BooleanArray(cellsN) }
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(cellsN / 2, cellsN / 2)); seen[cellsN / 2][cellsN / 2] = true
        order += intArrayOf(cellsN / 2 * 2, cellsN / 2 * 2)
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.last()
            val opts = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).filter { (dx, dy) -> cx + dx in 0 until cellsN && cy + dy in 0 until cellsN && !seen[cy + dy][cx + dx] }
            if (opts.isEmpty()) { stack.removeLast(); continue }
            val (dx, dy) = opts[rnd.nextInt(opts.size)]
            seen[cy + dy][cx + dx] = true
            order += intArrayOf(cx * 2 + dx, cy * 2 + dy); order += intArrayOf((cx + dx) * 2, (cy + dy) * 2)
            stack.add(intArrayOf(cx + dx, cy + dy))
        }
        val per = (order.size + 79) / 80
        val out = ArrayList<Frame>()
        for (i in 0 until 90) {
            val upto = min(order.size, (i + 1) * per)
            for (k in max(0, i * per) until upto) carved[order[k][1]][order[k][0]] = true
            val f = p.cells { x, y -> if (carved[y][x]) 150 else 0 }
            if (upto < order.size) f.put(order[upto - 1][0], order[upto - 1][1], 255)
            out += f
        }
        return out.anim(p, 60)
    }

    fun balls(p: DeviceProfile): Animation {
        val m = p.mid(); val r = p.size / 2.0 - 2.2
        val bx = doubleArrayOf(m - 4, m + 3, m, m + 5); val by = doubleArrayOf(m, m - 4, m + 4, m + 1)
        val vx = doubleArrayOf(0.9, -0.7, 0.5, -0.8); val vy = doubleArrayOf(0.4, 0.8, -0.9, -0.5)
        return p.frames(90, 45) { _, _ ->
            val f = Frame.blank(p)
            for (b in bx.indices) {
                bx[b] += vx[b]; by[b] += vy[b]
                val dx = bx[b] - m; val dy = by[b] - m; val d = hypot(dx, dy)
                if (d > r) {
                    val nx = dx / d; val ny = dy / d; val dot = vx[b] * nx + vy[b] * ny
                    vx[b] -= 2 * dot * nx; vy[b] -= 2 * dot * ny; bx[b] = m + nx * r; by[b] = m + ny * r
                }
                f.disc(bx[b], by[b], 1.4, if (b % 2 == 0) 255 else 170)
            }
            f
        }
    }

    fun helix(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        val f = Frame.blank(p)
        val m = p.mid(); val amp = m - 5
        for (y in 1 until p.size - 1) {
            val a = (y / 4.0) * PI - TAU * t
            val x1 = m + amp * sin(a); val x2 = m - amp * sin(a)
            val z1 = cos(a)
            f.dot(x1, y.toDouble(), if (z1 > 0) 255 else 120, 0.5); f.dot(x2, y.toDouble(), if (z1 > 0) 120 else 255, 0.5)
            if (y % 3 == 0) f.line(x1, y.toDouble(), x2, y.toDouble(), 70)
        }
        f
    }

    fun snow(p: DeviceProfile): Animation {
        val rnd = Random(17)
        val n = p.size
        val flakes = List(30) { doubleArrayOf(rnd.nextDouble() * n, rnd.nextDouble() * (n + 3), 1.0 + rnd.nextInt(2), rnd.nextDouble() * TAU) }
        return p.frames(60, 70) { _, t ->
            val f = Frame.blank(p)
            for ((x, y0, k, ph) in flakes) {
                val y = (y0 + t * (n + 3) * k) % (n + 3) - 1
                f.put((x + 1.2 * sin(TAU * t * k + ph)).roundToInt(), y.roundToInt(), if (k > 1) 255 else 150)
            }
            f
        }
    }

    fun windowRain(p: DeviceProfile): Animation {
        val rnd = Random(19)
        val n = p.size
        val drops = List(9) { doubleArrayOf(rnd.nextInt(1, n - 1).toDouble(), rnd.nextDouble() * n, 1.0 + rnd.nextInt(2)) }
        val still = List(18) { (rnd.nextInt(n)) to (rnd.nextInt(n)) }
        return p.frames(60, 60) { _, t ->
            val f = Frame.blank(p)
            for ((x, y) in still) f.put(x, y, 45)
            for ((x, y0, k) in drops) {
                val y = (y0 + t * n * k) % n
                f.put(x.toInt(), y.roundToInt(), 255); f.put(x.toInt(), y.roundToInt() - 1, 160)
                for (c in 2..4) f.put(x.toInt(), y.roundToInt() - c, (110 - c * 22).coerceAtLeast(0))
            }
            f
        }
    }

    fun lightning(p: DeviceProfile): Animation {
        val rnd = Random(23)
        val bolt = ArrayList<Pair<Int, Int>>()
        var x = p.size / 2
        val branch = ArrayList<Pair<Int, Int>>()
        for (y in 6 until p.size - 2) {
            x = (x + rnd.nextInt(-1, 2)).coerceIn(5, p.size - 6)
            bolt += x to y
            if (y == 12) { var bx = x; for (k in 1..6) { bx += 1; branch += bx to (y + k) } }
        }
        return p.frames(48, 60) { i, _ ->
            val flash = when (i) { in 14..15 -> 1.0; 16 -> 0.7; in 17..20 -> 0.35; 21, 22 -> 0.15; in 30..31 -> 0.5; else -> 0.0 }
            val f = p.field { u, v -> if (v < -0.35 && hypot(u * 0.8, (v + 0.7) * 1.4) < 0.75) 0.35 + flash * 0.3 else flash * 0.12 }
            if (i in 14..19 || i in 30..31) for ((bx, by) in bolt) { f.put(bx, by, if (i in 14..16 || i == 30) 255 else 140) }
            if (i in 14..18) for ((bx, by) in branch) f.put(bx, by, 200)
            f
        }
    }

    fun meteors(p: DeviceProfile): Animation {
        val n = p.size
        val rnd = Random(29)
        val ms = List(5) { doubleArrayOf(rnd.nextDouble() * 2 * n, rnd.nextInt(1, 3).toDouble()) }
        return p.frames(60, 45) { _, t ->
            val f = Frame.blank(p)
            for ((s, k) in ms) {
                val head = (s + t * 2 * n * k) % (2.0 * n)
                val off = (s.toInt() % 9) - 4
                for (c in 0 until 9) f.put((head - c - n / 2).roundToInt(), (head - c - n / 2).roundToInt() + off, br(1 - c / 9.0))
            }
            f
        }
    }

    fun candle(p: DeviceProfile) = p.frames(48, 55) { i, t ->
        val f = Frame.blank(p)
        val m = p.mid()
        val sway = 0.7 * noise(i * 0.35, 3.0, 2) - 0.35 + 0.4 * sin(TAU * t * 3)
        for (y in 4..14) {
            val k = (y - 4) / 10.0
            val w = sin(k * PI).pow(0.8) * (2.4 - k * 0.4)
            for (x in floor(m - 4).toInt()..floor(m + 4).toInt()) {
                val cx = m + sway * (1 - k)
                val d = abs(x - cx) / w.coerceAtLeast(0.2)
                if (d <= 1) f.put(x, y, br(1 - d * 0.5 - (1 - k) * 0.1 * 0))
            }
        }
        for (y in 15..22) for (x in (m - 2).toInt()..(m + 2).toInt()) f.put(x, y, if (x == m.toInt() && y == 15) 255 else 120)
        f
    }

    fun magma(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        p.field { u, v -> smooth((noiseLoop(u * 1.8 + 3, v * 1.8 + 3, t, 1.4, 50) - 0.42) * 5) * (0.6 + 0.4 * (v + 1) / 2) }
    }

    fun cloudDrift(p: DeviceProfile): Animation {
        val clouds = listOf(doubleArrayOf(0.0, -0.25, 1.0), doubleArrayOf(0.55, 0.35, 0.7), doubleArrayOf(1.1, -0.65, 0.5))
        return p.frames(60, 70) { _, t ->
            val f = Frame.blank(p)
            val m = p.mid()
            for ((o, yy, s) in clouds) {
                val cx = ((o + t * 1.0) % 1.6 - 0.8) * 2.0 * (m + 7) / 1.0 * 1.0
                val cy = m + yy * m
                f.disc(m + cx, cy, 3.3 * s, 235); f.disc(m + cx - 3.8 * s, cy + 1.0 * s, 2.4 * s, 200); f.disc(m + cx + 3.8 * s, cy + 1.2 * s, 2.5 * s, 200)
                f.disc(m + cx + 0.5 * s, cy - 2.0 * s, 2.2 * s, 255)
            }
            f
        }
    }

    fun waves(p: DeviceProfile) = p.frames(48, 55) { _, t ->
        p.cells { x, y ->
            val a = p.mid() + 3 * sin(TAU * (x / p.size.toDouble() * 1.2 - t))
            val b = p.mid() + 1 + 2 * sin(TAU * (x / p.size.toDouble() * 2.0 + t) + 1)
            val crest = max(1 - abs(y - a) / 1.4, 1 - abs(y - b) / 1.4).coerceAtLeast(0.0)
            max(br(crest), if (y > a) 35 else 0)
        }
    }

    fun aurora(p: DeviceProfile) = p.frames(60, 60) { _, t ->
        p.field { u, v ->
            val sway = sin(u * 3 + TAU * t) * 0.25 + sin(u * 7 - 2 * TAU * t) * 0.08
            val band = (1 - abs(v + 0.15 + sway) * 2.4).coerceAtLeast(0.0)
            val curtain = 0.55 + 0.45 * sin(u * 11 + TAU * t * 2 + sin(v * 4) * 2)
            band * curtain * (if (v > -0.15 + sway) 0.6 else 1.0)
        }
    }

    fun fireflies(p: DeviceProfile): Animation {
        val rnd = Random(9)
        val ff = List(9) { doubleArrayOf(rnd.nextDouble(-0.7, 0.7), rnd.nextDouble(-0.7, 0.7), rnd.nextInt(1, 3).toDouble(), rnd.nextDouble() * TAU, rnd.nextInt(1, 4).toDouble()) }
        return p.frames(72, 70) { _, t ->
            val f = Frame.blank(p)
            val m = p.mid()
            for ((cx, cy, k, ph, b) in ff) {
                val x = m + (cx + 0.2 * sin(TAU * t * k + ph)) * m; val y = m + (cy + 0.2 * cos(TAU * t * k + ph * 2)) * m
                val glow = (0.5 + 0.5 * sin(TAU * t * b + ph)).pow(3.0)
                f.dot(x, y, br(0.15 + 0.85 * glow), 0.8)
            }
            f
        }
    }

    fun bubbles(p: DeviceProfile): Animation {
        val rnd = Random(33)
        val bs = List(8) { doubleArrayOf(rnd.nextDouble(-0.7, 0.7), rnd.nextDouble(), 1.0 + rnd.nextInt(2), 0.8 + rnd.nextDouble() * 1.8) }
        return p.frames(60, 60) { _, t ->
            val f = Frame.blank(p)
            val m = p.mid()
            for ((x0, y0, k, r) in bs) {
                val rise = (y0 + t * k) % 1.0
                f.ring(m + x0 * m + sin(TAU * t * k * 2 + y0 * 9) * 1.2, m + m * 1.1 - rise * (m * 2.4), r, 0.5, 230)
            }
            f
        }
    }

    fun confetti(p: DeviceProfile): Animation {
        val rnd = Random(37)
        val n = p.size
        val bits = List(36) { doubleArrayOf(rnd.nextDouble() * n, rnd.nextDouble() * (n + 4), 1.0 + rnd.nextInt(2), rnd.nextDouble() * 6, 120.0 + rnd.nextInt(136)) }
        return p.frames(60, 55) { i, t ->
            val f = Frame.blank(p)
            for ((x, y0, k, ph, v) in bits) {
                val y = (y0 + t * (n + 4) * k) % (n + 4) - 2
                if ((i / 2 + ph.toInt()) % 3 != 0) f.put((x + sin(TAU * t * k + ph) * 1.5).roundToInt(), y.roundToInt(), v.toInt())
            }
            f
        }
    }

    fun floaters(p: DeviceProfile): Animation {
        val rnd = Random(41)
        val bl = List(6) { doubleArrayOf(rnd.nextDouble(-0.6, 0.6), rnd.nextDouble(-0.6, 0.6), rnd.nextInt(1, 3).toDouble(), rnd.nextDouble() * TAU, 0.2 + rnd.nextDouble() * 0.15) }
        return p.frames(60, 60) { _, t ->
            p.field { u, v ->
                bl.sumOf { (cx, cy, k, ph, r) ->
                    val x = cx + 0.3 * sin(TAU * t * k + ph); val y = cy + 0.3 * cos(TAU * t * k + ph)
                    (1 - hypot(u - x, v - y) / (r * 2.2)).coerceAtLeast(0.0).pow(1.5)
                }
            }
        }
    }

    fun equalizer(p: DeviceProfile) = p.frames(60, 55) { i, t ->
        val f = Frame.blank(p)
        val bars = 6
        val w = (p.size - 2) / bars
        for (b in 0 until bars) {
            val h = (0.15 + 0.85 * (0.5 * noiseLoop(b * 1.3 + 2, 1.0, t, 1.0, 60) + 0.5 * abs(sin(TAU * (t * (2 + b % 3) + b * 0.17))))) * (p.size - 6)
            val x0 = 1 + b * w + (p.size - 2 - bars * w) / 2
            for (x in x0 until x0 + w - 1) for (y in 0 until h.roundToInt()) f.put(x, p.size - 3 - y, if (y >= h.roundToInt() - 1) 255 else 170)
            val peak = (h + 2).roundToInt()
            for (x in x0 until x0 + w - 1) f.put(x, p.size - 3 - peak, 255)
        }
        f
    }

    fun scope(p: DeviceProfile) = p.frames(48, 45) { _, t ->
        val f = Frame.blank(p)
        var prevY = 0.0
        for (x in 0 until p.size) {
            val u = x / (p.size - 1.0)
            val amp = 0.5 + 0.5 * sin(TAU * (u - t)) * 0.6
            val y = p.mid() + (sin(TAU * (u * 2 - t)) * 0.6 + sin(TAU * (u * 5 + t * 2)) * 0.3) * amp * (p.mid() - 3)
            if (x > 0) f.line(x - 1.0, prevY, x.toDouble(), y, 255) else f.put(x, y.roundToInt(), 255)
            prevY = y
        }
        for (x in 0 until p.size step 2) f.put(x, p.mid().toInt(), 30)
        f
    }

    fun spinner(p: DeviceProfile) = p.frames(36, 40) { i, t ->
        val f = Frame.blank(p)
        val r = p.mid() - 3
        for (d in 0 until 3) for (k in 0 until 6) {
            val a = TAU * (t * (d + 1) - k * 0.03 * (d + 1)) + d * 2.1
            f.dot(p.mid() + r * cos(a) * (1 - 0.0), p.mid() + r * sin(a), br(1 - k / 6.0), 0.9)
        }
        f
    }

    // ---- simple pattern effects (small-grid LED matrix libraries scaled up) ----

    fun checkerboard(p: DeviceProfile) = p.frames(24, 90) { i, _ -> p.cells { x, y -> if ((x / 3 + y / 3 + i / 6) % 2 == 0) 255 else 0 } }

    fun centreExpand(p: DeviceProfile) = p.frames(36, 40) { i, t ->
        val r = t * 2 * (p.size / 2.0)
        p.cells { x, y -> val d = hypot(x - p.mid(), y - p.mid()); if (t < 0.5) (if (d <= r) 255 else 0) else (if (d > r - p.size / 2.0) 255 else 0) }
    }

    fun implode(p: DeviceProfile) = p.frames(30, 45) { _, t ->
        val f = Frame.blank(p)
        for (k in 0 until 3) { val r = (1 - ((t + k / 3.0) % 1.0)) * (p.size / 2.0); f.ring(p.mid(), p.mid(), r, 0.7, br(0.3 + 0.7 * (1 - r / (p.size / 2.0)))) }
        f
    }

    fun fan(p: DeviceProfile) = p.frames(36, 40) { _, t ->
        p.cells { x, y ->
            val a = (p.angleOf(x.toDouble(), y.toDouble()) / TAU - t + 1) % 1.0
            val sector = (a * 3) % 1.0
            if (hypot(x - p.mid(), y - p.mid()) < 1.5 || sector < 0.28) br(1 - sector * 1.2) else 0
        }
    }

    fun waterfall(p: DeviceProfile): Animation {
        val n = p.size
        return p.frames(48, 55) { i, t ->
            p.cells { x, y ->
                val speed = 1 + (x * 7 % 3)
                val head = ((hash(x, 1, 5) + t * speed) % 1.0) * (n + 8)
                val d = head - y
                if (d in 0.0..7.0) br(1 - d / 7.0) else 0
            }
        }
    }

    fun outerRing(p: DeviceProfile) = p.frames(60, 35) { i, _ ->
        val f = Frame.blank(p)
        val ring = p.ring
        for ((k, cell) in ring.withIndex()) {
            val d = ((i * ring.size / 60 - k) % ring.size + ring.size) % ring.size
            f.pixels[cell] = if (d < ring.size / 4) br(1 - d / (ring.size / 4.0)) else 25
        }
        f
    }

    fun crossBlink(p: DeviceProfile) = p.frames(24, 120) { i, _ ->
        val plus = (i / 6) % 2 == 0
        p.cells { x, y -> val dx = x - p.mid(); val dy = y - p.mid()
            val onPlus = abs(dx) < 1.5 || abs(dy) < 1.5; val onX = abs(abs(dx) - abs(dy)) < 1.5
            if ((plus && onPlus) || (!plus && onX)) 255 else 0 }
    }

    fun loadingBar(p: DeviceProfile) = p.frames(48, 50) { i, t ->
        val f = Frame.blank(p)
        val w = p.size - 8
        val fill = if (t < 0.85) t / 0.85 else 1.0
        for (x in 0 until w) for (y in -1..1) f.put(4 + x, p.mid().toInt() + y, if (x < fill * w) 255 else 40)
        if (t >= 0.85 && i % 2 == 0) for (x in 0 until w) for (y in -1..1) f.put(4 + x, p.mid().toInt() + y, 120)
        f
    }

    fun target(p: DeviceProfile) = p.frames(32, 50) { i, t ->
        val f = Frame.blank(p)
        for (k in 1..3) f.ring(p.mid(), p.mid(), k * (p.size / 7.0), 0.6, if (((t * 3).toInt() + 1) == k) 255 else 90)
        f.disc(p.mid(), p.mid(), 1.3, if (i % 8 < 4) 255 else 120)
        f
    }
}
