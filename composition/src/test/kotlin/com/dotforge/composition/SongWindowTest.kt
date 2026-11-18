package com.dotforge.composition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongWindowTest {
    @Test fun windowIsClampedToTheSongAndForty() {
        val w = SongWindow.of(180_000, 10_000, 60_000)
        assertEquals(40_000, w.lengthMs)
        assertEquals(10_000, w.startMs)
        assertEquals(50_000, w.endMs)
        assertTrue(w.cutsEnd)
        assertEquals(140_000, w.movedTo(170_000).startMs)
        assertEquals(0, w.movedTo(-5).startMs)
        assertEquals(SongWindow.MIN_MS, w.withLength(100).lengthMs)
        assertEquals(30_000, w.withEnd(40_000).lengthMs)
    }

    @Test fun lengthChangeNearTheEndMovesTheStartBack() {
        val w = SongWindow.of(60_000, 50_000, 5_000)
        assertEquals(55_000, w.endMs)
        val longer = w.withLength(40_000)
        assertEquals(40_000, longer.lengthMs)
        assertEquals(20_000, longer.startMs)
        assertFalse(longer.cutsEnd)
    }

    @Test fun shortSongIsWhole() {
        val w = SongWindow.of(12_000, 3_000, 40_000)
        assertTrue(w.isWhole)
        assertEquals(12_000, w.lengthMs)
        assertEquals(0, w.startMs)
        assertTrue(SongWindow.of(3_000, 0, 1_000).isWhole) // shorter than the minimum: the whole song
        assertTrue(SongWindow.loudest(30_000, FloatArray(600) { 0.5f }, 50).isWhole)
    }

    @Test fun loudestFindsTheChorus() {
        // 120 s at 50 ms buckets: quiet, then loud from 70 s to 110 s
        val env = FloatArray(2400) { if (it in 1400 until 2200) 0.9f else 0.1f }
        val w = SongWindow.loudest(120_000, env, 50)
        assertEquals(70_000, w.startMs)
        assertEquals(40_000, w.lengthMs)
        // flat song: earliest
        assertEquals(0, SongWindow.loudest(120_000, FloatArray(2400) { 0.3f }, 50).startMs)
    }

    @Test fun envelopeKeepsPeaksPerBucket() {
        val e = PeakEnvelope(sampleRate = 1000, channels = 2, bucketMs = 10) // 10 frames = 20 samples per bucket
        val samples = ShortArray(50) { 0 }
        samples[3] = 16384
        samples[25] = -32767
        e.add(samples.copyOfRange(0, 22))
        e.add(samples.copyOfRange(22, 50))
        val r = e.result()
        assertEquals(3, r.size)
        assertEquals(16384 / 32767f, r[0], 1e-4f)
        assertEquals(1f, r[1], 1e-6f)
        assertEquals(0f, r[2], 0f)
        assertArrayEquals(floatArrayOf(16384 / 32767f, 1f), PeakEnvelope.bars(r, 2), 1e-6f)
    }

    @Test fun fadeInStartsSilent() {
        val pcm = Pcm(1000, 1, ShortArray(1000) { 1000 })
        val f = pcm.fadedIn(100)
        assertEquals(0, f.samples[0].toInt())
        assertEquals(500, f.samples[50].toInt())
        assertEquals(1000, f.samples[500].toInt())
    }
}
