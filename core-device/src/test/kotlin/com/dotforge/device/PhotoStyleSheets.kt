package com.dotforge.device

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The four test photos (CC0 / public domain, credited in NOTICE), in core-device/src/test/resources/photos. */
object TestPhotos {
    val names = listOf("face", "pet", "logo", "landscape")

    /**
     * The face in face.jpg as Android's FaceDetector reports one (the point between the eyes, the eye distance),
     * measured on the photo: the JVM has no face detector, so tests and sheets use this.
     */
    val face = FaceSpot(167f, 127f, 40f)

    fun load(name: String): Picture {
        val img = TestPhotos::class.java.getResourceAsStream("/photos/$name.jpg")!!.use { ImageIO.read(it) }
        val argb = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
        return Picture(img.width, img.height, argb)
    }
}

/**
 * Contact sheets for photo clarity: each test photo in all 14 styles at 96 dp (xxhdpi), drawn the way the app's
 * DotMatrix draws them, next to the photo itself and build 1's converter (DotArt: Shades, Three levels, Dither).
 * For the face there is also a before / after sheet: the old centre crop against face focus and Portrait.
 * Writes the thumbnails and one sheet per photo to `<screens.out>/studio/photo-styles/`.
 */
class PhotoStyleSheets {
    private val p = DeviceProfile.Phone3
    private val out: File? = System.getProperty("screens.out")?.let { File(it, "studio/photo-styles") }

    private fun slug(label: String) = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    @Test fun contactSheets() {
        assumeTrue(out != null)
        val dir = out!!.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        for (name in TestPhotos.names) {
            val pic = TestPhotos.load(name)
            val samples = PhotoDots.sample(p, pic, PhotoCrop())
            val styles = PhotoStyle.entries.map { st -> st.label to PhotoDots.render(p, samples, PhotoSettings(style = st)) }
            styles.forEachIndexed { i, (label, f) -> ImageIO.write(dots(f, THUMB), "png", File(dir, "$name-${"%02d".format(i + 1)}-${slug(label)}.png")) }
            val build1 = listOf(DotArtMode.GREY, DotArtMode.POSTERISE, DotArtMode.DITHER).map { m ->
                "Build 1: ${m.label}" to DotArt.frame(p, pic.width, pic.height, pic.argb, m)
            }
            ImageIO.write(
                sheet("$name: 14 styles at 96 dp (centre crop, default settings), with build 1 for comparison", pic, PhotoCrop(), listOf("Photo (centre crop)" to null) + build1, styles),
                "png", File(dir, "$name-sheet.png"),
            )
        }
        // the face, focused: what a photo with a face now opens with
        val pic = TestPhotos.load("face")
        val focus = PhotoDots.defaults(pic, listOf(TestPhotos.face))
        val samples = PhotoDots.sample(p, pic, focus.crop)
        val before = PhotoDots.render(p, PhotoDots.sample(p, pic, PhotoCrop()), PhotoSettings())
        val styles = PhotoStyle.strip(face = true).map { st -> st.label to PhotoDots.render(p, samples, focus.copy(style = st)) }
        styles.forEachIndexed { i, (label, f) -> ImageIO.write(dots(f, THUMB), "png", File(dir, "face-focus-${"%02d".format(i + 1)}-${slug(label)}.png")) }
        val top = listOf(
            "Before: centre crop" to null,
            "Before: Shades" to before,
            "Build 1: Shades" to DotArt.frame(p, pic.width, pic.height, pic.argb, DotArtMode.GREY),
            "After: Portrait" to styles.first { it.first == "Portrait" }.second,
            "After: Shades" to styles.first { it.first == "Shades" }.second,
        )
        ImageIO.write(sheet("face: before and after face focus (crop on the face + 35%, Detail ${focus.detail})", pic, focus.crop, top, styles, beforeCrop = PhotoCrop()), "png", File(dir, "face-focus-sheet.png"))
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

    /** The square of [pic] that [crop] takes (fill, no turns), as the photo tile. */
    private fun cropped(pic: Picture, crop: PhotoCrop): BufferedImage {
        val src = BufferedImage(pic.width, pic.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, pic.width, pic.height, pic.argb, 0, pic.width) }
        val side = (minOf(pic.width, pic.height) / crop.zoom).toInt()
        val x0 = (crop.cx * pic.width - side / 2).toInt().coerceIn(0, maxOf(0, pic.width - side))
        val y0 = (crop.cy * pic.height - side / 2).toInt().coerceIn(0, maxOf(0, pic.height - side))
        val out = BufferedImage(THUMB, THUMB, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, THUMB, THUMB, x0, y0, x0 + side, y0 + side, null)
        g.dispose()
        return out
    }

    /** A title, a first row (tiles with a null frame show the photo as cropped), then the styles six to a row. */
    private fun sheet(
        title: String, pic: Picture, crop: PhotoCrop, first: List<Pair<String, Frame?>>, styles: List<Pair<String, Frame>>, beforeCrop: PhotoCrop? = null,
    ): BufferedImage {
        val cols = 6
        val cellW = THUMB + 24
        val cellH = THUMB + 48
        val rows = 1 + (styles.size + cols - 1) / cols
        val img = BufferedImage(cols * cellW + 24, rows * cellH + 80, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        g.drawString(title, 24, 48)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        fun tile(i: Int, row: Int, label: String, image: BufferedImage) {
            val x = 24 + i * cellW
            val y = 80 + row * cellH
            g.drawImage(image, x, y, null)
            g.color = Color(0xCC, 0xCC, 0xCC)
            g.drawString(label, x, y + THUMB + 30)
        }
        var photos = 0
        first.forEachIndexed { i, (label, f) ->
            val image = f?.let { dots(it, THUMB) } ?: cropped(pic, if (photos++ == 0 && beforeCrop != null) beforeCrop else crop)
            tile(i, 0, label, image)
        }
        // the focused crop itself, last in the first row of the before / after sheet
        if (beforeCrop != null) tile(first.size, 0, "After: face crop", cropped(pic, crop))
        styles.forEachIndexed { i, (label, f) -> tile(i % cols, 1 + i / cols, label, dots(f, THUMB)) }
        g.dispose()
        return img
    }

    private companion object {
        /** 96 dp at xxhdpi (3x), the Phone (3)'s density. */
        const val THUMB = 288
    }
}
