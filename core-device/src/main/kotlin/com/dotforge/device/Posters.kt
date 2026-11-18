package com.dotforge.device

/**
 * The still that stands for an animation in small previews (rows, picker tiles) and before a large preview
 * starts: never blank, never cut in half.
 * - Ring effects (chase, comet) show every frame laid over each other, so the whole ring reads.
 * - Anything else shows its fullest frame, moved to the centre when it sits off to one side.
 * - The brightest dot is always full brightness, so a dim effect still reads.
 */
object Posters {
    fun of(a: Animation): Frame {
        val p = a.profile
        // a frame held for a while is the pose the animation rests in (a still glyph, the bell at rest)
        val held = a.frames.filter { f -> f.frame.pixels.any { it > 0 } }.maxByOrNull { it.durationMs }
        val best = if (held != null && held.durationMs >= REST_MS) held.frame else a.frames.maxBy { it.frame.pixels.sum() }.frame
        val lit = p.ledCells.filter { best.pixels[it] > 0 }
        if (lit.isEmpty()) return best
        val ring = p.ring.toHashSet()
        val onRing = lit.count { it in ring }
        val f = if (onRing * 10 >= lit.size * 6) overlay(a) else centred(best)
        return normalised(f)
    }

    /** [text] as it looks once it has scrolled in: centred, at 2x when it fits, else 1x (a sample name, "MOM"). */
    fun text(p: DeviceProfile, text: String): Frame {
        val t = text.trim().uppercase().ifEmpty { "?" }
        val scale = if (DotText.width(t, 2) <= p.size - 2) 2 else 1
        val fit = if (DotText.width(t, scale) <= p.size) t else t.take(3)
        return Frame.of(p, DotText.centered(p.size, fit, scale = scale))
    }

    /** Every frame's brightest value at each dot. */
    fun overlay(a: Animation): Frame {
        val p = a.profile
        val out = Frame.blank(p)
        for (f in a.frames) for (cell in p.ledCells) {
            val v = f.frame.pixels[cell]
            if (v > out.pixels[cell]) out.pixels[cell] = v
        }
        return out
    }

    /** [f] moved so its lit dots are centred, unless that would push a dot off the matrix. */
    fun centred(f: Frame): Frame {
        val p = f.profile
        val n = p.size
        val lit = p.ledCells.filter { f.pixels[it] > 0 }
        if (lit.isEmpty()) return f
        val minX = lit.minOf { it % n }
        val maxX = lit.maxOf { it % n }
        val minY = lit.minOf { it / n }
        val maxY = lit.maxOf { it / n }
        val dx = ((n - 1) - (minX + maxX)) / 2
        val dy = ((n - 1) - (minY + maxY)) / 2
        if (dx == 0 && dy == 0) return f
        if (lit.any { !p.isActive(it % n + dx, it / n + dy) }) return f
        val out = Frame.blank(p)
        for (cell in lit) out.pixels[p.index(cell % n + dx, cell / n + dy)] = f.pixels[cell]
        return out
    }

    private const val REST_MS = 400

    /** [f] scaled so its brightest dot is 255. */
    fun normalised(f: Frame): Frame {
        val max = f.pixels.max()
        if (max <= 0 || max >= 255) return f
        return f.scaled(255f / max)
    }
}
