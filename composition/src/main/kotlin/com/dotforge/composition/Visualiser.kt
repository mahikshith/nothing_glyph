package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** How the live visualiser draws. */
enum class VisualStyle(val label: String, val hint: String) {
    BARS("Bars", "Bass on the left, treble on the right"),
    RING_PULSE("Ring pulse", "A ring that grows with the beat"),
    RADIAL("Radial spectrum", "Spokes from the centre, bass at the top"),
}

/** Maps an FFT magnitude spectrum to log-spaced bands (40 Hz to 16 kHz). */
object SpectrumBands {
    const val BANDS = 12
    private const val LOW_HZ = 40.0
    private const val HIGH_HZ = 16_000.0

    /**
     * Bin edges for [bins] magnitudes covering 0..sampleRate/2: band b is bins edges[b] until edges[b+1].
     * Every band has at least one bin.
     */
    fun edges(bins: Int, sampleRate: Int, bands: Int = BANDS): IntArray {
        val hzPerBin = sampleRate / 2.0 / bins
        val top = min(HIGH_HZ, sampleRate / 2.0 * 0.95)
        val e = IntArray(bands + 1) { b ->
            val hz = LOW_HZ * (top / LOW_HZ).pow(b.toDouble() / bands)
            (hz / hzPerBin).toInt().coerceIn(1, bins - 1)
        }
        for (b in 1..bands) if (e[b] <= e[b - 1]) e[b] = min(bins, e[b - 1] + 1)
        return e
    }

    /** The band a frequency falls in, or -1 outside 40 Hz..16 kHz. */
    fun bandOf(hz: Double, bins: Int, sampleRate: Int, bands: Int = BANDS): Int {
        val bin = (hz / (sampleRate / 2.0 / bins)).toInt()
        val e = edges(bins, sampleRate, bands)
        for (b in 0 until bands) if (bin >= e[b] && bin < e[b + 1]) return b
        return -1
    }

    /** Band levels in dB (mean power of each band's bins). */
    fun bandDb(magnitudes: FloatArray, sampleRate: Int, edges: IntArray = edges(magnitudes.size, sampleRate)): FloatArray =
        FloatArray(edges.size - 1) { b ->
            var p = 0.0
            val to = max(edges[b] + 1, edges[b + 1]).coerceAtMost(magnitudes.size)
            for (k in edges[b] until to) p += magnitudes[k].toDouble() * magnitudes[k]
            (10 * log10(p / max(1, to - edges[b]) + 1e-12)).toFloat()
        }

    /**
     * Magnitudes from [android.media.audiofx.Visualizer.getFft]'s bytes: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...],
     * signed 8-bit. Returns n/2 magnitudes (bin 0 is DC).
     */
    fun fromVisualizerFft(fft: ByteArray): FloatArray {
        val n = fft.size / 2
        return FloatArray(n) { k ->
            if (k == 0) abs(fft[0].toFloat()) else hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
        }
    }

    /** Magnitudes of [samples] (16-bit mono, power-of-two length) with a Hann window, n/2 bins. */
    fun fromPcm(samples: ShortArray): FloatArray {
        val n = samples.size
        val re = DoubleArray(n) { samples[it] / 32768.0 * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (n - 1))) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        return FloatArray(n / 2) { (sqrt(re[it] * re[it] + im[it] * im[it]) / (n / 2) * 128).toFloat() }
    }
}

/**
 * Turns band dB into steady 0..1 levels: bands are measured against the recent loudest band (a reference that
 * falls slowly, so quiet songs still fill the matrix while the balance between bass and treble is kept), then
 * smoothed with a fast attack and a slower decay.
 */
class LiveLevels(bands: Int = SpectrumBands.BANDS, private val rangeDb: Float = 36f) {
    private var peak = -200f
    val levels = FloatArray(bands)

    /** Updates [levels] from one spectrum; [dt] seconds since the last one. */
    fun update(db: FloatArray, dt: Float = 1f / 30) {
        val loudest = db.maxOrNull() ?: -200f
        peak = max(loudest, peak - 6f * dt)
        // nothing loud has been heard: stay dark
        val ref = max(peak, MIN_PEAK_DB)
        for (b in levels.indices) {
            val v = db.getOrElse(b) { -200f }
            val target = ((v - (ref - rangeDb)) / rangeDb).coerceIn(0f, 1f)
            levels[b] = if (target > levels[b]) levels[b] + (target - levels[b]) * 0.7f else levels[b] * 0.82f + target * 0.18f
        }
    }

    companion object {
        /** Below this a band counts as silence, whatever the recent peak. */
        const val MIN_PEAK_DB = -60f
    }
}

/** Draws band levels on the matrix (0-255 per cell). */
object VisualiserRenderer {
    fun frame(levels: FloatArray, style: VisualStyle, profile: DeviceProfile = DeviceProfile.Phone3): IntArray {
        val out = IntArray(profile.cellCount)
        val n = profile.size
        val c = (n - 1) / 2
        val maxR = sqrt(profile.radiusSquared.toFloat())
        val bands = levels.size
        if (bands == 0) return out
        val bass = (levels[0] + levels.getOrElse(1) { levels[0] }) / 2
        val overall = levels.average().toFloat()
        if (levels.all { it < 0.02f }) {
            // idle: a dim outline, so the matrix shows it is listening
            for (cell in profile.ring) out[cell] = 24
            return out
        }
        for (cell in profile.ledCells) {
            val x = cell % n
            val y = cell / n
            val dx = (x - c).toFloat()
            val dy = (y - c).toFloat()
            val r = sqrt(dx * dx + dy * dy)
            val v = when (style) {
                VisualStyle.BARS -> {
                    val b = (x * bands / n).coerceIn(0, bands - 1)
                    val h = levels[b] * n
                    if (n - y <= h + 0.5f) 1f else 0f
                }
                VisualStyle.RING_PULSE -> {
                    val radius = 1.5f + bass * (maxR - 1.5f)
                    val ring = (1f - abs(r - radius) / 1.6f).coerceAtLeast(0f)
                    max(ring, if (r < radius) overall * 0.35f else 0f)
                }
                VisualStyle.RADIAL -> {
                    // angle from the top, folded so both halves mirror: 0 at the top (bass), 1 at the bottom (treble)
                    val a = abs(atan2(dx, -dy)) / Math.PI.toFloat()
                    val b = (a * (bands - 1)).let { if (r == 0f) 0f else it }
                    val b0 = b.toInt().coerceIn(0, bands - 1)
                    val b1 = (b0 + 1).coerceAtMost(bands - 1)
                    val l = levels[b0] + (levels[b1] - levels[b0]) * (b - b0)
                    if (r <= 0.8f + l * maxR) 0.35f + 0.65f * l else 0f
                }
            }
            out[cell] = (v * 255).toInt().coerceIn(0, 255)
        }
        return out
    }
}
