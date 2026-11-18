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
}
