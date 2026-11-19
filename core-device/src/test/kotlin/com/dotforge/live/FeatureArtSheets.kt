package com.dotforge.live

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.TimerFaces
import com.dotforge.device.StopwatchStyle
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
        fun sub(l: Long, k: Float) = "${TimerFaces.clock(l)} +${(k * 1000).toInt()}ms"
        val rows = TimerStyle.entries.map { st ->
            val frames = ArrayList<Pair<String, Frame>>()
            frames += "5:00" to TimerFaces.countdown(p, st, total, total)
            for (k in listOf(0f, 0.25f, 0.5f, 0.75f)) frames += sub(252_000, k) to TimerFaces.countdown(p, st, 252_000, total, k)
            frames += "2:30" to TimerFaces.countdown(p, st, 150_000, total)
            frames += "1:00" to TimerFaces.countdown(p, st, 60_000, total)
            for (k in listOf(0f, 0.25f, 0.5f)) frames += sub(42_000, k) to TimerFaces.countdown(p, st, 42_000, total, k)
            frames += "0:12" to TimerFaces.countdown(p, st, 12_000, total)
            st.label to frames
        }
        write("timer-styles.png", "Timer styles, frame by frame: a 5 min timer (m:ss from a minute on, big seconds and a ticking ring under it)", rows)
        val watch = StopwatchStyle.entries.map { st ->
            st.label to (listOf(5_000L, 5_250L, 5_500L, 25_000L, 59_000L, 75_000L, 200_000L, 3_720_000L).map { e -> TimerFaces.lap(e) to TimerFaces.stopwatch(p, st, e, (e % 1000) / 1000f) })
        }
        write("stopwatch-styles.png", "Stopwatch styles: always m:ss (h:mm from an hour on)", watch)
        fun strip(a: com.dotforge.device.Animation, every: Int = 1) = a.frames.filterIndexed { i, _ -> i % every == 0 }.map { "${it.durationMs} ms" to it.frame }
        val burst = TimerFaces.finished(p)
        val half = burst.frames.size / 2
        val ends = listOf(
            "Last 10 s: 10 left" to strip(TimerFaces.lastSeconds(p, 10_000)),
            "9 left (segment ticks off)" to strip(TimerFaces.lastSeconds(p, 9_000)),
            "3 left" to strip(TimerFaces.lastSeconds(p, 3_000)),
            "1 left" to strip(TimerFaces.lastSeconds(p, 1_000)),
            "Finish: rays, ripples" to burst.frames.take(half).map { "${it.durationMs} ms" to it.frame },
            "Finish: fireworks" to burst.frames.drop(half).map { "${it.durationMs} ms" to it.frame },
            "Paused (slow blink)" to strip(TimerFaces.paused(p, TimerFaces.countdown(p, TimerStyle.RING_DRAIN, 150_000, total))),
            "Dots: one pops" to strip(TimerFaces.second(p, TimerStyle.DOTS, 108_000, total)),
            "Hourglass: the stream" to strip(TimerFaces.second(p, TimerStyle.HOURGLASS, 150_000, total)),
            "Orbit: the comet" to strip(TimerFaces.second(p, TimerStyle.ORBIT, 150_000, total)),
        )
        write("timer-ends.png", "Motion: the last 10 seconds, the finish, paused, and each style within one second", ends)
    }

    @Test fun liveProgress() {
        assumeTrue(out != null)
        fun icon(id: String) = LiveRing.icon(p, id)!!
        val rows = listOf(
            "Delivery: minutes in the middle" to listOf(0.1f to 25, 0.4f to 15, 0.7f to 8, 0.9f to 3, 0.97f to 1).map { (f, m) -> "$m min" to LiveRing.frame(p, f, Centre.Minutes(m)) },
            "Delivery stages (no minutes)" to listOf(Stage.PLACED, Stage.PREPARING, Stage.PACKED, Stage.ON_THE_WAY, Stage.ARRIVING, Stage.DONE).map { s ->
                s.label to LiveRing.frame(p, s.fraction, Centre.Icon(icon(LiveProgress.icon(s, null)!!)))
            },
            "Ride stages" to listOf(Stage.ASSIGNED, Stage.PICKUP, Stage.AT_PICKUP, Stage.ON_TRIP, Stage.DONE).map { s ->
                s.label to LiveRing.frame(p, s.fraction, Centre.Icon(icon(LiveProgress.icon(s, ProgressApps.byId("uber"))!!)))
            } + ("Auto" to LiveRing.frame(p, 0.3f, Centre.Icon(icon("g-auto")))),
            "Other apps" to listOf("Download" to LiveRing.frame(p, 0.5f, Centre.Icon(icon("download")))),
            "App icons" to ProgressApps.ALL.map { a -> a.name.substringBefore(" /") to LiveRing.frame(p, 0.35f, Centre.Icon(icon(a.icon))) },
            "Arriving: the ring pulses" to LiveRing.animation(p, 0.92f, Centre.Minutes(2), arriving = true).frames.filterIndexed { i, _ -> i % 2 == 0 }.map { "${it.durationMs} ms" to it.frame },
            "Done: delivery" to LiveRing.done(p, icon("g-parcel-done")).frames.filterIndexed { i, _ -> i % 3 == 0 }.map { "${it.durationMs} ms" to it.frame },
            "Done: ride" to LiveRing.done(p, icon("g-finish-flag")).frames.filterIndexed { i, _ -> i % 3 == 0 }.map { "${it.durationMs} ms" to it.frame },
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
