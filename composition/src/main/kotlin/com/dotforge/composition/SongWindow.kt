package com.dotforge.composition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The part of a song a ringtone is made from: [startMs] and [lengthMs] inside a song of [songMs]. Always valid:
 * the length is between [MIN_MS] (or the whole song when shorter) and [MAX_MS], and the window stays inside the
 * song. Build with [of], which clamps.
 */
data class SongWindow private constructor(val songMs: Long, val startMs: Long, val lengthMs: Long) {
    val endMs: Long get() = startMs + lengthMs

    /** True when the window stops before the song does (the saved sound then fades out). */
    val cutsEnd: Boolean get() = endMs < songMs

    /** True when the window is the whole song. */
    val isWhole: Boolean get() = startMs == 0L && lengthMs == songMs

    /** Same length, new start. */
    fun movedTo(start: Long) = of(songMs, start, lengthMs)

    /** Same start, new length (the start moves back if the end would pass the song's end). */
    fun withLength(length: Long) = of(songMs, startMs, length)

    /** Same start, new end. */
    fun withEnd(end: Long) = withLength(end - startMs)

    companion object {
        const val MAX_MS = 40_000L
        const val MIN_MS = 5_000L

        fun of(songMs: Long, startMs: Long, lengthMs: Long): SongWindow {
            val song = max(1L, songMs)
            val len = lengthMs.coerceIn(min(MIN_MS, song), min(MAX_MS, song))
            return SongWindow(song, startMs.coerceIn(0L, song - len), len)
        }

        /**
         * The loudest stretch of [MAX_MS] (or the whole song), from an [envelope] of peaks every [bucketMs]:
         * usually the chorus, which makes a better ringtone than the intro. Ties go to the earliest.
         */
        fun loudest(songMs: Long, envelope: FloatArray, bucketMs: Int): SongWindow {
            val len = min(MAX_MS, max(1L, songMs))
            if (envelope.isEmpty() || len >= songMs) return of(songMs, 0, len)
            val span = max(1, (len / bucketMs).toInt())
            var sum = 0.0
            for (i in 0 until min(span, envelope.size)) sum += envelope[i] * envelope[i]
            var best = sum
            var bestAt = 0
            for (i in span until envelope.size) {
                sum += envelope[i] * envelope[i] - envelope[i - span] * envelope[i - span]
                if (sum > best + 1e-9) { best = sum; bestAt = i - span + 1 }
            }
            return of(songMs, bestAt.toLong() * bucketMs, len)
        }
    }
}

/** Peak level (0..1) of every [bucketMs] of interleaved 16-bit audio, fed in chunks while decoding. */
class PeakEnvelope(private val sampleRate: Int, private val channels: Int, val bucketMs: Int = 50) {
    private val perBucket = max(1, (sampleRate.toLong() * bucketMs / 1000).toInt()) * channels
    private var peaks = FloatArray(1024)
    private var count = 0
    private var inBucket = 0
    private var peak = 0

    fun add(samples: ShortArray, size: Int = samples.size) {
        for (i in 0 until size) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
            if (++inBucket == perBucket) flush()
        }
    }

    private fun flush() {
        if (count == peaks.size) peaks = peaks.copyOf(peaks.size * 2)
        peaks[count++] = min(1f, peak / 32767f)
        peak = 0
        inBucket = 0
    }

    /** The peaks so far (a last partial bucket included). */
    fun result(): FloatArray {
        if (inBucket > 0) flush()
        return peaks.copyOf(count)
    }

    companion object {
        /** [envelope] reduced to [bars] values (max of each group), for drawing. */
        fun bars(envelope: FloatArray, bars: Int): FloatArray {
            if (envelope.isEmpty() || bars <= 0) return FloatArray(max(0, bars))
            return FloatArray(bars) { b ->
                val from = (b.toLong() * envelope.size / bars).toInt()
                val to = max(from + 1, ((b + 1).toLong() * envelope.size / bars).toInt()).coerceAtMost(envelope.size)
                var m = 0f
                for (i in from until to) if (envelope[i] > m) m = envelope[i]
                m
            }
        }
    }
}
