package com.dotforge.device

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The four test photos (CC0 / public domain, credited in NOTICE), in app/src/test/resources/photos. */
object TestPhotos {
    val names = listOf("face", "pet", "logo", "landscape")

    fun load(name: String): Picture {
        val img = TestPhotos::class.java.getResourceAsStream("/photos/$name.jpg")!!.use { ImageIO.read(it) }
        val argb = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
        return Picture(img.width, img.height, argb)
    }
}

/**
 * Contact sheets for photo clarity: each test photo in all 13 styles at 96 dp (xxhdpi), drawn the way the app's
 * DotMatrix draws them, next to the photo itself and build 1's converter (DotArt: Shades, Three levels, Dither).
 * Writes the 13 x 4 thumbnails and one sheet per photo to `<screens.out>/studio/photo-styles/`.
 */
class PhotoStyleSheets {
    private val p = DeviceProfile.Phone3
    private val out: File? = System.getProperty("screens.out")?.let { File(it, "studio/photo-styles") }

    @Test fun contactSheets() {
        assumeTrue(out != null)
        val dir = out!!.apply { mkdirs() }
        for (name in TestPhotos.names) {
            val pic = TestPhotos.load(name)
            val samples = PhotoDots.sample(p, pic, PhotoCrop())
            val styles = PhotoStyle.entries.map { st -> st.label to PhotoDots.render(p, samples, PhotoSettings(style = st)) }
            styles.forEachIndexed { i, (label, f) ->
                val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                ImageIO.write(dots(f, THUMB), "png", File(dir, "$name-${"%02d".format(i + 1)}-$slug.png"))
            }
            val build1 = listOf(DotArtMode.GREY, DotArtMode.POSTERISE, DotArtMode.DITHER).map { m ->
                "Build 1: ${m.label}" to DotArt.frame(p, pic.width, pic.height, pic.argb, m)
            }
            ImageIO.write(sheet(name, pic, build1, styles), "png", File(dir, "$name-sheet.png"))
        }
    }

    /** One frame as the app draws it: off dots #333, lit dots white at 25-100%, radius 0.4 of a cell. */
    private fun dots(f: Frame, side: Int): BufferedImage {
        val img = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.fillRect(0, 0, side, side)
        val cell = side / p.size.toFloat()
        val r = cell * 0.4f
        for (i in 0 until p.cellCount) {
            if (!p.mask[i]) continue
            val v = f.pixels[i]
            g.color = if (v == 0) Color(0x33, 0x33, 0x33) else Color(255, 255, 255, ((0.25f + 0.75f * v / 255f) * 255).toInt())
            val cx = (i % p.size + 0.5f) * cell
            val cy = (i / p.size + 0.5f) * cell
            g.fill(java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
        }
        g.dispose()
        return img
    }

    private fun sheet(name: String, pic: Picture, build1: List<Pair<String, Frame>>, styles: List<Pair<String, Frame>>): BufferedImage {
        val cols = 6
        val cellW = THUMB + 24
        val cellH = THUMB + 48
        val rows = 1 + (styles.size + cols - 1) / cols
        val img = BufferedImage(cols * cellW + 24, rows * cellH + 80, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = Color.BLACK
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        g.drawString("$name: 13 styles at 96 dp (default settings), with build 1 for comparison", 24, 48)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        fun tile(i: Int, row: Int, label: String, draw: (Int, Int) -> Unit) {
            val x = 24 + i * cellW
            val y = 80 + row * cellH
            draw(x, y)
            g.color = Color(0xCC, 0xCC, 0xCC)
            g.drawString(label, x, y + THUMB + 30)
        }
        // row 0: the photo (centre square, as the default crop takes it), then build 1
        val src = BufferedImage(pic.width, pic.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, pic.width, pic.height, pic.argb, 0, pic.width) }
        val s = minOf(pic.width, pic.height)
        tile(0, 0, "Photo") { x, y -> g.drawImage(src, x, y, x + THUMB, y + THUMB, (pic.width - s) / 2, (pic.height - s) / 2, (pic.width + s) / 2, (pic.height + s) / 2, null) }
        build1.forEachIndexed { i, (label, f) -> tile(i + 1, 0, label) { x, y -> g.drawImage(dots(f, THUMB), x, y, null) } }
        styles.forEachIndexed { i, (label, f) -> tile(i % cols, 1 + i / cols, label) { x, y -> g.drawImage(dots(f, THUMB), x, y, null) } }
        g.dispose()
        return img
    }

    private companion object {
        /** 96 dp at xxhdpi (3x), the Phone (3)'s density. */
        const val THUMB = 288
    }
}
