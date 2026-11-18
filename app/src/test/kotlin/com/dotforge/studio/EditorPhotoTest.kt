package com.dotforge.studio

import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Brush
import com.dotforge.device.EditorDoc
import com.dotforge.device.Frame
import com.dotforge.device.PhotoDots
import com.dotforge.screens.ScreenshotApp
import com.dotforge.ui.editor.Drafts
import com.dotforge.ui.editor.EditorSession
import com.dotforge.ui.studio.PhotoLoader
import com.dotforge.ui.studio.PhotoSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Photo to dots "Add as frame" into an open design, and the trace photo under the editor's grid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class)
class EditorPhotoTest {
    private val p get() = AppGraph.profile

    /** An animation of [n] frames, frame i lighting dot (i, i), each [ms] long. */
    private fun frames(n: Int, ms: Int = 120) = Animation(p, List(n) { i ->
        AnimFrame(Frame.blank(p).also { it.pixels[p.index(8 + i, 12)] = 255 }, ms)
    })

    private fun session(n: Int = 3): EditorSession =
        EditorSession.open(null, animated = true, draft = Drafts.take(Drafts.put("Walk", frames(n))))

    private fun photoFrame(): Frame = PhotoDots.frame(p, PhotoDots.samplePicture(160, 120), com.dotforge.device.PhotoSettings())

    private fun keepTrace(): String = PhotoLoader.keep(PhotoDots.samplePicture(200, 150), "${java.util.UUID.randomUUID()}-trace")

    @Test fun aPhotoGoesInAfterTheCurrentFrameAsOneUndoStep() {
        val s = session(3)
        val before = s.doc.frames.map { it.copyOf() }
        s.select(1)
        val photo = photoFrame()
        assertEquals(1, s.addFrames(Animation.single(photo, 1000)))
        assertEquals(4, s.doc.size)
        assertEquals(2, s.doc.cur)
        assertArrayEquals(Frame.of(p, photo.pixels).pixels, s.doc.frames[2])
        // a single photo takes the time of the frame it follows, not the still's second
        assertEquals(120, s.doc.durations[2])
        // the frames around it are untouched
        assertArrayEquals(before[1], s.doc.frames[1])
        assertArrayEquals(before[2], s.doc.frames[3])
        s.edit { undo() }
        assertEquals(3, s.doc.size)
        before.forEachIndexed { i, f -> assertArrayEquals(f, s.doc.frames[i]) }
        s.close()
    }

    @Test fun severalPhotosGoInAsFramesWithTheirOwnTimes() {
        val s = EditorSession.open(null, animated = false, draft = null)
        s.edit { brush(Brush(), 12, 12) }
        assertFalse(s.animatedView)
        val photos = PhotoSession(p, "Trip", List(3) { PhotoDots.samplePicture(160 + it * 20, 120) }, List(3) { PhotoSession.PHOTO_FRAME_MS })
        assertEquals(3, s.addFrames(photos.render()))
        assertEquals(4, s.doc.size)
        assertEquals(1, s.doc.cur)
        assertEquals(listOf(PhotoSession.PHOTO_FRAME_MS, PhotoSession.PHOTO_FRAME_MS, PhotoSession.PHOTO_FRAME_MS), s.doc.durations.drop(1))
        // a still becomes an animation, so the frame strip shows
        assertTrue(s.animatedView)
        s.save()
        assertEquals(4, AppGraph.designs.animation(AppGraph.designs.get(s.designId)!!)!!.frames.size)
        s.close()
    }

    @Test fun addingStopsAtThreeHundredFrames() {
        val s = session(EditorDoc.MAX_FRAMES - 1)
        assertEquals(1, s.addFrames(frames(5)))
        assertEquals(EditorDoc.MAX_FRAMES, s.doc.size)
        assertEquals(0, s.addFrames(Animation.single(photoFrame(), 1000)))
        s.close()
    }

    @Test fun theTracePhotoStaysWithTheDesignButNeverInItsFramesOrOnTheGlyph() {
        val s = session(2)
        val shown = s.visible().copyOf()
        val plays = s.design().frames
        val file = keepTrace()
        s.changeTrace(TracePhoto(file, opacity = 40))
        assertTrue(s.dirty)
        assertTrue(s.save())
        // only the editor draws it: the matrix, the saved frames and what the Glyph plays are unchanged
        assertArrayEquals(shown, s.visible())
        assertEquals(plays, s.design().frames)
        assertEquals(plays, AppGraph.designs.get(s.designId)!!.frames)
        s.close()
        // it is there next time, with its opacity
        val again = EditorSession.open(s.designId, animated = true, draft = null)
        assertEquals(TracePhoto(file, opacity = 40, visible = true), again.trace)
        assertTrue(File(AppGraph.designs.media, file).exists())
        again.changeTrace(again.trace!!.copy(visible = false))
        again.close()
        assertFalse(AppGraph.designs.get(s.designId)!!.trace!!.visible)
    }

    @Test fun traceOpacityIsTenToSixtyPercent() {
        val s = session(1)
        s.changeTrace(TracePhoto("a.png", opacity = 90))
        assertEquals(TracePhoto.MAX_OPACITY, s.trace!!.opacity)
        s.changeTrace(TracePhoto("a.png", opacity = 0))
        assertEquals(TracePhoto.MIN_OPACITY, s.trace!!.opacity)
        assertEquals(30, TracePhoto("a.png").opacity)
        s.changeTrace(null)
        s.close()
    }

    @Test fun tracePhotoFilesFollowTheDesign() {
        val s = session(2)
        val first = keepTrace()
        s.changeTrace(TracePhoto(first))
        s.save()
        // changing the photo keeps the old file until close (Undo can bring it back), then removes it
        val second = keepTrace()
        s.changeTrace(TracePhoto(second))
        assertTrue(File(AppGraph.designs.media, first).exists())
        s.close()
        assertFalse(File(AppGraph.designs.media, first).exists())
        assertTrue(File(AppGraph.designs.media, second).exists())

        // a duplicate gets its own copy; deleting the original leaves the copy's
        val copy = AppGraph.designs.duplicate(s.designId)!!
        assertNotEquals(second, copy.trace!!.file)
        assertTrue(File(AppGraph.designs.media, copy.trace!!.file).exists())
        AppGraph.designs.delete(s.designId)
        assertFalse(File(AppGraph.designs.media, second).exists())
        assertTrue(File(AppGraph.designs.media, copy.trace!!.file).exists())

        // removed then brought back with Undo: the file stays
        val t = EditorSession.open(copy.id, animated = true, draft = null)
        val kept = t.trace!!
        t.changeTrace(null)
        t.changeTrace(kept)
        t.close()
        assertTrue(File(AppGraph.designs.media, kept.file).exists())

        // a new design closed with no dots takes its trace photo with it
        val fresh = EditorSession.open(null, animated = false, draft = null)
        val orphan = keepTrace()
        fresh.changeTrace(TracePhoto(orphan))
        fresh.close()
        assertNull(AppGraph.designs.get(fresh.designId))
        assertFalse(File(AppGraph.designs.media, orphan).exists())
    }
}
