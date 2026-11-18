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
        for (item in all.filter { it.kind != ItemKind.SPRITE }) {
            val n = item.load(p).frames.size
            assertTrue("${item.id} has $n frames", n in 24..120)
        }
    }

    @Test fun spriteConversionKeepsFrameCount() {
        val sprites = SpriteStore.entries
        assertTrue(sprites.size > 300)
        for (e in sprites.filter { it.sourceFrames > 0 }) {
            val item = AnimationLibrary.item(e.id)!!
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
}
