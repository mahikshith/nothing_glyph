package com.dotforge.composition

/** Interleaved 16-bit PCM. */
class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val frames: Int get() = samples.size / channels
    val durationMs: Long get() = frames * 1000L / sampleRate

    /** Channels averaged, as floats in -1..1. */
    fun mono(): FloatArray = FloatArray(frames) { f ->
        var s = 0
        for (c in 0 until channels) s += samples[f * channels + c]
        s.toFloat() / channels / 32768f
    }

    /** At most two channels (first two kept, the rest dropped). */
    fun atMostStereo(): Pcm {
        if (channels <= 2) return this
        return Pcm(sampleRate, 2, ShortArray(frames * 2) { samples[(it / 2) * channels + it % 2] })
    }

    /** Linear-interpolation resampling to [rate]. */
    fun resampled(rate: Int): Pcm {
        if (rate == sampleRate || frames == 0) return this
        val outFrames = (frames.toLong() * rate / sampleRate).toInt()
        val out = ShortArray(outFrames * channels)
        val step = sampleRate.toDouble() / rate
        for (f in 0 until outFrames) {
            val pos = f * step
            val i = pos.toInt().coerceAtMost(frames - 1)
            val j = (i + 1).coerceAtMost(frames - 1)
            val t = pos - i
            for (c in 0 until channels) {
                val a = samples[i * channels + c]
                val b = samples[j * channels + c]
                out[f * channels + c] = (a + (b - a) * t).toInt().toShort()
            }
        }
        return Pcm(rate, channels, out)
    }

    /** Linear fade to silence over the last [ms]. */
    fun fadedOut(ms: Int): Pcm {
        val n = (ms.toLong() * sampleRate / 1000).toInt().coerceAtMost(frames)
        if (n <= 0) return this
        val out = samples.copyOf()
        val start = frames - n
        for (f in start until frames) {
            val g = (frames - f).toFloat() / n
            for (c in 0 until channels) out[f * channels + c] = (out[f * channels + c] * g).toInt().toShort()
        }
        return Pcm(sampleRate, channels, out)
    }
}
