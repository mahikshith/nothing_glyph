package com.dotforge.device.library

import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotFont
import com.dotforge.device.DotFonts
import com.dotforge.device.GlyphLibrary
import com.dotforge.device.TextBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Library step's content: new glyph categories, text designs and glyph + text designs. */
class LibraryContentTest {
    private val p = DeviceProfile.Phone3
    private val all = AnimationLibrary.all
    private val glyphCategories = with(Categories) { listOf(Anime, Utility, Love, Heartbreak, Moods, Seasonal, Sports, Food, Travel, Work, Time, NatureWeather) }
    private val art = all.filter { it.kind == ItemKind.GLYPH }

    @Test fun atLeast150GlyphsAnd40OfThemMove() {
        // a still and its motions count once: one item that moves, with the still as its poster
        val moving = art.filter { it.form == ItemForm.ANIMATION }
        assertTrue("only ${art.size} glyphs", art.size >= 150)
        assertTrue("only ${moving.size} animated glyphs", moving.size >= 40)
        for (c in glyphCategories) {
            val mine = art.filter { it.category == c }
            assertTrue("${c.title} has ${mine.size} glyphs", mine.size >= 10)
            assertTrue("${c.title} has no animation", mine.any { it.animated })
        }
    }

    /** Two tiles in one category never show the same picture (the festival banners all opened on HAPPY). */
    @Test fun noTwoPostersInACategoryAreTheSame() {
        val p = com.dotforge.device.DeviceProfile.Phone3
        for (c in Categories.all.filter { it != Categories.Featured }) {
            val same = AnimationLibrary.items(c).groupBy { AnimationLibrary.thumbnail(it, p).pixels.toList() }.values.filter { it.size > 1 }
            org.junit.Assert.assertTrue("${c.title}: ${same.map { g -> g.map { it.id } }}", same.isEmpty())
        }
        System.getProperty("screens.out")?.let { dir ->
            val out = StringBuilder()
            for (id in listOf("text-hello", "text-mwah", "text-cheers", "text-away")) {
                val f = AnimationLibrary.thumbnail(AnimationLibrary.item(id)!!, p)
                out.append(id).append('\n')
                for (y in 0 until p.size) out.append((0 until p.size).joinToString("") { x -> if (f.pixels[y * p.size + x] > 0) "#" else "." }).append('\n')
            }
            java.io.File(dir, "banners.txt").writeText(out.toString())
        }
    }

    @Test fun categoryCounts() {
        val counts = Categories.all.associateWith { AnimationLibrary.items(it).size }
        for ((c, n) in counts) assertTrue("${c.title} is empty", n > 0)
        assertEquals(Categories.Featured, Categories.all.first())
        // every item sits in exactly one real category, and the counts add up
        assertEquals(all.size, Categories.all.filter { it != Categories.Featured }.sumOf { counts.getValue(it) })
    }

    @Test fun iconsTheFeaturesNeedExist() {
        for (id in listOf("g-food-bag", "g-grocery-basket", "g-parcel", "g-car", "g-bike", "g-bell", "g-hourglass")) {
            // the features draw the still; the library may show it moving, with the still as its poster
            assertNotNull(id, AnimationLibrary.item(id))
            assertEquals(id, true, GlyphArt.entry(id)?.still)
        }
    }

    @Test fun glyphArtStaysInsideTheCircleAndIsTagged() {
        for (e in GlyphArt.entries) {
            for ((i, f) in e.frames.withIndex()) assertEquals("${e.id} frame $i lights dots outside the circle", 0, GlyphArt.outside(f))
            assertTrue("${e.id} has too few tags", e.tags.size >= 2)
        }
        for (item in art) assertTrue("${item.id} is dark", AnimationLibrary.thumbnail(item, p).pixels.count { it > 0 } >= 12)
    }

    @Test fun motionsUseKnownNamesAndPictures() {
        for (e in GlyphArt.entries.filter { it.motion.isNotEmpty() }) {
            assertTrue("${e.id}: ${e.motion[0]}", e.motion[0] in GlyphArt.Motion.names)
            for (id in e.motion.drop(1).filter { !it.first().isDigit() }) assertNotNull("${e.id} uses $id", GlyphArt.entry(id))
            assertTrue(e.id, GlyphArt.load(e, p).frames.size > 1)
        }
    }

    @Test fun textCategoryHasEveryGroupAndSixtyDesigns() {
        val texts = AnimationLibrary.items(Categories.Text).filter { it.form == ItemForm.TEXT }
        assertTrue("only ${texts.size} text designs", texts.size >= 60)
        for (g in TextItems.groups) assertTrue("no $g texts", texts.count { it.group == g } >= 5)
        for (c in glyphCategories) assertTrue("${c.title} has no matching text", AnimationLibrary.items(c).any { it.form == ItemForm.TEXT })
        val unknown = DotFonts.glyph(DotFont.REGULAR, '\u0001')
        for (t in all.filter { it.form == ItemForm.TEXT }) {
            val spec = t.text!!
            for (ch in spec.text.filter { it != ' ' }) assertTrue("${t.id}: '$ch' has no letter", DotFonts.glyph(DotFont.REGULAR, ch).px.toList() != unknown.px.toList() || ch == '?')
            assertTrue(t.id, t.load(p).frames.size > 1 || spec.style == com.dotforge.device.TextStyle.FIT)
        }
    }

    @Test fun glyphPlusTextDesignsKeepTheirLayers() {
        val layered = AnimationLibrary.items(Categories.GlyphText)
        assertTrue("only ${layered.size} glyph + text designs", layered.size >= 40)
        for (item in layered) {
            assertEquals(ItemForm.LAYERED, item.form)
            val layers = item.layers(p)!!
            assertNotNull(item.id, layers.background)
            assertEquals(item.id, TextBand.BOTTOM, layers.text!!.spec.band)
            assertEquals(item.id, item.name, layers.text!!.spec.text)
            assertTrue(item.id, item.load(p).frames.isNotEmpty())
        }
        for (want in listOf("I♥U", "HBD", "DIYA", "GN", "OMW", "FOOD", "MEMO", "BRB", "SIXER")) {
            assertTrue("missing $want", layered.any { it.name == want })
        }
    }

    @Test fun searchFindsNewItems() {
        assertEquals("g-food-bag", AnimationLibrary.search("food bag").first().id)
        assertTrue(AnimationLibrary.search("sixer").any { it.form == ItemForm.LAYERED })
        assertTrue(AnimationLibrary.search("hinglish").size >= 8)
        assertTrue(AnimationLibrary.search("kawaii").any { it.category == Categories.Anime })
        assertTrue(AnimationLibrary.search("diwali").size >= 3)
        assertTrue(AnimationLibrary.search("qqqzzz").isEmpty())
    }

    @Test fun alertsResolveLibraryGlyphs() {
        val g = GlyphLibrary.get("g-bell")
        assertNotNull(g)
        assertTrue(GlyphLibrary.alert(p, "g-bell").frames.isNotEmpty())
        assertTrue(GlyphLibrary.get("a-bell")!!.animated)
        // the built-in icons are still there and also listed in the library
        assertNotNull(GlyphLibrary.get("chat"))
        assertNotNull(AnimationLibrary.item("chat"))
    }
}
