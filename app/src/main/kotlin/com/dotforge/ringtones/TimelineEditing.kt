package com.dotforge.ringtones

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dotforge.composition.Key
import com.dotforge.composition.Lane
import com.dotforge.composition.Region
import com.dotforge.composition.Shape
import com.dotforge.composition.Timeline

/**
 * The timeline being edited, with undo. Changes that come in a stream (dragging a key, moving a slider) call
 * [checkpoint] once when they start; [undo] skips steps that changed nothing.
 */
class TimelineEditorState(initial: Timeline, val imported: List<IntArray>?) {
    var timeline by mutableStateOf(initial)
        private set
    private val history = ArrayDeque<Timeline>()
    var canUndo by mutableStateOf(false)
        private set

    /** The key whose sheet is open (lane id to key id). */
    var keySheet by mutableStateOf<Pair<Int, Int>?>(null)
    /** The lane whose sheet is open. */
    var laneSheet by mutableStateOf<Int?>(null)
    var addLaneSheet by mutableStateOf(false)
    /** The key last touched, drawn highlighted. */
    var selected by mutableStateOf<Pair<Int, Int>?>(null)

    fun checkpoint() {
        if (history.lastOrNull() == timeline) return
        history.addLast(timeline)
        if (history.size > MAX_UNDO) history.removeFirst()
        canUndo = true
    }

    fun undo() {
        while (history.isNotEmpty()) {
            val t = history.removeLast()
            if (t != timeline) {
                timeline = t
                break
            }
        }
        canUndo = history.isNotEmpty()
        keySheet = null
        laneSheet = null
    }

    private fun setLane(lane: Lane) {
        timeline = timeline.copy(lanes = timeline.lanes.map { if (it.id == lane.id) lane else it })
    }

    private fun clampFrame(f: Int) = f.coerceIn(0, (timeline.frames - 1).coerceAtLeast(0))

    /** Adds a key at [frame] with [level] percent; it takes the shape of the key before it. */
    fun addKey(laneId: Int, frame: Int, level: Int) {
        val lane = timeline.lane(laneId) ?: return
        checkpoint()
        val id = (lane.keys.maxOfOrNull { it.id } ?: -1) + 1
        val f = clampFrame(frame)
        val shape = lane.keys.filter { it.frame <= f }.maxByOrNull { it.frame }?.shape ?: Shape.HOLD
        setLane(lane.copy(keys = (lane.keys + Key(id, f, level.coerceIn(0, 100), shape)).sortedBy { it.frame }))
        selected = laneId to id
    }

    /** Moves a key (no checkpoint: call [checkpoint] when the drag starts). */
    fun moveKey(laneId: Int, keyId: Int, frame: Int, level: Int) {
        val lane = timeline.lane(laneId) ?: return
        setLane(lane.copy(keys = lane.keys.map { if (it.id == keyId) it.copy(frame = clampFrame(frame), level = level.coerceIn(0, 100)) else it }.sortedBy { it.frame }))
    }

    fun editKey(laneId: Int, keyId: Int, shape: Shape? = null, level: Int? = null) {
        val lane = timeline.lane(laneId) ?: return
        setLane(lane.copy(keys = lane.keys.map { k -> if (k.id == keyId) k.copy(shape = shape ?: k.shape, level = (level ?: k.level).coerceIn(0, 100)) else k }))
    }

    fun deleteKey(laneId: Int, keyId: Int) {
        val lane = timeline.lane(laneId) ?: return
        checkpoint()
        setLane(lane.copy(keys = lane.keys.filterNot { it.id == keyId }))
        keySheet = null
        selected = null
    }

    fun addLane(region: Region, part: Int) {
        checkpoint()
        timeline = timeline.copy(lanes = timeline.lanes + Lane(timeline.nextLaneId(), region, part))
    }

    fun removeLane(laneId: Int) {
        checkpoint()
        timeline = timeline.copy(lanes = timeline.lanes.filterNot { it.id == laneId })
        laneSheet = null
    }

    /** Trim and scale of the imported lane (no checkpoint: the sheet makes one when it opens). */
    fun editImported(laneId: Int, trimStart: Int, trimEnd: Int, scale: Float) {
        val lane = timeline.lane(laneId) ?: return
        val end = if (trimEnd >= timeline.frames) -1 else trimEnd
        setLane(lane.copy(trimStart = clampFrame(trimStart), trimEnd = end, scale = scale.coerceIn(0f, 2f)))
    }

    /** What is saved in CUSTOM1: the imported rows only while an imported lane is left. */
    fun forSaving(): Timeline =
        if (timeline.lanes.any { it.region == Region.IMPORTED }) timeline else timeline.copy(imported = null)

    private companion object {
        const val MAX_UNDO = 50
    }
}
