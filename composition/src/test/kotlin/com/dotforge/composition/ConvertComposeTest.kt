package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class ConvertComposeTest {
    private val p = DeviceProfile.Phone3
    private val columnCounts = listOf(5, 15, 26, 33, 36)
    private fun cell(x: Int, y: Int) = y * 25 + x

    private fun assertMask(rows: List<IntArray>) {
        for (r in rows) {
            assertEquals(625, r.size)
            for (c in 0 until 625) {
                if (!p.mask[c]) assertEquals("cell $c has no LED", 0, r[c])
                assertTrue(r[c] in 0..4095)
            }
        }
    }

    /** One row per zone, lighting only that zone. */
    private fun soloRows(n: Int) = List(n) { z -> IntArray(n) { if (it == z) 4000 else 0 } }

    @Test fun everyZoneLightsSomethingAndOnlyItsCells() {
        for (layout in listOf(ConvertLayout.RINGS, ConvertLayout.ARCS, ConvertLayout.BARS)) for (n in columnCounts) {
            val map = RingtoneConverter.zoneMap(n, layout, p)
            val out = RingtoneConverter.convert(soloRows(n), layout, p)
            assertEquals(n, out.size)
            assertMask(out)
            for (z in 0 until n) {
                val lit = out[z].indices.filter { out[z][it] > 0 }
                assertTrue("$layout $n zone $z lights nothing", lit.isNotEmpty())
                assertTrue(lit.all { z in map[it] })
            }
            // every LED shows some zone
            assertTrue(p.ledCells.all { map[it].isNotEmpty() })
        }
    }

    @Test fun ringsGoInsideOut() {
        for (n in columnCounts) {
            val map = RingtoneConverter.zoneMap(n, ConvertLayout.RINGS, p)
            assertEquals(0, map[cell(12, 12)].single())
            assertEquals(n - 1, map[cell(15, 0)].single()) // r^2 = 153, the outermost LEDs
            // zones never decrease with radius
            val byRadius = p.ledCells.sortedBy { hypot((it % 25 - 12).toDouble(), (it / 25 - 12).toDouble()) }
            byRadius.zipWithNext().forEach { (a, b) -> assertTrue(map[a].single() <= map[b].single()) }
        }
    }

    @Test fun arcsGoClockwiseFromTheTop() {
        val map = RingtoneConverter.zoneMap(4, ConvertLayout.ARCS, p)
        assertEquals(0, map[cell(13, 1)].single()) // just right of 12 o'clock
        assertEquals(1, map[cell(23, 13)].single()) // just below 3 o'clock
        assertEquals(2, map[cell(11, 23)].single()) // just left of 6 o'clock
        assertEquals(3, map[cell(1, 11)].single()) // just above 9 o'clock
    }

    @Test fun barsGoLeftToRightAndCoverEveryZone() {
        for (n in columnCounts) {
            val map = RingtoneConverter.zoneMap(n, ConvertLayout.BARS, p)
            assertEquals(0, map[cell(0, 12)].first())
            assertEquals(n - 1, map[cell(24, 12)].last())
            assertEquals((0 until n).toSet(), p.ledCells.flatMap { map[it].toList() }.toSet())
            // a whole column shows the same zones
            for (x in 0 until 25) assertEquals(1, (0 until 25).filter { p.mask[cell(x, it)] }.map { map[cell(x, it)].toList() }.toSet().size)
        }
    }

    @Test fun conversionKeepsTimingAndValues() {
        val rows = List(120) { r -> IntArray(5) { z -> (r * 30 + z * 500) % 4096 } }
        for (layout in ConvertLayout.entries) {
            val out = RingtoneConverter.convert(rows, layout, p)
            assertEquals(120, out.size)
            assertMask(out)
        }
        val rings = RingtoneConverter.convert(rows, ConvertLayout.RINGS, p)
        assertEquals(rows[7][0], rings[7][cell(12, 12)])
        assertEquals(rows[7][4], rings[7][cell(15, 0)])
        val half = RingtoneConverter.convert(rows, ConvertLayout.RINGS, p, intensity = 0.5f)
        assertEquals(rows[7][4] / 2, half[7][cell(15, 0)])
    }

    @Test fun burstGrowsWithEnergy() {
        val rows = listOf(IntArray(33), IntArray(33) { 800 }, IntArray(33) { 4095 })
        val out = RingtoneConverter.convert(rows, ConvertLayout.BURST, p)
        assertMask(out)
        val lit = out.map { r -> r.count { it > 0 } }
        assertEquals(0, lit[0])
        assertTrue(lit[1] in 1 until lit[2])
        assertTrue(out[2][cell(12, 0)] > 0)
    }

    @Test fun keepAppliesMaskAndIntensity() {
        val rows = listOf(IntArray(625) { 4000 })
        val kept = RingtoneConverter.keep(rows, p)
        assertMask(kept)
        assertEquals(489, kept[0].count { it == 4000 })
        assertEquals(2000, RingtoneConverter.keep(rows, p, 0.5f)[0][cell(12, 12)])
    }

    // --- auto-compose ---

    private val rate = 48_000

    /** 4 s: a quiet 80 Hz hum, and a loud 30 ms noise burst every 0.5 s starting at 0.25 s. */
    private fun synthetic(): FloatArray {
        val r = Random(11)
        val out = FloatArray(rate * 4) { (0.05 * sin(2 * PI * 80 * it / rate)).toFloat() }
        for (k in 0 until 8) {
            val start = rate / 4 + k * rate / 2
            for (i in 0 until rate * 3 / 100) out[start + i] += (r.nextFloat() * 2 - 1) * 0.8f
        }
        return out
    }

    private val burstFrames = (0 until 8).map { 15 + it * 30 }

    @Test fun fftFindsASine() {
        val n = 256
        val re = DoubleArray(n) { sin(2 * PI * 10 * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val mags = (0 until n / 2).map { hypot(re[it], im[it]) }
        assertEquals(10, mags.indices.maxBy { mags[it] })
    }

    @Test fun analysisFindsTheBursts() {
        val a = AudioAnalyzer.analyze(synthetic(), rate)
        assertEquals(240, a.frameCount)
        assertEquals(AudioAnalyzer.BANDS, a.bandCount)
        val onsets = a.onset.indices.filter { a.onset[it] > 0 }
        assertEquals(8, onsets.size)
        onsets.zip(burstFrames).forEach { (got, want) -> assertTrue("onset at $got, burst at $want", abs(got - want) <= 2) }
        assertTrue(a.highOnset.count { it > 0 } >= 6)
    }

    @Test fun composeIsDeterministicAndRespectsTheMask() {
        val signal = synthetic()
        val a1 = AudioAnalyzer.analyze(signal, rate)
        val a2 = AudioAnalyzer.analyze(signal.copyOf(), rate)
        for (style in ComposeStyle.entries) {
            val x = AutoComposer.compose(a1, style, p, intensity = 1f, speed = 1f)
            val y = AutoComposer.compose(a2, style, p, intensity = 1f, speed = 1f)
            assertEquals(240, x.size)
            x.indices.forEach { assertArrayEquals("$style frame $it", x[it], y[it]) }
            assertMask(x)
            assertTrue("$style is dark", x.any { r -> r.any { it > 0 } })
        }
    }

    @Test fun lightsFollowTheBeat() {
        val a = AudioAnalyzer.analyze(synthetic(), rate)
        val pulse = AutoComposer.compose(a, ComposeStyle.PULSE, p)
        val centre = cell(12, 12)
        for (f in burstFrames.drop(1)) {
            assertTrue("pulse peaks at the burst", (f..f + 3).maxOf { pulse[it][centre] } > pulse[f - 8][centre])
        }
        val ripple = AutoComposer.compose(a, ComposeStyle.RIPPLE, p)
        // a ring seen at the edge some frames after a burst
        val f = burstFrames[2]
        assertTrue((f + 40..f + 60).any { ripple[it][cell(12, 1)] > 0 })
    }

    @Test fun silenceStaysDark() {
        val a = AudioAnalyzer.analyze(FloatArray(rate), rate)
        for (style in ComposeStyle.entries) {
            assertTrue(AutoComposer.compose(a, style, p).all { r -> r.all { it == 0 } })
        }
    }

    @Test fun intensityAndSpeedChangeTheResult() {
        val a = AudioAnalyzer.analyze(synthetic(), rate)
        val base = AutoComposer.compose(a, ComposeStyle.PULSE, p)
        val dim = AutoComposer.compose(a, ComposeStyle.PULSE, p, intensity = 0.5f)
        assertTrue(dim.sumOf { it.sum().toLong() } < base.sumOf { it.sum().toLong() })
        val fast = AutoComposer.compose(a, ComposeStyle.PULSE, p, speed = 2f)
        assertTrue(fast.sumOf { it.sum().toLong() } < base.sumOf { it.sum().toLong() })
    }

    @Test fun pcmHelpers() {
        val pcm = Pcm(44_100, 2, ShortArray(44_100 * 2) { if (it % 2 == 0) 1000 else 3000 })
        assertEquals(2000f / 32768f, pcm.mono()[100], 1e-6f)
        val up = pcm.resampled(48_000)
        assertEquals(48_000, up.frames)
        assertEquals(1000.toShort(), up.samples[200])
        val faded = pcm.fadedOut(500)
        assertEquals(0, faded.samples[faded.samples.size - 2].toInt())
        assertEquals(1000.toShort(), faded.samples[0])
        assertEquals(1000L, pcm.durationMs)
    }
}
