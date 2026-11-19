package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Music page's Fall-back speed and Sensitivity, and the style previews. */
class VisualiserTuningTest {
    @Test fun fasterFallBackKeepsLess() {
        assertEquals(0.82f, LiveLevels.decay(1f), 0.0001f)
        assertTrue(LiveLevels.decay(0.5f) > LiveLevels.decay(1f))
        assertTrue(LiveLevels.decay(2f) < LiveLevels.decay(1f))
        // clamped to the panel's range
        assertEquals(LiveLevels.decay(4f), LiveLevels.decay(40f), 0f)
    }

    @Test fun higherSensitivityLightsMoreForTheSameSound() {
        val db = FloatArray(SpectrumBands.BANDS) { -30f - it }
        fun lit(range: Float) = LiveLevels(rangeDb = range).let { l -> repeat(10) { l.update(db) }; l.levels.sum() }
        // a wider range (High) reaches further below the loudest band, so quieter bands light too
        assertTrue(lit(48f) > lit(36f))
        assertTrue(lit(36f) > lit(24f))
    }

    @Test fun theDemoMovesInEveryStyle() {
        val p = DeviceProfile.Phone3
        for (style in VisualStyle.entries) {
            val a = VisualiserRenderer.demo(style, p)
            assertEquals(32, a.frames.size)
            assertTrue(style.name, a.frames.map { it.frame.pixels.toList() }.toSet().size > 8)
            assertTrue(style.name, a.frames.all { f -> f.frame.pixels.any { it > 100 } })
        }
        // a slower fall-back keeps the lights up longer after each kick
        val slow = VisualiserRenderer.demo(VisualStyle.BARS, p, fall = 0.5f).frames.sumOf { it.frame.pixels.sum() }
        val fast = VisualiserRenderer.demo(VisualStyle.BARS, p, fall = 2f).frames.sumOf { it.frame.pixels.sum() }
        assertTrue(slow > fast)
    }
}
