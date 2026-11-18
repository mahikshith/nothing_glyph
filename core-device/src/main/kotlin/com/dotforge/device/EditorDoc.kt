package com.dotforge.device

/**
 * The Create editor's document: frames, their durations, the selected frame and the loop switch, with undo
 * and redo over every change (drawing, clearing, adding, copying, deleting and moving frames, presets,
 * timing). Frames are never changed in place: each edit makes a new array, so a snapshot is cheap and the
 * frame on screen is always the one in [frames]. Not thread-safe; use it from the main thread.
 */
class EditorDoc(val profile: DeviceProfile, start: Animation, loop: Boolean = true, private val limit: Int = HISTORY) {
    /** One version of the document. Arrays in a state are never written after it is made. */
    class State(val frames: List<IntArray>, val durations: List<Int>, val cur: Int, val loop: Boolean)

    private var state = State(start.frames.map { Frame.of(profile, it.frame.pixels).pixels }, start.frames.map { it.durationMs }, 0, loop)
    private val undoStack = ArrayDeque<State>()
    private val redoStack = ArrayDeque<State>()

    /** The state at the start of a stroke, while one is going on. */
    private var strokeBase: State? = null

    /** Bumped on every change, for the UI to redraw. */
    var version = 0
        private set

    val frames: List<IntArray> get() = state.frames
    val durations: List<Int> get() = state.durations
    val cur: Int get() = state.cur
    val loop: Boolean get() = state.loop
    val size: Int get() = state.frames.size
    val current: IntArray get() = state.frames[state.cur]
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoSteps: Int get() = undoStack.size

    fun animation(): Animation =
        Animation(profile, state.frames.indices.map { AnimFrame(Frame(profile, state.frames[it].copyOf()), state.durations[it]) })

    private fun commit(next: State) {
        endStroke()
        undoStack.addLast(state)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        state = next
        version++
    }

    private fun withFrame(i: Int, pixels: IntArray): List<IntArray> = state.frames.toMutableList().also { it[i] = pixels }

    // ---- drawing ----

    /**
     * Starts a stroke: everything drawn until [endStroke] is one undo step. Calls to [draw] outside a stroke
     * are their own step.
     */
    fun beginStroke() {
        endStroke()
        strokeBase = state
    }

    /** The current frame as it was when the stroke began (shape tools redraw from it on every move). */
    fun strokeStart(): IntArray = (strokeBase ?: state).frames[state.cur]

    /** Replaces the current frame with [edit] applied to a copy of [from] (default: the current frame). */
    fun draw(from: IntArray = current, edit: (Frame) -> Unit) {
        val f = Frame(profile, from.copyOf())
        edit(f)
        val px = Frame.of(profile, f.pixels).pixels
        if (px.contentEquals(current)) return
        if (strokeBase == null) {
            commit(State(withFrame(state.cur, px), state.durations, state.cur, state.loop))
        } else {
            state = State(withFrame(state.cur, px), state.durations, state.cur, state.loop)
            version++
        }
    }

    /** Ends a stroke; records it as one step when it changed anything. */
    fun endStroke() {
        val base = strokeBase ?: return
        strokeBase = null
        if (base.frames[base.cur].contentEquals(state.frames[state.cur]) && base.frames.size == state.frames.size) return
        undoStack.addLast(base)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Puts a picture into the current frame (a library glyph). */
    fun setCurrent(pixels: IntArray) = draw { f -> Frame.of(profile, pixels).pixels.copyInto(f.pixels) }

    fun clearFrame() {
        if (current.all { it == 0 }) return
        commit(State(withFrame(state.cur, IntArray(profile.cellCount)), state.durations, state.cur, state.loop))
    }

    /** Starts over with one blank frame (timing and loop stay). */
    fun clearAll() = commit(State(listOf(IntArray(profile.cellCount)), listOf(state.durations[state.cur]), 0, state.loop))

    // ---- frames ----

    fun select(i: Int) {
        endStroke()
        if (i !in state.frames.indices || i == state.cur) return
        state = State(state.frames, state.durations, i, state.loop)
        version++
    }

    /** A copy of the current frame, right after it, selected. */
    fun addCopy() = insert(current.copyOf())

    /** A blank frame right after the current one, selected. */
    fun addBlank() = insert(IntArray(profile.cellCount))

    private fun insert(px: IntArray) {
        val at = state.cur + 1
        val frames = state.frames.toMutableList().also { it.add(at, px) }
        val durations = state.durations.toMutableList().also { it.add(at, state.durations[state.cur]) }
        commit(State(frames, durations, at, state.loop))
    }

    /** Deletes the current frame; the last one can't be deleted. */
    fun deleteFrame(): Boolean {
        if (state.frames.size < 2) return false
        val i = state.cur
        val frames = state.frames.toMutableList().also { it.removeAt(i) }
        val durations = state.durations.toMutableList().also { it.removeAt(i) }
        commit(State(frames, durations, (i - 1).coerceAtLeast(0), state.loop))
        return true
    }

    /** Moves frame [from] to position [to]; it stays selected. */
    fun moveFrame(from: Int, to: Int) {
        if (from !in state.frames.indices || to !in state.frames.indices || from == to) return
        val frames = state.frames.toMutableList().also { it.add(to, it.removeAt(from)) }
        val durations = state.durations.toMutableList().also { it.add(to, it.removeAt(from)) }
        commit(State(frames, durations, to, state.loop))
    }

    // ---- timing ----

    fun setDuration(i: Int, ms: Int) {
        val v = ms.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)
        if (i !in state.durations.indices || state.durations[i] == v) return
        commit(State(state.frames, state.durations.toMutableList().also { it[i] = v }, state.cur, state.loop))
    }

    /** The global speed: every frame gets [ms]. */
    fun setAllDurations(ms: Int) {
        val v = ms.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)
        if (state.durations.all { it == v }) return
        commit(State(state.frames, List(state.frames.size) { v }, state.cur, state.loop))
    }

    fun setLoop(on: Boolean) {
        if (on == state.loop) return
        commit(State(state.frames, state.durations, state.cur, on))
    }

    /** Replaces every frame (an "Animate this" preset); the first new frame is selected. */
    fun replaceAll(a: Animation) =
        commit(State(a.frames.map { Frame.of(profile, it.frame.pixels).pixels }, a.frames.map { it.durationMs }, 0, state.loop))

    // ---- history ----

    fun undo(): Boolean {
        endStroke()
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(state)
        state = prev
        version++
        return true
    }

    fun redo(): Boolean {
        endStroke()
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state)
        state = next
        version++
        return true
    }

    companion object {
        /** Undo steps kept. */
        const val HISTORY = 150

        /** The editor's drawing for one tap or drag point: [tool] at ([x], [y]) with [value], from [anchor]. */
        fun apply(doc: EditorDoc, tool: EditorTool, anchor: Pair<Int, Int>?, x: Int, y: Int, value: Int) {
            when (tool) {
                EditorTool.PEN -> doc.draw { it[x, y] = value }
                EditorTool.ERASE -> doc.draw { it[x, y] = 0 }
                EditorTool.FILL -> doc.draw { Draw.fill(it, x, y, value) }
                EditorTool.LINE, EditorTool.BOX, EditorTool.CIRCLE -> {
                    val (ax, ay) = anchor ?: (x to y)
                    doc.draw(from = doc.strokeStart()) { f ->
                        when (tool) {
                            EditorTool.LINE -> Draw.line(f, ax, ay, x, y, value)
                            EditorTool.BOX -> Draw.rect(f, ax, ay, x, y, value)
                            else -> Draw.circle(f, ax, ay, x, y, value)
                        }
                    }
                }
            }
        }
    }
}

/** The editor's tools; shapes are dragged from one corner (or the centre) to the other. */
enum class EditorTool(val label: String) { PEN("Draw"), ERASE("Erase"), FILL("Fill"), LINE("Line"), BOX("Box"), CIRCLE("Circle") }
