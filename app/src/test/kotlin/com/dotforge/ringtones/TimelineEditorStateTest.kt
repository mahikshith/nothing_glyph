package com.dotforge.ringtones

import com.dotforge.composition.Region
import com.dotforge.composition.Shape
import com.dotforge.composition.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEditorStateTest {
    private fun editor() = TimelineEditorState(Timeline.fromImported(List(120) { IntArray(625) }), null)

    @Test fun addMoveAndUndo() {
        val e = editor()
        assertFalse(e.canUndo)
        e.addLane(Region.RING, 1)
        val lane = e.timeline.lanes.last().id
        e.addKey(lane, 30, 80)
        e.addKey(lane, 10, 40)
        assertEquals(listOf(10, 30), e.timeline.lane(lane)!!.keys.map { it.frame })
        val first = e.timeline.lane(lane)!!.keys.first()

        e.checkpoint() // a drag starts
        e.moveKey(lane, first.id, 50, 100)
        e.moveKey(lane, first.id, 500, 120) // clamped to the timeline
        assertEquals(listOf(30, 119), e.timeline.lane(lane)!!.keys.map { it.frame })
        assertEquals(100, e.timeline.lane(lane)!!.keys.last().level)

        e.undo() // the whole drag
        assertEquals(listOf(10, 30), e.timeline.lane(lane)!!.keys.map { it.frame })
        e.undo()
        e.undo()
        assertTrue(e.timeline.lane(lane)!!.keys.isEmpty())
        e.undo()
        assertNull(e.timeline.lane(lane))
        assertFalse(e.canUndo)
    }

    @Test fun newKeysTakeThePreviousShape() {
        val e = editor()
        e.addLane(Region.WHOLE, 0)
        val lane = e.timeline.lanes.last().id
        e.addKey(lane, 0, 100)
        val k = e.timeline.lane(lane)!!.keys.single()
        e.editKey(lane, k.id, shape = Shape.FADE)
        e.addKey(lane, 60, 0)
        assertEquals(Shape.FADE, e.timeline.lane(lane)!!.keys.last().shape)
    }

    @Test fun savingDropsImportedRowsWithoutTheLane() {
        val e = editor()
        assertTrue(e.forSaving().imported != null)
        e.removeLane(e.timeline.lanes.single { it.region == Region.IMPORTED }.id)
        assertNull(e.forSaving().imported)
        e.editImported(0, 5, 500, 3f) // no such lane any more: ignored
    }

    @Test fun importedTrimAndScaleAreClamped() {
        val e = editor()
        e.editImported(0, -3, 500, 3f)
        val l = e.timeline.lane(0)!!
        assertEquals(0, l.trimStart)
        assertEquals(-1, l.trimEnd)
        assertEquals(2f, l.scale)
    }
}
