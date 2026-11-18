package com.dotforge.device

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fonts, text styles, photo to dots, GIF and layers. */
class StudioModelsTest {
    private val p = DeviceProfile.Phone3
    private fun lit(px: IntArray) = px.count { it > 0 }
    private fun litRows(px: IntArray) = px.indices.filter { px[it] > 0 }.map { it / p.size }.toSet()

    // ---- fonts

    @Test fun everyFontHasEveryLetterAndDigit() {
        for (font in DotFont.entries) for (ch in ('A'..'Z') + ('0'..'9')) {
            val g = DotFonts.glyph(font, ch)
            assertTrue("$font $ch", g.px.any { it })
            assertEquals("$font $ch height", DotFonts.height(font), DotFonts.strip(font, ch.toString()).h)
        }
    }

    @Test fun fontsDifferInSizeAndShape() {
        val w = DotFont.entries.associateWith { DotFonts.width(it, "HI") }
        assertTrue(w.getValue(DotFont.SMALL) < w.getValue(DotFont.REGULAR))
        assertTrue(w.getValue(DotFont.BOLD) > w.getValue(DotFont.REGULAR))
        assertEquals(w.getValue(DotFont.REGULAR) * 2, w.getValue(DotFont.BIG))
        assertTrue(w.getValue(DotFont.SERIF) > w.getValue(DotFont.TALL))
        assertEquals(7, DotFonts.height(DotFont.TALL))
        // Tall keeps H's crossbar one dot thick
        val h = DotFonts.glyph(DotFont.TALL, 'H')
        assertEquals(1, (0 until h.h).count { y -> (0 until h.w).all { x -> h.lit(x, y) } })
    }

    @Test fun symbolsAreFiveByFiveAndDrawInEveryFont() {
        assertTrue(DotFonts.SYMBOLS.size >= 25)
        assertEquals(DotFonts.SYMBOLS.size, DotFonts.SYMBOLS.map { it.char }.toSet().size)
        for (s in DotFonts.SYMBOLS) {
            assertTrue(s.name, s.rows.size == 5 && s.rows.all { it.length == 5 })
            for (font in DotFont.entries) assertTrue("${s.name} $font", DotFonts.glyph(font, s.char).px.any { it })
        }
        // a heart isn't the unknown-character block
        assertNotEquals(DotFonts.glyph(DotFont.REGULAR, '♥').px.count { it }, 25)
    }

    // ---- text

    @Test fun everyStyleMakesFramesInsideTheCircle() {
        for (style in TextStyle.entries) {
            val a = TextRender.render(p, TextSpec("HI THERE", style = style))
            assertTrue("$style frames", a.frames.isNotEmpty())
            assertTrue("$style lights", a.frames.any { lit(it.frame.pixels) > 0 })
            for (f in a.frames) for (c in 0 until p.cellCount) if (!p.mask[c]) assertEquals(0, f.frame.pixels[c])
        }
        assertTrue(TextStyle.entries.size >= 16)
    }

    @Test fun scrollGoesTheChosenWay() {
        fun firstLitX(px: IntArray) = px.indices.filter { px[it] > 0 }.minOfOrNull { it % p.size }
        val left = TextRender.render(p, TextSpec("A", direction = TextDirection.LEFT, pauseMs = 0))
        val right = TextRender.render(p, TextSpec("A", direction = TextDirection.RIGHT, pauseMs = 0))
        val l = left.frames.mapNotNull { firstLitX(it.frame.pixels) }
        val r = right.frames.mapNotNull { firstLitX(it.frame.pixels) }
        assertTrue(l.first() > l.last())
        assertTrue(r.first() < r.last())
        fun firstLitY(px: IntArray) = px.indices.filter { px[it] > 0 }.minOfOrNull { it / p.size }
        val up = TextRender.render(p, TextSpec("A", direction = TextDirection.UP)).frames.mapNotNull { firstLitY(it.frame.pixels) }
        assertTrue(up.first() > up.last())
        // back and forth comes back to where it started
        val bounce = TextRender.render(p, TextSpec("HELLO THERE", direction = TextDirection.BOUNCE)).frames
        assertArrayEquals(bounce.first().frame.pixels, bounce.last().frame.pixels)
    }

    @Test fun aroundTheRingLightsOnlyTheOuterBand() {
        val a = TextRender.render(p, TextSpec("HELLO", direction = TextDirection.RING))
        val c = (p.size - 1) / 2.0
        var any = false
        for (f in a.frames) for (cell in p.ledCells) if (f.frame.pixels[cell] > 0) {
            any = true
            assertTrue(kotlin.math.hypot(cell % p.size - c, cell / p.size - c) > 6.5)
        }
        assertTrue(any)
    }

    @Test fun theBottomBandKeepsLettersInItsRows() {
        val a = TextRender.render(p, TextSpec("GO", style = TextStyle.FIT, band = TextBand.BOTTOM))
        assertTrue(litRows(a.frames[0].frame.pixels).all { it in TextBand.BOTTOM.rows })
    }

    @Test fun fitUsesTheBiggestFontThatFitsAndWrapsLongText() {
        val big = TextRender.render(p, TextSpec("HI", style = TextStyle.FIT)).frames.single().frame.pixels
        assertTrue(litRows(big).size >= 10)
        val long = TextRender.render(p, TextSpec("GOOD NIGHT ALL", style = TextStyle.FIT)).frames.single().frame.pixels
        assertTrue(litRows(long).size >= 12) // three lines of Small
    }

    @Test fun countdownCountsFromTheNumber() {
        val a = TextRender.render(p, TextSpec("5", style = TextStyle.COUNTDOWN))
        // 5,4,3,2,1,0 each shown then dimmed, then a burst
        assertTrue(a.frames.count { it.durationMs == 850 } == 6)
    }

    @Test fun longWordsSplitIntoChunksThatFit() {
        val chunks = TextRender.chunks(DotFont.REGULAR, "SUPERCALIFRAGILISTIC HI", 20, 1)
        assertTrue(chunks.all { DotFonts.width(DotFont.REGULAR, it, 1) <= 20 })
        assertEquals("SUPERCALIFRAGILISTIC HI".replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test fun lookOptions() {
        val plain = TextRender.poster(p, TextSpec("A"))
        val border = TextRender.poster(p, TextSpec("A", border = true))
        assertTrue(p.ring.all { border.pixels[it] == 255 })
        val inverted = TextRender.poster(p, TextSpec("A", invert = true))
        assertTrue(lit(inverted.pixels) > p.ledCount / 2)
        val outline = TextRender.poster(p, TextSpec("A", outline = true))
        assertTrue(lit(outline.pixels) > lit(plain.pixels))
    }

    @Test fun textSpecRoundTrips() {
        val s = TextSpec("I ♥ U", DotFont.SERIF, TextStyle.WAVE, TextDirection.RIGHT, outline = true, band = TextBand.TOP)
        assertEquals(s, Json.decodeFromString(TextSpec.serializer(), Json.encodeToString(TextSpec.serializer(), s)))
    }

    // ---- photo

    private val pic = PhotoDots.samplePicture()

    @Test fun everyPhotoStyleLightsDotsAndTheyDiffer() {
        val samples = PhotoDots.sample(p, pic, PhotoCrop())
        val frames = PhotoStyle.entries.associateWith { PhotoDots.render(p, samples, PhotoSettings(style = it)).pixels.toList() }
        assertEquals(13, frames.size)
        frames.forEach { (s, px) -> assertTrue("$s", px.any { it > 0 }) }
        assertTrue(frames.values.toSet().size >= 12)
        // the on/off styles only use full dots
        for (s in listOf(PhotoStyle.TONES2, PhotoStyle.FINE_DITHER, PhotoStyle.ATKINSON, PhotoStyle.PATTERN, PhotoStyle.HALFTONE, PhotoStyle.SILHOUETTE, PhotoStyle.OUTLINE)) {
            assertTrue("$s", frames.getValue(s).all { it == 0 || it == 255 })
        }
        // soft shades has at most the chosen number of levels
        val soft = PhotoDots.render(p, samples, PhotoSettings(style = PhotoStyle.SOFT_SHADES, shades = 4)).pixels.filterIndexed { i, _ -> p.mask[i] }.toSet()
        assertTrue(soft.size <= 4)
    }

    @Test fun autoLevelsStretchADarkPhoto() {
        val dark = Picture(50, 50, IntArray(2500) { i -> val v = 10 + (i % 50) / 2; (0xFF shl 24) or (v shl 16) or (v shl 8) or v })
        val f = PhotoDots.frame(p, dark, PhotoSettings(detail = 0))
        assertTrue(f.pixels.max() > 230)
    }

    @Test fun adjustmentsChangeTheResult() {
        val base = PhotoDots.frame(p, pic, PhotoSettings()).pixels
        assertTrue(PhotoDots.frame(p, pic, PhotoSettings(brightness = 60)).pixels.sum() > base.sum())
        assertTrue(PhotoDots.frame(p, pic, PhotoSettings(brightness = -60)).pixels.sum() < base.sum())
        val inv = PhotoDots.frame(p, pic, PhotoSettings(invert = true)).pixels
        assertTrue(p.ledCells.count { (base[it] > 128) != (inv[it] > 128) } > p.ledCount / 2)
        val turned = PhotoDots.frame(p, pic, PhotoSettings(crop = PhotoCrop(turns = 1))).pixels
        assertFalse(turned.contentEquals(base))
        val removed = PhotoDots.frame(p, pic, PhotoSettings(background = PhotoBackground.REMOVE)).pixels
        assertTrue(lit(removed) < lit(base))
    }

    @Test fun photoMotionMakesFrames() {
        for (m in PhotoMotion.entries) {
            val a = PhotoDots.motion(p, pic, PhotoSettings(), m, count = 8)
            assertEquals("$m", 8, a.frames.size)
            assertTrue("$m moves", a.frames.map { it.frame.pixels.toList() }.toSet().size > 1)
        }
    }

    // ---- GIF

    @Test fun gifRoundTrips() {
        val a = Animation(p, listOf(
            AnimFrame(Frame.of(p, IntArray(p.cellCount) { if (it % 3 == 0) 255 else 0 }), 200),
            AnimFrame(Frame.of(p, IntArray(p.cellCount) { if (it % 2 == 0) 128 else 0 }), 100),
        ))
        val bytes = Gif.encode(a, cell = 4)
        val frames = Gif.decode(bytes)
        assertEquals(2, frames.size)
        assertEquals(2, Gif.count(bytes))
        assertEquals(0, Gif.count("hello".toByteArray()))
        assertEquals(listOf(200, 100), frames.map { it.delayMs })
        // the middle of each lit dot is bright, of each dark dot is not
        val side = p.size * 4
        val pic0 = frames[0].picture
        assertEquals(side, pic0.width)
        for (cell in p.ledCells.take(60)) {
            val cx = cell % p.size * 4 + 2
            val cy = cell / p.size * 4 + 2
            val v = pic0.argb[cy * side + cx] and 0xFF
            if (cell % 3 == 0) assertTrue(v > 200) else assertTrue(v < 80)
        }
        // and the decoded GIF becomes the same dots again
        val back = PhotoDots.frame(p, pic0, PhotoSettings(style = PhotoStyle.TONES2, detail = 0))
        assertTrue(p.ledCells.count { (back.pixels[it] > 0) == (it % 3 == 0) } > p.ledCount * 0.9)
    }

    @Test(expected = Gif.GifException::class)
    fun notAGif() {
        Gif.decode("hello".toByteArray())
    }

    // ---- layers

    private fun heartJson() = Animation.single(Frame.of(p, IntArray(p.cellCount) { 255 })).toMuseumJson()

    @Test fun cutOutTurnsOffTheBackgroundRoundEachLetter() {
        val bg = IntArray(p.cellCount) { if (p.mask[it]) 200 else 0 }
        val text = TextRender.poster(p, TextSpec("I", style = TextStyle.FIT, band = TextBand.MIDDLE)).pixels
        val cut = Compositor.compose(p, BehindText.CUT_OUT, TextBand.MIDDLE, bg, text, null)
        val n = p.size
        for (c in 0 until p.cellCount) {
            if (!p.mask[c]) continue
            val x = c % n
            val y = c / n
            val nearText = (-1..1).any { dy -> (-1..1).any { dx -> val xx = x + dx; val yy = y + dy; xx in 0 until n && yy in 0 until n && text[yy * n + xx] > 0 } }
            when {
                text[c] > 0 -> assertEquals(255, cut[c])
                nearText -> assertEquals(0, cut[c])
                else -> assertEquals(200, cut[c])
            }
        }
        val dim = Compositor.compose(p, BehindText.DIM, TextBand.MIDDLE, bg, text, null)
        assertTrue(TextBand.MIDDLE.rows.all { y -> (0 until n).all { x -> val c = y * n + x; !p.mask[c] || text[c] > 0 || dim[c] == 60 } })
        val clear = Compositor.compose(p, BehindText.CLEAR, TextBand.MIDDLE, bg, text, null)
        assertTrue(p.ledCells.all { clear[it] == if (text[it] > 0) 255 else 200 })
    }

    @Test fun layersLoopOnTheirOwnAndTheDesignLastsTheLongest() {
        val bg = BackgroundLayer(Animation(p, listOf(AnimFrame(Frame.of(p, IntArray(p.cellCount) { 100 }), 300), AnimFrame(Frame.blank(p), 300))).toMuseumJson(), "Blink", BackgroundEffect.PLAY)
        val layers = Layers(background = bg, text = TextLayer(TextSpec("HI", band = TextBand.BOTTOM)))
        val textLen = Compositor.text(p, layers.text!!).totalMs
        val out = Compositor.render(p, layers, Animation.single(Frame.blank(p)))
        assertEquals(maxOf(600, textLen), out.totalMs)
        // at 300-600 ms (and every other 300 ms) the background is dark
        fun bgAt(t: Int) = Compositor.frameAt(p, layers.copy(text = null), Animation.single(Frame.blank(p)), t)
        assertTrue(lit(bgAt(100)) > 0)
        assertEquals(0, lit(bgAt(400)))
        assertTrue(lit(bgAt(700)) > 0)
        // a set length wins
        assertEquals(2000, Compositor.render(p, layers.copy(lengthMs = 2000), Animation.single(Frame.blank(p))).totalMs)
    }

    @Test fun flattenKeepsWhatPlays() {
        val layers = Layers(background = BackgroundLayer(heartJson(), "Full", BackgroundEffect.BREATHE), text = TextLayer(TextSpec("GN", band = TextBand.BOTTOM)))
        val doc = EditorDoc(p, Animation.single(Frame.blank(p), 150), layers = layers)
        val before = doc.rendered()
        doc.flatten()
        assertEquals(null, doc.layers)
        val after = doc.animation()
        assertEquals(before.frames.size, after.frames.size)
        before.frames.indices.forEach { assertArrayEquals(before.frames[it].frame.pixels, after.frames[it].frame.pixels) }
        doc.undo()
        assertEquals(layers, doc.layers)
    }

    @Test fun layersRoundTripAndUndo() {
        val layers = Layers(BackgroundLayer(heartJson(), "Heart", BackgroundEffect.ZOOM, 80), TextLayer(TextSpec("I ♥ U", band = TextBand.BOTTOM), 90), behind = BehindText.DIM)
        val json = Json.encodeToString(Layers.serializer(), layers)
        assertEquals(layers, Json.decodeFromString(Layers.serializer(), json))
        val doc = EditorDoc(p, Animation.single(Frame.blank(p), 150))
        doc.setLayers(layers)
        doc.setLayers(layers.copy(behind = BehindText.CUT_OUT))
        doc.undo()
        assertEquals(BehindText.DIM, doc.layers?.behind)
        doc.undo()
        assertEquals(null, doc.layers)
        doc.redo()
        assertEquals(layers, doc.layers)
    }
}
