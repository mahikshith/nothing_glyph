package com.dotforge.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private typealias Shape = (u: Double, v: Double) -> Boolean

/**
 * The built-in glyphs: small pictures drawn by code (no artwork from other apps), picked for alerts, calls,
 * the spam glyph and as a starting point in Create. Shapes are tests on (u, v) in -1..1 across the matrix,
 * v pointing down. A few are short animations.
 */
object GlyphLibrary {
    class Glyph(val id: String, val name: String, val animated: Boolean, private val make: (DeviceProfile) -> Animation) {
        fun animation(p: DeviceProfile): Animation = make(p)

        /** One picture for thumbnails and the editor: the first frame, or the busiest frame of an animation. */
        fun still(p: DeviceProfile): Frame = animation(p).frames.maxBy { f -> f.frame.pixels.count { it > 0 } }.frame
    }

    private fun shape(p: DeviceProfile, s: Shape): Frame {
        val c = (p.size - 1) / 2.0
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) {
            val u = (cell % p.size - c) / c
            val v = (cell / p.size - c) / c
            if (s(u, v)) out[cell] = 255
        }
        return Frame(p, out)
    }

    private fun still(id: String, name: String, s: Shape) = Glyph(id, name, false) { p -> Animation.single(shape(p, s)) }

    // ---- shape helpers ----
    private fun dist(u: Double, v: Double, x: Double, y: Double) = hypot(u - x, v - y)
    private fun disc(u: Double, v: Double, x: Double, y: Double, r: Double) = dist(u, v, x, y) <= r
    private fun ring(u: Double, v: Double, x: Double, y: Double, r: Double, w: Double = 0.1) = abs(dist(u, v, x, y) - r) <= w
    private fun box(u: Double, v: Double, x0: Double, y0: Double, x1: Double, y1: Double) = u in x0..x1 && v in y0..y1
    private fun frameBox(u: Double, v: Double, x0: Double, y0: Double, x1: Double, y1: Double, w: Double = 0.13) =
        box(u, v, x0, y0, x1, y1) && !box(u, v, x0 + w, y0 + w, x1 - w, y1 - w)

    private fun segD(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx - px, ay + t * dy - py)
    }

    private fun seg(u: Double, v: Double, ax: Double, ay: Double, bx: Double, by: Double, w: Double = 0.1) = segD(u, v, ax, ay, bx, by) <= w

    /** A path through [pts] (x, y pairs) drawn with half-width [w]. */
    private fun path(u: Double, v: Double, w: Double, vararg pts: Double): Boolean {
        var i = 0
        while (i + 3 < pts.size) {
            if (seg(u, v, pts[i], pts[i + 1], pts[i + 2], pts[i + 3], w)) return true
            i += 2
        }
        return false
    }

    private fun heartTest(u: Double, v: Double, k: Double = 1.3): Boolean {
        val x = u * k
        val y = -v * k + 0.3
        val a = x * x + y * y - 1
        return a * a * a - x * x * y * y * y <= 0
    }

    private fun chatBubble(u: Double, v: Double): Boolean {
        val body = (u / 0.8).let { it * it } + ((v + 0.12) / 0.58).let { it * it } <= 1
        val tail = v > 0.3 && v < 0.82 && u > -0.58 && u < -0.12 - (v - 0.3) * 0.55
        return body || tail
    }

    private fun cloud(u: Double, v: Double, dy: Double = 0.0) =
        disc(u, v, -0.38, 0.12 + dy, 0.33) || disc(u, v, 0.05, -0.12 + dy, 0.43) || disc(u, v, 0.45, 0.15 + dy, 0.3) ||
            box(u, v, -0.38, 0.12 + dy, 0.45, 0.45 + dy)

    private fun angleDeg(u: Double, v: Double): Double {
        val a = Math.toDegrees(atan2(u, -v)) // clockwise from 12 o'clock
        return if (a < 0) a + 360 else a
    }

    private fun arrow(u: Double, v: Double, dir: Int): Boolean {
        // drawn pointing up, then rotated by quarter turns
        val (x, y) = when (dir) {
            0 -> u to v
            1 -> v to -u
            2 -> -u to -v
            else -> -v to u
        }
        return seg(x, y, 0.0, -0.7, 0.0, 0.75, 0.11) || path(x, y, 0.11, -0.5, -0.2, 0.0, -0.72, 0.5, -0.2)
    }

    private fun digit(d: Char) = Glyph("digit-$d", d.toString(), false) { p ->
        val f = Frame.blank(p)
        val scale = 4
        BigDigits.draw(f, d.toString(), (p.size - BigDigits.width("0", scale)) / 2, (p.size - BigDigits.GLYPH_HEIGHT * scale) / 2, scale)
        Animation.single(f)
    }

    // ---- animated glyphs ----
    private fun frames(p: DeviceProfile, n: Int, ms: Int, draw: (i: Int, u: Double, v: Double) -> Int): Animation {
        val c = (p.size - 1) / 2.0
        return Animation(p, List(n) { i ->
            val out = IntArray(p.cellCount)
            for (cell in p.ledCells) {
                val u = (cell % p.size - c) / c
                val v = (cell / p.size - c) / c
                out[cell] = draw(i, u, v).coerceIn(0, 255)
            }
            AnimFrame(Frame(p, out), ms)
        })
    }

    private fun pulseAnim(p: DeviceProfile) = frames(p, 16, 70) { i, u, v ->
        // a disc that swells and fades, like a heartbeat seen from above
        val t = i / 16.0
        val r = 0.25 + 0.6 * t
        val level = 255 * (1 - t * 0.8)
        if (hypot(u, v) <= r && hypot(u, v) >= r - 0.22) level.roundToInt() else 0
    }

    private fun rippleAnim(p: DeviceProfile) = frames(p, 20, 60) { i, u, v ->
        val d = hypot(u, v)
        var out = 0.0
        for (k in 0 until 2) {
            val r = ((i / 20.0) + k / 2.0) % 1.0
            out = max(out, (1 - abs(d - r) / 0.1).coerceAtLeast(0.0) * (1 - r * 0.7))
        }
        (out * 255).roundToInt()
    }

    private fun spinnerAnim(p: DeviceProfile) = frames(p, 24, 40) { i, u, v ->
        val d = hypot(u, v)
        if (d < 0.55 || d > 0.85) return@frames 0
        val head = i * 15.0
        val behind = ((head - angleDeg(u, v)) % 360 + 360) % 360
        if (behind <= 120) (255 * (1 - behind / 140)).roundToInt() else 0
    }

    private fun blinkAnim(p: DeviceProfile): Animation {
        val on = shape(p) { u, v -> disc(u, v, 0.0, 0.0, 0.42) }
        val dim = on.scaled(0.25f)
        return Animation(p, listOf(AnimFrame(on, 400), AnimFrame(dim, 120), AnimFrame(Frame.blank(p), 300), AnimFrame(dim, 120)))
    }

    private fun waveAnim(p: DeviceProfile) = frames(p, 20, 50) { i, u, v ->
        val y = 0.35 * sin((u * 2.2 + i / 20.0 * 2) * PI)
        if (abs(v - y) <= 0.1) 255 else if (abs(v - y) <= 0.2) 70 else 0
    }

    val all: List<Glyph> = listOf(
        still("chat", "Chat") { u, v -> chatBubble(u, v) && listOf(-0.38, 0.0, 0.38).none { dist(u, v, it, -0.12) < 0.13 } },
        still("heart", "Heart") { u, v -> heartTest(u, v) },
        still("mail", "Mail") { u, v ->
            val inR = abs(u) <= 0.74 && abs(v) <= 0.5
            val edge = inR && (abs(u) > 0.58 || abs(v) > 0.34)
            val t = -0.5 + 0.58 * (1 - abs(u) / 0.74)
            edge || (inR && abs(v - t) < 0.11)
        },
        still("star", "Star") { u, v ->
            val r = hypot(u, v)
            val th = atan2(v, u)
            r <= 0.34 + 0.52 * abs(cos(2.5 * (th + PI / 2))).let { it * it * it }
        },
        still("bell", "Bell") { u, v ->
            (v >= -0.6 && v <= 0.42 && abs(u) <= 0.2 + (v + 0.6) * 0.5) || disc(u, v, 0.0, 0.6, 0.17) || (abs(u) < 0.09 && v < -0.55 && v > -0.8)
        },
        still("ring", "Ring") { u, v -> hypot(u, v) in 0.52..0.8 },
        still("rings", "Rings") { u, v -> hypot(u, v) < 0.98 && floor(hypot(u, v) * 5).toInt() % 2 == 0 },
        still("check", "Check") { u, v -> min(segD(u, v, -0.55, 0.0, -0.15, 0.4), segD(u, v, -0.15, 0.4, 0.6, -0.42)) < 0.12 },
        still("phone", "Phone") { u, v -> abs(u) <= 0.42 && abs(v) <= 0.75 && !(abs(u) <= 0.28 && v > -0.58 && v < 0.48) },
        still("spam", "Spam") { u, v ->
            if (v < -0.74 || v > 0.84) return@still false
            val w = if (v < 0.12) 0.64 else 0.64 * (1 - (v - 0.12) / 0.72)
            val x = hypot(u, v) < 0.36 && abs(abs(u) - abs(v)) < 0.1
            abs(u) <= w && !x
        },
        still("calendar", "Calendar") { u, v ->
            frameBox(u, v, -0.68, -0.55, 0.68, 0.68) || box(u, v, -0.68, -0.55, 0.68, -0.28) ||
                seg(u, v, -0.35, -0.8, -0.35, -0.5, 0.08) || seg(u, v, 0.35, -0.8, 0.35, -0.5, 0.08) ||
                listOf(-0.3, 0.0, 0.3).any { x -> listOf(0.0, 0.33).any { y -> disc(u, v, x, y, 0.09) } }
        },
        still("alarm", "Alarm") { u, v ->
            ring(u, v, 0.0, 0.1, 0.58, 0.1) || seg(u, v, 0.0, 0.1, 0.0, -0.25, 0.08) || seg(u, v, 0.0, 0.1, 0.25, 0.25, 0.08) ||
                disc(u, v, -0.58, -0.55, 0.2) || disc(u, v, 0.58, -0.55, 0.2) ||
                seg(u, v, -0.35, 0.6, -0.5, 0.8, 0.08) || seg(u, v, 0.35, 0.6, 0.5, 0.8, 0.08)
        },
        still("music", "Music") { u, v ->
            disc(u, v, -0.4, 0.48, 0.22) || disc(u, v, 0.4, 0.32, 0.22) ||
                seg(u, v, -0.22, 0.48, -0.22, -0.55, 0.08) || seg(u, v, 0.58, 0.32, 0.58, -0.72, 0.08) ||
                seg(u, v, -0.22, -0.55, 0.58, -0.72, 0.12)
        },
        still("camera", "Camera") { u, v ->
            (frameBox(u, v, -0.78, -0.35, 0.78, 0.6) || box(u, v, -0.35, -0.58, 0.15, -0.35) || ring(u, v, 0.0, 0.12, 0.28, 0.09)) &&
                !disc(u, v, 0.0, 0.12, 0.12)
        },
        still("cart", "Cart") { u, v ->
            path(u, v, 0.09, -0.88, -0.62, -0.58, -0.38, -0.42, 0.22, 0.48, 0.22, 0.62, -0.38, -0.58, -0.38) ||
                seg(u, v, -0.5, -0.08, 0.55, -0.08, 0.07) || disc(u, v, -0.3, 0.52, 0.15) || disc(u, v, 0.38, 0.52, 0.15)
        },
        still("rupee", "Rupee") { u, v ->
            seg(u, v, -0.45, -0.62, 0.45, -0.62, 0.09) || seg(u, v, -0.45, -0.3, 0.45, -0.3, 0.09) ||
                (ring(u, v, -0.12, -0.3, 0.32, 0.09) && u > -0.12) || seg(u, v, -0.45, 0.02, -0.12, 0.02, 0.09) ||
                seg(u, v, -0.35, 0.05, 0.42, 0.78, 0.1)
        },
        still("bank", "Bank") { u, v ->
            (v in -0.78..-0.38 && abs(u) <= (v + 0.78) * 2.0) ||
                listOf(-0.55, -0.18, 0.18, 0.55).any { x -> seg(u, v, x, -0.25, x, 0.42, 0.09) } || box(u, v, -0.78, 0.5, 0.78, 0.68)
        },
        still("car", "Car") { u, v ->
            val body = box(u, v, -0.82, -0.05, 0.82, 0.38) || (v in -0.45..-0.05 && abs(u) <= 0.42 + (v + 0.45) * 0.6)
            val window = v in -0.33..-0.12 && abs(u) <= 0.3 + (v + 0.33) * 0.6 && abs(u) > 0.06
            (body && !window && !disc(u, v, -0.45, 0.42, 0.24) && !disc(u, v, 0.45, 0.42, 0.24)) ||
                disc(u, v, -0.45, 0.45, 0.16) || disc(u, v, 0.45, 0.45, 0.16)
        },
        still("plane", "Plane") { u, v ->
            seg(u, v, 0.0, -0.85, 0.0, 0.75, 0.11) || path(u, v, 0.09, -0.82, 0.18, 0.0, -0.22, 0.82, 0.18) ||
                path(u, v, 0.08, -0.35, 0.8, 0.0, 0.6, 0.35, 0.8)
        },
        still("home", "Home") { u, v ->
            path(u, v, 0.1, -0.85, 0.02, 0.0, -0.75, 0.85, 0.02) ||
                (frameBox(u, v, -0.58, -0.12, 0.58, 0.72, 0.12) && v > -0.12) || box(u, v, -0.16, 0.3, 0.16, 0.72)
        },
        still("warning", "Warning") { u, v ->
            path(u, v, 0.1, 0.0, -0.8, 0.85, 0.65, -0.85, 0.65, 0.0, -0.8) || seg(u, v, 0.0, -0.32, 0.0, 0.14, 0.09) || disc(u, v, 0.0, 0.38, 0.1)
        },
        still("lock", "Lock") { u, v ->
            (ring(u, v, 0.0, -0.25, 0.36, 0.1) && v <= -0.25) || seg(u, v, -0.36, -0.25, -0.36, 0.0, 0.1) || seg(u, v, 0.36, -0.25, 0.36, 0.0, 0.1) ||
                (box(u, v, -0.6, -0.02, 0.6, 0.72) && !disc(u, v, 0.0, 0.28, 0.11) && !box(u, v, -0.05, 0.28, 0.05, 0.5))
        },
        still("battery", "Battery") { u, v ->
            frameBox(u, v, -0.78, -0.38, 0.6, 0.38, 0.12) || box(u, v, 0.6, -0.16, 0.8, 0.16) || box(u, v, -0.58, -0.18, 0.1, 0.18)
        },
        still("wifi", "Wi-Fi") { u, v ->
            val d = dist(u, v, 0.0, 0.55)
            val inCone = abs(u) <= (0.55 - v) * 1.05
            disc(u, v, 0.0, 0.55, 0.14) || (inCone && listOf(0.45, 0.8, 1.15).any { abs(d - it) <= 0.1 })
        },
        still("download", "Download") { u, v ->
            seg(u, v, 0.0, -0.8, 0.0, 0.3, 0.1) || path(u, v, 0.1, -0.42, -0.1, 0.0, 0.32, 0.42, -0.1) ||
                path(u, v, 0.09, -0.7, 0.4, -0.7, 0.68, 0.7, 0.68, 0.7, 0.4)
        },
        still("game", "Game") { u, v ->
            val body = box(u, v, -0.6, -0.32, 0.6, 0.4) || disc(u, v, -0.6, 0.04, 0.36) || disc(u, v, 0.6, 0.04, 0.36)
            val pad = (abs(u + 0.5) <= 0.06 && abs(v) <= 0.2) || (abs(v) <= 0.06 && abs(u + 0.5) <= 0.2)
            body && !pad && !disc(u, v, 0.42, -0.08, 0.08) && !disc(u, v, 0.62, 0.12, 0.08)
        },
        still("smile", "Smile") { u, v ->
            ring(u, v, 0.0, 0.0, 0.82, 0.1) || disc(u, v, -0.3, -0.25, 0.12) || disc(u, v, 0.3, -0.25, 0.12) ||
                (ring(u, v, 0.0, 0.0, 0.45, 0.09) && v > 0.15)
        },
        still("sad", "Sad") { u, v ->
            ring(u, v, 0.0, 0.0, 0.82, 0.1) || disc(u, v, -0.3, -0.25, 0.12) || disc(u, v, 0.3, -0.25, 0.12) ||
                (ring(u, v, 0.0, 0.75, 0.42, 0.09) && v < 0.48)
        },
        still("fire", "Fire") { u, v ->
            val outer = disc(u, v, 0.0, 0.28, 0.5) || (v in -0.85..0.28 && abs(u) <= 0.5 * (v + 0.85) / 1.13)
            val inner = disc(u, v, 0.0, 0.42, 0.22) || (v in -0.15..0.42 && abs(u) <= 0.22 * (v + 0.15) / 0.57)
            outer && !inner
        },
        still("moon", "Moon") { u, v -> disc(u, v, 0.0, 0.0, 0.72) && !disc(u, v, 0.36, -0.22, 0.6) },
        still("sun", "Sun") { u, v ->
            val r = hypot(u, v)
            r <= 0.36 || (r in 0.56..0.9 && (angleDeg(u, v) + 11.25) % 45 < 14)
        },
        still("cloud", "Cloud") { u, v -> cloud(u, v) },
        still("rain", "Rain") { u, v ->
            cloud(u, v, -0.3) || listOf(-0.45, 0.0, 0.45).any { x -> seg(u, v, x, 0.4, x - 0.12, 0.72, 0.07) }
        },
        still("coffee", "Coffee") { u, v ->
            (frameBox(u, v, -0.6, -0.1, 0.35, 0.62, 0.12) || ring(u, v, 0.45, 0.22, 0.2, 0.08) || box(u, v, -0.75, 0.7, 0.55, 0.8)) ||
                listOf(-0.3, 0.05).any { x -> abs(u - x - 0.1 * sin((v + 0.8) * 6)) <= 0.07 && v in -0.75..-0.25 }
        },
        still("gift", "Gift") { u, v ->
            frameBox(u, v, -0.62, -0.15, 0.62, 0.72, 0.12) || box(u, v, -0.75, -0.42, 0.75, -0.15) || abs(u) <= 0.08 && v in -0.42..0.72 ||
                ring(u, v, -0.24, -0.6, 0.17, 0.07) || ring(u, v, 0.24, -0.6, 0.17, 0.07)
        },
        still("people", "People") { u, v ->
            disc(u, v, -0.36, -0.32, 0.22) || disc(u, v, 0.36, -0.32, 0.22) ||
                (disc(u, v, -0.36, 0.55, 0.42) && v <= 0.55) || (disc(u, v, 0.36, 0.55, 0.42) && v <= 0.55)
        },
        still("chat-dots", "Typing") { u, v ->
            (chatBubble(u, v) && !(chatBubble(u * 1.22, (v + 0.12) * 1.3 - 0.12) && v < 0.3)) ||
                listOf(-0.36, 0.0, 0.36).any { dist(u, v, it, -0.12) < 0.12 }
        },
        still("at", "At") { u, v ->
            (ring(u, v, 0.0, 0.0, 0.72, 0.1) && !(u > 0.2 && v > 0.05 && v < 0.6)) || ring(u, v, 0.0, 0.0, 0.26, 0.1) ||
                seg(u, v, 0.28, -0.3, 0.28, 0.3, 0.09) || seg(u, v, 0.28, 0.3, 0.6, 0.3, 0.09)
        },
        still("hash", "Hash") { u, v ->
            seg(u, v, -0.18, -0.75, -0.32, 0.75, 0.09) || seg(u, v, 0.32, -0.75, 0.18, 0.75, 0.09) ||
                seg(u, v, -0.68, -0.28, 0.68, -0.28, 0.09) || seg(u, v, -0.68, 0.28, 0.68, 0.28, 0.09)
        },
        still("cross", "Cross") { u, v -> seg(u, v, -0.55, -0.55, 0.55, 0.55, 0.12) || seg(u, v, -0.55, 0.55, 0.55, -0.55, 0.12) },
        still("plus", "Plus") { u, v -> seg(u, v, -0.6, 0.0, 0.6, 0.0, 0.13) || seg(u, v, 0.0, -0.6, 0.0, 0.6, 0.13) },
        still("question", "Question") { u, v ->
            (ring(u, v, 0.0, -0.32, 0.36, 0.1) && !(u < 0 && v > -0.32)) || seg(u, v, 0.0, 0.04, 0.0, 0.25, 0.1) || disc(u, v, 0.0, 0.55, 0.11)
        },
        still("info", "Info") { u, v -> ring(u, v, 0.0, 0.0, 0.82, 0.09) || disc(u, v, 0.0, -0.4, 0.11) || seg(u, v, 0.0, -0.12, 0.0, 0.48, 0.1) },
        still("pin", "Place") { u, v ->
            val top = disc(u, v, 0.0, -0.25, 0.5)
            val point = v in -0.25..0.85 && abs(u) <= 0.5 * (0.85 - v) / 1.1
            (top || point) && !disc(u, v, 0.0, -0.25, 0.2)
        },
        still("person", "Person") { u, v -> disc(u, v, 0.0, -0.38, 0.3) || (disc(u, v, 0.0, 0.7, 0.62) && v <= 0.7 && v >= 0.08) },
        still("clock", "Clock") { u, v ->
            ring(u, v, 0.0, 0.0, 0.8, 0.1) || seg(u, v, 0.0, 0.0, 0.0, -0.5, 0.09) || seg(u, v, 0.0, 0.0, 0.35, 0.2, 0.09)
        },
        still("dot", "Dot") { u, v -> hypot(u, v) <= 0.35 },
        still("arrow-up", "Up") { u, v -> arrow(u, v, 0) },
        still("arrow-right", "Right") { u, v -> arrow(u, v, 1) },
        still("arrow-down", "Down") { u, v -> arrow(u, v, 2) },
        still("arrow-left", "Left") { u, v -> arrow(u, v, 3) },
    ) + ('0'..'9').map(::digit) + listOf(
        Glyph("pulse", "Pulse", true, ::pulseAnim),
        Glyph("ripple", "Ripple", true, ::rippleAnim),
        Glyph("spinner", "Spinner", true, ::spinnerAnim),
        Glyph("blink", "Blink", true, ::blinkAnim),
        Glyph("wave", "Wave", true, ::waveAnim),
    )

    private val byId = all.associateBy { it.id }

    /** A built-in glyph, or any library item by its id (so alerts can show library glyphs too). */
    fun get(id: String?): Glyph? = id?.let { byId[it] ?: fromLibrary(it) }

    private val fromLibraryCache = java.util.concurrent.ConcurrentHashMap<String, Glyph>()

    private fun fromLibrary(id: String): Glyph? = fromLibraryCache[id] ?: com.dotforge.device.library.AnimationLibrary.item(id)?.let { item ->
        Glyph(item.id, item.name, item.animated) { p -> item.load(p) }.also { fromLibraryCache[id] = it }
    }

    /**
     * How a glyph shows as an alert: a still picture fades in, holds and dims a little (it loops while an alert
     * stays on); an animated glyph plays as it is.
     */
    fun alert(p: DeviceProfile, id: String?): Animation {
        val g = get(id) ?: get("dot")!!
        if (g.animated) return g.animation(p)
        val f = g.still(p)
        val steps = listOf(0.3f to 60, 0.65f to 60, 1f to 1400, 0.6f to 120, 0.35f to 160)
        return Animation(p, steps.map { (k, ms) -> AnimFrame(f.scaled(k), ms) })
    }

    /** Lit cells of a still glyph as a quick check (tests and thumbnails). */
    fun litCount(p: DeviceProfile, id: String): Int = get(id)?.still(p)?.pixels?.count { it > 0 } ?: 0
}
