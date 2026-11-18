package com.dotforge.device

/**
 * The editor's document: frames, their durations, the selected frame, the name, the design's timing and its
 * layers, with undo and redo over every change (strokes, fills, shapes, moves, pastes, clears, effects, frame
 * operations, durations, timing, layers and renames). Frames are never changed in place: each edit makes a new
 * array, so a snapshot is cheap and the frame on screen is always the one in [frames]. Not thread-safe; use it
 * from the main thread.
 */
class EditorDoc(
    val profile: DeviceProfile,
    start: Animation,
    loop: Boolean = true,
    private val limit: Int = HISTORY,
    name: String = "",
    timing: Timing = Timing(),
    layers: Layers? = null,
) {
    /** One version of the document. Arrays in a state are never written after it is made. */
    class State(
        val frames: List<IntArray>,
        val durations: List<Int>,
        val cur: Int,
        val loop: Boolean,
        val name: String = "",
        val timing: Timing = Timing(),
        val layers: Layers? = null,
    ) {
        fun with(
            frames: List<IntArray> = this.frames, durations: List<Int> = this.durations, cur: Int = this.cur, loop: Boolean = this.loop,
            name: String = this.name, timing: Timing = this.timing, layers: Layers? = this.layers,
        ) = State(frames, durations, cur, loop, name, timing, layers)
    }

    private var state = State(
        start.frames.map { Frame.of(profile, it.frame.pixels).pixels }, start.frames.map { it.durationMs }, 0, loop, name, timing, layers,
    )
    private val undoStack = ArrayDeque<State>()
    private val redoStack = ArrayDeque<State>()

    /** The state at the start of a stroke, while one is going on. */
    private var strokeBase: State? = null

    /** Dots a Brighten or Dim stroke has already changed (each changes once per stroke). */
    private val strokeTouched = HashSet<Int>()

    /** Bumped on every change, for the UI to redraw. */
    var version = 0
        private set

    /** Bumped on every change that should be saved (not on selecting a frame). */
    var edits = 0
        private set

    val frames: List<IntArray> get() = state.frames
    val durations: List<Int> get() = state.durations
    val cur: Int get() = state.cur
    val loop: Boolean get() = state.loop
    val name: String get() = state.name
    val timing: Timing get() = state.timing
    val layers: Layers? get() = state.layers
    val size: Int get() = state.frames.size
    val current: IntArray get() = state.frames[state.cur]
    val canUndo: Boolean get() = undoStack.isNotEmpty() || strokeBase != null
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoSteps: Int get() = undoStack.size
    val canAddFrame: Boolean get() = state.frames.size < MAX_FRAMES
    /** Lit dots in the current frame (TalkBack reads "120 of 489 dots lit"). */
    val litDots: Int get() = current.count { it > 0 }
    val totalMs: Int get() = state.durations.sum()

    fun animation(): Animation =
        Animation(profile, state.frames.indices.map { AnimFrame(Frame(profile, state.frames[it].copyOf()), state.durations[it]) })

    /** What plays: the drawing alone, or all the layers flattened. */
    fun rendered(): Animation = state.layers?.let { Compositor.render(profile, it, animation()) } ?: animation()

    private fun commit(next: State) {
        endStroke()
        undoStack.addLast(state)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        state = next
        version++
        edits++
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
        strokeTouched.clear()
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
            commit(state.with(frames = withFrame(state.cur, px)))
        } else {
            state = state.with(frames = withFrame(state.cur, px))
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
        edits++
    }

    /** A Draw touch with [brush] at ([x], [y]) inside a stroke: paint, or brighten / dim each dot once. */
    fun brush(brush: Brush, x: Int, y: Int, erase: Boolean = false) {
        val cells = brush.cells(profile, x, y)
        draw { f ->
            for (c in cells) {
                f.pixels[c] = when {
                    erase -> 0
                    brush.mode == BrushMode.PAINT -> brush.value.coerceIn(0, 255)
                    !strokeTouched.add(c) -> f.pixels[c]
                    brush.mode == BrushMode.BRIGHTEN -> (f.pixels[c] + Brush.STEP).coerceAtMost(255)
                    else -> (f.pixels[c] - Brush.STEP).coerceAtLeast(0)
                }
            }
        }
    }

    /** Every dot of shade [from] becomes [to], in this frame or all frames. */
    fun replaceShade(from: Int, to: Int, all: Boolean) {
        val target = to.coerceIn(0, 255)
        val frames = state.frames.mapIndexed { i, f ->
            if (!all && i != state.cur) f else IntArray(f.size) { c -> if (profile.mask[c] && f[c] == from) target else f[c] }
        }
        if (frames.indices.all { frames[it].contentEquals(state.frames[it]) }) return
        commit(state.with(frames = frames))
    }

    /** An Effects "Change it" step on this frame or all frames. */
    fun change(op: FrameOp, all: Boolean) {
        val frames = state.frames.mapIndexed { i, f -> if (!all && i != state.cur) f else op.apply(Frame(profile, f.copyOf())).pixels }
        if (frames.indices.all { frames[it].contentEquals(state.frames[it]) }) return
        commit(state.with(frames = frames))
    }

    // ---- select, copy, paste, move

    /** Clears [r] in the current frame. */
    fun deleteRegion(r: Region) = draw { Regions.erase(it, r) }

    /** Mirrors [r]'s dots in place. */
    fun flipRegion(r: Region, horizontal: Boolean) {
        val clip = Regions.flipped(Regions.copy(current, profile, r), horizontal)
        draw { f -> Regions.erase(f, r); Regions.stamp(f, clip, r.left, r.top) }
    }

    /**
     * Moves [r]'s dots by ([dx], [dy]) from where they were when the stroke began (call inside a stroke while
     * dragging; outside one, a nudge is its own step).
     */
    fun moveRegion(r: Region, dx: Int, dy: Int) {
        val from = if (strokeBase != null) strokeStart() else current
        val clip = Regions.copy(from, profile, r)
        draw(from = from) { f -> Regions.erase(f, r); Regions.stamp(f, clip, r.left + dx, r.top + dy, opaque = false) }
    }

    /** Puts [clip] with its top left at ([x], [y]). */
    fun paste(clip: Clip, x: Int, y: Int) = draw { f -> Regions.stamp(f, clip, x, y, opaque = false) }

    // ---- name, timing, layers

    fun rename(name: String) {
        val v = name.trim().take(MAX_NAME)
        if (v.isEmpty() || v == state.name) return
        commit(state.with(name = v))
    }

    fun setTiming(t: Timing) {
        val v = t.clamped()
        if (v == state.timing) return
        commit(state.with(timing = v))
    }

    fun setLayers(l: Layers?) {
        if (l == state.layers) return
        commit(state.with(layers = l))
    }

    /** Layers become plain frames: the design keeps how it looks and loses its layers. */
    fun flatten() {
        val l = state.layers ?: return
        val a = Compositor.render(profile, l, animation())
        commit(state.with(frames = a.frames.map { it.frame.pixels.copyOf() }, durations = a.frames.map { it.durationMs }, cur = 0, layers = null))
    }

    /** Puts a picture into the current frame (a library glyph). */
    fun setCurrent(pixels: IntArray) = draw { f -> Frame.of(profile, pixels).pixels.copyInto(f.pixels) }

    fun clearFrame() {
        if (current.all { it == 0 }) return
        commit(state.with(frames = withFrame(state.cur, IntArray(profile.cellCount))))
    }

    /** Starts over with one blank frame (timing, name and layers stay). */
    fun clearAll() = commit(state.with(frames = listOf(IntArray(profile.cellCount)), durations = listOf(state.durations[state.cur]), cur = 0))

    // ---- frames ----

    fun select(i: Int) {
        endStroke()
        if (i !in state.frames.indices || i == state.cur) return
        state = state.with(cur = i)
        version++
    }

    /** A copy of the current frame, right after it, selected; false at [MAX_FRAMES]. */
    fun addCopy(): Boolean = insert(current.copyOf())

    /** A blank frame right after the current one, selected; false at [MAX_FRAMES]. */
    fun addBlank(): Boolean = insert(IntArray(profile.cellCount))

    private fun insert(px: IntArray): Boolean {
        if (!canAddFrame) return false
        val at = state.cur + 1
        val frames = state.frames.toMutableList().also { it.add(at, px) }
        val durations = state.durations.toMutableList().also { it.add(at, state.durations[state.cur]) }
        commit(state.with(frames = frames, durations = durations, cur = at))
        return true
    }

    /** "Make it move": the current frame becomes [a]'s frames (the first selected). */
    fun replaceCurrentWith(a: Animation) {
        val i = state.cur
        val frames = state.frames.toMutableList().also { it.removeAt(i); it.addAll(i, a.frames.map { f -> Frame.of(profile, f.frame.pixels).pixels }) }
        val durations = state.durations.toMutableList().also { it.removeAt(i); it.addAll(i, a.frames.map { f -> f.durationMs }) }
        commit(state.with(frames = frames.take(MAX_FRAMES), durations = durations.take(MAX_FRAMES), cur = i))
    }

    /** Insert from library: [a]'s frames go in after the current frame (up to 300 in all); the first is selected. */
    fun insertAfter(a: Animation): Boolean {
        val room = MAX_FRAMES - state.frames.size
        if (room <= 0) return false
        val add = a.frames.take(room)
        val at = state.cur + 1
        val frames = state.frames.toMutableList().also { it.addAll(at, add.map { f -> Frame.of(profile, f.frame.pixels).pixels }) }
        val durations = state.durations.toMutableList().also { it.addAll(at, add.map { f -> f.durationMs }) }
        commit(state.with(frames = frames, durations = durations, cur = at))
        return true
    }

    /** Insert a still on top of the current frame: its lit dots replace the ones under them. */
    fun stamp(f: Frame) = draw { out -> for (i in f.pixels.indices) if (f.pixels[i] > 0 && profile.isActive(i % profile.size, i / profile.size)) out.pixels[i] = f.pixels[i] }

    /** Deletes the current frame; the last one can't be deleted. */
    fun deleteFrame(): Boolean {
        if (state.frames.size < 2) return false
        val i = state.cur
        val frames = state.frames.toMutableList().also { it.removeAt(i) }
        val durations = state.durations.toMutableList().also { it.removeAt(i) }
        commit(state.with(frames = frames, durations = durations, cur = (i - 1).coerceAtLeast(0)))
        return true
    }

    /** Moves frame [from] to position [to]; it stays selected. */
    fun moveFrame(from: Int, to: Int) {
        if (from !in state.frames.indices || to !in state.frames.indices || from == to) return
        val frames = state.frames.toMutableList().also { it.add(to, it.removeAt(from)) }
        val durations = state.durations.toMutableList().also { it.add(to, it.removeAt(from)) }
        commit(state.with(frames = frames, durations = durations, cur = to))
    }

    // ---- timing ----

    fun setDuration(i: Int, ms: Int) {
        val v = ms.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)
        if (i !in state.durations.indices || state.durations[i] == v) return
        commit(state.with(durations = state.durations.toMutableList().also { it[i] = v }))
    }

    /** The global speed: every frame gets [ms]. */
    fun setAllDurations(ms: Int) {
        val v = ms.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)
        if (state.durations.all { it == v }) return
        commit(state.with(durations = List(state.frames.size) { v }))
    }

    fun setLoop(on: Boolean) {
        if (on == state.loop) return
        commit(state.with(loop = on))
    }

    /** Replaces every frame (an "Animate this" preset); the first new frame is selected. */
    fun replaceAll(a: Animation) =
        commit(state.with(frames = a.frames.map { Frame.of(profile, it.frame.pixels).pixels }.take(MAX_FRAMES), durations = a.frames.map { it.durationMs }.take(MAX_FRAMES), cur = 0))

    // ---- history ----

    fun undo(): Boolean {
        endStroke()
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(state)
        state = prev
        version++
        edits++
        return true
    }

    fun redo(): Boolean {
        endStroke()
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state)
        state = next
        version++
        edits++
        return true
    }

    companion object {
        /** Undo steps kept. */
        const val HISTORY = 150
        /** Frames a design can have. */
        const val MAX_FRAMES = 300
        const val MAX_NAME = 30

        /** The editor's drawing for one tap or drag point: [tool] at ([x], [y]) with [value], from [anchor]. */
        fun apply(doc: EditorDoc, tool: EditorTool, anchor: Pair<Int, Int>?, x: Int, y: Int, value: Int, filled: Boolean = false, mirror: Mirror = Mirror.OFF) {
            when (tool) {
                EditorTool.PEN -> doc.draw { it[x, y] = value }
                EditorTool.ERASE -> doc.draw { it[x, y] = 0 }
                EditorTool.FILL -> doc.draw { Draw.fill(it, x, y, value) }
                EditorTool.LINE, EditorTool.BOX, EditorTool.CIRCLE -> {
                    val (ax, ay) = anchor ?: (x to y)
                    val n = doc.profile.size - 1
                    doc.draw(from = doc.strokeStart()) { f ->
                        fun shape(x0: Int, y0: Int, x1: Int, y1: Int) = when (tool) {
                            EditorTool.LINE -> Draw.line(f, x0, y0, x1, y1, value)
                            EditorTool.BOX -> if (filled) Draw.box(f, x0, y0, x1, y1, value) else Draw.rect(f, x0, y0, x1, y1, value)
                            else -> if (filled) Draw.disc(f, x0, y0, x1, y1, value) else Draw.circle(f, x0, y0, x1, y1, value)
                        }
                        shape(ax, ay, x, y)
                        if (mirror == Mirror.H || mirror == Mirror.BOTH) shape(n - ax, ay, n - x, y)
                        if (mirror == Mirror.V || mirror == Mirror.BOTH) shape(ax, n - ay, x, n - y)
                        if (mirror == Mirror.BOTH) shape(n - ax, n - ay, n - x, n - y)
                    }
                }
            }
        }
    }
}

/** The editor's tools; shapes are dragged from one corner (or the centre) to the other. */
enum class EditorTool(val label: String) { PEN("Draw"), ERASE("Erase"), FILL("Fill"), LINE("Line"), BOX("Box"), CIRCLE("Circle") }
