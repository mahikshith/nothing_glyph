package com.dotforge.device.library

import com.dotforge.device.Animation
import com.dotforge.device.BigDigits
import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotText
import com.dotforge.device.Frame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Scripted game demos, text effects and seasonal icons. */
internal object OrigPlay {
    fun snake(p: DeviceProfile) = p.frames(72, 60) { i, _ ->
        val f = Frame.blank(p)
        val path = ArrayList<Pair<Int, Int>>()
        var x0 = 4; var y0 = 4; var x1 = p.size - 5; var y1 = p.size - 5
        while (x0 < x1 && y0 < y1) {
            for (x in x0..x1) path += x to y0; for (y in y0 + 1..y1) path += x1 to y
            for (x in x1 - 1 downTo x0) path += x to y1; for (y in y1 - 1 downTo y0 + 2) path += x0 to y
            x0 += 3; y0 += 3; x1 -= 3; y1 -= 3
        }
        val head = (i * 2) % path.size
        for (k in 0 until 6 + i / 12) { val (x, y) = path[Math.floorMod(head - k, path.size)]; f.put(x, y, if (k == 0) 255 else 170) }
        val food = path[(head + 9) % path.size]; f.put(food.first, food.second, if (i % 2 == 0) 255 else 100)
        f
    }

    fun pong(p: DeviceProfile) = p.frames(72, 45) { _, t ->
        val f = Frame.blank(p)
        val bx = p.mid() + (p.mid() - 4) * (tri(t * 2) * 2 - 1); val by = p.mid() + 7 * sin(TAU * t * 3)
        f.disc(bx, by, 1.2, 255)
        for (d in -2..2) { f.put(2, (p.mid() + 7 * sin(TAU * t * 3 + 0.4) + d).roundToInt(), 255); f.put(p.size - 3, (by + d).roundToInt(), 255) }
        for (y in 0 until p.size step 3) f.put(p.mid().toInt(), y, 40)
        f
    }

    fun brickBreaker(p: DeviceProfile) = p.frames(72, 50) { i, t ->
        val f = Frame.blank(p)
        val bx = p.mid() + 7 * sin(TAU * t * 2); val by = 20 - 14 * abs(sin(TAU * t * 1.5))
        for (r in 0 until 3) for (c in 0 until 6) { val gone = by < 9 + r * 2 && (c + r * 2 + i / 18) % 4 == 0 && i % 36 > 12; if (!gone) for (x in 0..2) f.put(3 + c * 3 + x, 4 + r * 2, 190 + r * 20) }
        f.disc(bx, by, 0.9, 255)
        for (d in -2..2) f.put((bx + d).roundToInt(), p.size - 4, 255)
        f
    }

    fun invaders(p: DeviceProfile) = p.frames(60, 55) { i, t ->
        val f = Frame.blank(p)
        val sway = (3 * sin(TAU * t * 2)).roundToInt(); val drop = i / 30
        for (r in 0 until 3) for (c in 0 until 5) if (!((i / 15 + r + c) % 7 == 0 && i > 20)) { val x = 4 + c * 3 + sway; val y = 4 + r * 3 + drop
            f.put(x, y, 255); f.put(x + 1, y, 255); f.put(x, y + 1, if (i % 8 < 4) 255 else 120); f.put(x + 1, y + 1, if (i % 8 < 4) 120 else 255) }
        val sx = (p.mid() + 6 * sin(TAU * t * 2)).roundToInt()
        f.put(sx, p.size - 5, 255); f.put(sx - 1, p.size - 4, 255); f.put(sx, p.size - 4, 255); f.put(sx + 1, p.size - 4, 255)
        f.put(sx, p.size - 6 - (i * 3) % 14, 255)
        f
    }

    fun flappy(p: DeviceProfile) = p.frames(72, 45) { i, t ->
        val f = Frame.blank(p)
        for (k in 0 until 3) { val x = ((k * 12 - i * 0.5) % 36 + 36) % 36 - 6; val gap = 8 + (k * 5) % 7
            for (y in 0 until p.size) if (abs(y - gap - 2) > 3) for (w in 0..1) f.put(x.roundToInt() + w, y, 200) }
        f.disc(7.0, p.mid() + 5 * sin(TAU * t * 3), 1.3, 255)
        f
    }

    fun racer(p: DeviceProfile) = p.frames(48, 45) { i, t ->
        val f = Frame.blank(p)
        for (y in 0 until p.size) { if ((y + i) % 4 < 2) { f.put(p.mid().toInt(), y, 110) }; f.put(3, y, 200); f.put(p.size - 4, y, 200) }
        val car = (p.mid() + 4 * sin(TAU * t * 2)).roundToInt()
        f.art(listOf(".#.", "###", "#.#"), car, p.size - 5)
        val ox = if ((i / 24) % 2 == 0) p.mid().toInt() - 4 else p.mid().toInt() + 4
        f.art(listOf("###", "###"), ox, ((i % 24) * 1.2).roundToInt())
        f
    }

    fun blocks(p: DeviceProfile): Animation {
        val shapes = listOf(listOf("####"), listOf("##", "##"), listOf("#..", "###"), listOf(".#.", "###"))
        val heights = IntArray(p.size)
        val rnd = Random(55)
        val out = ArrayList<Frame>()
        val placed = ArrayList<Triple<List<String>, Int, Int>>()
        var n = 0
        while (out.size < 96) {
            val s = shapes[rnd.nextInt(shapes.size)]; val w = s[0].length; val x = rnd.nextInt(5, p.size - 5 - w)
            val top = (x until x + w).minOf { p.size - 3 - heights[it] } - s.size
            for (y in 0..top step 1) {
                if (out.size >= 96) break
                val f = Frame.blank(p)
                for ((ps, px, py) in placed) f.art2(ps, px, py)
                f.art2(s, x, y); out += f
            }
            for (c in x until x + w) heights[c] = p.size - 3 - top
            placed += Triple(s, x, top)
            if (++n > 9) { placed.clear(); heights.fill(0) }
        }
        return Animation(p, out.map { com.dotforge.device.AnimFrame(it, 70) })
    }

    private fun Frame.art2(rows: List<String>, x: Int, y: Int) { for ((ry, r) in rows.withIndex()) for ((rx, ch) in r.withIndex()) if (ch == '#') put(x + rx, y + ry, 255) }

    fun dice(p: DeviceProfile) = p.frames(48, 70) { i, t ->
        val f = Frame.blank(p)
        val face = if (t < 0.7) 1 + (hash(i / 2, 0, 5) * 6).toInt().coerceAtMost(5) else 5
        val jitter = if (t < 0.7) ((hash(i, 1, 2) - 0.5) * 3).roundToInt() else 0
        val c = p.mid().toInt()
        for (x in -6..6) for (y in -6..6) if (abs(x) == 6 || abs(y) == 6) f.put(c + x + jitter, c + y, 255)
        val pips = mapOf(1 to listOf(0 to 0), 2 to listOf(-3 to -3, 3 to 3), 3 to listOf(-3 to -3, 0 to 0, 3 to 3), 4 to listOf(-3 to -3, 3 to -3, -3 to 3, 3 to 3),
            5 to listOf(-3 to -3, 3 to -3, 0 to 0, -3 to 3, 3 to 3), 6 to listOf(-3 to -3, 3 to -3, -3 to 0, 3 to 0, -3 to 3, 3 to 3))
        for ((x, y) in pips.getValue(face)) f.disc((c + x + jitter).toDouble(), (c + y).toDouble(), 0.9, 255)
        f
    }

    fun magic8(p: DeviceProfile) = p.frames(48, 80) { i, t ->
        val f = Frame.blank(p)
        f.ring(p.mid(), p.mid(), 11.0, 0.6, 255)
        val shake = if (t < 0.3) ((i % 2) * 2 - 1) else 0
        val show = t > 0.5
        if (show) f.textCentered("YES", br(((t - 0.5) * 4).coerceAtMost(1.0))) else f.textCentered("8", 255, 2, shake)
        f
    }

    fun coinFlip(p: DeviceProfile) = p.frames(48, 55) { i, t ->
        val f = Frame.blank(p)
        val k = if (t < 0.75) abs(cos(TAU * t * 3)) else 1.0
        val lift = if (t < 0.75) 8 * sin(PI_ * t / 0.75) else 0.0
        val rx = 9 * k.coerceAtLeast(0.12)
        for (y in -9..9) for (x in -9..9) if ((x / rx) * (x / rx) + (y / 9.0) * (y / 9.0) <= 1.0 && p.isActive(p.mid().toInt() + x, p.mid().toInt() + y - lift.roundToInt()))
            if (hypot(x / rx, y / 9.0) > 0.8) f.put(p.mid().toInt() + x, p.mid().toInt() + y - lift.roundToInt(), 255)
        if (t >= 0.75) f.textCentered("H", 255, 2)
        f
    }
    private const val PI_ = Math.PI

    fun marble(p: DeviceProfile) = p.frames(60, 50) { _, t ->
        val f = Frame.blank(p)
        for (x in 3 until p.size - 3) { f.put(x, 5, 130); f.put(x, p.size - 6, 130) }
        for (y in 5..p.size - 6) { f.put(3, y, 130); f.put(p.size - 4, y, 130) }
        for (x in 6..p.size - 7) f.put(x, 10, 130); for (x in 3..p.size - 10) f.put(x, 15, 130)
        val pts = listOf(5.0 to 7.0, 20.0 to 7.0, 20.0 to 12.0, 6.0 to 12.0, 6.0 to 17.0, 20.0 to 18.0)
        val seg = t * (pts.size - 1); val i = seg.toInt().coerceAtMost(pts.size - 2); val l = seg - i
        f.disc(pts[i].first + (pts[i + 1].first - pts[i].first) * l, pts[i].second + (pts[i + 1].second - pts[i].second) * l, 1.1, 255)
        f
    }

    fun reactionTest(p: DeviceProfile) = p.frames(60, 60) { i, t ->
        val f = Frame.blank(p)
        when { t < 0.5 -> f.ring(p.mid(), p.mid(), 10.0, 0.5, 70)
            t < 0.56 -> f.fill(255)
            else -> { f.ring(p.mid(), p.mid(), 11.0, 0.5, 100); f.digits("247", (p.size - BigDigits.width("247", 1)) / 2 - 0, p.size / 2 - 2, 1) } }
        f
    }

    fun pet(p: DeviceProfile) = p.frames(72, 70) { i, t ->
        val f = Frame.blank(p)
        val c = p.mid().toInt()
        val hop = if (t < 0.4) (if ((i / 3) % 2 == 0) 0 else -1) else 0
        f.disc(p.mid(), p.mid() + 3 + hop, 6.0, 255)
        val sleeping = t > 0.75
        val blink = (i % 20) in 0..1
        if (sleeping || blink) { f.line(c - 4.0, c + hop.toDouble(), c - 2.0, c + hop.toDouble(), 0); f.line(c + 2.0, c + hop.toDouble(), c + 4.0, c + hop.toDouble(), 0) }
        else { f.disc(c - 3.0, c + hop.toDouble(), 0.9, 0); f.disc(c + 3.0, c + hop.toDouble(), 0.9, 0) }
        if (t in 0.4..0.7) { f.disc(p.mid(), p.mid() + 5, 1.0 + (i % 4 < 2).compareTo(false), 0); f.put(c + 8, c - 8 + (i % 6), 255) }
        if (sleeping) f.art(Art.ZZZ, c + 8, c - 8 - (i % 8))
        f
    }

    // ---- text ----
    private fun scroll(p: DeviceProfile, text: String, scale: Int = 1, wave: Boolean = false): Animation {
        val len = DotText.scrollLength(p.size, text, scale)
        val frames = ((len + 1) / 2).coerceIn(24, 120)
        return p.frames(frames, 55) { i, _ ->
            val off = i * len / frames
            val f = Frame.blank(p)
            if (!wave) f.text(text, p.size - off, (p.size - DotText.height(scale)) / 2, 255, scale)
            else { var x = p.size - off; for (ch in text) { f.text(ch.toString(), x, (p.mid() - 2 + 4 * sin(x * 0.45 + i * 0.3)).roundToInt(), 255, scale); x += DotText.width(ch.toString(), scale) + 1 } }
            f
        }
    }

    fun marquee(p: DeviceProfile) = scroll(p, "DOTFORGE GLYPH ", 1)
    fun marqueeBig(p: DeviceProfile) = scroll(p, "HELLO", 2)
    fun wavyText(p: DeviceProfile) = scroll(p, "WAVES", 1, true)

    fun typewriter(p: DeviceProfile) = p.frames(60, 90) { i, _ ->
        val f = Frame.blank(p)
        val word = "HELLO"
        val n = (i / 8).coerceAtMost(word.length)
        f.text(word.take(n), (p.size - DotText.width(word)) / 2, p.size / 2 - 2)
        if (i % 6 < 3 && n < word.length) f.put((p.size - DotText.width(word)) / 2 + DotText.width(word.take(n)) + (if (n > 0) 1 else 0), p.size / 2 + 2, 255)
        if (n == word.length && i % 6 < 3) f.put((p.size + DotText.width(word)) / 2 + 1, p.size / 2 + 2, 255)
        f
    }

    fun letterFlip(p: DeviceProfile) = p.frames(48, 80) { i, t ->
        val letters = "HI!"
        val k = (t * 3).toInt(); val l = (t * 3) % 1.0
        val f = Frame.blank(p)
        val s = if (l < 0.3) l / 0.3 else 1.0
        val src = Frame.blank(p); src.text(letters[k].toString(), (p.size - DotText.width(letters[k].toString(), 3)) / 2, p.size / 2 - 7, 255, 3)
        for (y in 0 until p.size) { val sy = (y - 12 * (1 - s)).roundToInt(); if (sy in 0 until p.size) for (x in 0 until p.size) f.put(x, y, src.pixels[p.index(x, sy)]) }
        f
    }

    fun rainIn(p: DeviceProfile) = p.frames(60, 60) { i, t ->
        val f = Frame.blank(p)
        val word = "RAIN"
        var x = (p.size - DotText.width(word)) / 2
        for ((k, ch) in word.withIndex()) { val land = p.size / 2 - 2; val y = (land - 18 + (t * 2.2 - k * 0.2).coerceIn(0.0, 1.0) * 18).roundToInt(); f.text(ch.toString(), x, y); x += DotText.width(ch.toString()) + 1 }
        f
    }

    fun ticker(p: DeviceProfile) = p.frames(48, 60) { i, t ->
        val f = Frame.blank(p)
        val target = "1234"
        val w = BigDigits.width(target, 2)
        for ((k, ch) in target.withIndex()) {
            val settle = (t * 1.6 - k * 0.15).coerceIn(0.0, 1.0)
            val d = if (settle >= 1.0) ch.digitToInt() else ((hash(i, k, 3) * 10).toInt())
            val roll = if (settle >= 1.0) 0 else ((i + k) % 3) - 1
            f.digits("$d", (p.size - w) / 2 + k * 8, p.size / 2 - 5 + roll, 2)
        }
        f
    }

    fun bannerBlink(p: DeviceProfile) = p.frames(24, 120) { i, _ ->
        val f = Frame.blank(p)
        val inv = (i / 6) % 2 == 1
        if (inv) f.fill(255)
        val tmp = Frame.blank(p); tmp.textCentered("SALE", 255, 1)
        for (cell in p.ledCells) if (tmp.pixels[cell] > 0 && inv) f.pixels[cell] = 0 else if (tmp.pixels[cell] > 0) f.pixels[cell] = 255
        f
    }

    fun emojiCaption(p: DeviceProfile) = p.frames(48, 100) { i, t ->
        val f = Frame.blank(p)
        if (t < 0.5) f.art(Art.HEART, p.mid().toInt(), p.mid().toInt(), 2.0) else { f.textCentered("LOVE", 255, 1) }
        f
    }

    // ---- seasonal ----
    fun tree(p: DeviceProfile) = p.frames(36, 90) { i, t ->
        val f = Frame.blank(p)
        f.art(Art.TREE, p.mid().toInt(), p.mid().toInt(), 2.0)
        for ((k, pos) in listOf(-3 to -6, 3 to -1, -5 to 4, 4 to 6, 0 to -10).withIndex()) f.put(p.mid().toInt() + pos.first, p.mid().toInt() + pos.second, if ((i / 3 + k) % 2 == 0) 0 else 255)
        f.put(p.mid().toInt(), 1, if (i % 6 < 3) 255 else 120)
        f
    }

    fun pumpkin(p: DeviceProfile) = p.frames(36, 80) { i, t ->
        val f = Frame.blank(p)
        f.art(Art.PUMPKIN, p.mid().toInt(), p.mid().toInt(), 2.0)
        val glow = 0.6 + 0.4 * sin(TAU * t * 2)
        for (cell in p.ledCells) if (f.pixels[cell] > 0) f.pixels[cell] = br(0.35 * glow + 0.4)
        for (x in listOf(-4, 4)) for (dy in 0..1) f.disc(p.mid() + x, p.mid() - 2.0 + dy, 1.4, 255)
        for (x in -4..4 step 2) f.put(p.mid().toInt() + x, p.mid().toInt() + 5, 255)
        f
    }

    fun ghost(p: DeviceProfile) = p.frames(48, 70) { i, t ->
        val f = Frame.blank(p)
        val bob = (2 * sin(TAU * t)).roundToInt()
        f.art(Art.GHOST, p.mid().toInt(), p.mid().toInt() + bob, 2.0)
        f.disc(p.mid() - 3, p.mid() - 3.0 + bob, 1.1, 0); f.disc(p.mid() + 3, p.mid() - 3.0 + bob, 1.1, 0)
        f
    }

    fun easterEgg(p: DeviceProfile) = p.frames(36, 80) { i, t ->
        val f = Frame.blank(p)
        val tilt = (1.5 * sin(TAU * t)).roundToInt()
        f.art(Art.EGG, p.mid().toInt() + tilt, p.mid().toInt(), 2.0)
        for (y in -4..6 step 4) for (x in -8..8) f.put(p.mid().toInt() + x + tilt, p.mid().toInt() + y, if ((x + y) % 4 == 0) 0 else 255)
        f
    }

    fun newYearSparkle(p: DeviceProfile): Animation {
        val rnd = Random(2027)
        val pts = List(14) { doubleArrayOf(rnd.nextDouble(4.0, 20.0), rnd.nextDouble(4.0, 20.0), rnd.nextDouble()) }
        return p.frames(48, 70) { _, t ->
            val f = Frame.blank(p)
            for ((x, y, o) in pts) { val ph = (t * 2 + o) % 1.0; if (ph < 0.45) { val r = (1 - ph / 0.45); val v = br(r)
                f.put(x.roundToInt(), y.roundToInt(), v); if (r > 0.4) for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) f.put(x.roundToInt() + dx, y.roundToInt() + dy, br(r * 0.6)) } }
            f
        }
    }
}
