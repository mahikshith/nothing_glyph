package com.dotforge.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Brush
import com.dotforge.device.Clip
import com.dotforge.device.EditorDoc
import com.dotforge.device.EditorTool
import com.dotforge.device.Frame
import com.dotforge.device.LayerKind
import com.dotforge.device.Layers
import com.dotforge.device.Playback
import com.dotforge.device.Region
import com.dotforge.device.Timing
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.studio.TracePhoto
import java.io.File
import java.util.UUID

/** New designs that aren't saved yet (an import, a library pick, photo frames), handed to the editor by key. In memory only. */
object Drafts {
    class Draft(val name: String, val animation: Animation, val author: String? = null, val layers: Layers? = null, val kind: DesignKind = DesignKind.DRAW)

    private val drafts = HashMap<String, Draft>()
    private var next = 0

    fun put(name: String, animation: Animation, author: String? = null, layers: Layers? = null, kind: DesignKind = DesignKind.DRAW): String = synchronized(drafts) {
        "d${next++}".also { drafts[it] = Draft(name, animation, author, layers, kind) }
    }

    fun take(key: String?): Draft? = key?.let { synchronized(drafts) { drafts.remove(it) } }
}

/** Copied dots stay for the whole session, across frames and designs (row 21). */
object EditorClipboard {
    var clip: Clip? = null
    var from: Region? = null
}

/** The five tools of the tools row. */
enum class Tool(val label: String) { DRAW("Draw"), ERASE("Erase"), FILL("Fill"), SHAPES("Shapes"), SELECT("Select") }

/**
 * One open editor: the document, the tools' state, playback, saving and Show on phone. Lives as long as the
 * editor page; every change goes through [edit] so Compose redraws.
 */
class EditorSession(
    val designId: String,
    val isNew: Boolean,
    val doc: EditorDoc,
    val kind: DesignKind,
    private val existing: Design?,
    val author: String?,
    /** Animation was picked in + New: the frame strip shows from the start. */
    startAnimated: Boolean,
) {
    /** Bumped on every change, read by the UI. */
    var tick by mutableIntStateOf(0)
        private set

    var tool by mutableStateOf(Tool.DRAW)
    var shape by mutableStateOf(EditorTool.LINE)
    var filled by mutableStateOf(false)
    var brush by mutableStateOf(Brush())
    var picking by mutableStateOf(false)
    var selection by mutableStateOf<Region?>(null)
    var layer by mutableStateOf(LayerKind.DRAWING)
    var onion by mutableStateOf(true)
    var showOnPhone by mutableStateOf(true)
    var animatedView by mutableStateOf(startAnimated || doc.size > 1)

    /** The matrix's pinch zoom (1x-3x) and two-finger pan; Fit sets them back. */
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    val playback = Playback(doc.durations)
    var playing by mutableStateOf(false)
        private set
    /** The frame playback shows (the editor's current frame while paused). */
    var shown by mutableIntStateOf(doc.cur)
        private set
    var speed by mutableStateOf(Playback.Speed.NORMAL)
        private set

    /** "Saved" / "Saving…" under the name. */
    var saving by mutableStateOf(false)
    private var savedEdits = doc.edits

    /** The trace photo under the grid (a guide only: [visible] and [design] frames never include it). */
    var trace by mutableStateOf(existing?.trace)
        private set
    private var savedTrace = existing?.trace
    /** Trace photos replaced or removed in this session, kept until close so Undo can bring them back. */
    private val oldTraces = HashSet<String>()

    /** Sets, changes or removes the trace photo; saved with the design but not on the undo stack. */
    fun changeTrace(t: TracePhoto?) {
        trace?.file?.takeIf { it != t?.file }?.let { oldTraces += it }
        trace = t?.clamped()
        tick++
    }

    /**
     * Photo to dots "Add as frame": [a]'s frames go in after the current frame as one undo step, and the first
     * is selected. A single photo takes the current frame's time. Returns how many went in (0 at 300 frames).
     */
    fun addFrames(a: Animation): Int {
        val add = if (a.frames.size == 1) Animation.single(a.frames[0].frame, doc.durations[doc.cur]) else a
        val before = doc.size
        edit { insertAfter(add) }
        if (doc.size > before) animatedView = true
        return doc.size - before
    }
    var everSaved = existing != null
        private set

    fun edit(block: EditorDoc.() -> Unit) {
        doc.block()
        if (doc.size > 1) animatedView = true
        playback.sync(doc.durations)
        if (!playing) { playback.seek(doc.cur); shown = doc.cur }
        tick++
    }

    fun select(i: Int) = edit { select(i) }

    fun togglePlay() {
        if (playing) pause() else {
            playback.speed = speed
            playback.seek(doc.cur)
            playback.play()
            playing = playback.playing
        }
        tick++
    }

    fun pause() {
        playback.pause()
        playing = false
        doc.select(playback.frame)
        shown = doc.cur
        tick++
    }

    fun step(forward: Boolean) {
        if (forward) playback.stepForward() else playback.stepBack()
        playing = false
        doc.select(playback.frame)
        shown = doc.cur
        tick++
    }

    fun scrub(ms: Int) {
        playback.scrubTo(ms)
        playing = false
        doc.select(playback.frame)
        shown = doc.cur
        tick++
    }

    fun cycleSpeed() {
        speed = Playback.Speed.entries[(speed.ordinal + 1) % Playback.Speed.entries.size]
        playback.speed = speed
        tick++
    }

    /** Advances playback by [ms] of wall time; returns how long until the next frame. */
    fun tickPlayback(ms: Long): Long {
        if (playback.tick(ms)) { shown = playback.frame; tick++ }
        if (!playback.playing) { playing = false; doc.select(playback.frame); tick++ }
        return playback.msToNextFrame()
    }

    /** What the matrix shows now: the composited layers, or the frame being drawn. */
    fun visible(): IntArray {
        val l = doc.layers
        val i = if (playing) shown else doc.cur
        if (l == null) return doc.frames[i]
        val start = doc.durations.take(i).sum()
        return com.dotforge.device.Compositor.frameAt(AppGraph.profile, l, Animation(AppGraph.profile, listOf(com.dotforge.device.AnimFrame(Frame(AppGraph.profile, doc.frames[i]), doc.durations[i]))), start)
    }

    val hasDots: Boolean get() = doc.frames.any { f -> f.any { it > 0 } } || doc.layers != null
    val dirty: Boolean get() = doc.edits != savedEdits || trace != savedTrace

    /** The saved form of the document. */
    fun design(): Design {
        val drawn = doc.animation()
        val plays = doc.rendered()
        return Design(
            id = designId,
            name = doc.name,
            animated = plays.frames.size > 1,
            frames = plays.toMuseumJson(),
            updatedAt = System.currentTimeMillis(),
            timing = doc.timing.takeIf { it != Timing() },
            // text or a photo edited as dots is a drawing from then on
            kind = DesignKind.DRAW,
            text = null,
            photo = null,
            photoFile = null,
            layers = doc.layers,
            drawing = if (doc.layers != null) drawn.toMuseumJson() else null,
            author = author,
            trace = trace,
        )
    }

    /** Autosave: writes the design when anything changed. A new design with no lit dots is never written. */
    fun save(): Boolean {
        if (!dirty && everSaved) return true
        if (isNew && !everSaved && !hasDots) return false
        saving = true
        return try {
            AppGraph.designs.update(design())
            savedEdits = doc.edits
            savedTrace = trace
            everSaved = true
            true
        } catch (t: Exception) {
            false
        } finally {
            saving = false
        }
    }

    /** Close: saves, and drops a new design that was left with no lit dots. */
    fun close() {
        val dropped = isNew && !hasDots
        if (dropped) AppGraph.designs.delete(designId) else save()
        // trace photos that were replaced or removed, and a dropped new design's own
        val keep = if (dropped) null else trace?.file
        (oldTraces + listOfNotNull(trace?.file)).filter { it != keep }.forEach { File(AppGraph.designs.media, it).delete() }
        AppGraph.designs.endEditing()
        AppGraph.arbiter.remove(PHONE_ID)
    }

    // ---- Show on phone

    /** Mirrors what the matrix shows to the Glyph (debounced by the caller to 100 ms). */
    fun mirror() {
        if (!showOnPhone) { AppGraph.arbiter.remove(PHONE_ID); return }
        val a = if (playing) Animation(AppGraph.profile, doc.rendered().frames) else Animation.single(Frame.of(AppGraph.profile, visible()), 60_000)
        AppGraph.arbiter.post(DisplayRequest(PHONE_ID, Priority.STUDIO_PREVIEW, a, loop = true, label = "Editor: ${doc.name}"))
    }

    fun release() = AppGraph.arbiter.remove(PHONE_ID)

    companion object {
        const val PHONE_ID = "editor"

        /** Opens [designId], or a new design from [draft] or blank. */
        fun open(designId: String?, animated: Boolean, draft: Drafts.Draft?): EditorSession {
            val p = AppGraph.profile
            val d = AppGraph.designs.get(designId)
            if (d != null) {
                val drawing = d.drawing?.takeIf { d.layers != null }?.let { runCatching { Animation.fromMuseumJson(p, it) }.getOrNull() }
                val a = drawing ?: AppGraph.designs.animation(d) ?: Animation.single(Frame.blank(p), Playback.DEFAULT_MS)
                val doc = EditorDoc(p, a, name = d.name, timing = d.timing ?: Timing(), layers = d.layers)
                return EditorSession(d.id, false, doc, d.kind, d, d.author, d.animated)
            }
            val name = draft?.name?.take(EditorDoc.MAX_NAME) ?: AppGraph.designs.nextName()
            val a = draft?.animation ?: Animation.single(Frame.blank(p), Playback.DEFAULT_MS)
            val doc = EditorDoc(p, a, name = name, layers = draft?.layers)
            val s = EditorSession(UUID.randomUUID().toString(), true, doc, draft?.kind ?: DesignKind.DRAW, null, draft?.author, animated || a.frames.size > 1)
            // an import or library pick is already worth keeping
            if (draft != null) s.save()
            return s
        }
    }
}
