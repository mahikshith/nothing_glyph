package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostersTest {
    private val p = DeviceProfile.Phone3
    private val n = p.size

    private fun lit(f: Frame) = p.ledCells.filter { f.pixels[it] > 0 }

    @Test fun aRingEffectShowsTheWholeRingAtFullBrightness() {
        val f = Posters.of(Effects.cometLaps(p))
        assertEquals(255, f.pixels.max())
        val ring = p.ring.toSet()
        assertTrue(ring.all { f.pixels[it] > 0 })
    }

    @Test fun aDimEffectIsNeverBlank() {
        val dim = Animation.single(Frame.blank(p).also { it.pixels[p.ledCells[200]] = 40 })
        assertEquals(255, Posters.of(dim).pixels.max())
    }

    @Test fun aGlyphOffToOneSideIsCentred() {
        val f = Frame.blank(p)
        for (y in 10..14) for (x in 2..6) f[x, y] = 255
        val c = Posters.of(Animation.single(f))
        val cols = lit(c).map { it % n }
        assertEquals(n - 1, cols.min() + cols.max())
    }

    @Test fun theReminderBellIsWhole() {
        val f = Posters.of(AlertEffects.reminder(p))
        val cols = lit(f).map { it % n }
        // the bell sits across the middle, not cut off on one side
        assertTrue(cols.min() < n / 2 && cols.max() > n / 2)
    }

    @Test fun aNameShowsScrolledIn() {
        val f = Posters.text(p, "Mom")
        val cols = lit(f).map { it % n }
        assertTrue(cols.max() - cols.min() >= 10)
        assertTrue(kotlin.math.abs((cols.min() + cols.max()) - (n - 1)) <= 1)
    }
}
