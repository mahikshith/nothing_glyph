package com.dotforge.device

import kotlin.math.pow
import kotlin.math.roundToInt

/** How a picture becomes dots. */
enum class DotArtMode(val label: String, val hint: String) {
    DITHER("Dither", "Fine detail from on and off dots"),
    POSTERISE("Three levels", "Bold: off, dim and bright"),
    GREY("Shades", "Every brightness, smooth"),
}

/**
 * A picture to a matrix frame: the centre square is cropped, averaged down to the grid in linear light,
 * stretched to the full brightness range (auto levels), then dithered (Bayer 4x4), posterised or kept grey.
 */
object DotArt {
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun linear(c: Int): Double = (c / 255.0).pow(2.2)

    /** [argb]: [width] x [height] pixels, row by row. Transparent pixels count as black. */
    fun frame(p: DeviceProfile, width: Int, height: Int, argb: IntArray, mode: DotArtMode, invert: Boolean = false): Frame {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad picture" }
        val side = minOf(width, height)
        val x0 = (width - side) / 2
        val y0 = (height - side) / 2
        val n = p.size
        val lum = DoubleArray(p.cellCount)
        for (cy in 0 until n) for (cx in 0 until n) {
            val sx0 = x0 + cx * side / n
            val sx1 = (x0 + (cx + 1) * side / n).coerceAtLeast(sx0 + 1)
            val sy0 = y0 + cy * side / n
            val sy1 = (y0 + (cy + 1) * side / n).coerceAtLeast(sy0 + 1)
            var sum = 0.0
            var count = 0
            for (y in sy0 until sy1) for (x in sx0 until sx1) {
                val c = argb[y * width + x]
                val a = (c ushr 24 and 0xFF) / 255.0
                val l = 0.2126 * linear(c shr 16 and 0xFF) + 0.7152 * linear(c shr 8 and 0xFF) + 0.0722 * linear(c and 0xFF)
                sum += l * a
                count++
            }
            val v = sum / count
            lum[cy * n + cx] = if (invert) 1 - v else v
        }
        val active = p.ledCells.map { lum[it] }
        val lo = active.minOrNull() ?: 0.0
        val hi = active.maxOrNull() ?: 1.0
        val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
        val out = IntArray(p.cellCount)
        for (cell in p.ledCells) {
            // back to perceived brightness after levels, so mid-greys look mid on the LEDs
            val v = ((lum[cell] - lo) / span).coerceIn(0.0, 1.0).pow(1 / 2.2)
            out[cell] = when (mode) {
                DotArtMode.GREY -> (v * 255).roundToInt()
                DotArtMode.POSTERISE -> when {
                    v < 0.33 -> 0
                    v < 0.66 -> 110
                    else -> 255
                }
                DotArtMode.DITHER -> {
                    val t = (BAYER[(cell / n % 4) * 4 + cell % n % 4] + 0.5) / 16
                    if (v > t) 255 else 0
                }
            }
        }
        return Frame(p, out)
    }
}
