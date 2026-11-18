package com.dotforge.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDocTest {
    private val p = DeviceProfile.Phone3
    private fun blankDoc() = EditorDoc(p, Animation.single(Frame.blank(p), 150))
    private fun lit(px: IntArray) = px.count { it > 0 }
    private fun at(doc: EditorDoc, x: Int, y: Int) = doc.current[p.index(x, y)]

    /** One tap with [tool] at (x, y), as the editor does it: a stroke of one point. */
    private fun tap(doc: EditorDoc, tool: EditorTool, x: Int, y: Int) {
        doc.beginStroke()
        EditorDoc.apply(doc, tool, null, x, y, 255)
        doc.endStroke()
    }

    /** A drag with [tool] from (x0, y0) to (x1, y1) through the middle point. */
    private fun drag(doc: EditorDoc, tool: EditorTool, x0: Int, y0: Int, x1: Int, y1: Int) {
        doc.beginStroke()
        val anchor = x0 to y0
        EditorDoc.apply(doc, tool, anchor, x0, y0, 255)
        EditorDoc.apply(doc, tool, anchor, (x0 + x1) / 2, (y0 + y1) / 2, 255)
        EditorDoc.apply(doc, tool, anchor, x1, y1, 255)
        doc.endStroke()
    }

    @Test fun aStrokeIsOneUndoStep() {
        val doc = blankDoc()
        doc.beginStroke()
        for (x in 5..15) EditorDoc.apply(doc, EditorTool.PEN, null, x, 12, 255)
        doc.endStroke()
        assertEquals(11, lit(doc.current))
        assertEquals(1, doc.undoSteps)
        doc.undo()
        assertEquals(0, lit(doc.current))
        doc.redo()
        assertEquals(11, lit(doc.current))
    }

    @Test fun shapeToolsRedrawFromTheStrokeStart() {
        val doc = blankDoc()
        tap(doc, EditorTool.PEN, 6, 6)
        doc.beginStroke()
        EditorDoc.apply(doc, EditorTool.LINE, 5 to 12, 20, 12, 255)
        EditorDoc.apply(doc, EditorTool.LINE, 5 to 12, 10, 12, 255)
        doc.endStroke()
        // only the last line is left, plus the dot from before
        assertEquals(255, at(doc, 10, 12))
        assertEquals(0, at(doc, 15, 12))
        assertEquals(255, at(doc, 6, 6))
    }

    /** "Can't draw after Clear": drawing must work after clear frame, clear all and undo, with every tool. */
    @Test fun drawingWorksAfterClearClearAllAndUndoForEveryTool() {
        for (tool in EditorTool.entries) {
            val doc = blankDoc()
            doc.addBlank()
            drag(doc, EditorTool.PEN, 6, 8, 18, 8)
            var row = 14
            fun drawsWith(where: String) {
                row++
                // erase needs something to erase
                if (tool == EditorTool.ERASE) {
                    tap(doc, EditorTool.PEN, 12, 12)
                    tap(doc, tool, 12, 12)
                    assertEquals("$tool $where", 0, at(doc, 12, 12))
                    return
                }
                if (tool == EditorTool.FILL) {
                    tap(doc, EditorTool.ERASE, 12, row)
                    tap(doc, tool, 12, row)
                    assertEquals("$tool $where", 255, at(doc, 12, row))
                    return
                }
                val before = lit(doc.current)
                if (tool == EditorTool.PEN) tap(doc, tool, 12, row) else drag(doc, tool, 8, row, 16, row)
                assertTrue("$tool $where", lit(doc.current) > before)
            }
            doc.clearFrame()
            assertEquals(0, lit(doc.current))
            drawsWith("after clear frame")
            doc.clearAll()
            assertEquals(1, doc.size)
            drawsWith("after clear all")
            doc.undo()
            drawsWith("after undo")
            doc.undo()
            doc.redo()
            drawsWith("after redo")
            doc.select(0)
            drawsWith("after switching frames")
            doc.replaceAll(AnimatePreset.BLINK.apply(Frame.of(p, doc.current)))
            doc.select(1)
            drawsWith("after a preset")
        }
    }

    @Test fun clearFrameVersusClearAllWithUndoAndRedo() {
        val doc = blankDoc()
        tap(doc, EditorTool.PEN, 12, 12)
        doc.addCopy()
        tap(doc, EditorTool.PEN, 13, 12)
        doc.addCopy()
        tap(doc, EditorTool.PEN, 14, 12)
        val all = doc.frames.map { it.copyOf() }
        assertEquals(3, doc.size)
        assertEquals(2, doc.cur)

        doc.select(1)
        doc.clearFrame()
        assertEquals(3, doc.size)
        assertEquals(0, lit(doc.frames[1]))
        assertArrayEquals(all[0], doc.frames[0])
        assertArrayEquals(all[2], doc.frames[2])
        doc.undo()
        assertArrayEquals(all[1], doc.frames[1])
        doc.redo()
        assertEquals(0, lit(doc.frames[1]))
        doc.undo()

        doc.clearAll()
        assertEquals(1, doc.size)
        assertEquals(0, lit(doc.current))
        doc.undo()
        assertEquals(3, doc.size)
        doc.frames.indices.forEach { assertArrayEquals(all[it], doc.frames[it]) }
        doc.redo()
        assertEquals(1, doc.size)
        assertEquals(0, lit(doc.current))
    }

    @Test fun frameOperationsAndTimingAreUndoable() {
        val doc = blankDoc()
        tap(doc, EditorTool.PEN, 12, 12)
        doc.addCopy()
        doc.addBlank()
        assertEquals(3, doc.size)
        assertEquals(2, doc.cur)
        doc.moveFrame(2, 0)
        assertEquals(0, lit(doc.frames[0]))
        doc.setDuration(1, 500)
        doc.setAllDurations(80)
        doc.setLoop(false)
        assertTrue(doc.deleteFrame())
        assertEquals(2, doc.size)
        repeat(5) { assertTrue(doc.undo()) }
        assertEquals(listOf(150, 150, 150), doc.durations)
        assertTrue(doc.loop)
        assertEquals(3, doc.size)
        assertEquals(255, doc.frames[0][p.index(12, 12)])
        assertEquals(0, lit(doc.frames[2]))
        repeat(5) { assertTrue(doc.redo()) }
        assertEquals(2, doc.size)
        assertEquals(listOf(80, 80), doc.durations)
        assertFalse(doc.loop)
        assertFalse(doc.redo())
    }

    @Test fun theLastFrameCantBeDeleted() {
        val doc = blankDoc()
        assertFalse(doc.deleteFrame())
        assertFalse(doc.canUndo)
    }

    @Test fun keepsAtLeastAHundredSteps() {
        val doc = blankDoc()
        for (i in 0 until 130) tap(doc, if (i % 2 == 0) EditorTool.PEN else EditorTool.ERASE, 12, 12)
        var n = 0
        while (doc.undo()) n++
        assertTrue("$n", n >= 100)
    }

    @Test fun aNewEditDropsTheRedoSteps() {
        val doc = blankDoc()
        tap(doc, EditorTool.PEN, 12, 12)
        doc.undo()
        assertTrue(doc.canRedo)
        tap(doc, EditorTool.PEN, 13, 12)
        assertFalse(doc.canRedo)
    }

    @Test fun frameArraysAreNeverChangedInPlace() {
        val doc = blankDoc()
        val shown = doc.current
        tap(doc, EditorTool.PEN, 12, 12)
        assertEquals(0, lit(shown))
        doc.clearFrame()
        assertEquals(0, lit(doc.current))
    }

    @Test fun importIntoTheEditorKeepsThePixels() {
        val a = AnimatePreset.ROTATE.apply(GlyphLibrary.get("star")!!.still(p))
        val json = a.toMuseumJson()
        val doc = EditorDoc(p, Animation.fromMuseumJson(p, json))
        assertEquals(a.frames.size, doc.size)
        a.frames.indices.forEach {
            assertArrayEquals(a.frames[it].frame.pixels, doc.frames[it])
            assertEquals(a.frames[it].durationMs, doc.durations[it])
        }
        // drawing on top keeps the rest, and the saved animation round-trips
        tap(doc, EditorTool.PEN, 0, 12)
        val back = Animation.fromMuseumJson(p, doc.animation().toMuseumJson())
        assertEquals(255, back.frames[0].frame.pixels[p.index(0, 12)])
        assertArrayEquals(a.frames[1].frame.pixels, back.frames[1].frame.pixels)
    }

    @Test fun libraryGlyphGoesIntoTheCurrentFrameAsOneStep() {
        val doc = blankDoc()
        val g = GlyphLibrary.get("bell")!!.still(p).pixels
        doc.setCurrent(g)
        assertArrayEquals(g, doc.current)
        doc.undo()
        assertEquals(0, lit(doc.current))
    }

    // ---- the rebuilt editor's operations

    private fun stroke(doc: EditorDoc, brush: Brush, vararg pts: Pair<Int, Int>, erase: Boolean = false) {
        doc.beginStroke()
        for ((x, y) in pts) doc.brush(brush, x, y, erase)
        doc.endStroke()
    }

    @Test fun brushSizesAndMirror() {
        val doc = blankDoc()
        stroke(doc, Brush(size = 1), 12 to 12)
        assertEquals(1, lit(doc.current))
        stroke(doc, Brush(size = 2), 5 to 12)
        assertEquals(5, lit(doc.current))
        stroke(doc, Brush(size = 3), 18 to 12)
        assertEquals(10, lit(doc.current))
        doc.clearFrame()
        stroke(doc, Brush(mirror = Mirror.BOTH), 8 to 9)
        assertEquals(4, lit(doc.current))
        assertEquals(255, at(doc, 24 - 8, 24 - 9))
        assertEquals(1, doc.undoSteps - 4) // each stroke is one step (3 strokes, a clear, the mirrored stroke)
    }

    @Test fun shadesBrightenDimAndReplace() {
        val doc = blankDoc()
        stroke(doc, Brush(value = 111), 12 to 12)
        assertEquals(111, at(doc, 12, 12))
        // brighten changes each dot once per stroke, however often the finger passes
        stroke(doc, Brush(mode = BrushMode.BRIGHTEN), 12 to 12, 12 to 12, 12 to 12)
        assertEquals(111 + Brush.STEP, at(doc, 12, 12))
        stroke(doc, Brush(mode = BrushMode.DIM), 12 to 12, 12 to 12)
        assertEquals(111, at(doc, 12, 12))
        doc.addCopy()
        doc.replaceShade(111, 255, all = false)
        assertEquals(255, at(doc, 12, 12))
        assertEquals(111, doc.frames[0][p.index(12, 12)])
        doc.replaceShade(255, 48, all = true)
        assertEquals(48, at(doc, 12, 12))
        doc.undo()
        assertEquals(255, at(doc, 12, 12))
        assertEquals(8, Brush.SHADES.size)
    }

    @Test fun selectMoveCopyPasteFlipAndDelete() {
        val doc = blankDoc()
        drag(doc, EditorTool.PEN, 8, 10, 10, 10)
        val r = Region(8, 10, 10, 10)
        // dragging the selection moves it, as one step
        doc.beginStroke()
        doc.moveRegion(r, 1, 0)
        doc.moveRegion(r, 2, 1)
        doc.endStroke()
        assertEquals(0, at(doc, 8, 10))
        assertEquals(255, at(doc, 10, 11))
        assertEquals(255, at(doc, 12, 11))
        doc.undo()
        assertEquals(255, at(doc, 8, 10))
        // copy and paste keeps the copy
        val clip = Regions.copy(doc.current, p, r)
        doc.paste(clip, 8, 14)
        assertEquals(6, lit(doc.current))
        // flip a lopsided region
        doc.clearFrame()
        tap(doc, EditorTool.PEN, 8, 10)
        doc.flipRegion(Region(8, 10, 10, 10), horizontal = true)
        assertEquals(0, at(doc, 8, 10))
        assertEquals(255, at(doc, 10, 10))
        doc.deleteRegion(Region(0, 0, 24, 24))
        assertEquals(0, lit(doc.current))
        // and drawing still works after it all
        tap(doc, EditorTool.PEN, 12, 12)
        assertEquals(255, at(doc, 12, 12))
    }

    @Test fun effectsOnThisFrameOrAllFrames() {
        val doc = blankDoc()
        tap(doc, EditorTool.PEN, 3, 12)
        doc.addCopy()
        doc.change(FrameOp.FLIP_H, all = false)
        assertEquals(255, at(doc, 21, 12))
        assertEquals(255, doc.frames[0][p.index(3, 12)])
        doc.change(FrameOp.SHIFT_DOWN, all = true)
        assertEquals(255, doc.frames[0][p.index(3, 13)])
        assertEquals(255, at(doc, 21, 13))
        doc.undo()
        doc.undo()
        assertEquals(255, at(doc, 3, 12))
    }

    @Test fun outlineFillInAndLevels() {
        val f = Frame.blank(p)
        Draw.box(f, 8, 8, 16, 16, 255)
        val o = FrameOps.outline(f)
        assertEquals(0, o.pixels[p.index(12, 12)])
        assertEquals(255, o.pixels[p.index(8, 12)])
        val filled = FrameOps.fillIn(o)
        assertEquals(255, filled.pixels[p.index(12, 12)])
        assertEquals(0, filled.pixels[p.index(2, 12)])
        val faint = Frame.of(p, IntArray(p.cellCount) { if (it == p.index(12, 12)) 40 else if (it == p.index(13, 12)) 20 else 0 })
        val lv = FrameOps.levels(faint)
        assertEquals(255, lv.pixels[p.index(12, 12)])
        assertEquals(64, lv.pixels[p.index(13, 12)])
    }

    @Test fun nameAndTimingAreOnTheUndoStack() {
        val doc = EditorDoc(p, Animation.single(Frame.blank(p), 150), name = "Design 3")
        doc.rename("  Heart  ")
        assertEquals("Heart", doc.name)
        doc.rename("")
        assertEquals("Heart", doc.name)
        doc.rename("x".repeat(40))
        assertEquals(30, doc.name.length)
        doc.setTiming(Timing(speed = 2f))
        doc.undo()
        assertEquals(1f, doc.timing.speed)
        doc.undo()
        assertEquals("Heart", doc.name)
        doc.undo()
        assertEquals("Design 3", doc.name)
    }

    @Test fun framesStopAtThreeHundred() {
        val doc = blankDoc()
        repeat(EditorDoc.MAX_FRAMES - 1) { assertTrue(doc.addBlank()) }
        assertFalse(doc.canAddFrame)
        assertFalse(doc.addCopy())
        assertEquals(EditorDoc.MAX_FRAMES, doc.size)
    }

    @Test fun makeItMoveReplacesOnlyTheCurrentFrame() {
        val doc = blankDoc()
        tap(doc, EditorTool.PEN, 12, 12)
        doc.addBlank()
        doc.select(0)
        doc.replaceCurrentWith(AnimatePreset.BLINK.apply(Frame.of(p, doc.current)))
        assertTrue(doc.size > 2)
        assertEquals(0, lit(doc.frames.last()))
        doc.undo()
        assertEquals(2, doc.size)
    }

    @Test fun shapesCanBeFilledAndMirrored() {
        val doc = blankDoc()
        doc.beginStroke()
        EditorDoc.apply(doc, EditorTool.BOX, 4 to 4, 7, 7, 255, filled = true, mirror = Mirror.H)
        doc.endStroke()
        assertEquals(32, lit(doc.current))
        assertEquals(255, at(doc, 20, 5))
        doc.clearFrame()
        doc.beginStroke()
        EditorDoc.apply(doc, EditorTool.CIRCLE, 12 to 12, 15, 12, 255, filled = true)
        doc.endStroke()
        assertEquals(255, at(doc, 12, 12))
    }
}
