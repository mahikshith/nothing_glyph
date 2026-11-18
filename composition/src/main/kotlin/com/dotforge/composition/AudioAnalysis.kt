package com.dotforge.composition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT (own implementation). */
object Fft {
    /** [re] and [im] have the same power-of-two length. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Per-frame features of a song at the glyph frame rate. Everything is normalised to 0..1 over the whole track.
 * [bands] are log-spaced from bass ([bands]\[i]\[0]) to treble; onsets are 0 except on detected onset frames,
 * where they hold the onset strength.
 */
class AudioAnalysis(
    val frameCount: Int,
    val fps: Int,
    val bands: Array<FloatArray>,
    val onset: FloatArray,
    val bassOnset: FloatArray,
    val highOnset: FloatArray,
) {
    val bandCount: Int get() = bands.firstOrNull()?.size ?: 0

    /** Mean of the lowest [n] bands at frame [i]. */
    fun bass(i: Int, n: Int = 2): Float = (0 until n).map { bands[i][it] }.average().toFloat()

    /** Mean of the highest [n] bands at frame [i]. */
    fun treble(i: Int, n: Int = 3): Float = (bandCount - n until bandCount).map { bands[i][it] }.average().toFloat()
}

/** Short-time FFT analysis: band energies and spectral-flux onsets at 60 fps. Deterministic. */
object AudioAnalyzer {
    const val BANDS = 12
    private const val FFT_SIZE = 2048
    private const val LOW_HZ = 40.0
    private const val HIGH_HZ = 16_000.0
    private const val BASS_HZ = 200.0
    private const val TREBLE_HZ = 4_000.0

    fun analyze(mono: FloatArray, sampleRate: Int, fps: Int = GlyphAuthor.FPS): AudioAnalysis {
        val frames = max(1, ((mono.size.toLong() * fps + sampleRate - 1) / sampleRate).toInt())
        val n = FFT_SIZE
        val half = n / 2
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val hzPerBin = sampleRate.toDouble() / n
        val top = min(HIGH_HZ, sampleRate / 2.0 * 0.95)
        // band edges in bins, log-spaced
        val edges = IntArray(BANDS + 1) { b ->
            val hz = LOW_HZ * (top / LOW_HZ).pow(b.toDouble() / BANDS)
            (hz / hzPerBin).toInt().coerceIn(1, half - 1)
        }
        for (b in 1..BANDS) if (edges[b] <= edges[b - 1]) edges[b] = min(half, edges[b - 1] + 1)
        val bassBin = (BASS_HZ / hzPerBin).toInt().coerceIn(1, half)
        val trebleBin = (TREBLE_HZ / hzPerBin).toInt().coerceIn(1, half)

        val bandDb = Array(frames) { FloatArray(BANDS) }
        val flux = FloatArray(frames)
        val bassFlux = FloatArray(frames)
        val highFlux = FloatArray(frames)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var prev = DoubleArray(half)
        var cur = DoubleArray(half)
        for (f in 0 until frames) {
            val center = (f.toLong() * sampleRate / fps).toInt()
            val start = center - n / 2
            for (k in 0 until n) {
                val s = start + k
                re[k] = if (s in mono.indices) mono[s] * window[k] else 0.0
                im[k] = 0.0
            }
            Fft.transform(re, im)
            for (k in 0 until half) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / half
                cur[k] = ln(1 + 1000 * mag)
            }
            for (b in 0 until BANDS) {
                var p = 0.0
                for (k in edges[b] until max(edges[b] + 1, edges[b + 1])) {
                    val m = re[k] * re[k] + im[k] * im[k]
                    p += m
                }
                p /= max(1, edges[b + 1] - edges[b]).toDouble() * half * half
                bandDb[f][b] = (10 * log10(p + 1e-12)).toFloat()
            }
            if (f > 0) {
                var all = 0.0; var lo = 0.0; var hi = 0.0
                for (k in 1 until half) {
                    val d = cur[k] - prev[k]
                    if (d > 0) {
                        all += d
                        if (k < bassBin) lo += d
                        if (k >= trebleBin) hi += d
                    }
                }
                flux[f] = all.toFloat(); bassFlux[f] = lo.toFloat(); highFlux[f] = hi.toFloat()
            }
            val t = prev; prev = cur; cur = t
        }

        // normalise each band: 0 at 40 dB under its loud level (98th percentile), 1 at that level
        val bands = Array(frames) { FloatArray(BANDS) }
        for (b in 0 until BANDS) {
            val col = FloatArray(frames) { bandDb[it][b] }
            val hiDb = percentile(col, 0.98f)
            val loDb = max(percentile(col, 0.05f), hiDb - 40f)
            val span = max(6f, hiDb - loDb)
            for (f in 0 until frames) bands[f][b] = ((bandDb[f][b] - loDb) / span).coerceIn(0f, 1f)
        }
        return AudioAnalysis(frames, fps, bands, onsets(flux), onsets(bassFlux), onsets(highFlux))
    }

    /**
     * Peak picking on a flux curve: normalised by its 99th percentile, a frame is an onset when it is the
     * largest within ±3 frames, above the local median + 0.1, above 0.15, and at least 6 frames (100 ms)
     * after the previous onset.
     */
    fun onsets(flux: FloatArray): FloatArray {
        val out = FloatArray(flux.size)
        val ref = percentile(flux, 0.99f)
        if (ref <= 1e-6f) return out
        val norm = FloatArray(flux.size) { (flux[it] / ref).coerceIn(0f, 1.5f) }
        var last = -100
        val w = 8
        for (i in norm.indices) {
            val v = norm[i]
            if (v < 0.15f || i - last < 6) continue
            val from = max(0, i - 3)
            val to = min(norm.size - 1, i + 3)
            var isMax = true
            for (k in from..to) if (norm[k] > v || (norm[k] == v && k < i)) { isMax = false; break }
            if (!isMax) continue
            val window = norm.copyOfRange(max(0, i - w), min(norm.size, i + w + 1))
            if (v < percentile(window, 0.5f) + 0.1f) continue
            out[i] = v.coerceAtMost(1f)
            last = i
        }
        return out
    }

    fun percentile(a: FloatArray, q: Float): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf().also { it.sort() }
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }
}
