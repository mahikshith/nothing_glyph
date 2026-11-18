package com.dotforge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFacesTest {
    private val p = DeviceProfile.Phone3

    @Test fun noFaceNoCrop() {
        assertNull(PhotoFaces.crop(emptyList(), 320, 400))
    }

    @Test fun oneFaceFillsTheCropWithMargin() {
        val crop = PhotoFaces.crop(listOf(TestPhotos.face), 320, 400)!!
        // the face box (2.2 x 2.6 eye distances) plus 35 % is the square's side
        val side = 320 / crop.zoom
        assertEquals(2.6f * 40 * 1.35f, side, 1f)
        assertEquals(167f / 320, crop.cx, 0.01f)
        // centred a little below the eyes, so the mouth and chin fit
        assertTrue(crop.cy * 400 in 127f..150f)
    }

    @Test fun severalFacesShareOneBox() {
        val a = FaceSpot(100f, 200f, 30f)
        val b = FaceSpot(300f, 210f, 30f)
        val crop = PhotoFaces.crop(listOf(a, b), 600, 400)!!
        val side = 400 / crop.zoom
        val left = crop.cx * 600 - side / 2
        val right = crop.cx * 600 + side / 2
        assertTrue("both faces inside: $left..$right", left <= 100 - 33 && right >= 300 + 33)
    }

    @Test fun tinyFaceZoomIsCapped() {
        val crop = PhotoFaces.crop(listOf(FaceSpot(2000f, 1500f, 4f)), 4000, 3000)!!
        assertEquals(8f, crop.zoom, 0.001f)
    }

    @Test fun defaultsByContent() {
        val face = PhotoDots.defaults(TestPhotos.load("face"), listOf(TestPhotos.face))
        assertEquals(PhotoStyle.PORTRAIT, face.style)
        assertTrue(face.face)
        assertTrue(face.detail > PhotoSettings().detail)
        assertEquals(PhotoStyle.TONES2, PhotoDots.defaults(TestPhotos.load("logo"), emptyList()).style)
        for (name in listOf("face", "pet", "landscape")) assertEquals(name, PhotoStyle.SHADES, PhotoDots.defaults(TestPhotos.load(name), emptyList()).style)
    }

    @Test fun graphicDetection() {
        assertTrue(PhotoDots.looksLikeGraphic(TestPhotos.load("logo")))
        for (name in listOf("face", "pet", "landscape")) assertFalse(name, PhotoDots.looksLikeGraphic(TestPhotos.load(name)))
    }

    @Test fun portraitLeadsTheStripOnlyWithAFace() {
        assertEquals(PhotoStyle.PORTRAIT, PhotoStyle.strip(face = true).first())
        assertEquals(PhotoStyle.SHADES, PhotoStyle.strip(face = false).first())
        assertEquals(PhotoStyle.entries.toSet(), PhotoStyle.strip(true).toSet())
        assertEquals(PhotoStyle.entries.size, PhotoStyle.strip(true).size)
    }

    @Test fun portraitDarkensFeaturesAndOutlinesTheFace() {
        val pic = TestPhotos.load("face")
        val s = PhotoDots.defaults(pic, listOf(TestPhotos.face))
        val f = PhotoDots.frame(p, pic, s)
        val n = p.size
        val lit = p.ledCells.filter { f.pixels[it] > 0 }
        // a face, not a blob or nothing: a fair share lit, and off dots inside the middle (eyes, mouth)
        assertTrue("lit ${lit.size}", lit.size in p.ledCells.size / 5..p.ledCells.size * 3 / 4)
        val middle = p.ledCells.filter { kotlin.math.hypot(it % n - (n - 1) / 2.0, it / n - (n - 1) / 2.0) <= 5 }
        assertTrue("features in the middle", middle.count { f.pixels[it] == 0 } >= 4)
        // 6 tones at most
        assertTrue(f.pixels.toSet().size <= 6)
    }

    @Test fun faceTonesGiveTheFeaturesContrast() {
        val pic = TestPhotos.load("face")
        val focus = PhotoDots.defaults(pic, listOf(TestPhotos.face)).copy(style = PhotoStyle.SHADES)
        val withTones = PhotoDots.frame(p, pic, focus)
        val without = PhotoDots.frame(p, pic, focus.copy(face = false))
        fun spread(f: Frame) = p.ledCells.map { f.pixels[it] }.let { v -> v.sorted()[v.size * 9 / 10] - v.sorted()[v.size / 10] }
        assertTrue("${spread(withTones)} vs ${spread(without)}", spread(withTones) > spread(without))
    }
}
