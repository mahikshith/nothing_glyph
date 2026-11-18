package com.dotforge.composition

import java.io.ByteArrayOutputStream

/**
 * Rewrites the comment header of an Ogg Opus or Ogg Vorbis file without touching the audio.
 *
 * The header packets (Opus: OpusHead, OpusTags; Vorbis: identification, comment, setup) end on a page
 * boundary in conforming streams, so the pages after them can be kept byte for byte apart from their sequence
 * number (and so their CRC). The identification page is kept, the comment packet (and the Vorbis setup packet
 * after it) is laid out again on as many pages as it needs, and every later page of the stream is renumbered.
 */
object OggRepager {
    enum class Codec(val headerPackets: Int) { OPUS(2), VORBIS(3) }

    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.ISO_8859_1)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.ISO_8859_1)
    private val VORBIS_ID = byteArrayOf(0x01) + "vorbis".toByteArray(Charsets.ISO_8859_1)

    /** The codec of the first logical stream, or null when it is neither Opus nor Vorbis. */
    fun codecOf(data: ByteArray): Codec? {
        val first = OggReader.readPackets(data, maxPackets = 1).firstOrNull() ?: return null
        return when {
            first.startsWith(OPUS_HEAD) -> Codec.OPUS
            first.startsWith(VORBIS_ID) -> Codec.VORBIS
            else -> null
        }
    }

    /** True when [retag] can rewrite this file's comments. */
    fun canRetag(data: ByteArray): Boolean = runCatching { split(data) }.isSuccess

    /** Replaces the comments with what [edit] returns for the current ones. Throws [IllegalArgumentException] for streams it cannot handle. */
    fun retag(data: ByteArray, edit: (OggTags) -> OggTags): ByteArray {
        val s = split(data)
        val old = VorbisComments.parseOgg(data) ?: OggTags("", emptyList())
        val tags = edit(old)
        val comment = when (s.codec) {
            Codec.OPUS -> VorbisComments.buildOpusPacket(tags)
            Codec.VORBIS -> VorbisComments.buildVorbisPacket(tags)
        }
        val out = ByteArrayOutputStream(data.size + comment.size)
        var seq = 0
        // identification header: alone on the first page
        out.write(paginate(listOf(s.headers[0]), s.serial, seq, firstPage = true).also { seq += it.second }.first)
        // comment (+ setup) headers, each starting on a fresh page
        for (p in listOf(comment) + s.headers.drop(2)) {
            val (bytes, pages) = paginate(listOf(p), s.serial, seq, firstPage = false)
            out.write(bytes)
            seq += pages
        }
        for (page in s.rest) {
            if (page.serial == s.serial) {
                out.write(OggPage(page.headerType, page.granule, page.serial, seq++, page.segments, page.body).toBytes())
            } else {
                out.write(page.toBytes())
            }
        }
        return out.toByteArray()
    }

    private class Split(val codec: Codec, val serial: Int, val headers: List<ByteArray>, val rest: List<OggPage>)

    private fun split(data: ByteArray): Split {
        val pages = OggReader.readPages(data)
        require(pages.isNotEmpty()) { "not an Ogg file" }
        val serial = pages[0].serial
        val headers = ArrayList<ByteArray>()
        val cur = ByteArrayOutputStream()
        var codec: Codec? = null
        var lastHeaderPage = -1
        var leftover: OggPage? = null
        val others = ArrayList<OggPage>()
        loop@ for ((i, page) in pages.withIndex()) {
            if (page.serial != serial) { others += page; continue }
            var off = 0
            for ((k, seg) in page.segments.withIndex()) {
                cur.write(page.body, off, seg)
                off += seg
                if (seg < 255) {
                    headers += cur.toByteArray()
                    cur.reset()
                    if (codec == null) {
                        codec = when {
                            headers[0].startsWith(OPUS_HEAD) -> Codec.OPUS
                            headers[0].startsWith(VORBIS_ID) -> Codec.VORBIS
                            else -> throw IllegalArgumentException("not Opus or Vorbis")
                        }
                    }
                    if (headers.size == codec.headerPackets) {
                        lastHeaderPage = i
                        if (k < page.segments.lastIndex) leftover = leftoverPage(page, k + 1, off)
                        break@loop
                    }
                }
            }
        }
        require(codec != null && lastHeaderPage >= 0) { "headers incomplete" }
        if (codec == Codec.OPUS) require(headers[1].startsWith(OPUS_TAGS)) { "second packet is not OpusTags" }
        val rest = others + listOfNotNull(leftover) + pages.subList(lastHeaderPage + 1, pages.size)
        return Split(codec, serial, headers, rest)
    }

    /**
     * Audio that a muxer put on the last header page after the headers (not allowed by the specs, but seen):
     * moved to a page of its own. It keeps the page's granule when a packet ends on it, else -1.
     */
    private fun leftoverPage(page: OggPage, fromSegment: Int, fromByte: Int): OggPage {
        val segs = page.segments.copyOfRange(fromSegment, page.segments.size)
        val body = page.body.copyOfRange(fromByte, page.body.size)
        val endsPacket = segs.any { it < 255 }
        return OggPage(page.headerType and 0x04, if (endsPacket) page.granule else -1L, page.serial, page.sequence, segs, body)
    }

    /**
     * Lays [packets] out on pages starting at [firstSeq]: up to 255 lacing values per page, continued pages
     * flagged, granule 0 on a page where a packet ends and -1 where none does (header packets have granule 0).
     * Returns the bytes and the number of pages written.
     */
    fun paginate(packets: List<ByteArray>, serial: Int, firstSeq: Int, firstPage: Boolean): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var seq = firstSeq
        for ((idx, pkt) in packets.withIndex()) {
            val lacing = ArrayList<Int>()
            var rem = pkt.size
            while (rem >= 255) { lacing += 255; rem -= 255 }
            lacing += rem
            var segIdx = 0
            var dataPos = 0
            while (segIdx < lacing.size) {
                val take = minOf(255, lacing.size - segIdx)
                val segs = lacing.subList(segIdx, segIdx + take).toIntArray()
                val len = segs.sum()
                var type = 0
                if (segIdx > 0) type = type or 0x01
                if (firstPage && idx == 0 && segIdx == 0) type = type or 0x02
                val ends = segIdx + take == lacing.size
                val body = pkt.copyOfRange(dataPos, dataPos + len)
                out.write(OggPage(type, if (ends) 0L else -1L, serial, seq++, segs, body).toBytes())
                dataPos += len
                segIdx += take
            }
        }
        return out.toByteArray() to (seq - firstSeq)
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
