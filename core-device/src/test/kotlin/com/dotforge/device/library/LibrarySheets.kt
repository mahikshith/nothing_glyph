package com.dotforge.device.library

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Contact sheets of the DotForge glyph art, text and glyph + text items for review, written only with
 * -Pscreens=<folder> to `<screens.out>/library/`: one sheet of stills per category and one of animation strips.
 */
class LibrarySheets {
    private val p = DeviceProfile.Phone3
    private val out: File? = System.getProperty("screens.out")?.let { File(it, "library-art") }
    private val tile = 150

    @Test fun sheets() {
        assumeTrue(out != null)
        val dir = out!!.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val mine = AnimationLibrary.all.filter { it.kind == ItemKind.GLYPH || it.form == ItemForm.TEXT || it.form == ItemForm.LAYERED }
        for ((cat, items) in mine.groupBy { it.category }) {
            val slug = cat.id.replace('_', '-')
            val stills = items.filter { it.form == ItemForm.STILL }
            if (stills.isNotEmpty()) ImageIO.write(grid("${cat.title}: ${stills.size} stills", stills.map { it.name to it.load(p).frames[0].frame }), "png", File(dir, "$slug-stills.png"))
            val moving = items.filter { it.form != ItemForm.STILL }
            for ((k, chunk) in moving.chunked(14).withIndex()) {
                ImageIO.write(strips("${cat.title}: moving (${moving.size})", chunk), "png", File(dir, "$slug-moving-${k + 1}.png"))
            }
        }
    }

    private fun grid(title: String, cells: List<Pair<String, Frame>>): BufferedImage {
        val cols = 8
        val rows = (cells.size + cols - 1) / cols
        val img = BufferedImage(cols * tile, 40 + rows * (tile + 24), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 20); g.drawString(title, 8, 28)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        for ((i, c) in cells.withIndex()) {
            val x = (i % cols) * tile
            val y = 40 + (i / cols) * (tile + 24)
            dots(g, c.second, x, y, tile)
            g.color = Color.LIGHT_GRAY; g.drawString(c.first.take(22), x + 4, y + tile + 16)
        }
        g.dispose()
        return img
    }

    private fun strips(title: String, items: List<LibraryItem>): BufferedImage {
        val n = 8
        val t = 110
        val img = BufferedImage(170 + n * t, 40 + items.size * (t + 6), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 20); g.drawString(title, 8, 28)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        for ((r, item) in items.withIndex()) {
            val a = item.load(p)
            val y = 40 + r * (t + 6)
            g.color = Color.LIGHT_GRAY
            g.drawString(item.name.take(20), 4, y + 40)
            g.drawString("${a.frames.size} fr, ${a.totalMs} ms", 4, y + 58)
            // frames spread evenly over the loop's time
            val starts = a.frames.runningFold(0) { acc, f -> acc + f.durationMs }
            for (k in 0 until n) {
                val at = (a.totalMs * k / n)
                val i = (starts.indexOfLast { it <= at }).coerceIn(0, a.frames.size - 1)
                dots(g, a.frames[i].frame, 170 + k * t, y, t)
            }
        }
        g.dispose()
        return img
    }

    private fun dots(g: java.awt.Graphics2D, f: Frame, ox: Int, oy: Int, side: Int) {
        val cell = side / p.size.toFloat()
        val r = cell * 0.4f
        for (i in 0 until p.cellCount) {
            if (!p.mask[i]) continue
            val v = f.pixels[i]
            g.color = if (v == 0) Color(0x2a, 0x2a, 0x2a) else Color(255, 255, 255, ((0.25f + 0.75f * v / 255f) * 255).toInt())
            g.fill(java.awt.geom.Ellipse2D.Float(ox + (i % p.size + 0.5f) * cell - r, oy + (i / p.size + 0.5f) * cell - r, r * 2, r * 2))
        }
    }
}
