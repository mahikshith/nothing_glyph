package com.dotforge.device

/** Renders text with the 5x5 [PixelFont] into full frames, optionally scaled up by an integer [scale]. */
object DotText {
    /** Width in columns of [text] (glyphs plus 1 column spacing, no trailing spacing), times [scale]. */
    fun width(text: String, scale: Int = 1): Int =
        if (text.isEmpty()) 0
        else (text.sumOf { PixelFont.getGlyphWidth(it) } + (text.length - 1) * PixelFont.CHAR_SPACING) * scale

    /** Height in rows of any text at [scale]. */
    fun height(scale: Int = 1): Int = PixelFont.GLYPH_HEIGHT * scale

    /** Draw [text] with its top-left at ([x0], [y0]) into [frame] (size x size). Clips to the grid. */
    fun draw(frame: IntArray, size: Int, text: String, x0: Int, y0: Int, brightness: Int = 255, scale: Int = 1) {
        var x = x0
        for (ch in text) {
            val g = PixelFont.getGlyph(ch)
            for (row in g.indices) for (col in g[row].indices) {
                if (g[row][col] == 0) continue
                for (sy in 0 until scale) for (sx in 0 until scale) {
                    val px = x + col * scale + sx
                    val py = y0 + row * scale + sy
                    if (px in 0 until size && py in 0 until size) frame[py * size + px] = brightness
                }
            }
            x += (g[0].size + PixelFont.CHAR_SPACING) * scale
        }
    }

    /** One static frame with [text] centred. */
    fun centered(size: Int, text: String, brightness: Int = 255, scale: Int = 1): IntArray {
        val f = IntArray(size * size)
        draw(f, size, text, (size - width(text, scale)) / 2, (size - height(scale)) / 2, brightness, scale)
        return f
    }

    /** Frame for horizontally scrolling [text]: [offset] 0 starts fully off the right edge; ends when offset == [scrollLength]. */
    fun scrollFrame(size: Int, text: String, offset: Int, brightness: Int = 255, scale: Int = 1): IntArray {
        val f = IntArray(size * size)
        draw(f, size, text, size - offset, (size - height(scale)) / 2, brightness, scale)
        return f
    }

    fun scrollLength(size: Int, text: String, scale: Int = 1): Int = size + width(text, scale)
}
