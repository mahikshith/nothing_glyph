package com.dotforge.composition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import kotlin.random.Random

class RingtoneWriterTest {
    private val serial = 0x5EED

    /** One page holding whole [packets] (each < 255 * 255 bytes in total). */
    private fun page(type: Int, granule: Long, seq: Int, vararg packets: ByteArray): ByteArray {
        val lacing = ArrayList<Int>()
        for (p in packets) {
            var rem = p.size
            while (rem >= 255) { lacing += 255; rem -= 255 }
            lacing += rem
        }
        val body = ByteArrayOutputStream().also { o -> packets.forEach { o.write(it) } }.toByteArray()
        return OggPage(type, granule, serial, seq, lacing.toIntArray(), body).toBytes()
    }

    private val audio = List(40) { i -> ByteArray(60 + (i * 37) % 300) { (i * 7 + it).toByte() } }

    private fun opusHead() = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    /** OpusHead page, OpusTags page, then audio pages with 4 packets each and growing granules; last page EOS. */
    private fun syntheticOpus(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(page(0x02, 0, 0, opusHead()))
        o.write(page(0, 0, 1, VorbisComments.buildOpusPacket(OggTags("libopus test", listOf("ARTIST" to "Someone", "TITLE" to "old")))))
        val chunks = audio.chunked(4)
        chunks.forEachIndexed { i, c -> o.write(page(if (i == chunks.lastIndex) 0x04 else 0, 960L * 4 * (i + 1), 2 + i, *c.toTypedArray())) }
        return o.toByteArray()
    }

    private fun audioPackets(ogg: ByteArray, headers: Int) = OggReader.readPackets(ogg).drop(headers)

    private fun rows(n: Int, cols: Int, seed: Int = 1): List<IntArray> {
        val r = Random(seed)
        return List(n) { IntArray(cols) { if (r.nextInt(3) == 0) r.nextInt(4096) else 0 } }
    }

    @Test fun repagerKeepsAudioAndRenumbersPages() {
        val src = syntheticOpus()
        val bigValue = "x".repeat(150_000) // forces OpusTags across three pages
        val out = OggRepager.retag(src) { OggTags(it.vendor, it.comments + ("BIG" to bigValue)) }

        val pages = OggReader.readPages(out)
        assertEquals(pages.size, OggReader.verifyCrc(out))
        pages.forEachIndexed { i, p -> assertEquals(i, p.sequence) }
        assertTrue(pages.first().isFirst)
        assertTrue(pages.last().isLast)
        assertEquals(1, pages.count { it.isFirst })
        // OpusHead page + 3 comment pages + 10 audio pages
        assertEquals(1 + 3 + 10, pages.size)
        assertEquals(-1L, pages[1].granule)
        assertEquals(0L, pages[3].granule)
        assertEquals(OggReader.readPages(src).drop(2).map { it.granule }, pages.drop(4).map { it.granule })

        val before = audioPackets(src, 2)
        val after = audioPackets(out, 2)
        assertEquals(before.size, after.size)
        before.indices.forEach { assertArrayEquals(before[it], after[it]) }
        assertArrayEquals(opusHead(), OggReader.readPackets(out, 1)[0])

        val tags = VorbisComments.parseOgg(out)!!
        assertEquals("libopus test", tags.vendor)
        assertEquals(bigValue, tags.first("BIG"))
        assertEquals("Someone", tags.first("ARTIST"))
    }

    @Test fun repagerRoundTripIsStable() {
        val once = OggRepager.retag(syntheticOpus()) { it }
        val twice = OggRepager.retag(once) { it }
        assertArrayEquals(once, twice)
        assertEquals(OggReader.readPages(once).size, OggReader.verifyCrc(once))
    }

    @Test fun crcCheckCatchesCorruption() {
        val out = OggRepager.retag(syntheticOpus()) { it }
        out[out.size - 3] = (out[out.size - 3] + 1).toByte()
        assertTrue(runCatching { OggReader.verifyCrc(out) }.isFailure)
    }

    @Test fun repagerHandlesVorbisWithCommentAndSetupSharingAPage() {
        val id = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23) { 3 }
        val comment = VorbisComments.buildVorbisPacket(OggTags("Xiph", listOf("TITLE" to "v")))
        val setup = byteArrayOf(5) + "vorbis".toByteArray() + ByteArray(700) { 9 }
        val o = ByteArrayOutputStream()
        o.write(page(0x02, 0, 0, id))
        o.write(page(0, 0, 1, comment, setup))
        o.write(page(0, 2048, 2, *audio.take(5).toTypedArray()))
        o.write(page(0x04, 4096, 3, *audio.drop(5).take(5).toTypedArray()))
        val src = o.toByteArray()
        assertEquals(OggRepager.Codec.VORBIS, OggRepager.codecOf(src))

        val rows = rows(30, 15)
        val out = GlyphRingtone.write(src, "Converted", rows)
        assertEquals(OggReader.readPages(out).size, OggReader.verifyCrc(out))
        val packets = OggReader.readPackets(out)
        assertArrayEquals(setup, packets[2])
        audio.take(10).forEachIndexed { i, p -> assertArrayEquals(p, packets[3 + i]) }
        val info = RingtoneParser.parse(out)
        assertEquals(15, info.columnCount)
        rows.indices.forEach { assertArrayEquals(rows[it], info.rows!![it]) }
    }

    @Test fun audioOnAHeaderPageIsRefused() {
        val o = ByteArrayOutputStream()
        o.write(page(0x02, 0, 0, opusHead()))
        o.write(page(0x04, 960, 1, VorbisComments.buildOpusPacket(OggTags("v", emptyList())), audio[0]))
        assertFalse(OggRepager.canRetag(o.toByteArray()))
        assertFalse(OggRepager.canRetag(ByteArray(100)))
    }

    @Test fun writerOutputReadsBackWithIdenticalAuthor() {
        val rows = rows(240, 625, seed = 3)
        val out = GlyphRingtone.write(syntheticOpus(), "My tone", rows)
        assertEquals(OggReader.readPages(out).size, OggReader.verifyCrc(out))

        val info = RingtoneParser.parse(out)
        assertNull(info.authorError)
        assertEquals("v1-Metroid Glyph Composer", info.composer)
        assertTrue(info.composer!!.contains("Glyph Composer"))
        assertEquals("625cols", info.custom2)
        assertEquals("My tone", info.tags!!.first("TITLE"))
        assertEquals("DotForge", info.tags!!.first("ALBUM"))
        assertEquals("Someone", info.tags!!.first("ARTIST"))
        assertEquals(1, info.tags!!.all("TITLE").size)
        assertEquals(240, info.rowCount)
        rows.indices.forEach { assertArrayEquals(rows[it], info.rows!![it]) }
        // audio untouched
        val before = audioPackets(syntheticOpus(), 2)
        val after = audioPackets(out, 2)
        before.indices.forEach { assertArrayEquals(before[it], after[it]) }
        // the same AUTHOR text as the encoder gives
        assertEquals(GlyphAuthor.encodeComposer(rows), info.tags!!.first("AUTHOR"))
    }

    @Test fun authorIsComposerStyle() {
        val author = GlyphAuthor.encodeComposer(listOf(intArrayOf(0, 4095, 9000, -5), intArrayOf(1, 2, 3, 4)))
        assertFalse(author.contains('='))
        assertTrue(author.lines().all { it.length <= 76 })
        val csv = String(InflaterInputStream(GlyphAuthor.base64Decode(author).inputStream()).readBytes())
        assertEquals("0,4095,4095,0,\r\n1,2,3,4,\r\n", csv)
    }

    @Test fun custom1IsAnEmptyZlibStream() {
        val bytes = GlyphAuthor.base64Decode(GlyphAuthor.EMPTY_ZLIB)
        assertEquals(0x78, bytes[0].toInt() and 0xFF)
        assertEquals(0, InflaterInputStream(bytes.inputStream()).readBytes().size)
        assertFalse(GlyphAuthor.EMPTY_ZLIB.contains('='))
    }

    @Test fun importKindFollowsColumnCount() {
        fun info(rows: List<IntArray>?) = ComposerInfo("x Glyph Composer", null, null, null, rows)
        assertTrue(ImportKind.of(info(rows(3, 625))) is ImportKind.Phone3)
        val other = ImportKind.of(info(rows(3, 33)))
        assertTrue(other is ImportKind.OtherPhone)
        assertEquals(33, (other as ImportKind.OtherPhone).columns)
        assertEquals(ImportKind.AudioOnly, ImportKind.of(info(null)))
        assertEquals(ImportKind.AudioOnly, ImportKind.of(null))
        assertEquals(ImportKind.AudioOnly, ImportKind.of(info(listOf(IntArray(0)))))
        assertEquals("Phone (2)", GlyphRingtone.phoneFor(33))
        assertNotNull(GlyphRingtone.phoneFor(36))
    }

    @Test fun levelsMapBothWays() {
        assertEquals(255, GlyphAuthor.toLevel(4095))
        assertEquals(0, GlyphAuthor.toLevel(0))
        assertEquals(4095, GlyphAuthor.fromLevel(255))
        for (l in 0..255) assertEquals(l, GlyphAuthor.toLevel(GlyphAuthor.fromLevel(l)))
    }
}
