package com.dotforge.device.library

import com.dotforge.device.Animation
import com.dotforge.device.Compositor
import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotFont
import com.dotforge.device.DotFonts
import com.dotforge.device.Frame
import com.dotforge.device.TextRender
import com.dotforge.device.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glyph + text layout, text posters and the letters that must stay legible. */
class TextLayoutTest {
    private val p = DeviceProfile.Phone3
    private val layered = AnimationLibrary.all.filter { it.form == ItemForm.LAYERED }
    private val texts = AnimationLibrary.all.filter { it.form == ItemForm.TEXT }

    private fun rowsLit(px: IntArray) = p.ledCells.filter { px[it] > 0 }.map { it / p.size }.toSet()

    @Test fun wordsInsideARingKeepOneDarkDotFromIt() {
        val ring = p.ring.toSet()
        val n = p.size
        val near = (0 until p.cellCount).filter { c ->
            c !in ring && (-1..1).any { dy -> (-1..1).any { dx -> val x = c % n + dx; val y = c / n + dy; x in 0 until n && y in 0 until n && (y * n + x) in ring } }
        }
        val ringed = texts.filter { it.text!!.border || it.text!!.style == TextStyle.MARQUEE }
        assertTrue("BUSY, ON A CALL, COME IN and MEMO have rings", ringed.size >= 4)
        for (item in ringed) for ((i, f) in item.load(p).frames.withIndex()) {
            val touching = near.filter { f.frame.pixels[it] > 0 }
            assertTrue("${item.id} frame $i lights $touching next to the ring", touching.isEmpty())
        }
    }

    @Test fun glyphTextPictureAndWordsKeepTheirRows() {
        val words = TextItems.WORD_ROWS
        for (item in layered) {
            val layers = item.layers(p)!!
            for ((i, f) in Compositor.background(p, layers.background!!).frames.withIndex()) {
                val bad = rowsLit(f.frame.pixels).filter { it in words }
                assertTrue("${item.id} picture frame $i lights rows $bad", bad.isEmpty())
            }
            for ((i, f) in Compositor.text(p, layers.text!!).frames.withIndex()) {
                val bad = rowsLit(f.frame.pixels).filter { it !in words }
                assertTrue("${item.id} words frame $i light rows $bad", bad.isEmpty())
            }
            // what plays and what the tile shows come from the same layout
            val shown = rowsLit(AnimationLibrary.thumbnail(item, p).pixels)
            assertTrue("${item.id} poster has no picture", shown.any { it in TextItems.PICTURE_ROWS })
            assertTrue("${item.id} poster has no words", shown.any { it in words })
            val playing = item.load(p).frames.map { it.frame.pixels }
            assertTrue("${item.id} plays its poster", playing.any { f -> rowsLit(f).any { it in words } })
        }
    }

    @Test fun glyphTextWordsAreShortStillAndDistinct() {
        val banned = setOf("ON", "AT", "I'M", "HAPPY", "MY", "IT'S", "LET'S", "NO", "THE", "A")
        val all = TextItems.wordsById
        assertEquals("every glyph + text word is different", all.size, all.values.toSet().size)
        for ((id, w) in all) {
            val parts = w.split('/')
            assertTrue("$id: at most two words in turn", parts.size <= 2)
            for (part in parts) {
                assertTrue("$id: '$part' is at most ${TextItems.MAX_WORD} characters", part.length <= TextItems.MAX_WORD)
                assertTrue("$id: '$part' says little on its own", part !in banned)
                assertTrue("$id: '$part' fits the band", DotFonts.width(DotFont.SMALL, part) <= TextItems.BAND_WIDTH)
            }
        }
        for (item in layered) {
            val st = item.layers(p)!!.text!!.spec.style
            assertTrue("${item.id} holds its words still", st == TextStyle.FIT || st == TextStyle.WORDS)
        }
    }

    @Test fun textPostersShowOnlyWholeWords() {
        for (item in texts) {
            val spec = item.text!!
            val words = spec.text.trim().split(Regex("\\s+")).toSet()
            val plan = TextRender.posterPlan(p, spec)
            assertTrue("${item.id}: no word of '${spec.text}' fits the Glyph whole", plan.fits)
            for (line in plan.lines) for (w in line.split(' ')) assertTrue("${item.id}: '$w' of ${plan.lines} is not a whole word of '${spec.text}'", w in words)
        }
    }

    @Test fun noTwoPostersAreTheSame() {
        val seen = HashMap<List<Int>, String>()
        for (item in texts + layered) {
            val key = AnimationLibrary.thumbnail(item, p).pixels.toList()
            val other = seen.put(key, item.id)
            assertTrue("${item.id} and $other have the same poster", other == null)
        }
    }

    @Test fun nAndMHaveTheirDiagonalsInEveryFont() {
        for (font in DotFont.entries) {
            val h = DotFonts.glyph(font, 'H')
            for (ch in listOf('M', 'N')) {
                val g = DotFonts.glyph(font, ch)
                fun row(y: Int) = (0 until g.w).map { g.lit(it, y) }
                assertNotEquals("$font $ch differs from H", (0 until h.h).map { y -> (0 until h.w).map { h.lit(it, y) } }, (0 until g.h).map { row(it) })
                // a diagonal: a dot inside the stems on two different rows, in two different columns
                val inner = (0 until g.h).flatMap { y -> (0 until g.w).filter { x -> g.lit(x, y) && x > stem(g, y, true) && x < stem(g, y, false) }.map { x -> x to y } }
                assertTrue("$font $ch has a diagonal", inner.map { it.first }.toSet().size >= 2 && inner.map { it.second }.toSet().size >= 2)
            }
        }
    }

    /**
     * The last column of the left stem (true) or the first of the right stem (false), read on the row above the
     * bottom one, where no serif foot spreads the stems.
     */
    private fun stem(g: com.dotforge.device.Strip, @Suppress("UNUSED_PARAMETER") y: Int, left: Boolean): Int {
        val row = g.h - 2
        val lit = (0 until g.w).filter { g.lit(it, row) }
        return if (left) (lit.first()..lit.last()).takeWhile { g.lit(it, row) }.last()
        else (lit.last() downTo lit.first()).takeWhile { g.lit(it, row) }.last()
    }

    @Test fun dump() {
        val out = System.getProperty("screens.out") ?: return
        val sb = StringBuilder()
        for (item in texts) {
            val spec = item.text!!
            val plan = TextRender.posterPlan(p, spec)
            sb.append("${item.id} | ${spec.text} | ${spec.style} ${spec.font} ${if (spec.border) "border" else ""} | ${plan.font} ${plan.lines} | ${item.load(p).totalMs} ms\n")
        }
        java.io.File(out).apply { mkdirs() }.resolve("text-plans.txt").writeText(sb.toString())
        // look-alike posters inside each category, most alike first
        val dup = StringBuilder()
        for (c in AnimationLibrary.categories.filter { it != Categories.Featured }) {
            val items = AnimationLibrary.items(c).filter { it.form != ItemForm.TEXT }
            val px = items.map { i -> AnimationLibrary.thumbnail(i, p).pixels }
            val pairs = ArrayList<Triple<Double, String, String>>()
            for (a in items.indices) for (b2 in a + 1 until items.size) {
                var diff = 0.0
                var tot = 0.0
                for (c in p.ledCells) { diff += kotlin.math.abs(px[a][c] - px[b2][c]); tot += maxOf(px[a][c], px[b2][c]) }
                val j = 1 - diff / tot.coerceAtLeast(1.0)
                if (j >= 0.8) pairs += Triple(j, "${items[a].name} [${items[a].id}]", "${items[b2].name} [${items[b2].id}]")
            }
            dup.append("== ${c.title}: ${items.size} items\n")
            for ((j, x, y) in pairs.sortedByDescending { it.first }) dup.append("  %.2f  %s  ~  %s\n".format(j, x, y))
        }
        java.io.File(out).resolve("lookalikes.txt").writeText(dup.toString())
        @Suppress("UNUSED_VARIABLE") val unused = Animation.single(Frame.blank(p), 1)
    }
}
