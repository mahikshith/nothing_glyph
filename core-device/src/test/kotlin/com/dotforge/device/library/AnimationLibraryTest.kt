package com.dotforge.device.library

import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationLibraryTest {
    private val p = DeviceProfile.Phone3
    private val all get() = AnimationLibrary.all

    @Test fun idsAreUnique() {
        val dupes = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate ids $dupes", dupes.isEmpty())
    }

    @Test fun atLeast300Items() {
        assertTrue("only ${all.size} items", all.size >= 300)
    }

    @Test fun everyItemLoadsInsideTheMask() {
        for (item in all) {
            val a = item.load(p)
            assertTrue("${item.id} has no frames", a.frames.isNotEmpty())
            for ((i, f) in a.frames.withIndex()) {
                assertTrue("${item.id} frame $i is ${item.load(p).frames[i].durationMs} ms", f.durationMs >= 16)
                for (cell in 0 until p.cellCount) {
                    val v = f.frame.pixels[cell]
                    assertTrue("${item.id} frame $i cell $cell value $v", v in 0..255)
                    if (!p.mask[cell]) assertEquals("${item.id} frame $i lights cell $cell outside the mask", 0, v)
                }
            }
        }
    }

    @Test fun generatedItemsHave24To120Frames() {
        for (item in all.filter { it.kind == ItemKind.GENERATOR || it.kind == ItemKind.ORIGINAL && it.form == ItemForm.ANIMATION }) {
            val n = item.load(p).frames.size
            assertTrue("${item.id} has $n frames", n in 24..120)
        }
    }

    @Test fun spriteConversionKeepsFrameCount() {
        val sprites = SpriteStore.entries
        assertTrue(sprites.size > 300)
        for (e in sprites.filter { it.sourceFrames > 0 }) {
            val item = AnimationLibrary.item(e.id)!!
            // a sprite merged into a same-named item resolves to that one
            if (item.id != e.id) continue
            assertEquals(e.id, e.sourceFrames, item.load(p).frames.size)
        }
        for (e in sprites.filter { it.sourceFrames == 0 }) assertTrue(e.id, AnimationLibrary.item(e.id)!!.load(p).frames.size > 1)
    }

    @Test fun spritesDrawSomething() {
        for (item in all.filter { it.kind == ItemKind.SPRITE }) {
            assertTrue(item.id, AnimationLibrary.thumbnail(item).pixels.any { it > 0 })
            assertTrue(item.id + " needs a credit", item.credit != null)
        }
    }

    @Test fun spritesResampleToOtherGrids() {
        val small = DeviceProfile("small", 13, 36)
        val a = AnimationLibrary.item("sprite-mini-heart")!!.load(small)
        assertTrue(a.frames.first().frame.pixels.any { it > 0 })
    }

    @Test fun generatorsAreDeterministic() {
        for (item in all.filter { it.kind != ItemKind.SPRITE }) {
            val a = item.load(p)
            assertEquals(item.id, a.toMuseumJson(), item.loadUncached(p).toMuseumJson())
        }
    }

    @Test fun searchFindsHeartAndRain() {
        assertTrue(AnimationLibrary.search("heart").size >= 3)
        assertTrue(AnimationLibrary.search("rain").any { it.name.contains("rain", ignoreCase = true) })
        assertTrue(AnimationLibrary.search("zzzzqq").isEmpty())
    }

    @Test fun featuredIsCuratedAndFirst() {
        assertEquals(Categories.Featured, AnimationLibrary.categories.first())
        val featured = AnimationLibrary.items(Categories.Featured)
        assertTrue("featured has ${featured.size}", featured.size in 30..50)
        assertEquals(featured.size, featured.map { it.id }.toSet().size)
        for (item in featured) {
            assertTrue(item.id, item.category != Categories.Featured && item.category in AnimationLibrary.categories)
            assertTrue(item.id, AnimationLibrary.items(item.category).contains(item))
        }
    }

    @Test fun everyCategoryHasItems() {
        for (c in AnimationLibrary.categories) assertTrue(c.title, AnimationLibrary.items(c).isNotEmpty())
    }

    @Test fun nothingIsBlank() {
        for (item in all) assertTrue(item.id, AnimationLibrary.thumbnail(item).pixels.any { it > 0 })
    }

    @Test fun retiredIdsStillFindAPicture() {
        for (id in listOf("g-anime-uwu", "a-anime-blink", "g-anime-cat", "g-confetti", "g-road", "g-heart-rain")) {
            assertTrue(id, AnimationLibrary.item(id) != null)
        }
        assertEquals("g-anime-sweat-drop", AnimationLibrary.item("g-anime-sweat")?.id)
    }

    @Test fun newYearFireworksShowTheComingYear() {
        assertEquals(2027, GlyphArt.newYear(java.time.LocalDate.of(2026, 11, 19)))
        assertEquals(2027, GlyphArt.newYear(java.time.LocalDate.of(2027, 1, 1)))
        assertEquals("New Year fireworks", AnimationLibrary.item("g-newyear-2027")?.name)
    }
}
