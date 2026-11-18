package com.dotforge.device

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A picture: [width] x [height] ARGB pixels, row by row. */
class Picture(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad picture" }
    }
}

/** The 14 ways a photo becomes dots. Portrait is made for faces (and listed first when the photo has one). */
@Serializable
enum class PhotoStyle(val label: String) {
    SHADES("Shades"), SOFT_SHADES("Soft shades"), TONES4("4 tones"), TONES3("3 tones"), TONES2("2 tones"),
    FINE_DITHER("Fine dither"), ATKINSON("Atkinson dither"), PATTERN("Pattern"), HALFTONE("Halftone"),
    OUTLINE("Outline"), OUTLINE_FILL("Outline + fill"), SILHOUETTE("Silhouette"), POP("Pop"), PORTRAIT("Portrait");

    companion object {
        /** The Style strip's order: Portrait first for a photo with a face, last otherwise. */
        fun strip(face: Boolean): List<PhotoStyle> = if (face) listOf(PORTRAIT) + (entries - PORTRAIT) else entries.toList()
    }
}

/**
 * A face found in a photo (by the platform's face detector): the point between the eyes and the distance
 * between them, in the photo's pixels.
 */
@Serializable
data class FaceSpot(val x: Float, val y: Float, val eyes: Float)

/** Face focus: where to crop a photo so its faces fill the Glyph. */
object PhotoFaces {
    /** Margin round the faces, as a share of their size. */
    const val MARGIN = 0.35f

    /**
     * The crop that holds [faces] (one to a few) with [MARGIN] round them, centred on the eyes: each face's box
     * runs about 1.1 eye-distances to each side, 1 above the eyes and 1.6 below (the chin).
     */
    fun crop(faces: List<FaceSpot>, width: Int, height: Int): PhotoCrop? {
        if (faces.isEmpty() || width <= 0 || height <= 0) return null
        val left = faces.minOf { it.x - 1.1f * it.eyes }
        val right = faces.maxOf { it.x + 1.1f * it.eyes }
        val top = faces.minOf { it.y - 1.0f * it.eyes }
        val bottom = faces.maxOf { it.y + 1.6f * it.eyes }
        val side = maxOf(right - left, bottom - top) * (1 + MARGIN)
        val short = minOf(width, height).toFloat()
        val zoom = (short / side).coerceIn(1f, 8f)
        // centred on the eyes across; a little below them down, so the mouth and chin are in
        val eyeY = faces.map { it.y }.average().toFloat()
        val mean = faces.map { it.eyes }.average().toFloat()
        val cx = (left + right) / 2 / width
        val cy = (eyeY + 0.3f * mean) / height
        return PhotoCrop(zoom = zoom, cx = cx, cy = cy).clamped()
    }

    /** Detail for faces: features (eyes, brows, mouth) need more local contrast than a landscape. */
    const val FACE_DETAIL = 55
}

/** What happens to the area round the subject (found from the photo's edges). */
@Serializable
enum class PhotoBackground(val label: String) { KEEP("Keep"), DARKEN("Darken"), REMOVE("Remove") }

/** Moving a photo: each makes frames from the same picture. */
@Serializable
enum class PhotoMotion(val label: String) { ZOOM("Zoom"), PAN("Pan"), FADE_IN("Fade in"), REVEAL("Reveal"), PULSE("Pulse"), GLITCH("Glitch") }

/** Where the square on the Glyph comes from. [zoom] 1 = the whole short side; [cx], [cy] are the centre, 0-1. */
@Serializable
data class PhotoCrop(
    val zoom: Float = 1f,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    /** Clockwise quarter turns, 0-3. */
    val turns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Fit: the whole photo inside the circle, with dark edges. Fill: the middle square. */
    val fit: Boolean = false,
) {
    fun clamped() = copy(zoom = zoom.coerceIn(1f, 8f), cx = cx.coerceIn(0f, 1f), cy = cy.coerceIn(0f, 1f), turns = ((turns % 4) + 4) % 4)
}

/** Every setting of the Photo screen, saved with the design so it can be re-tuned. Adjustments are -100..100. */
@Serializable
data class PhotoSettings(
    val style: PhotoStyle = PhotoStyle.SHADES,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val midtones: Int = 0,
    /** Local contrast, 0-100. */
    val detail: Int = 30,
    /** For Soft shades: 2-16 levels. */
    val shades: Int = 8,
    /** For the on/off styles: where light becomes a lit dot, 0-100. */
    val threshold: Int = 50,
    val background: PhotoBackground = PhotoBackground.KEEP,
    val invert: Boolean = false,
    val crop: PhotoCrop = PhotoCrop(),
    /**
     * The photo has a face (focused on it): every style maps the skin to a light tone and spreads the tones
     * below it, so eyes, brows and mouth read darker than the skin.
     */
    val face: Boolean = false,
) {
    fun clamped() = copy(
        brightness = brightness.coerceIn(-100, 100), contrast = contrast.coerceIn(-100, 100), midtones = midtones.coerceIn(-100, 100),
        detail = detail.coerceIn(0, 100), shades = shades.coerceIn(2, 16), threshold = threshold.coerceIn(0, 100), crop = crop.clamped(),
    )
}

/**
 * Photo to dots. The crop is sampled at 4x the grid in linear light, levelled on the LED circle only (2nd to
 * 98th percentile), adjusted (brightness, contrast, midtones, local contrast), averaged down to the grid and
 * drawn in one of the 14 styles (Portrait leads the strip when there is a face).
 */
object PhotoDots {
    private const val SS = 4
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    /** A clustered 4x4 order: dots grow from the middle of each cell, like print halftone. */
    private val CLUSTER = intArrayOf(12, 5, 6, 13, 4, 0, 1, 7, 11, 3, 2, 8, 15, 10, 9, 14)

    private fun linear(c: Int): Double = (c / 255.0).pow(2.2)

    /** Luminance in linear light at [SS]x the grid, row by row, for [crop]. */
    fun sample(p: DeviceProfile, pic: Picture, crop: PhotoCrop): DoubleArray {
        val c = crop.clamped()
        val n = p.size * SS
        val out = DoubleArray(n * n)
        val short = minOf(pic.width, pic.height).toDouble()
        val long = maxOf(pic.width, pic.height).toDouble()
        // the side of the square, in source pixels
        val side = (if (c.fit) long else short) / c.zoom
        val cx = c.cx * pic.width
        val cy = c.cy * pic.height
        // keep the square inside the photo when filling
        val half = side / 2
        val x0 = if (c.fit) cx - half else (cx - half).coerceIn(0.0, maxOf(0.0, pic.width - side))
        val y0 = if (c.fit) cy - half else (cy - half).coerceIn(0.0, maxOf(0.0, pic.height - side))
        val step = side / n
        val sub = if (step >= 2) 2 else 1
        for (j in 0 until n) for (i in 0 until n) {
            // turn and flip in the square's own space
            var u = (i + 0.5) / n
            var v = (j + 0.5) / n
            if (c.flipH) u = 1 - u
            if (c.flipV) v = 1 - v
            repeat(c.turns) { val t = u; u = v; v = 1 - t }
            var sum = 0.0
            var count = 0
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val px = (x0 + (u * n - 0.5 + (sx + 0.5) / sub) * step).toInt()
                val py = (y0 + (v * n - 0.5 + (sy + 0.5) / sub) * step).toInt()
                count++
                if (px !in 0 until pic.width || py !in 0 until pic.height) continue
                val col = pic.argb[py * pic.width + px]
                val a = (col ushr 24 and 0xFF) / 255.0
                sum += a * (0.2126 * linear(col shr 16 and 0xFF) + 0.7152 * linear(col shr 8 and 0xFF) + 0.0722 * linear(col and 0xFF))
            }
            out[j * n + i] = sum / count
        }
        return out
    }

    /** One frame of [pic] with [settings]. */
    fun frame(p: DeviceProfile, pic: Picture, settings: PhotoSettings): Frame = render(p, sample(p, pic, settings.crop), settings)

    /** The style step on samples from [sample] (so the Style tab's 14 thumbnails share one sampling). */
    fun render(p: DeviceProfile, samples: DoubleArray, settings: PhotoSettings, brightnessScale: Float = 1f): Frame {
        val s = settings.clamped()
        val n = p.size
        val hi = n * SS
        val inCircle = BooleanArray(hi * hi) { p.mask[(it / hi / SS) * n + (it % hi) / SS] }
        // levels on the circle only
        val inside = samples.indices.filter { inCircle[it] }.map { samples[it] }.sorted()
        val lo = inside.getOrElse((inside.size * 0.02).toInt()) { 0.0 }
        val top = inside.getOrElse((inside.size * 0.98).toInt().coerceAtMost(inside.size - 1)) { 1.0 }
        val span = (top - lo).takeIf { it > 1e-4 } ?: 1.0
        val k = 2.0.pow(s.contrast / 50.0)
        val gamma = 2.0.pow(-s.midtones / 60.0)
        var v = DoubleArray(samples.size) { i ->
            var x = ((samples[i] - lo) / span).coerceIn(0.0, 1.0).pow(1 / 2.2)
            x = x.pow(gamma)
            x = (x - 0.5) * k + 0.5 + s.brightness / 200.0
            x.coerceIn(0.0, 1.0)
        }
        // Portrait: local contrast first, so features in a flat-lit face stand out
        if (s.style == PhotoStyle.PORTRAIT) {
            // half strength: full CLAHE flattens the skin and the features get lost in its texture
            val eq = clahe(v, hi, tiles = 3, clip = 1.5)
            v = DoubleArray(v.size) { (v[it] * 3 + eq[it]) / 4 }
        }
        if (s.detail > 0) {
            val blur = blur(blur(v, hi, 3), hi, 3)
            val amt = s.detail / 100.0 * 1.6
            v = DoubleArray(v.size) { (v[it] + amt * (v[it] - blur[it])).coerceIn(0.0, 1.0) }
        }
        val edges = if (s.style == PhotoStyle.OUTLINE || s.style == PhotoStyle.OUTLINE_FILL || s.style == PhotoStyle.POP) edges(v, hi) else null
        // down to the grid
        val g = DoubleArray(p.cellCount)
        val e = DoubleArray(p.cellCount)
        for (y in 0 until hi) for (x in 0 until hi) {
            val cell = (y / SS) * n + x / SS
            g[cell] += v[y * hi + x] / (SS * SS)
            if (edges != null) e[cell] = maxOf(e[cell], edges[y * hi + x])
        }
        if (s.face && s.style != PhotoStyle.PORTRAIT) faceTones(p, g)
        background(p, g, s.background)
        if (s.invert) for (cell in p.ledCells) g[cell] = 1 - g[cell]
        val out = IntArray(p.cellCount)
        val t = s.threshold / 100.0
        fun level(x: Double, levels: Int) = (x * (levels - 1)).roundToInt() / (levels - 1.0)
        when (s.style) {
            PhotoStyle.SHADES -> for (c in p.ledCells) out[c] = (g[c] * 255).roundToInt()
            PhotoStyle.SOFT_SHADES -> for (c in p.ledCells) out[c] = (level(g[c], s.shades) * 255).roundToInt()
            PhotoStyle.TONES4 -> for (c in p.ledCells) out[c] = (level(g[c], 4) * 255).roundToInt()
            PhotoStyle.TONES3 -> for (c in p.ledCells) out[c] = (level(g[c], 3) * 255).roundToInt()
            PhotoStyle.TONES2 -> for (c in p.ledCells) out[c] = if (g[c] > t) 255 else 0
            PhotoStyle.FINE_DITHER -> diffuse(p, g, out, t, atkinson = false)
            PhotoStyle.ATKINSON -> diffuse(p, g, out, t, atkinson = true)
            PhotoStyle.PATTERN -> for (c in p.ledCells) {
                val th = (BAYER[(c / n % 4) * 4 + c % n % 4] + 0.5) / 16 + (t - 0.5)
                out[c] = if (g[c] > th) 255 else 0
            }
            PhotoStyle.HALFTONE -> for (c in p.ledCells) {
                val th = (CLUSTER[(c / n % 4) * 4 + c % n % 4] + 0.5) / 16 + (t - 0.5)
                out[c] = if (g[c] > th) 255 else 0
            }
            PhotoStyle.OUTLINE -> {
                val cut = edgeCut(p, e, t)
                for (c in p.ledCells) out[c] = if (e[c] >= cut) 255 else 0
            }
            PhotoStyle.OUTLINE_FILL -> {
                val cut = edgeCut(p, e, t)
                for (c in p.ledCells) out[c] = if (e[c] >= cut) 255 else (level(g[c], 4) * 130).roundToInt()
            }
            PhotoStyle.SILHOUETTE -> {
                val smooth = blur(g, n, 1)
                for (c in p.ledCells) out[c] = if (smooth[c] > t) 255 else 0
            }
            PhotoStyle.PORTRAIT -> portrait(p, g, out)
            PhotoStyle.POP -> {
                val cut = edgeCut(p, e, 0.6)
                for (c in p.ledCells) {
                    val x = ((g[c] - 0.5) * 1.6 + 0.5).coerceIn(0.0, 1.0)
                    out[c] = if (e[c] >= cut && x < 0.5) 255 else (level(x, 3) * 200).roundToInt()
                }
            }
        }
        if (brightnessScale != 1f) for (c in p.ledCells) out[c] = (out[c] * brightnessScale).roundToInt()
        return Frame.of(p, out)
    }

    /** Box blur of radius [r] on an [w]-wide square. */
    private fun blur(v: DoubleArray, w: Int, r: Int): DoubleArray {
        val h = v.size / w
        val tmp = DoubleArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            var c = 0
            for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += v[y * w + xx]; c++ } }
            tmp[y * w + x] = s / c
        }
        val out = DoubleArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            var c = 0
            for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += tmp[yy * w + x]; c++ } }
            out[y * w + x] = s / c
        }
        return out
    }

    /** Sobel edge strength. */
    private fun edges(v: DoubleArray, w: Int): DoubleArray {
        val sm = blur(v, w, 1)
        val out = DoubleArray(v.size)
        fun at(x: Int, y: Int) = sm[y.coerceIn(0, w - 1) * w + x.coerceIn(0, w - 1)]
        for (y in 0 until w) for (x in 0 until w) {
            val gx = at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)
            val gy = at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)
            out[y * w + x] = hypot(gx, gy)
        }
        return out
    }

    /** The edge strength above which a dot lights: about the strongest 15-45 % of the circle, set by [t]. */
    private fun edgeCut(p: DeviceProfile, e: DoubleArray, t: Double): Double {
        val vals = p.ledCells.map { e[it] }.sorted()
        val keep = 0.45 - t * 0.3
        return vals[((1 - keep) * (vals.size - 1)).toInt()].coerceAtLeast(0.08)
    }

    private fun diffuse(p: DeviceProfile, g: DoubleArray, out: IntArray, t: Double, atkinson: Boolean) {
        val n = p.size
        val err = g.copyOf()
        for (y in 0 until n) for (x in 0 until n) {
            val c = y * n + x
            if (!p.mask[c]) continue
            val old = err[c]
            val new = if (old > t) 1.0 else 0.0
            out[c] = if (new > 0) 255 else 0
            val q = old - new
            fun push(dx: Int, dy: Int, w: Double) {
                if (p.isActive(x + dx, y + dy)) err[(y + dy) * n + x + dx] += q * w
            }
            if (atkinson) {
                for ((dx, dy) in listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2)) push(dx, dy, 1 / 8.0)
            } else {
                push(1, 0, 7 / 16.0); push(-1, 1, 3 / 16.0); push(0, 1, 5 / 16.0); push(1, 1, 1 / 16.0)
            }
        }
    }

    /**
     * Background: the area joined to the circle's edge whose brightness is close to the edge's typical
     * brightness. Darken keeps a third of it; Remove turns it off.
     */
    internal fun background(p: DeviceProfile, g: DoubleArray, mode: PhotoBackground) {
        if (mode == PhotoBackground.KEEP) return
        val n = p.size
        val ring = p.ring
        val edge = ring.map { g[it] }.sorted()
        val median = edge[edge.size / 2]
        val tol = 0.18
        val seen = BooleanArray(p.cellCount)
        val stack = ArrayDeque(ring.filter { abs(g[it] - median) < tol })
        stack.forEach { seen[it] = true }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            val x = c % n
            val y = c / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                if (!p.isActive(x + dx, y + dy)) continue
                val nc = (y + dy) * n + x + dx
                if (!seen[nc] && abs(g[nc] - median) < tol) {
                    seen[nc] = true
                    stack.addLast(nc)
                }
            }
        }
        for (c in p.ledCells) if (seen[c]) g[c] = if (mode == PhotoBackground.REMOVE) 0.0 else g[c] * 0.33
    }

    /** [count] frames of [pic] moving with [motion], [frameMs] each. */
    fun motion(p: DeviceProfile, pic: Picture, settings: PhotoSettings, motion: PhotoMotion, count: Int = 16, frameMs: Int = 90): Animation {
        val s = settings.clamped()
        val n = p.size
        val frames = ArrayList<AnimFrame>()
        val base by lazy { render(p, sample(p, pic, s.crop), s) }
        val r = Random(11)
        for (i in 0 until count) {
            val k = i / (count - 1.0).coerceAtLeast(1.0)
            val f: Frame = when (motion) {
                PhotoMotion.ZOOM -> frame(p, pic, s.copy(crop = s.crop.copy(zoom = s.crop.zoom * (1f + 0.8f * k.toFloat()))))
                PhotoMotion.PAN -> {
                    val z = maxOf(s.crop.zoom, 1.6f)
                    frame(p, pic, s.copy(crop = s.crop.copy(zoom = z, cx = (0.3f + 0.4f * k.toFloat()))))
                }
                PhotoMotion.FADE_IN -> base.scaled(((i + 1) / count.toFloat()))
                PhotoMotion.REVEAL -> {
                    val radius = (n / 2.0 + 1) * (i + 1) / count
                    val c = (n - 1) / 2.0
                    Frame.of(p, IntArray(p.cellCount) { cell -> if (hypot(cell % n - c, cell / n - c) <= radius) base.pixels[cell] else 0 })
                }
                PhotoMotion.PULSE -> base.scaled((0.55 + 0.45 * cos(2 * PI * k)).toFloat())
                PhotoMotion.GLITCH -> if (i % 3 == 1) {
                    val px = IntArray(p.cellCount)
                    for (y in 0 until n) {
                        val shift = if (r.nextInt(3) == 0) r.nextInt(-3, 4) else 0
                        for (x in 0 until n) { val sx = x - shift; if (sx in 0 until n) px[y * n + x] = base.pixels[y * n + sx] }
                    }
                    Frame.of(p, px)
                } else base
            }
            frames += AnimFrame(f, frameMs)
        }
        return Animation(p, frames)
    }

    /**
     * The settings a photo opens with, from what it shows: a face gets Portrait, focused on the faces with more
     * detail; a graphic or logo (few flat colours) gets 2 tones; anything else gets Shades.
     */
    fun defaults(pic: Picture, faces: List<FaceSpot>): PhotoSettings {
        PhotoFaces.crop(faces, pic.width, pic.height)?.let { return PhotoSettings(style = PhotoStyle.PORTRAIT, detail = PhotoFaces.FACE_DETAIL, crop = it, face = true) }
        return if (looksLikeGraphic(pic)) PhotoSettings(style = PhotoStyle.TONES2) else PhotoSettings()
    }

    /**
     * True for high-contrast graphics (logos, signs, icons, text): a few flat colours cover most of the picture.
     * Colours are counted at 4 bits a channel on a 64 x 64 grid, so JPEG noise doesn't split a flat area.
     */
    fun looksLikeGraphic(pic: Picture): Boolean {
        val counts = HashMap<Int, Int>()
        val n = 64
        for (j in 0 until n) for (i in 0 until n) {
            val c = pic.argb[(j * pic.height / n) * pic.width + i * pic.width / n]
            val key = (c shr 20 and 0xF shl 8) or (c shr 12 and 0xF shl 4) or (c shr 4 and 0xF)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top = counts.values.sortedDescending().take(6).sum()
        return top >= n * n * 0.6
    }

    /**
     * Contrast-limited adaptive histogram equalisation on an [w]-wide square: [tiles] x [tiles] tiles, each
     * equalised with its histogram clipped at [clip] times the mean, blended between tile centres.
     */
    internal fun clahe(v: DoubleArray, w: Int, tiles: Int = 4, clip: Double = 2.5, bins: Int = 64): DoubleArray {
        val tw = w.toDouble() / tiles
        val maps = Array(tiles * tiles) { t ->
            val tx = t % tiles
            val ty = t / tiles
            val hist = DoubleArray(bins)
            var count = 0
            for (y in (ty * tw).toInt() until ((ty + 1) * tw).toInt().coerceAtMost(w)) for (x in (tx * tw).toInt() until ((tx + 1) * tw).toInt().coerceAtMost(w)) {
                hist[(v[y * w + x] * (bins - 1)).roundToInt().coerceIn(0, bins - 1)] += 1.0
                count++
            }
            val limit = clip * count / bins
            var excess = 0.0
            for (b in 0 until bins) if (hist[b] > limit) { excess += hist[b] - limit; hist[b] = limit }
            for (b in 0 until bins) hist[b] += excess / bins
            val cdf = DoubleArray(bins)
            var acc = 0.0
            for (b in 0 until bins) { acc += hist[b]; cdf[b] = acc / count.coerceAtLeast(1) }
            cdf
        }
        return DoubleArray(v.size) { i ->
            val x = i % w
            val y = i / w
            val b = (v[i] * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
            val gx = ((x + 0.5) / tw - 0.5).coerceIn(0.0, tiles - 1.0)
            val gy = ((y + 0.5) / tw - 0.5).coerceIn(0.0, tiles - 1.0)
            val x0 = gx.toInt().coerceAtMost(tiles - 1); val x1 = (x0 + 1).coerceAtMost(tiles - 1)
            val y0 = gy.toInt().coerceAtMost(tiles - 1); val y1 = (y0 + 1).coerceAtMost(tiles - 1)
            val fx = gx - x0; val fy = gy - y0
            val top = maps[y0 * tiles + x0][b] * (1 - fx) + maps[y0 * tiles + x1][b] * fx
            val bot = maps[y1 * tiles + x0][b] * (1 - fx) + maps[y1 * tiles + x1][b] * fx
            top * (1 - fy) + bot * fy
        }
    }

    /**
     * Portrait on the grid, like a drawing: the face tones from [faceTones], then the face is the light area
     * joined to the middle (inside an upright oval). Inside it, the 6 tones are kept and anything clearly darker
     * than its neighbours (eyes, brows, nostrils, mouth) goes fully dark; outside it, everything is dimmed so
     * the face leads; and a one-dot dark line runs round the face.
     */
    private fun portrait(p: DeviceProfile, g: DoubleArray, out: IntArray) {
        val n = p.size
        faceTones(p, g)
        val cx = (n - 1) / 2.0
        val cy = (n - 1) / 2.0 + 0.5
        fun oval(x: Int, y: Int) = ((x - cx) / (n * 0.36)).pow(2) + ((y - cy) / (n * 0.46)).pow(2) <= 1
        val smooth = blur(g, n, 1)
        val face = BooleanArray(p.cellCount)
        val stack = ArrayDeque<Int>()
        for (c in p.ledCells) {
            if (hypot(c % n - cx, c / n - cy) <= 3.5 && smooth[c] > SKIN_CUT) { face[c] = true; stack.addLast(c) }
        }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            val x = c % n; val y = c / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx; val ny = y + dy
                if (!p.isActive(nx, ny) || !oval(nx, ny)) continue
                val nc = ny * n + nx
                if (!face[nc] && smooth[nc] > SKIN_CUT) { face[nc] = true; stack.addLast(nc) }
            }
        }
        // features are holes in the skin: a cell with face on both sides (in its row or its column) is face too
        val inside = BooleanArray(p.cellCount) { c ->
            val x = c % n; val y = c / n
            face[c] || (p.mask[c] && oval(x, y) &&
                (((0 until x).any { face[y * n + it] } && (x + 1 until n).any { face[y * n + it] }) ||
                    ((0 until y).any { face[it * n + x] } && (y + 1 until n).any { face[it * n + x] })))
        }
        val tones = doubleArrayOf(0.0, 0.12, 0.3, 0.55, 0.8, 1.0)
        fun tone(x: Double) = tones.minBy { abs(it - x) }
        for (c in p.ledCells) {
            out[c] = (255 * when {
                // a feature: clearly darker than the skin round it goes off, a shadow half way
                inside[c] && g[c] < smooth[c] - 0.2 && g[c] < 0.6 -> 0.0
                inside[c] && g[c] < smooth[c] - 0.1 -> tone(g[c] * 0.5)
                inside[c] -> tone(g[c])
                else -> tone(g[c] * 0.4)
            }).roundToInt()
        }
        for (c in p.ledCells) {
            if (inside[c]) continue
            val x = c % n; val y = c / n
            if (listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) -> p.isActive(x + dx, y + dy) && inside[(y + dy) * n + x + dx] }) out[c] = 0
        }
    }

    private const val SKIN_CUT = 0.4

    /**
     * Tones for a face: the skin (the light end of the middle of the circle) goes to 80 %; darker values fall
     * away on a steep curve, so eyes, brows and mouth, a little darker than skin, become clearly dark and hair
     * goes near black; lighter values (teeth, highlights) share the top.
     */
    private fun faceTones(p: DeviceProfile, g: DoubleArray) {
        val n = p.size
        val c0 = (n - 1) / 2.0
        val mid = p.ledCells.filter { hypot(it % n - c0, it / n - c0 - 0.5) <= 6.0 }.map { g[it] }.sorted()
        if (mid.isEmpty()) return
        val m = mid[(mid.size * 0.6).toInt().coerceAtMost(mid.size - 1)].coerceIn(0.1, 0.95)
        for (c in p.ledCells) {
            val x = g[c]
            val y = if (x < m) 0.8 * (x / m).pow(2.5) else 0.8 + 0.2 * (x - m) / (1 - m)
            // the darkest go fully off: on the Glyph a faint dot still reads as lit
            g[c] = if (y < 0.08) 0.0 else y
        }
    }

    /** A test picture without a photo: soft light from the top left and a bright disc (previews and tests). */
    fun samplePicture(w: Int = 120, h: Int = 120): Picture = Picture(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val d = sqrt(((x - w * 0.55) / w).pow(2) + ((y - h * 0.45) / h).pow(2))
        val v = (if (d < 0.22) 235.0 else 40 + 120 * (1 - (x + y) / (w + h).toDouble())).roundToInt() + (sin(x * 0.3) * 6).roundToInt()
        val c = v.coerceIn(0, 255)
        (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    })
}
