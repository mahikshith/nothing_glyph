package com.dotforge.device

/**
 * Compact 3x5 numerals for large read-outs (clock, percentages) drawn at 2x on the 25x25 matrix,
 * where the 5x5 [PixelFont] would not fit two digits side by side.
 */
object BigDigits {
    const val GLYPH_WIDTH = 3
    const val GLYPH_HEIGHT = 5

    private val GLYPHS = mapOf(
        '0' to arrayOf("###", "#.#", "#.#", "#.#", "###"),
        '1' to arrayOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to arrayOf("###", "..#", "###", "#..", "###"),
        '3' to arrayOf("###", "..#", ".##", "..#", "###"),
        '4' to arrayOf("#.#", "#.#", "###", "..#", "..#"),
        '5' to arrayOf("###", "#..", "###", "..#", "###"),
        '6' to arrayOf("###", "#..", "###", "#.#", "###"),
        '7' to arrayOf("###", "..#", ".#.", ".#.", ".#."),
        // notched at the corners and the waist on both sides, so it never reads as a B
        '8' to arrayOf(".#.", "#.#", ".#.", "#.#", ".#."),
        '9' to arrayOf("###", "#.#", "###", "..#", "###"),
        // the hour mark in "1h02"
        'h' to arrayOf("#..", "#..", "##.", "#.#", "#.#"),
        '%' to arrayOf("#.#", "..#", ".#.", "#..", "#.#"),
    )

    fun width(text: String, scale: Int): Int = if (text.isEmpty()) 0 else (text.length * (GLYPH_WIDTH + 1) - 1) * scale

    /** Draws [text] with its top-left at ([x0], [y0]); characters without a glyph leave a gap. */
    fun draw(frame: Frame, text: String, x0: Int, y0: Int, scale: Int, value: Int = 255) {
        var x = x0
        for (ch in text) {
            if (ch == '8' && scale >= 2) drawEight(frame, x, y0, scale, value)
            else GLYPHS[ch]?.let { g ->
                for (row in 0 until GLYPH_HEIGHT) for (col in 0 until GLYPH_WIDTH) {
                    if (g[row][col] != '#') continue
                    for (sy in 0 until scale) for (sx in 0 until scale) {
                        frame[x + col * scale + sx, y0 + row * scale + sy] = value
                    }
                }
            }
            x += (GLYPH_WIDTH + 1) * scale
        }
    }

    /**
     * A big 8: two stacked loops with the strokes of the other digits, each bar one dot in from both edges, so
     * the corners and the waist are notched on both sides and it never reads as a B (flat on the left). At 1x
     * the glyph itself does this; scaled, the 1x glyph would turn into a thin chain of diamonds.
     */
    private fun drawEight(frame: Frame, x0: Int, y0: Int, scale: Int, value: Int) {
        val w = GLYPH_WIDTH * scale
        for (row in 0 until GLYPH_HEIGHT) {
            val bar = row % 2 == 0
            for (sy in 0 until scale) for (px in 0 until w) {
                val lit = if (bar) px in 1 until w - 1 else px < scale || px >= w - scale
                if (lit) frame[x0 + px, y0 + row * scale + sy] = value
            }
        }
    }
}
