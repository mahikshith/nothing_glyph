package com.dotforge.system

import com.dotforge.device.DeviceProfile
import com.dotforge.tile.TorchLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Features > Torch: brightness, the fade's speed and the auto-off. */
class TorchLightTest {
    private val p = DeviceProfile.Phone3

    @Test fun fadesUpThenHoldsUntilItTurnsOff() {
        val a = TorchLight.animation(p, 100, 1f, 5 * 60_000L)
        val levels = a.frames.map { f -> f.frame.pixels[p.ledCells.first()] }
        assertEquals(levels.sorted(), levels)
        assertEquals(255, levels.last())
        assertTrue(p.ledCells.all { a.frames.last().frame.pixels[it] == 255 })
        assertEquals(5 * 60_000, a.frames.last().durationMs)
        // the fade takes FADE_MS at 1x, half at 2x
        val fade = a.frames.dropLast(1).sumOf { it.durationMs }
        assertTrue(fade in TorchLight.FADE_MS - 100..TorchLight.FADE_MS)
        val quick = TorchLight.animation(p, 100, 2f, null).frames.dropLast(1).sumOf { it.durationMs }
        assertTrue(quick < fade)
    }

    @Test fun brightnessAndNever() {
        val half = TorchLight.animation(p, 50, 1f, null)
        assertEquals(127, half.frames.last().frame.pixels[p.ledCells.first()])
        // Never: a long hold the arbiter loops
        assertEquals(6 * 60 * 60_000, half.frames.last().durationMs)
        // brightness stays in the panel's range
        assertEquals(25, TorchLight.animation(p, 1, 1f, 1_000).frames.last().frame.pixels[p.ledCells.first()])
    }

    @Test fun torchChipsAndDefaults() {
        assertEquals(listOf(1, 5, 10, 30, 0), FeatureSettings.TORCH_CHIPS)
        assertEquals(com.dotforge.device.ShowFor.UNTIL_CHECKED, FeatureTiming.TORCH.default.showFor)
        assertEquals(5, FeatureTiming.CHARGING.default.seconds)
        assertEquals(2, FeatureTiming.VOLUME.default.seconds)
        assertEquals(10, FeatureTiming.APPS.default.seconds)
        assertEquals(32, FeatureSettings.newKey().length)
        assertTrue(FeatureSettings.newKey() != FeatureSettings.newKey())
    }
}
