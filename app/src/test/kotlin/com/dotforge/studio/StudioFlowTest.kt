package com.dotforge.studio

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.BackgroundLayer
import com.dotforge.device.Brush
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.PhotoDots
import com.dotforge.device.PhotoMotion
import com.dotforge.device.PhotoStyle
import com.dotforge.device.TextBand
import com.dotforge.screens.ScreenshotApp
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Tab
import com.dotforge.ui.editor.Drafts
import com.dotforge.ui.editor.EditorSession
import com.dotforge.ui.studio.PhotoSession
import com.dotforge.ui.studio.Picked
import com.dotforge.ui.studio.StudioText
import com.dotforge.ui.studio.TextDraft
import com.dotforge.ui.studio.parseClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class)
class StudioFlowTest {
    private val p get() = AppGraph.profile

    @Test fun subtitlesNameFramesAndSecondsNeverStillOrAnimation() {
        val d = Design(name = "A", animated = true, frames = "")
        assertEquals("12 frames · 2.4 s", StudioText.subtitle(d, Animation(p, List(12) { com.dotforge.device.AnimFrame(Frame.blank(p), 200) })))
        assertEquals("1 frame", StudioText.subtitle(d, Animation.single(Frame.blank(p), 100)))
        assertEquals("Text", StudioText.subtitle(d.copy(kind = DesignKind.TEXT), null))
        assertEquals("12 s", StudioText.seconds(12_000))
        assertEquals("100 ms", StudioText.seconds(100))
        // one format: never 150 ms rounded to "0.2 s"
        assertEquals("150 ms", StudioText.seconds(150))
        assertEquals("1.2 s", StudioText.seconds(1200))
        assertEquals("1 s", StudioText.seconds(1000))
    }

    @Test fun aNewEditorDesignIsOnlySavedOnceItHasDots() {
        val s = EditorSession.open(null, animated = false, draft = null)
        assertFalse(s.save())
        assertNull(AppGraph.designs.get(s.designId))
        s.edit { brush(Brush(), 12, 12) }
        assertTrue(s.save())
        val saved = AppGraph.designs.get(s.designId)!!
        assertEquals(DesignKind.DRAW, saved.kind)
        assertEquals(255, AppGraph.designs.animation(saved)!!.frames[0].frame.pixels[12 * p.size + 12])
        // Clear and drawing again still works, and is saved
        s.edit { clearFrame() }
        s.edit { brush(Brush(), 10, 10) }
        s.save()
        assertEquals(255, AppGraph.designs.animation(AppGraph.designs.get(s.designId)!!)!!.frames[0].frame.pixels[10 * p.size + 10])
        s.close()
    }

    @Test fun closingANewEmptyDesignLeavesNothing() {
        val before = AppGraph.designs.designs.value.size
        val s = EditorSession.open(null, animated = true, draft = null)
        s.edit { addBlank() }
        s.close()
        assertEquals(before, AppGraph.designs.designs.value.size)
        assertNull(AppGraph.designs.editing.value)
    }

    @Test fun aDraftIsKeptAtOnceAndAuthorTravelsWithIt() {
        val key = Drafts.put("Imported", Effects.pulse(p), author = "Ana")
        val s = EditorSession.open(null, animated = true, draft = Drafts.take(key))
        val d = AppGraph.designs.get(s.designId)!!
        assertEquals("Imported", d.name)
        assertEquals("Ana", d.author)
        assertTrue(d.animated)
        s.close()
    }

    @Test fun pickedJsonOpensTheEditorAndOtherFilesSaySo() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = ctx.cacheDir
        val json = File(dir, "heart.json").apply { writeText(Effects.pulse(p).toMuseumJson()) }
        val nav = Nav(Tab.STUDIO)
        assertNull(Picked.route(ctx, nav, listOf(Uri.fromFile(json))))
        val page = nav.page as Page.Editor
        assertTrue(page.animated)
        assertEquals("heart", Drafts.take(page.draft)!!.name)

        val junk = File(dir, "notes.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(Picked.CANT_READ, Picked.route(ctx, Nav(Tab.STUDIO), listOf(Uri.fromFile(junk))))
    }

    @Test fun photoSettingsUndoOneDragAsOneStep() {
        val s = PhotoSession(p, "Sky", listOf(PhotoDots.samplePicture()), listOf(1000))
        s.set(s.settings.copy(brightness = 10), key = "BRIGHTNESS")
        s.set(s.settings.copy(brightness = 20), key = "BRIGHTNESS")
        s.set(s.settings.copy(brightness = 30), key = "BRIGHTNESS")
        s.set(s.settings.copy(style = PhotoStyle.HALFTONE))
        s.undo()
        assertEquals(PhotoStyle.SHADES, s.settings.style)
        assertEquals(30, s.settings.brightness)
        s.undo()
        assertEquals(0, s.settings.brightness)
        assertFalse(s.canUndo)
        s.redo(); s.redo()
        assertEquals(PhotoStyle.HALFTONE, s.settings.style)
        assertFalse(s.canRedo)
    }

    @Test fun aPhotoWithAFaceOpensOnTheFaceInPortraitAndTheFaceChipToggles() {
        val pic = PhotoDots.samplePicture(320, 400)
        com.dotforge.ui.studio.PhotoLoader.faces = { listOf(com.dotforge.device.FaceSpot(160f, 150f, 40f)) }
        val s = try { com.dotforge.ui.studio.PhotoLoader.session("Me", listOf(pic), listOf(1000)) } finally { com.dotforge.ui.studio.PhotoLoader.faces = com.dotforge.ui.studio.FaceFinder::find }
        assertEquals(PhotoStyle.PORTRAIT, s.settings.style)
        assertEquals(PhotoStyle.PORTRAIT, s.styles.first())
        assertTrue(s.settings.face)
        assertEquals(s.faceCrop, s.settings.crop)
        assertTrue(s.settings.crop.zoom > 1.5f)
        // Face off: the whole photo, one undo step
        s.face(false)
        assertEquals(com.dotforge.device.PhotoCrop(), s.settings.crop)
        assertFalse(s.settings.face)
        s.face(true)
        assertEquals(s.faceCrop, s.settings.crop)
        s.undo()
        assertEquals(com.dotforge.device.PhotoCrop(), s.settings.crop)
        // no face: no Face chip, Shades first, and nothing to toggle
        val plain = PhotoSession(p, "Sky", listOf(pic), listOf(1000))
        assertNull(plain.faceCrop)
        assertEquals(PhotoStyle.SHADES, plain.styles.first())
        // several pictures are never searched for faces
        com.dotforge.ui.studio.PhotoLoader.faces = { error("not for several pictures") }
        try { assertNull(com.dotforge.ui.studio.PhotoLoader.session("Trip", listOf(pic, pic), listOf(500, 500)).faceCrop) } finally { com.dotforge.ui.studio.PhotoLoader.faces = com.dotforge.ui.studio.FaceFinder::find }
    }

    @Test fun theFaceFinderNeverThrows() {
        // Robolectric has no native face detector: no faces, no crash
        assertEquals(emptyList<com.dotforge.device.FaceSpot>(), com.dotforge.ui.studio.FaceFinder.find(PhotoDots.samplePicture(321, 200)))
    }

    @Test fun severalPhotosBecomeFramesAndOneCanMove() {
        val pics = List(3) { PhotoDots.samplePicture() }
        val frames = PhotoSession(p, "Trip", pics, List(3) { PhotoSession.PHOTO_FRAME_MS })
        assertEquals(3, frames.render().frames.size)
        assertEquals(1500, frames.render().totalMs)
        val one = PhotoSession(p, "Sky", pics.take(1), listOf(PhotoSession.STILL_MS))
        assertEquals(1, one.render().frames.size)
        assertEquals(12, one.motion(PhotoMotion.ZOOM, 12, 80).frames.size)
    }

    @Test fun severalPhotosCanBeReorderedAndRemovedWithUndo() {
        val pics = listOf(PhotoDots.samplePicture(100, 80), PhotoDots.samplePicture(120, 90), PhotoDots.samplePicture(140, 100))
        val s = PhotoSession(p, "Trip", pics, listOf(400, 500, 600))
        val firstFrame = s.render().frames[0].frame.pixels.toList()
        s.move(0, 2)
        assertEquals(listOf(pics[1], pics[2], pics[0]), s.pictures)
        assertEquals(listOf(500, 600, 400), s.delays)
        assertEquals(firstFrame, s.render().frames[2].frame.pixels.toList()) // the frame went with its photo
        s.remove(1)
        assertEquals(listOf(pics[1], pics[0]), s.pictures)
        assertEquals(900, s.render().totalMs)
        s.remove(0)
        assertFalse(s.frames) // one photo left: a still again, so Animate is back
        s.remove(0)
        assertEquals(1, s.pictures.size) // the last photo always stays
        s.undo(); s.undo(); s.undo()
        assertEquals(pics, s.pictures)
        assertEquals(listOf(400, 500, 600), s.delays)
        s.redo()
        assertEquals(listOf(pics[1], pics[2], pics[0]), s.pictures)
    }

    @Test fun textIsSavedAsTextWithItsBackgroundAsLayers() {
        val t = TextDraft.open(null, "ON MY WAY")
        assertEquals("ON MY WAY", t.name)
        val plain = t.design()
        assertEquals(DesignKind.TEXT, plain.kind)
        assertEquals("ON MY WAY", plain.text!!.text)
        assertNull(plain.layers)

        t.background = BackgroundLayer(Effects.pulse(p).toMuseumJson(), "Pulse")
        t.spec = t.spec.copy(band = TextBand.BOTTOM)
        val layered = t.design()
        assertNotNull(layered.layers?.background)
        assertEquals("ON MY WAY", layered.layers!!.text!!.spec.text)
        assertTrue(AppGraph.designs.animation(layered)!!.frames.size > 1)
    }

    @Test fun startsAtReadsMinutesAndSeconds() {
        assertEquals(65_000L, parseClock("1:05"))
        assertEquals(42_000L, parseClock("0:42"))
        assertNull(parseClock("1:5"))
        assertNull(parseClock("1:75"))
        assertNull(parseClock("abc"))
    }
}
