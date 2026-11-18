package com.dotforge.ringtones

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dotforge.composition.Pcm
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

    /** The first audio track of [file] as 16-bit PCM, stopping after [maxMs]. */
    fun decode(file: File, maxMs: Long = Long.MAX_VALUE): Decoded {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("There's no sound in this file.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val codec = MediaCodec.createDecoderByType(mime)
            val out = ShortBuilder()
            var cut = false
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
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            while (fb.hasRemaining()) out.add((fb.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.hasRemaining()) out.add(sb.get())
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (maxMs != Long.MAX_VALUE && out.size / channels.coerceAtLeast(1) >= maxMs * rate / 1000) {
                            cut = true
                            break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val keep = if (cut) (maxMs * rate / 1000).toInt() * channels else out.size / channels * channels
            return Decoded(Pcm(rate, channels, out.toArray(keep)), cut)
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

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(n: Int): ShortArray = data.copyOf(n.coerceIn(0, size))
    }
}
