package com.dotforge.live

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.TimerFaces
import com.dotforge.device.TimerStyle
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Contact sheets of what Timer & stopwatch and Live progress draw on the Glyph, frame by frame, for review.
 * Written only with -Pscreens=<folder>, to `<folder>/features-art/`.
 */
class FeatureArtSheets {
    private val p = DeviceProfile.Phone3
    private val out: File? = System.getProperty("screens.out")?.let { File(it, "features-art") }
    private val cell = 10
    private val side get() = p.size * cell

    @Test fun timerStyles() {
        assumeTrue(out != null)
        val total = 5 * 60_000L
        val lefts = listOf(total, 4 * 60_000L + 12_000, 2 * 60_000L + 30_000, 61_000L, 42_000L, 12_000L)
        val rows = TimerStyle.entries.map { st ->
            st.label to lefts.map { l -> "${TimerFaces.clock(l)} left" to TimerFaces.countdown(p, st, l, total, 0.5f) }
        }
        write("timer-styles.png", "Timer styles: a 5 min timer at 5:00, 4:12, 2:30, 1:01, 0:42, 0:12", rows)
        val watch = TimerStyle.entries.map { st ->
            st.label to listOf(5_000L, 25_000L, 59_000L, 75_000L, 3 * 60_000L + 20_000).map { e -> TimerFaces.lap(e) to TimerFaces.stopwatch(p, st, e, 0.25f) }
        }
        write("stopwatch-styles.png", "Stopwatch in each style at 0:05, 0:25, 0:59, 1:15, 3:20", watch)
        val ends = listOf(
            "Last 10 s (9 s left, one second)" to TimerFaces.lastSeconds(p, 9_000).frames.map { "${it.durationMs} ms" to it.frame },
            "Last seconds (3 s left)" to TimerFaces.lastSeconds(p, 3_000).frames.map { "${it.durationMs} ms" to it.frame },
            "Finish burst (loops until dismissed)" to TimerFaces.finished(p).frames.filterIndexed { i, _ -> i % 2 == 0 }.map { "${it.durationMs} ms" to it.frame },
            "Paused" to TimerFaces.paused(p, TimerFaces.countdown(p, TimerStyle.RING_DRAIN, 150_000, total)).frames.map { "${it.durationMs} ms" to it.frame },
        )
        write("timer-ends.png", "The last 10 seconds, the finish burst and paused", ends)
    }

    @Test fun liveProgress() {
        assumeTrue(out != null)
        fun icon(id: String) = LiveRing.icon(p, id)!!
        val rows = listOf(
            "Delivery: minutes in the middle" to listOf(0.1f to 25, 0.4f to 15, 0.7f to 8, 0.9f to 3, 0.97f to 1).map { (f, m) -> "$m min" to LiveRing.frame(p, f, Centre.Minutes(m)) },
            "Stage icons (no minutes)" to listOf(Stage.PLACED, Stage.PREPARING, Stage.PACKED, Stage.ON_THE_WAY, Stage.ARRIVING, Stage.DONE).map { s ->
                s.label to LiveRing.frame(p, s.fraction, Centre.Icon(icon(LiveProgress.icon(s, null)!!)))
            },
            "App icons" to ProgressApps.ALL.map { a -> a.name.substringBefore(" /") to LiveRing.frame(p, 0.35f, Centre.Icon(icon(a.icon))) },
            "Arriving: the ring pulses" to LiveRing.animation(p, 0.92f, Centre.Minutes(2), arriving = true).frames.filterIndexed { i, _ -> i % 2 == 0 }.map { "${it.durationMs} ms" to it.frame },
            "Done" to LiveRing.done(p, icon("g-parcel-done")).frames.filterIndexed { i, _ -> i % 3 == 0 }.map { "${it.durationMs} ms" to it.frame },
            "Download (percent)" to listOf(12, 48, 87).map { "$it%" to LiveRing.frame(p, it / 100f, Centre.Percent(it)) },
            "Next event / alarm" to listOf(
                "30 min lead, 20 left" to LiveRing.approaching(p, 1 / 3f, 20, icon("g-calendar")).frames[0].frame,
                "5 left" to LiveRing.approaching(p, 25 / 30f, 5, icon("g-calendar")).frames[0].frame,
                "last minute" to LiveRing.approaching(p, 0.98f, 1, icon("g-alarm")).frames[0].frame,
            ),
        )
        write("live-progress.png", "Live progress: ring = progress, middle = minutes left or the stage", rows)
    }

    private fun write(name: String, title: String, rows: List<Pair<String, List<Pair<String, Frame>>>>) {
        val dir = out!!.apply { mkdirs() }
        val labelW = 230
        val gap = 16
        val cols = rows.maxOf { it.second.size }
        val rowH = side + 34
        val w = labelW + cols * (side + gap) + gap
        val h = 50 + rows.size * rowH
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.fillRect(0, 0, w, h)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        g.drawString(title, gap, 30)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        rows.forEachIndexed { r, (label, frames) ->
            val y = 50 + r * rowH
            g.color = Color.WHITE
            g.drawString(label, gap, y + side / 2)
            frames.forEachIndexed { i, (cap, f) ->
                val x = labelW + i * (side + gap)
                for (cellIndex in p.ledCells) {
                    val v = f.pixels[cellIndex]
                    g.color = if (v == 0) Color(0x33, 0x33, 0x33) else Color(255, 255, 255, (64 + 191 * v / 255).coerceIn(0, 255))
                    val cx = x + (cellIndex % p.size) * cell + cell / 2
                    val cy = y + (cellIndex / p.size) * cell + cell / 2
                    val rr = (cell * 0.4).toInt()
                    g.fillOval(cx - rr, cy - rr, rr * 2, rr * 2)
                }
                g.color = Color(0xA3, 0xA3, 0xA3)
                g.drawString(cap, x, y + side + 20)
            }
        }
        g.dispose()
        ImageIO.write(img, "png", File(dir, name))
    }
}
