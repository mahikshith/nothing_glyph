package com.dotforge.composition

import org.junit.Assert.*
import org.junit.Test

class CompositionTest {
    private fun rows(n: Int, cols: Int) = List(n) { r -> IntArray(cols) { c -> (r * 31 + c) % 256 } }

    @Test fun authorRoundTrip() {
        val r = rows(50, 625)
        val back = GlyphAuthor.decode(GlyphAuthor.encode(r))
        assertEquals(50, back.size)
        for (i in r.indices) assertArrayEquals(r[i], back[i])
    }

    @Test fun authorToleratesPaddingAndLineBreaks() {
        val enc = GlyphAuthor.encode(rows(5, 40))
        val noPad = enc.trimEnd('=')
        val broken = noPad.chunked(16).joinToString("\r\n")
        assertEquals(5, GlyphAuthor.decode(broken).size)
        assertEquals(40, GlyphAuthor.decode(noPad)[0].size)
    }

    @Test fun csvTrailingCommaAndCrlf() {
        val r = GlyphAuthor.parseCsv("1,2,3,\r\n4,5,6,\r\n\r\n")
        assertEquals(2, r.size)
        assertArrayEquals(intArrayOf(1, 2, 3), r[0])
        assertArrayEquals(intArrayOf(4, 5, 6), r[1])
    }

    @Test fun oggVorbisRoundTrip() {
        val author = GlyphAuthor.encode(rows(120, 625))
        val tags = OggTags("test vendor", listOf("TITLE" to "x", "COMPOSER" to "Me", "CUSTOM1" to "a", "CUSTOM2" to "b", "AUTHOR" to author))
        val ogg = OggWriter.write(listOf(
            byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23),
            VorbisComments.buildVorbisPacket(tags),
            ByteArray(300) { 7 }, ByteArray(100) { 9 },
        ))
        val info = RingtoneParser.parse(ogg)
        assertEquals("Me", info.composer)
        assertEquals("a", info.custom1)
        assertEquals("b", info.custom2)
        assertEquals(625, info.columnCount)
        assertEquals(120, info.rowCount)
        assertEquals(2.0, info.durationSeconds, 1e-9)
        assertNull(info.authorError)
    }

    @Test fun oggOpusLargeCommentSpansPages() {
        // comment packet > 64 KB so it must be continued across several pages
        val big = "z".repeat(200_000)
        val tags = OggTags("v", listOf("COMPOSER" to "Opus", "BIG" to big))
        val ogg = OggWriter.write(listOf("OpusHead".toByteArray() + ByteArray(11), VorbisComments.buildOpusPacket(tags), ByteArray(50)))
        assertTrue(OggReader.readPages(ogg).size > 3)
        val info = RingtoneParser.parse(ogg)
        assertEquals("Opus", info.composer)
        assertEquals(big, info.tags!!.first("BIG"))
        assertNull(info.rows)
    }

    @Test fun readsPagesWithValidCrcAndFlags() {
        val ogg = OggWriter.write(listOf(ByteArray(10), ByteArray(10)))
        val pages = OggReader.readPages(ogg)
        assertEquals(2, pages.size)
        assertTrue(pages[0].isFirst)
        assertTrue(pages[1].isLast)
    }

    @Test fun truncatedFileDoesNotThrow() {
        val ogg = OggWriter.write(listOf(ByteArray(10), VorbisComments.buildVorbisPacket(OggTags("v", emptyList()))))
        val info = RingtoneParser.parse(ogg.copyOf(40))
        assertNotNull(info.authorError)
    }

    @Test fun badAuthorReportsError() {
        val tags = OggTags("v", listOf("AUTHOR" to "!!!notbase64???"))
        val ogg = OggWriter.write(listOf(byteArrayOf(1) + "vorbis".toByteArray(), VorbisComments.buildVorbisPacket(tags)))
        assertNotNull(RingtoneParser.parse(ogg).authorError)
    }
}
