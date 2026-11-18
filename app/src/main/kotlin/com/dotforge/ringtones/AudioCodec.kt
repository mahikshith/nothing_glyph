package com.dotforge.ringtones

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dotforge.composition.Pcm
import com.dotforge.composition.PeakEnvelope
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio Android can read to PCM (MediaExtractor + MediaCodec) and encodes PCM to Ogg Opus
 * (MediaCodec Opus encoder + MediaMuxer OGG, API 29+). The muxer writes its own OpusTags packet; the glyph
 * comments replace it later ([com.dotforge.composition.GlyphRingtone.write]).
 */
object AudioCodec {
    private const val TIMEOUT_US = 10_000L
    const val OPUS_RATE = 48_000

    class Decoded(val pcm: Pcm, /** True when the audio went on past the limit and was cut. */ val cut: Boolean)

    /** A whole song at a glance: its peak envelope (for the waveform) and length. */
    class Scan(val envelope: FloatArray, val bucketMs: Int, val durationMs: Long)

    /** The first audio track of [file] as 16-bit PCM from [startMs], stopping after [maxMs]. */
    fun decode(file: File, maxMs: Long = Long.MAX_VALUE, startMs: Long = 0L): Decoded {
        val out = ShortBuilder()
        var cut = false
        var limit = -1L
        val (rate, channels) = pump(file, startMs) { chunk, n, rate, ch ->
            if (limit < 0) limit = if (maxMs == Long.MAX_VALUE) Long.MAX_VALUE else maxMs * rate / 1000 * ch
            val room = (limit - out.size).coerceAtMost(n.toLong()).toInt()
            out.addAll(chunk, room)
            if (room < n || out.size.toLong() >= limit) { cut = true; false } else true
        }
        val keep = out.size / channels * channels
        return Decoded(Pcm(rate, channels, out.toArray(keep)), cut)
    }

    /** Reads the whole song (up to [maxMs]) without keeping the samples: peaks every 50 ms and the length. */
    fun scan(file: File, maxMs: Long = 15 * 60_000L): Scan {
        var env: PeakEnvelope? = null
        var samples = 0L
        val (rate, channels) = pump(file, 0L) { chunk, n, rate, ch ->
            val e = env ?: PeakEnvelope(rate, ch).also { env = it }
            e.add(chunk, n)
            samples += n
            samples / ch * 1000 / rate < maxMs
        }
        val e = env ?: PeakEnvelope(rate, channels)
        return Scan(e.result(), e.bucketMs, samples / channels.coerceAtLeast(1) * 1000 / rate.coerceAtLeast(1))
    }

    /**
     * Decodes [file] from [startMs] and hands each block of interleaved samples to [sink] (which returns false to
     * stop). Returns the sample rate and channel count.
     */
    private fun pump(file: File, startMs: Long, sink: (ShortArray, Int, Int, Int) -> Boolean): Pair<Int, Int> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("There's no sound in this file.")
            ex.selectTrack(track)
            val startUs = startMs * 1000
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var chunk = ShortArray(16 * 1024)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val n = if (isFloat) info.size / 4 else info.size / 2
                        if (chunk.size < n) chunk = ShortArray(n)
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            for (k in 0 until n) chunk[k] = (fb.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        } else {
                            buf.asShortBuffer().get(chunk, 0, n)
                        }
                        codec.releaseOutputBuffer(o, false)
                        // drop what the decoder gives before the start (seeking lands on the sync point before it)
                        val ch = channels.coerceAtLeast(1)
                        val skipFrames = if (startUs > info.presentationTimeUs) ((startUs - info.presentationTimeUs) * rate / 1_000_000L) else 0L
                        val skip = (skipFrames * ch).coerceAtMost(n.toLong()).toInt() / ch * ch
                        if (skip < n) {
                            if (skip > 0) System.arraycopy(chunk, skip, chunk, 0, n - skip)
                            if (!sink(chunk, n - skip, rate, ch)) break
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return rate to channels.coerceAtLeast(1)
        } finally {
            ex.release()
        }
    }

    /** Encodes [pcm] (resampled to 48 kHz, at most stereo) to an Ogg Opus file at [out]. */
    fun encodeOggOpus(pcm: Pcm, out: File) {
        val src = pcm.atMostStereo().resampled(OPUS_RATE)
        val ch = src.channels
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, ch)
        format.setInteger(MediaFormat.KEY_BIT_RATE, if (ch == 2) 128_000 else 96_000)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var pos = 0
            var inputDone = false
            fun ptsUs(sampleIndex: Int) = sampleIndex.toLong() / ch * 1_000_000L / OPUS_RATE
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val room = buf.remaining() / 2 / ch * ch
                        val n = minOf(room, src.samples.size - pos)
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, ptsUs(pos), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(src.samples, pos, n)
                            codec.queueInputBuffer(i, 0, n * 2, ptsUs(pos), 0)
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        if (!started || out.length() == 0L) throw IOException("Couldn't encode the sound.")
    }

    private class ShortBuilder {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        fun addAll(src: ShortArray, n: Int) {
            if (n <= 0) return
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(src, 0, data, size, n)
            size += n
        }

        fun toArray(n: Int): ShortArray = data.copyOf(n.coerceIn(0, size))
    }
}
