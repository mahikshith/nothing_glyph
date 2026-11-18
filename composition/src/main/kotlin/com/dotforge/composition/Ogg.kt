package com.dotforge.composition

import java.io.ByteArrayOutputStream

/** One Ogg page (RFC 3533). */
class OggPage(
    val headerType: Int,
    val granule: Long,
    val serial: Int,
    val sequence: Int,
    val segments: IntArray,
    val body: ByteArray,
) {
    val isContinued get() = headerType and 0x01 != 0
    val isFirst get() = headerType and 0x02 != 0
    val isLast get() = headerType and 0x04 != 0

    /** True when the last segment is shorter than 255, so the page's last packet ends here. */
    val endsPacket get() = segments.isNotEmpty() && segments.last() < 255

    /** The page as bytes, with a freshly computed CRC. */
    fun toBytes(): ByteArray {
        val out = ByteArray(27 + segments.size + body.size)
        "OggS".toByteArray(Charsets.ISO_8859_1).copyInto(out)
        out[4] = 0
        out[5] = headerType.toByte()
        for (i in 0 until 8) out[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) out[14 + i] = ((serial ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) out[18 + i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
        out[26] = segments.size.toByte()
        for (i in segments.indices) out[27 + i] = segments[i].toByte()
        body.copyInto(out, 27 + segments.size)
        val crc = OggCrc.crc(out)
        for (i in 0 until 4) out[22 + i] = ((crc ushr (8 * i)) and 0xFF).toByte()
        return out
    }
}

object OggCrc {
    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    fun crc(data: ByteArray): Int {
        var c = 0
        for (b in data) c = (c shl 8) xor table[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }
}

object OggReader {
    private fun isCapture(d: ByteArray, i: Int) =
        d[i] == 'O'.code.toByte() && d[i + 1] == 'g'.code.toByte() && d[i + 2] == 'g'.code.toByte() && d[i + 3] == 'S'.code.toByte()

    /** Parse the page at or after [from] (resyncing over garbage). Returns the page and the offset after it, or null at end/truncation. */
    private fun pageAt(data: ByteArray, from: Int): Pair<OggPage, Int>? {
        var pos = from
        while (pos + 27 <= data.size) {
            if (!isCapture(data, pos)) { pos++; continue }
            val nSeg = data[pos + 26].toInt() and 0xFF
            if (pos + 27 + nSeg > data.size) return null
            val lacing = IntArray(nSeg) { data[pos + 27 + it].toInt() and 0xFF }
            val bodyStart = pos + 27 + nSeg
            val bodyLen = lacing.sum()
            if (bodyStart + bodyLen > data.size) return null
            val page = OggPage(
                headerType = data[pos + 5].toInt() and 0xFF,
                granule = le64(data, pos + 6),
                serial = le32(data, pos + 14),
                sequence = le32(data, pos + 18),
                segments = lacing,
                body = data.copyOfRange(bodyStart, bodyStart + bodyLen),
            )
            return page to (bodyStart + bodyLen)
        }
        return null
    }

    /**
     * Checks the CRC of every page (from the first capture pattern on). Returns the number of pages, or throws
     * [IllegalStateException] naming the first bad page.
     */
    fun verifyCrc(data: ByteArray): Int {
        var pos = 0
        var n = 0
        while (true) {
            val (page, next) = pageAt(data, pos) ?: break
            val start = next - (27 + page.segments.size + page.body.size)
            val raw = data.copyOfRange(start, next)
            val stored = le32(raw, 22)
            for (i in 22 until 26) raw[i] = 0
            check(OggCrc.crc(raw) == stored) { "bad CRC on page ${page.sequence} (#$n)" }
            pos = next
            n++
        }
        return n
    }

    /** Parses up to [maxPages] pages. Resyncs on garbage; never throws on truncation. */
    fun readPages(data: ByteArray, maxPages: Int = Int.MAX_VALUE): List<OggPage> {
        val pages = ArrayList<OggPage>()
        var pos = 0
        while (pages.size < maxPages) {
            val (p, next) = pageAt(data, pos) ?: break
            pages += p
            pos = next
        }
        return pages
    }

    /**
     * Reassembles packets of the first logical stream across page boundaries, stopping once [maxPackets]
     * complete packets exist (so large audio data is not walked). A trailing incomplete packet is dropped.
     */
    fun readPackets(data: ByteArray, maxPackets: Int = Int.MAX_VALUE): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val cur = ByteArrayOutputStream()
        var serial: Int? = null
        var pos = 0
        while (packets.size < maxPackets) {
            val (page, next) = pageAt(data, pos) ?: break
            pos = next
            if (serial == null) serial = page.serial
            if (page.serial != serial) continue
            var off = 0
            for (seg in page.segments) {
                cur.write(page.body, off, seg)
                off += seg
                if (seg < 255) {
                    packets += cur.toByteArray()
                    cur.reset()
                    if (packets.size >= maxPackets) break
                }
            }
        }
        return packets
    }

    private fun le32(d: ByteArray, p: Int): Int =
        (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
            ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

    private fun le64(d: ByteArray, p: Int): Long =
        (le32(d, p).toLong() and 0xFFFFFFFFL) or (le32(d, p + 4).toLong() shl 32)
}

/** Minimal Ogg muxer, used by tests and for building fixtures. Each packet is placed on its own page(s); packets longer than a page are split with continuation. */
object OggWriter {
    fun write(packets: List<ByteArray>, serial: Int = 0x1234, pageDataLimit: Int = 255 * 255): ByteArray {
        val out = ByteArrayOutputStream()
        var seq = 0
        packets.forEachIndexed { idx, pkt ->
            // lacing for the packet
            val lacing = ArrayList<Int>()
            var rem = pkt.size
            while (rem >= 255) { lacing += 255; rem -= 255 }
            lacing += rem
            var segIdx = 0
            var dataPos = 0
            var continued = false
            while (segIdx < lacing.size) {
                val take = minOf(255, lacing.size - segIdx)
                val segs = lacing.subList(segIdx, segIdx + take)
                val len = segs.sum()
                var type = 0
                if (continued) type = type or 0x01
                if (idx == 0 && segIdx == 0) type = type or 0x02
                val last = segIdx + take == lacing.size
                if (last && idx == packets.size - 1) type = type or 0x04
                writePage(out, type, if (last) 0L else -1L, serial, seq++, segs, pkt, dataPos, len)
                dataPos += len
                segIdx += take
                continued = true
            }
        }
        return out.toByteArray()
    }

    private fun writePage(out: ByteArrayOutputStream, type: Int, granule: Long, serial: Int, seq: Int,
                          segs: List<Int>, src: ByteArray, srcPos: Int, len: Int) {
        val h = ByteArrayOutputStream()
        h.write("OggS".toByteArray(Charsets.ISO_8859_1))
        h.write(0)
        h.write(type)
        for (i in 0 until 8) h.write(((granule ushr (8 * i)) and 0xFF).toInt())
        for (i in 0 until 4) h.write((serial ushr (8 * i)) and 0xFF)
        for (i in 0 until 4) h.write((seq ushr (8 * i)) and 0xFF)
        for (i in 0 until 4) h.write(0) // crc placeholder
        h.write(segs.size)
        segs.forEach { h.write(it) }
        h.write(src, srcPos, len)
        val bytes = h.toByteArray()
        val crc = OggCrc.crc(bytes)
        for (i in 0 until 4) bytes[22 + i] = ((crc ushr (8 * i)) and 0xFF).toByte()
        out.write(bytes)
    }
}
