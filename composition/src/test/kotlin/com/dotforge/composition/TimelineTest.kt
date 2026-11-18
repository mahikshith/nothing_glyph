package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TimelineTest {
    private val p = DeviceProfile.Phone3
    private val centre = 12 * 25 + 12

    @Test fun keyShapes() {
        val hold = listOf(Key(0, 10, 50, Shape.HOLD), Key(1, 30, 0))
        assertEquals(0f, TimelineRenderer.level(hold, 9))
        assertEquals(0.5f, TimelineRenderer.level(hold, 10))
        assertEquals(0.5f, TimelineRenderer.level(hold, 29))
        assertEquals(0f, TimelineRenderer.level(hold, 30))

        val fade = listOf(Key(0, 0, 0, Shape.FADE), Key(1, 60, 100))
        assertEquals(0.5f, TimelineRenderer.level(fade, 30), 1e-6f)
        assertEquals(1f, TimelineRenderer.level(fade, 200), 1e-6f) // last key holds

        val pulse = listOf(Key(0, 100, 100, Shape.PULSE))
        assertEquals(1f, TimelineRenderer.level(pulse, 100))
        assertEquals(0.5f, TimelineRenderer.level(pulse, 112), 1e-6f)
        assertEquals(0f, TimelineRenderer.level(pulse, 100 + TimelineRenderer.PULSE_FRAMES))
        // unsorted keys work too
        assertEquals(0.5f, TimelineRenderer.level(hold.reversed(), 20))
    }

    @Test fun regionsCoverTheirParts() {
        val outline = TimelineRenderer.cells(Region.OUTLINE, 0, p)
        assertEquals(p.ring.size, outline.size)
        assertEquals(489, TimelineRenderer.cells(Region.WHOLE, 0, p).size)
        for (r in listOf(Region.RING, Region.ARC, Region.BAR)) {
            val parts = (0 until r.parts).map { TimelineRenderer.cells(r, it, p).toSet() }
            assertTrue(parts.all { it.isNotEmpty() })
            assertEquals(489, parts.sumOf { it.size }) // every LED in exactly one part
            assertEquals(489, parts.flatten().toSet().size)
        }
        assertTrue(centre in TimelineRenderer.cells(Region.RING, 0, p))
    }

    @Test fun renderToAuthorRows() {
        val t = Timeline(
            frames = 120,
            lanes = listOf(
                Lane(0, Region.WHOLE, keys = listOf(Key(0, 0, 25), Key(1, 60, 0))),
                Lane(1, Region.OUTLINE, keys = listOf(Key(0, 30, 100, Shape.PULSE))),
                Lane(2, Region.RING, part = 0, keys = listOf(Key(0, 90, 50))),
            ),
        )
        val rows = TimelineRenderer.render(t, null, p)
        assertEquals(120, rows.size)
        val edge = p.ring.first()
        assertEquals(4095 / 4, rows[10][centre])
        assertEquals(4095 / 4, rows[10][edge])
        assertEquals(4095, rows[30][edge]) // the brighter lane wins
        assertEquals(4095 / 4, rows[30][centre])
        assertEquals(0, rows[70][centre])
        assertEquals(4095 / 2, rows[100][centre])
        for (r in rows) for (c in 0 until 625) if (!p.mask[c]) assertEquals(0, r[c])
    }

    @Test fun importedLaneIsTrimmedAndScaled() {
        val imported = List(100) { IntArray(625) { c -> if (p.mask[c]) 2000 else 0 } }
        val t = Timeline(100, listOf(Lane(0, Region.IMPORTED, trimStart = 10, trimEnd = 50, scale = 1.5f)))
        val rows = TimelineRenderer.render(t, imported, p)
        assertEquals(0, rows[5][centre])
        assertEquals(3000, rows[10][centre])
        assertEquals(3000, rows[49][centre])
        assertEquals(0, rows[50][centre])
        val full = TimelineRenderer.render(Timeline.fromImported(imported), imported, p)
        assertArrayEquals(imported[99], full[99])
        assertEquals(2000, TimelineRenderer.render(t.copy(lanes = listOf(Lane(0, Region.IMPORTED, scale = 1f))), imported, p)[99][centre])
    }

    private fun sample(): Timeline {
        val imported = List(30) { f -> IntArray(625) { c -> if (p.mask[c]) (f * 100 + c) % 4096 else 0 } }
        return Timeline.fromImported(imported, 30).let {
            it.copy(lanes = it.lanes + Lane(1, Region.ARC, part = 2, keys = listOf(Key(0, 3, 70, Shape.FADE), Key(1, 20, 10, Shape.PULSE))))
        }
    }

    @Test fun custom1RoundTrip() {
        val t = sample()
        val text = TimelineCodec.encode(t)
        assertTrue(text.lines().all { it.length <= 76 })
        assertTrue(!text.contains('='))
        val back = TimelineCodec.decode(text)
        assertEquals(t, back)
        val rows = TimelineCodec.importedRows(back!!)!!
        assertEquals(30, rows.size)
        assertArrayEquals(TimelineCodec.importedRows(t)!![7], rows[7])
    }

    @Test fun custom1ThatIsNotOursIsIgnored() {
        assertNull(TimelineCodec.decode(GlyphAuthor.EMPTY_ZLIB))
        assertNull(TimelineCodec.decode(null))
        assertNull(TimelineCodec.decode(GlyphAuthor.packText("12,34,\r\n")))
        assertNull(TimelineCodec.decode(GlyphAuthor.packText("{\"frames\":1,\"lanes\":[],\"app\":\"Other\"}")))
        assertNull(TimelineCodec.decode("@@@ not base64"))
    }

    @Test fun timelineSurvivesTheWriterAndParser() {
        val t = sample()
        val rows = TimelineRenderer.render(t, TimelineCodec.importedRows(t), p)
        val o = ByteArrayOutputStream()
        o.write(OggPage(0x02, 0, 9, 0, intArrayOf(19), "OpusHead".toByteArray() + ByteArray(11)).toBytes())
        val tags = VorbisComments.buildOpusPacket(OggTags("v", emptyList()))
        o.write(OggPage(0, 0, 9, 1, intArrayOf(tags.size), tags).toBytes())
        o.write(OggPage(0x04, 960, 9, 2, intArrayOf(3), byteArrayOf(1, 2, 3)).toBytes())
        val file = GlyphRingtone.write(o.toByteArray(), "Edited", rows, TimelineCodec.encode(t))
        val info = RingtoneParser.parse(file)
        assertEquals(30, info.rowCount)
        rows.indices.forEach { assertArrayEquals(rows[it], info.rows!![it]) }
        val back = TimelineCodec.decode(info.custom1)
        assertNotNull(back)
        assertEquals(t, back)
        // re-rendering the stored timeline gives the same AUTHOR
        val again = TimelineRenderer.render(back!!, TimelineCodec.importedRows(back), p)
        rows.indices.forEach { assertArrayEquals(rows[it], again[it]) }
    }
}
