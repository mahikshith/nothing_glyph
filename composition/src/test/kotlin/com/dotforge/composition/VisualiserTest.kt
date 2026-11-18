package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VisualiserTest {
    private val p = DeviceProfile.Phone3

    @Test fun edgesAreIncreasingAndCoverEveryBand() {
        for ((bins, rate) in listOf(512 to 44_100, 512 to 48_000, 128 to 44_100, 1024 to 22_050)) {
            val e = SpectrumBands.edges(bins, rate)
            assertEquals(SpectrumBands.BANDS + 1, e.size)
            for (b in 0 until SpectrumBands.BANDS) assertTrue("$bins/$rate band $b", e[b + 1] > e[b])
            assertTrue(e.last() <= bins)
        }
    }

    @Test fun frequenciesLandInTheRightBands() {
        assertEquals(0, SpectrumBands.bandOf(50.0, 512, 44_100))
        assertEquals(SpectrumBands.BANDS - 1, SpectrumBands.bandOf(14_000.0, 512, 44_100))
        assertEquals(-1, SpectrumBands.bandOf(20.0, 512, 44_100))
        val bands = listOf(60.0, 150.0, 400.0, 1_000.0, 2_500.0, 6_000.0, 12_000.0).map { SpectrumBands.bandOf(it, 1024, 44_100) }
        assertEquals(bands.sorted(), bands)
        assertEquals(bands.size, bands.toSet().size)
    }

    private fun sine(hz: Double, rate: Int = 44_100, n: Int = 1024) =
        ShortArray(n) { (sin(2 * PI * hz * it / rate) * 12_000).toInt().toShort() }

    @Test fun aSineLightsItsBand() {
        for (hz in listOf(100.0, 1_000.0, 8_000.0)) {
            val mags = SpectrumBands.fromPcm(sine(hz))
            val db = SpectrumBands.bandDb(mags, 44_100)
            val loudest = db.indices.maxBy { db[it] }
            assertEquals("$hz Hz", SpectrumBands.bandOf(hz, mags.size, 44_100), loudest)
        }
    }

    @Test fun visualizerBytesDecode() {
        // n = 8 bytes: Re0=-4, Re4=1, (Re1,Im1)=(3,4), (Re2,Im2)=(0,-2), (Re3,Im3)=(6,8)
        val mags = SpectrumBands.fromVisualizerFft(byteArrayOf(-4, 1, 3, 4, 0, -2, 6, 8))
        assertEquals(4, mags.size)
        assertEquals(4f, mags[0])
        assertEquals(5f, mags[1])
        assertEquals(2f, mags[2])
        assertEquals(10f, mags[3])
    }

    @Test fun levelsFollowLoudnessAndStayDarkInSilence() {
        val live = LiveLevels()
        repeat(30) { live.update(FloatArray(SpectrumBands.BANDS) { -120f }) }
        assertTrue(live.levels.all { it == 0f })
        val loud = FloatArray(SpectrumBands.BANDS) { if (it == 0) -10f else -50f }
        repeat(10) { live.update(loud) }
        assertTrue(live.levels[0] > 0.9f)
        assertTrue(live.levels[5] < 0.1f)
        repeat(30) { live.update(FloatArray(SpectrumBands.BANDS) { -120f }) }
        assertTrue(live.levels[0] < 0.05f)
    }

    @Test fun framesRespectTheMaskAndGrowWithLevel() {
        for (style in VisualStyle.entries) {
            val quiet = VisualiserRenderer.frame(FloatArray(12) { 0.2f }, style, p)
            val loud = VisualiserRenderer.frame(FloatArray(12) { 0.9f }, style, p)
            for (f in listOf(quiet, loud)) for (c in 0 until 625) if (!p.mask[c]) assertEquals(0, f[c])
            assertTrue("$style", loud.count { it > 0 } >= quiet.count { it > 0 })
            assertTrue("$style", loud.any { it > 0 })
        }
        val idle = VisualiserRenderer.frame(FloatArray(12), VisualStyle.BARS, p)
        assertEquals(p.ring.size, idle.count { it > 0 })
        // bars: only the bass column lights when only bass plays
        val bass = VisualiserRenderer.frame(FloatArray(12) { if (it == 0) 1f else 0f }, VisualStyle.BARS, p)
        assertTrue(bass[18 * 25 + 2] > 0) // x=0..2 show the bass band
        assertEquals(0, bass[20 * 25 + 20])
    }
}
