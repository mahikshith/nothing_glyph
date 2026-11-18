package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {
    private fun pb(loop: Boolean = true) = Playback(listOf(100, 200, 100), loop)

    @Test fun playsThroughFramesByTheirOwnDurations() {
        val p = pb()
        p.play()
        assertTrue(p.playing)
        assertFalse(p.tick(99))
        assertTrue(p.tick(1))
        assertEquals(1, p.frame)
        p.tick(199)
        assertEquals(1, p.frame)
        p.tick(1)
        assertEquals(2, p.frame)
        p.tick(100)
        assertEquals(0, p.frame) // loops
    }

    @Test fun withoutLoopItStopsOnTheLastFrameAndPlayStartsOver() {
        val p = pb(loop = false)
        p.play()
        p.tick(1_000)
        assertFalse(p.playing)
        assertEquals(2, p.frame)
        p.play()
        assertEquals(0, p.frame)
        assertTrue(p.playing)
    }

    @Test fun speedScalesTime() {
        val p = pb()
        p.speed = Playback.Speed.DOUBLE
        p.play()
        p.tick(50)
        assertEquals(1, p.frame)
        p.speed = Playback.Speed.HALF
        assertEquals(400L, p.msToNextFrame())
    }

    @Test fun aTapOnTheMatrixPausesInsteadOfDrawing() {
        val p = pb()
        assertFalse(p.tapMatrix()) // paused: the tap draws
        p.play()
        assertTrue(p.tapMatrix()) // playing: the tap pauses
        assertFalse(p.playing)
        assertFalse(p.tapMatrix())
    }

    @Test fun stepsPauseAndWrapOnlyWhenLooping() {
        val p = pb()
        p.play()
        p.stepForward()
        assertFalse(p.playing)
        assertEquals(1, p.frame)
        p.stepBack()
        p.stepBack()
        assertEquals(2, p.frame)
        val q = pb(loop = false)
        q.stepBack()
        assertEquals(0, q.frame)
        q.seek(2)
        q.stepForward()
        assertEquals(2, q.frame)
    }

    @Test fun scrubbingPicksTheFrameForATime() {
        val p = pb()
        p.play()
        p.scrubTo(150)
        assertFalse(p.playing)
        assertEquals(1, p.frame)
        assertEquals(150, p.positionMs)
        p.scrubTo(399)
        assertEquals(2, p.frame)
        p.scrubTo(10_000)
        assertEquals(2, p.frame)
        assertEquals(400, p.totalMs)
    }

    @Test fun syncKeepsTheFrameInRangeAndOneFrameNeverPlays() {
        val p = pb()
        p.seek(2)
        p.sync(listOf(100))
        assertEquals(0, p.frame)
        p.play()
        assertFalse(p.playing)
    }

    @Test fun theEditorFitsAt360x640WithFontScale13() {
        // 640 dp tall minus a 24 dp status bar and a 48 dp navigation bar
        val h = 640f - 24f - 48f
        for (animated in listOf(true, false)) {
            val m = EditorLayout.matrixDp(360f, h, 1.3f, animated)
            assertTrue("matrix $m dp", m >= EditorLayout.MIN_MATRIX_DP)
            assertTrue(EditorLayout.chromeDp(1.3f, animated) + m <= h)
        }
        // at 2x the matrix shrinks but stays usable
        assertTrue(EditorLayout.matrixDp(360f, h, 2f, true) >= 120f)
        assertEquals(EditorLayout.MAX_MATRIX_DP, EditorLayout.matrixDp(412f, 900f, 1f, true))
    }
}
