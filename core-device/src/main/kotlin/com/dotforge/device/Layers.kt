package com.dotforge.device

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the background does under the dots behind the letters. */
@Serializable
enum class BehindText(val label: String, val hint: String) {
    CLEAR("None", "Letters straight on the picture"),
    DIM("Dim", "The picture dims behind the letters"),
    CUT_OUT("Cut-out", "The picture goes dark round each letter"),
}

@Serializable
enum class BackgroundEffect(val label: String) {
    STILL("Still"), BLINK("Blink"), BREATHE("Breathe"), ZOOM("Slow zoom"), PAN("Pan"), PLAY("Play animation");
}

@Serializable
enum class LayerKind(val label: String) { BACKGROUND("Background"), TEXT("Text"), DRAWING("Drawing") }

/** A picture or animation behind: a library design, one of yours, or a photo. [frames] is Glyph Museum JSON. */
@Serializable
data class BackgroundLayer(
    val frames: String,
    val name: String,
    val effect: BackgroundEffect = BackgroundEffect.STILL,
    val brightness: Int = 100,
    val visible: Boolean = true,
)

/** Words in front, in a band of rows, with their own style (the Text screen's styles). */
@Serializable
data class TextLayer(
    val spec: TextSpec = TextSpec(band = TextBand.BOTTOM),
    val brightness: Int = 100,
    val visible: Boolean = true,
    /** The text's own speed, 0.25x-4x. */
    val speed: Float = 1f,
)

/**
 * A design made of up to three layers: Background, Text and Drawing (the frames you draw, always on top).
 * Each layer loops on its own; the design lasts as long as its longest layer, or [lengthMs] when set.
 */
@Serializable
data class Layers(
    val background: BackgroundLayer? = null,
    val text: TextLayer? = null,
    val drawingVisible: Boolean = true,
    val drawingBrightness: Int = 100,
    val behind: BehindText = BehindText.CUT_OUT,
    val lengthMs: Int? = null,
) {
    val count: Int get() = 1 + (if (background != null) 1 else 0) + (if (text != null) 1 else 0)
}

object Compositor {
    /** Most frames a flattened design gets. */
    const val MAX_FRAMES = 600
    private const val DIM = 0.3f

    /** The background's loop with its effect. */
    fun background(p: DeviceProfile, bg: BackgroundLayer): Animation {
        val src = runCatching { Animation.fromMuseumJson(p, bg.frames) }.getOrElse { Animation.single(Frame.blank(p)) }
        val still = Posters.of(src)
        val out: Animation = when (bg.effect) {
            BackgroundEffect.STILL -> Animation.single(still, 1000)
            BackgroundEffect.PLAY -> src
            BackgroundEffect.BLINK -> Animation(p, listOf(AnimFrame(still, 600), AnimFrame(Frame.blank(p), 400)))
            BackgroundEffect.BREATHE -> Animation(p, List(24) { i -> AnimFrame(still.scaled((0.3 + 0.7 * (0.5 - 0.5 * kotlin.math.cos(2 * PI * i / 24))).toFloat()), 100) })
            BackgroundEffect.ZOOM -> Animation(p, List(30) { i -> AnimFrame(AnimatePresets.scale(still, 1.0 + 0.3 * sin(PI * i / 30)), 100) })
            BackgroundEffect.PAN -> Animation(p, List(24) { i -> AnimFrame(AnimatePresets.shift(still, (3 * sin(2 * PI * i / 24)).roundToInt(), 0), 120) })
        }
        return if (bg.brightness >= 100) out else Animation(p, out.frames.map { AnimFrame(it.frame.scaled(bg.brightness / 100f), it.durationMs) })
    }

    /** The text's loop at its own speed and brightness. */
    fun text(p: DeviceProfile, t: TextLayer): Animation {
        val a = TextRender.render(p, t.spec)
        val k = t.speed.coerceIn(Timing.MIN_SPEED, Timing.MAX_SPEED)
        return Animation(p, a.frames.map {
            AnimFrame(if (t.brightness >= 100) it.frame else it.frame.scaled(t.brightness / 100f), (it.durationMs / k).roundToInt().coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS))
        })
    }

    private class Track(val a: Animation) {
        val total = a.totalMs.coerceAtLeast(1)
        val starts = IntArray(a.frames.size).also { s -> var t = 0; a.frames.forEachIndexed { i, f -> s[i] = t; t += f.durationMs } }
        val moving: Boolean get() = a.frames.size > 1

        fun at(t: Int): IntArray {
            if (!moving) return a.frames[0].frame.pixels
            val m = t % total
            var i = starts.size - 1
            while (i > 0 && starts[i] > m) i--
            return a.frames[i].frame.pixels
        }

        /** Times in [0, end) where this track changes frame. */
        fun changes(end: Int, into: MutableSet<Int>) {
            if (!moving) return
            var loop = 0
            while (loop * total < end) {
                for (s in starts) { val t = loop * total + s; if (t < end) into += t }
                loop++
                if (into.size > MAX_FRAMES * 4) return
            }
        }
    }

    /** The design as plain frames: what plays on the Glyph and what "Flatten to frames" keeps. */
    fun render(p: DeviceProfile, layers: Layers, drawing: Animation): Animation {
        val bg = layers.background?.takeIf { it.visible }?.let { Track(background(p, it)) }
        val tx = layers.text?.takeIf { it.visible }?.let { Track(text(p, it)) }
        val dr = drawing.takeIf { layers.drawingVisible }?.let { Track(it) }
        val tracks = listOfNotNull(bg, tx, dr)
        val longest = tracks.filter { it.moving }.maxOfOrNull { it.total } ?: 1000
        val end = (layers.lengthMs ?: longest).coerceIn(Animation.MIN_FRAME_MS, 600_000)
        val times = sortedSetOf(0)
        tracks.forEach { it.changes(end, times) }
        // change points closer than the shortest frame merge, so the length stays exact
        val list = ArrayList<Int>()
        for (t in times) if (list.isEmpty() || (t - list.last() >= Animation.MIN_FRAME_MS && end - t >= Animation.MIN_FRAME_MS)) list += t
        if (list.size > MAX_FRAMES) list.subList(MAX_FRAMES, list.size).clear()
        val frames = list.mapIndexed { i, t ->
            val next = if (i + 1 < list.size) list[i + 1] else end
            AnimFrame(Frame.of(p, at(p, layers, bg, tx, dr, t)), (next - t).coerceAtMost(Animation.MAX_FRAME_MS))
        }
        return Animation(p, frames)
    }

    /** One composited frame at [t] ms (the editor shows this while a layer is selected). */
    fun frameAt(p: DeviceProfile, layers: Layers, drawing: Animation, t: Int): IntArray {
        val bg = layers.background?.takeIf { it.visible }?.let { Track(background(p, it)) }
        val tx = layers.text?.takeIf { it.visible }?.let { Track(text(p, it)) }
        val dr = drawing.takeIf { layers.drawingVisible }?.let { Track(it) }
        return at(p, layers, bg, tx, dr, t)
    }

    private fun at(p: DeviceProfile, layers: Layers, bg: Track?, tx: Track?, dr: Track?, t: Int): IntArray =
        compose(p, layers.behind, layers.text?.spec?.band ?: TextBand.FULL, bg?.at(t), tx?.at(t), dr?.at(t), layers.drawingBrightness)

    /** Background, then text with [behind] applied, then the drawing on top. */
    fun compose(p: DeviceProfile, behind: BehindText, band: TextBand, bg: IntArray?, text: IntArray?, drawing: IntArray?, drawingBrightness: Int = 100): IntArray {
        val n = p.size
        val out = bg?.copyOf() ?: IntArray(p.cellCount)
        if (text != null) {
            when (behind) {
                BehindText.CLEAR -> Unit
                BehindText.DIM -> for (y in band.rows) for (x in 0 until n) out[y * n + x] = (out[y * n + x] * DIM).roundToInt()
                // only round the text's band, so a picture above or below the words keeps its shape
                BehindText.CUT_OUT -> for (y in (band.rows.first - 1).coerceAtLeast(0)..(band.rows.last + 1).coerceAtMost(n - 1)) for (x in 0 until n) {
                    var near = false
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until n && yy in 0 until n && text[yy * n + xx] > 0) near = true
                    }
                    if (near) out[y * n + x] = 0
                }
            }
            for (c in 0 until p.cellCount) if (text[c] > 0) out[c] = text[c]
        }
        if (drawing != null) for (c in 0 until p.cellCount) if (drawing[c] > 0) out[c] = if (drawingBrightness >= 100) drawing[c] else (drawing[c] * drawingBrightness / 100f).roundToInt()
        return Frame.of(p, out).pixels
    }
}
