package com.dotforge.device

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** How the words move or appear. [moves] styles use the direction; the others play in place. */
@Serializable
enum class TextStyle(val label: String, val hint: String, val moves: Boolean) {
    SCROLL("Scroll", "Slides across", true),
    TYPEWRITER("Typewriter", "Types one letter at a time", false),
    LETTERS("Letter by letter", "One big letter at a time", false),
    WORDS("Word by word", "One word at a time", true),
    FLASH("Flash", "Blinks on and off", false),
    FADE("Fade", "Fades in and out", false),
    CURSOR("Cursor", "Types, then a blinking cursor", false),
    BOUNCE("Bounce", "Drops in and bounces", false),
    WAVE("Wave", "Scrolls on a wave", true),
    RAIN("Rain", "Dots fall into place", false),
    ASSEMBLE("Assemble", "Dots fly together", false),
    GLITCH("Glitch", "Shakes and flickers", false),
    ZOOM("Zoom", "Grows from the middle", false),
    COUNTDOWN("Countdown", "Counts down, then shows it", false),
    MARQUEE("Marquee border", "Lights run round the edge", true),
    SPOTLIGHT("Spotlight", "A bright spot sweeps over", true),
    FIT("Fit", "Still, as big as fits", false),
}

/** Which way scrolling text goes. */
@Serializable
enum class TextDirection(val label: String) {
    LEFT("Left"), RIGHT("Right"), UP("Up"), DOWN("Down"), BOUNCE("Back and forth"), RING("Around the ring");
}

/** Where text sits: the whole Glyph or a band of rows (with a background behind it). */
@Serializable
enum class TextBand(val label: String, val rows: IntRange) {
    FULL("Full", 0..24), TOP("Top", 2..8), MIDDLE("Middle", 9..15), BOTTOM("Bottom", 16..22);

    val centre: Int get() = (rows.first + rows.last + 1) / 2
}

/**
 * A text design, saved as text so it stays editable: the words, font, style, direction and look. How long,
 * how fast, how often and how bright are the design's [Timing].
 */
@Serializable
data class TextSpec(
    val text: String = "HELLO",
    val font: DotFont = DotFont.REGULAR,
    val style: TextStyle = TextStyle.SCROLL,
    val direction: TextDirection = TextDirection.LEFT,
    /** A dim ring of dots round each letter, so it stands out on a background. */
    val outline: Boolean = false,
    /** Lit background, dark letters. */
    val invert: Boolean = false,
    /** The outer ring lit as a frame. */
    val border: Boolean = false,
    /** Dots between letters, 0-3. */
    val spacing: Int = 1,
    /** Pause when the text is fully in view (and at each end of back and forth), 0-3000 ms. */
    val pauseMs: Int = 600,
    val band: TextBand = TextBand.FULL,
) {
    fun clamped() = copy(text = text.take(MAX_CHARS), spacing = spacing.coerceIn(0, 3), pauseMs = pauseMs.coerceIn(0, 3000))

    companion object {
        const val MAX_CHARS = 60
    }
}

/**
 * Text to frames. Durations are at Normal speed; the design's [Timing] scales them. Everything is drawn on the
 * full grid and clipped to the round mask; a [TextBand] centres the letters on its rows.
 */
object TextRender {
    /** One dot of scroll at Normal speed. */
    const val STEP_MS = 70
    private const val DIM_OUTLINE = 70

    private class Builder(val p: DeviceProfile) {
        val frames = ArrayList<AnimFrame>()
        fun add(px: IntArray, ms: Int) {
            frames += AnimFrame(Frame.of(p, px), ms.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS))
        }

        fun build() = Animation(p, frames.ifEmpty { listOf(AnimFrame(Frame.blank(p), 1000)) })
    }

    fun render(p: DeviceProfile, spec: TextSpec): Animation {
        val s = spec.clamped()
        val text = s.text.ifBlank { " " }
        val b = Builder(p)
        when (s.style) {
            TextStyle.SCROLL -> scroll(b, s, text)
            TextStyle.WAVE -> scroll(b, s, text, wave = true)
            TextStyle.SPOTLIGHT -> scroll(b, s, text, spotlight = true)
            TextStyle.MARQUEE -> marquee(b, s, text)
            TextStyle.TYPEWRITER -> type(b, s, text, cursor = false)
            TextStyle.CURSOR -> type(b, s, text, cursor = true)
            TextStyle.LETTERS -> letters(b, s, text)
            TextStyle.WORDS -> words(b, s, text)
            TextStyle.FLASH -> chunks(b, s, text) { px ->
                repeat(3) { b.add(px, 300); b.add(IntArray(p.cellCount), 180) }
            }
            TextStyle.FADE -> chunks(b, s, text) { px ->
                for (i in 1..5) b.add(scale(px, i / 5f), 70)
                b.add(px, 700 + s.pauseMs)
                for (i in 4 downTo 0) b.add(scale(px, i / 5f), 70)
            }
            TextStyle.BOUNCE -> bounce(b, s, text)
            TextStyle.RAIN -> particles(b, s, text, rain = true)
            TextStyle.ASSEMBLE -> particles(b, s, text, rain = false)
            TextStyle.GLITCH -> glitch(b, s, text)
            TextStyle.ZOOM -> zoom(b, s, text)
            TextStyle.COUNTDOWN -> countdown(b, s, text)
            TextStyle.FIT -> b.add(fitFrame(p, s, text), 1000)
        }
        return b.build()
    }

    /** A still picture for previews: the text centred (or its start, when it doesn't fit). */
    fun poster(p: DeviceProfile, spec: TextSpec): Frame {
        val s = spec.clamped()
        if (s.style == TextStyle.FIT) return Frame.of(p, fitFrame(p, s, s.text.ifBlank { " " }))
        val strip = DotFonts.strip(s.font, s.text.ifBlank { " " }, s.spacing)
        val x = if (strip.w <= p.size) (p.size - strip.w) / 2 else 1
        return Frame.of(p, place(p, s, strip, x, top(s, strip.h)))
    }

    // ---- placing

    private fun top(s: TextSpec, h: Int) = s.band.centre - h / 2

    /** [strip] at ([x], [y]) on a blank frame with the look applied. */
    private fun place(p: DeviceProfile, s: TextSpec, strip: Strip, x: Int, y: Int, value: Int = 255, yOffset: (Int) -> Int = { 0 }): IntArray {
        val px = IntArray(p.cellCount)
        for (sy in 0 until strip.h) for (sx in 0 until strip.w) {
            if (!strip.lit(sx, sy)) continue
            val gx = x + sx
            val gy = y + sy + yOffset(gx)
            if (gx in 0 until p.size && gy in 0 until p.size) px[gy * p.size + gx] = value
        }
        return look(p, s, px)
    }

    /** Outline, invert and border. */
    private fun look(p: DeviceProfile, s: TextSpec, px: IntArray): IntArray {
        var out = px
        if (s.outline) {
            out = px.copyOf()
            val n = p.size
            for (y in 0 until n) for (x in 0 until n) {
                if (px[y * n + x] > 0) continue
                var near = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until n && yy in 0 until n && px[yy * n + xx] > 0) near = true
                }
                if (near) out[y * n + x] = DIM_OUTLINE
            }
        }
        if (s.invert) out = IntArray(p.cellCount) { if (!p.mask[it]) 0 else if (out[it] >= 255) 0 else 255 - out[it] }
        if (s.border) {
            out = out.copyOf()
            for (c in p.ring) out[c] = 255
        }
        return out
    }

    private fun scale(px: IntArray, k: Float) = IntArray(px.size) { (px[it] * k).roundToInt() }

    // ---- chunks: words that fit the width, long words split into letters that fit

    internal fun chunks(font: DotFont, text: String, width: Int, spacing: Int): List<String> {
        val out = ArrayList<String>()
        for (word in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (DotFonts.width(font, word, spacing) <= width) {
                // join short words while the line still fits
                val last = out.lastOrNull()
                if (last != null && DotFonts.width(font, "$last $word", spacing) <= width) out[out.lastIndex] = "$last $word" else out += word
                continue
            }
            var cur = ""
            for (ch in word) {
                if (cur.isNotEmpty() && DotFonts.width(font, cur + ch, spacing) > width) {
                    out += cur
                    cur = ""
                }
                cur += ch
            }
            if (cur.isNotEmpty()) out += cur
        }
        return out.ifEmpty { listOf(" ") }
    }

    /** The width the letters can use at the band's rows (the circle is narrower away from the middle). */
    private fun usable(p: DeviceProfile, s: TextSpec, h: Int): Int {
        val y0 = top(s, h)
        var w = p.size
        for (y in y0 until y0 + h) {
            if (y !in 0 until p.size) continue
            w = minOf(w, (0 until p.size).count { p.isActive(it, y) })
        }
        return (w - 2).coerceAtLeast(5)
    }

    private inline fun chunks(b: Builder, s: TextSpec, text: String, each: (IntArray) -> Unit) {
        val h = DotFonts.height(s.font)
        for (c in chunks(s.font, text, usable(b.p, s, h), s.spacing)) {
            val strip = DotFonts.strip(s.font, c, s.spacing)
            each(place(b.p, s, strip, (b.p.size - strip.w) / 2, top(s, h)))
        }
    }

    // ---- styles

    private fun scroll(b: Builder, s: TextSpec, text: String, wave: Boolean = false, spotlight: Boolean = false) {
        val p = b.p
        val n = p.size
        if (s.direction == TextDirection.RING) return ring(b, s, text)
        if (s.direction == TextDirection.UP || s.direction == TextDirection.DOWN) return vertical(b, s, text)
        val strip = DotFonts.strip(s.font, text, s.spacing)
        val y = top(s, strip.h)
        val fits = strip.w <= n - 2
        val centre = (n - strip.w) / 2
        fun frame(x: Int, t: Int): IntArray {
            val off: (Int) -> Int = if (wave) { gx -> (sin((gx + t) * 0.45) * 2.5).roundToInt() } else { _ -> 0 }
            var px = place(p, s, strip, x, y, yOffset = off)
            if (spotlight) {
                val cx = (t * 0.9) % (n + 10) - 5
                px = IntArray(px.size) { i ->
                    val d = kotlin.math.abs(i % n - cx)
                    if (px[i] == 0) 0 else if (d < 3.5) px[i] else (px[i] * 0.3).roundToInt()
                }
            }
            return px
        }
        when (s.direction) {
            TextDirection.BOUNCE -> {
                // fits: slides between the edges; too long: shows its start, then its end
                val lo = minOf(1, n - 1 - strip.w)
                val hi = maxOf(1, n - 1 - strip.w)
                var t = 0
                for (x in hi downTo lo) b.add(frame(x, t++), if (x == hi) STEP_MS + s.pauseMs else STEP_MS)
                for (x in lo..hi) b.add(frame(x, t++), if (x == lo) STEP_MS + s.pauseMs else STEP_MS)
            }
            else -> {
                val left = s.direction != TextDirection.RIGHT
                val xs = if (left) (n downTo -strip.w) else (-strip.w..n)
                // the pause: where the whole text is centred, or where its start reaches the left edge
                val hold = if (fits) centre else if (left) 1 else n - 1 - strip.w
                var t = 0
                for (x in xs) b.add(frame(x, t++), if (x == hold) STEP_MS + s.pauseMs else STEP_MS)
            }
        }
    }

    /** Up or down: words stacked as lines, sliding vertically. */
    private fun vertical(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val n = p.size
        val h = DotFonts.height(s.font)
        val lines = chunks(s.font, text, usable(p, s.copy(band = TextBand.FULL), h), s.spacing)
        val gap = 3
        val total = lines.size * (h + gap) - gap
        val strip = Strip(n, total)
        lines.forEachIndexed { i, line ->
            val ls = DotFonts.strip(s.font, line, s.spacing)
            val x0 = (n - ls.w) / 2
            for (y in 0 until ls.h) for (x in 0 until ls.w) if (ls.lit(x, y)) strip[x0 + x, i * (h + gap) + y] = true
        }
        val up = s.direction == TextDirection.UP
        val ys = if (up) (n downTo -total) else (-total..n)
        val hold = if (total <= n) (n - total) / 2 else Int.MIN_VALUE
        for (y in ys) b.add(place(p, s, strip, 0, y), if (y == hold) STEP_MS + s.pauseMs else STEP_MS + 10)
    }

    /** Around the ring: the letters ride the outer band of dots, clockwise (or the other way for Right). */
    private fun ring(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val n = p.size
        val c = (n - 1) / 2.0
        val strip = DotFonts.strip(if (DotFonts.height(s.font) > 5) DotFont.REGULAR else s.font, text, s.spacing)
        val outer = c + 0.5
        val circumference = (2 * PI * (outer - strip.h / 2.0)).roundToInt()
        val span = strip.w + circumference
        val clockwise = true
        for (step in 0 until span) {
            val px = IntArray(p.cellCount)
            for (cell in p.ledCells) {
                val dx = cell % n - c
                val dy = cell / n - c
                val d = hypot(dx, dy)
                val row = floor(outer - d).toInt()
                if (row !in 0 until strip.h) continue
                var a = atan2(dx, -dy) / (2 * PI)
                if (a < 0) a += 1.0
                val col = floor((if (clockwise) a else 1 - a) * circumference).toInt() - circumference + step
                if (strip.lit(col, row)) px[cell] = 255
            }
            b.add(look(p, s, px), STEP_MS + 20)
        }
    }

    private fun marquee(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val strip = DotFonts.strip(s.font, text, s.spacing)
        val ring = p.ring
        val y = top(s, strip.h)
        val fits = strip.w <= usable(p, s, strip.h)
        val steps = if (fits) 48 else p.size + strip.w
        val reverse = s.direction == TextDirection.RIGHT
        for (t in 0 until steps) {
            val x = if (fits) (p.size - strip.w) / 2 else p.size - t
            val px = place(p, s.copy(border = false), strip, x, y)
            for (i in ring.indices) {
                val k = if (reverse) (ring.size - i + t) % 4 else (i + t) % 4
                if (k == 0) px[ring[i]] = 255 else if (k == 1) px[ring[i]] = maxOf(px[ring[i]], 60)
            }
            b.add(px, STEP_MS + 10)
        }
    }

    private fun type(b: Builder, s: TextSpec, text: String, cursor: Boolean) {
        val p = b.p
        val n = p.size
        val h = DotFonts.height(s.font)
        val y = top(s, h)
        val width = usable(p, s, h)
        val full = DotFonts.width(s.font, text, s.spacing)
        val x0 = if (full <= width) (n - full) / 2 else 1
        for (i in 1..text.length) {
            val part = text.substring(0, i)
            val w = DotFonts.width(s.font, part, s.spacing)
            // keep the newest letter in view
            val x = if (x0 + w + 2 > n - 1) n - 2 - w else x0
            val px = place(p, s, DotFonts.strip(s.font, part, s.spacing), x, y)
            if (cursor || i < text.length) bar(p, px, x + w + 1, y, h)
            b.add(px, if (text[i - 1] == ' ') 90 else 160)
        }
        val endW = DotFonts.width(s.font, text, s.spacing)
        val endX = if (x0 + endW + 2 > n - 1) n - 2 - endW else x0
        val done = place(p, s, DotFonts.strip(s.font, text, s.spacing), endX, y)
        if (cursor) {
            repeat(3) {
                b.add(done.copyOf().also { bar(p, it, endX + endW + 1, y, h) }, 400)
                b.add(done, 400)
            }
        }
        b.add(done, 900 + s.pauseMs)
    }

    private fun bar(p: DeviceProfile, px: IntArray, x: Int, y: Int, h: Int) {
        for (yy in y until y + h) if (p.isActive(x, yy)) px[yy * p.size + x] = 255
    }

    private fun letters(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val big = if (s.font == DotFont.SMALL || s.font == DotFont.REGULAR || s.font == DotFont.BOLD) DotFont.BIG else s.font
        val font = if (s.band == TextBand.FULL) big else s.font
        for (ch in text) {
            if (ch == ' ') {
                b.add(IntArray(p.cellCount), 300)
                continue
            }
            val g = DotFonts.strip(font, ch.toString(), s.spacing)
            b.add(place(p, s, g, (p.size - g.w) / 2, top(s, g.h)), 380)
            b.add(IntArray(p.cellCount), 70)
        }
        b.add(IntArray(p.cellCount), 300 + s.pauseMs)
    }

    private fun words(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val h = DotFonts.height(s.font)
        val y = top(s, h)
        val width = usable(p, s, h)
        val slide = when (s.direction) {
            TextDirection.RIGHT -> -1 to 0
            TextDirection.UP -> 0 to 1
            TextDirection.DOWN -> 0 to -1
            else -> 1 to 0
        }
        for (w in text.trim().split(Regex("\\s+"))) {
            val strip = DotFonts.strip(s.font, w, s.spacing)
            if (strip.w > width) {
                for (x in p.size downTo -strip.w) b.add(place(p, s, strip, x, y), STEP_MS)
                continue
            }
            val x = (p.size - strip.w) / 2
            // a short slide in from the chosen side
            for (k in 3 downTo 1) b.add(place(p, s, strip, x + slide.first * k * 3, y + slide.second * k * 3), 50)
            b.add(place(p, s, strip, x, y), 550)
        }
        b.add(IntArray(p.cellCount), 200 + s.pauseMs)
    }

    private fun bounce(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val h = DotFonts.height(s.font)
        val y = top(s, h)
        val drops = intArrayOf(-14, -10, -6, -3, 0, -3, -4, -3, 0, -1, 0)
        for (c in chunks(s.font, text, usable(p, s, h), s.spacing)) {
            val strip = DotFonts.strip(s.font, c, s.spacing)
            val x = (p.size - strip.w) / 2
            for (d in drops) b.add(place(p, s, strip, x, y + d), 60)
            b.add(place(p, s, strip, x, y), 900 + s.pauseMs)
        }
    }

    /** Rain: each dot falls from above into place. Assemble: each dot flies in from a random spot. */
    private fun particles(b: Builder, s: TextSpec, text: String, rain: Boolean) {
        val p = b.p
        val n = p.size
        val h = DotFonts.height(s.font)
        val r = Random(text.hashCode())
        for (c in chunks(s.font, text, usable(p, s, h), s.spacing)) {
            val strip = DotFonts.strip(s.font, c, s.spacing)
            val target = place(p, s.copy(outline = false, invert = false, border = false), strip, (n - strip.w) / 2, top(s, h))
            val dots = target.indices.filter { target[it] > 0 }
            val starts = dots.map { cell ->
                if (rain) (cell % n) to (cell / n - 6 - r.nextInt(14)) else r.nextInt(n) to r.nextInt(n)
            }
            val steps = 10
            for (t in 1..steps) {
                val px = IntArray(p.cellCount)
                dots.forEachIndexed { i, cell ->
                    val (sx, sy) = starts[i]
                    val k = if (rain) minOf(1.0, t / steps.toDouble() * (1.0 + (i % 5) * 0.15)) else t / steps.toDouble()
                    val x = (sx + (cell % n - sx) * k).roundToInt()
                    val y = (sy + (cell / n - sy) * k).roundToInt()
                    if (x in 0 until n && y in 0 until n) px[y * n + x] = 255
                }
                b.add(px, 70)
            }
            b.add(look(p, s, target), 900 + s.pauseMs)
        }
    }

    private fun glitch(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val n = p.size
        val r = Random(text.hashCode() + 1)
        chunks(b, s, text) { px ->
            repeat(10) { i ->
                if (i % 3 == 1) {
                    // rows torn sideways, a few stray dots
                    val out = IntArray(px.size)
                    for (y in 0 until n) {
                        val shift = if (r.nextInt(3) == 0) r.nextInt(-3, 4) else 0
                        for (x in 0 until n) {
                            val sx = x - shift
                            if (sx in 0 until n) out[y * n + x] = px[y * n + sx]
                        }
                    }
                    repeat(6) { out[r.nextInt(out.size)] = 255 }
                    b.add(out, 60)
                } else {
                    b.add(px, if (i == 9) 700 + s.pauseMs else 110)
                }
            }
        }
    }

    private fun zoom(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val n = p.size
        chunks(b, s, text) { px ->
            for (k in listOf(0.15, 0.3, 0.5, 0.7, 0.85, 1.0)) {
                val out = IntArray(px.size)
                val c = (n - 1) / 2.0
                val cy = s.band.centre.toDouble()
                for (y in 0 until n) for (x in 0 until n) {
                    val sx = (c + (x - c) / k).roundToInt()
                    val sy = (cy + (y - cy) / k).roundToInt()
                    if (sx in 0 until n && sy in 0 until n) out[y * n + x] = px[sy * n + sx]
                }
                b.add(out, 60)
            }
            b.add(px, 900 + s.pauseMs)
        }
    }

    private fun countdown(b: Builder, s: TextSpec, text: String) {
        val p = b.p
        val number = text.trim().toIntOrNull()?.takeIf { it in 1..99 }
        val from = number ?: 3
        val font = if (s.band == TextBand.FULL) DotFont.BIG else s.font
        for (i in from downTo (if (number != null) 0 else 1)) {
            val strip = DotFonts.strip(font, i.toString(), 1)
            val x = (p.size - strip.w) / 2
            val px = place(p, s, strip, x, top(s, strip.h))
            b.add(px, 850)
            b.add(scale(px, 0.3f), 150)
        }
        if (number != null) {
            // the end: a burst on the ring
            repeat(3) {
                b.add(IntArray(p.cellCount).also { a -> for (c in p.ring) a[c] = 255 }, 150)
                b.add(IntArray(p.cellCount), 150)
            }
        } else {
            scroll(b, s.copy(style = TextStyle.SCROLL), text)
        }
    }

    /** Fit: the biggest font that fits the width; otherwise up to three lines in Small. */
    private fun fitFrame(p: DeviceProfile, s: TextSpec, text: String): IntArray {
        val n = p.size
        val order = listOf(DotFont.BIG, if (s.font == DotFont.SERIF) DotFont.SERIF else DotFont.TALL, s.font, DotFont.REGULAR, DotFont.SMALL).distinct()
        for (f in order) {
            val strip = DotFonts.strip(f, text, s.spacing)
            if (strip.w <= usable(p, s, strip.h) && strip.h <= s.band.rows.count()) {
                return place(p, s, strip, (n - strip.w) / 2, top(s, strip.h))
            }
        }
        val lines = chunks(DotFont.SMALL, text, 19, maxOf(1, s.spacing)).take(3)
        val px = IntArray(p.cellCount)
        val y0 = (n - (lines.size * 6 - 1)) / 2
        lines.forEachIndexed { i, line ->
            val strip = DotFonts.strip(DotFont.SMALL, line, maxOf(1, s.spacing))
            val x0 = (n - strip.w) / 2
            for (y in 0 until strip.h) for (x in 0 until strip.w) {
                if (strip.lit(x, y) && p.isActive(x0 + x, y0 + i * 6 + y)) px[(y0 + i * 6 + y) * n + x0 + x] = 255
            }
        }
        return look(p, s, px)
    }
}
