package com.dotforge.device

/**
 * Playback of the frames being edited, as a small state machine the UI ticks: play and pause, step one frame,
 * scrub, speed (0.5x, 1x, 2x) and loop. While playing the matrix doesn't draw: the first tap pauses.
 * Durations come from the editor and may change at any time ([sync]).
 */
class Playback(durations: List<Int>, loop: Boolean = true) {
    enum class Speed(val factor: Double, val label: String) { HALF(0.5, "0.5x"), NORMAL(1.0, "1x"), DOUBLE(2.0, "2x") }

    var durations: List<Int> = durations.ifEmpty { listOf(DEFAULT_MS) }
        private set
    var frame = 0
        private set
    var playing = false
        private set
    var loop = loop
    var speed = Speed.NORMAL

    /** Time spent on [frame] so far, in ms of the animation's own time. */
    private var inFrame = 0.0

    val count: Int get() = durations.size
    val totalMs: Int get() = durations.sum()

    /** Where playback is in the animation's own time, 0 until [totalMs]. */
    val positionMs: Int get() = durations.take(frame).sum() + inFrame.toInt()

    fun play() {
        if (count < 2) return
        // playing from the end of a non-looping run starts again
        if (!loop && frame == count - 1) seek(0)
        playing = true
    }

    fun pause() {
        playing = false
    }

    fun toggle() = if (playing) pause() else play()

    /** A tap on the matrix: pauses and returns true while playing (the tap is used up), else false (draw). */
    fun tapMatrix(): Boolean {
        if (!playing) return false
        pause()
        return true
    }

    fun stepForward() {
        pause()
        seek(if (frame == count - 1) (if (loop) 0 else frame) else frame + 1)
    }

    fun stepBack() {
        pause()
        seek(if (frame == 0) (if (loop) count - 1 else 0) else frame - 1)
    }

    /** Shows frame [i] from its start (the scrubber, the strip, the editor's current frame). */
    fun seek(i: Int) {
        frame = i.coerceIn(0, count - 1)
        inFrame = 0.0
    }

    /** The scrubber across all frames: [ms] of animation time picks the frame it falls in. */
    fun scrubTo(ms: Int) {
        pause()
        var t = ms.coerceIn(0, (totalMs - 1).coerceAtLeast(0))
        for (i in durations.indices) {
            if (t < durations[i]) {
                frame = i
                inFrame = t.toDouble()
                return
            }
            t -= durations[i]
        }
        seek(count - 1)
    }

    /** New durations from the editor; keeps the frame when it still exists. */
    fun sync(newDurations: List<Int>) {
        durations = newDurations.ifEmpty { listOf(DEFAULT_MS) }
        if (frame >= count) seek(count - 1)
        if (count < 2) playing = false
        inFrame = inFrame.coerceAtMost(durations[frame].toDouble())
    }

    /**
     * Advances by [realMs] of wall time; returns true when the frame changed. A non-looping run stops on the
     * last frame.
     */
    fun tick(realMs: Long): Boolean {
        if (!playing) return false
        val start = frame
        inFrame += realMs * speed.factor
        while (inFrame >= durations[frame].coerceAtLeast(Animation.MIN_FRAME_MS)) {
            inFrame -= durations[frame].coerceAtLeast(Animation.MIN_FRAME_MS)
            if (frame == count - 1) {
                if (!loop) {
                    playing = false
                    inFrame = 0.0
                    break
                }
                frame = 0
            } else {
                frame++
            }
        }
        return frame != start
    }

    /** Wall-clock ms until the next frame change at the current speed. */
    fun msToNextFrame(): Long =
        ((durations[frame].coerceAtLeast(Animation.MIN_FRAME_MS) - inFrame) / speed.factor).toLong().coerceAtLeast(1)

    companion object {
        const val DEFAULT_MS = 150
    }
}

/** How much room the editor's pinned parts take, so the matrix gets the rest and the screen never scrolls. */
object EditorLayout {
    /** The biggest matrix, in dp. */
    const val MAX_MATRIX_DP = 330f
    /** Below this the matrix is too small to draw on; the layout check keeps it above. */
    const val MIN_MATRIX_DP = 150f

    /**
     * Height in dp of everything pinned around the matrix at [fontScale]: the top bar (with Undo and Redo), two
     * tool rows, and for animations the frame row (step, "Frame 2 of 5", Play), the scrubber row (speed, loop,
     * show on phone) and the frame strip. Labels under buttons and frames grow with the font; buttons don't.
     */
    fun chromeDp(fontScale: Float, animated: Boolean): Float {
        val label = 16f * fontScale
        val topBar = 56f
        val tools = 2 * (48f + label)
        val frames = if (animated) 48f + 48f + (48f + label + 8f) else 0f
        val gaps = (if (animated) 5 else 2) * 4f
        return topBar + tools + frames + gaps
    }

    /** The matrix side in dp for a window of [widthDp] x [heightDp] (system bars already removed). */
    fun matrixDp(widthDp: Float, heightDp: Float, fontScale: Float, animated: Boolean): Float =
        minOf(MAX_MATRIX_DP, widthDp - 32f, heightDp - chromeDp(fontScale, animated))
}
