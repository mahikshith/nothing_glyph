package com.dotforge.notify

import com.dotforge.device.Effects
import com.dotforge.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    private fun f(vararg e: Pair<String, Any?>) = Progress.fraction(mapOf(*e))

    @Test fun classicSetProgressIsRead() {
        // NotificationCompat.Builder.setProgress(200, 50, false)
        assertEquals(0.25f, f(Progress.PROGRESS to 50, Progress.MAX to 200, Progress.INDETERMINATE to false)!!, 1e-6f)
        assertEquals(1f, f(Progress.PROGRESS to 200, Progress.MAX to 200)!!, 1e-6f)
        // over-full and negative values stay on the ring
        assertEquals(1f, f(Progress.PROGRESS to 250, Progress.MAX to 200)!!, 1e-6f)
        assertEquals(0f, f(Progress.PROGRESS to -3, Progress.MAX to 200)!!, 1e-6f)
    }

    @Test fun noOrIndeterminateProgressIsNothing() {
        assertNull(f())
        assertNull(f(Progress.PROGRESS to 10, Progress.MAX to 100, Progress.INDETERMINATE to true))
        // setProgress(0, 0, false) takes the bar away
        assertNull(f(Progress.PROGRESS to 0, Progress.MAX to 0))
        assertNull(f(Progress.MAX to 100))
        assertNull(f(Progress.PROGRESS to "half", Progress.MAX to 100))
    }

    @Test fun progressStyleBundleIsRead() {
        // Android 16 ProgressStyle: progress in android.progress, its length as segments, no android.progressMax
        val segments = listOf(mapOf(Progress.SEGMENT_LENGTH to 300), mapOf(Progress.SEGMENT_LENGTH to 100))
        assertEquals(
            0.5f,
            f(Progress.TEMPLATE to Progress.PROGRESS_STYLE, Progress.PROGRESS to 200, Progress.SEGMENTS to segments, Progress.INDETERMINATE to false)!!,
            1e-6f,
        )
        // a ProgressStyle without segments is 100 long
        assertEquals(0.42f, f(Progress.TEMPLATE to Progress.PROGRESS_STYLE, Progress.PROGRESS to 42)!!, 1e-6f)
        // segments alone mark the style too; broken segments are skipped
        assertEquals(
            0.25f,
            f(Progress.PROGRESS to 25, Progress.SEGMENTS to listOf(mapOf(Progress.SEGMENT_LENGTH to 100), mapOf("color" to 1), "junk"))!!,
            1e-6f,
        )
        assertNull(f(Progress.TEMPLATE to Progress.PROGRESS_STYLE, Progress.PROGRESS to 42, Progress.INDETERMINATE to true))
        assertNull(f(Progress.TEMPLATE to Progress.PROGRESS_STYLE))
    }

    @Test fun ringLightsItsShareOfCells() {
        val profile = DeviceProfile.Phone3
        val n = profile.ring.size
        assertEquals(0, Progress.litCells(n, 0f))
        assertEquals(n, Progress.litCells(n, 1f))
        assertEquals(n / 2, Progress.litCells(n, 0.5f))
        assertEquals(n, Progress.litCells(n, 7f))
        assertEquals(0, Progress.litCells(n, -1f))
        for (fraction in listOf(0f, 0.1f, 0.33f, 0.5f, 0.99f, 1f)) {
            val frame = Effects.ring(profile, fraction)
            assertEquals(Progress.litCells(n, fraction), profile.ring.count { frame.pixels[it] == 255 })
        }
    }

    @Test fun mediaPositionMovesOnWhilePlaying() {
        // 60 s in, reported 2 s ago, at normal speed: 62 of 240 s
        assertEquals(62f / 240, Progress.media(60_000, 240_000, updatedAt = 1_000, now = 3_000, speed = 1f, playing = true)!!, 1e-6f)
        // paused: stays where it was
        assertEquals(0.25f, Progress.media(60_000, 240_000, updatedAt = 1_000, now = 90_000, speed = 0f, playing = false)!!, 1e-6f)
        // double speed
        assertEquals(64f / 240, Progress.media(60_000, 240_000, updatedAt = 1_000, now = 3_000, speed = 2f, playing = true)!!, 1e-6f)
        // never past the end, live streams have no length
        // no update time: the reported position as it is
        assertEquals(239f / 240, Progress.media(239_000, 240_000, updatedAt = 0, now = 9_000, speed = 1f, playing = true)!!, 1e-6f)
        assertEquals(1f, Progress.media(239_000, 240_000, updatedAt = 1, now = 10_000, speed = 1f, playing = true)!!, 1e-6f)
        assertNull(Progress.media(5_000, 0, updatedAt = 1, now = 2, speed = 1f, playing = true))
        assertNull(Progress.media(-1, 240_000, updatedAt = 1, now = 2, speed = 1f, playing = true))
    }
}
