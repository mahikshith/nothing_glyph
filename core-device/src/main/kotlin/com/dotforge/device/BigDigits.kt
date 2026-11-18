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
        '8' to arrayOf("###", "#.#", "###", "#.#", "###"),
        '9' to arrayOf("###", "#.#", "###", "..#", "###"),
        '%' to arrayOf("#.#", "..#", ".#.", "#..", "#.#"),
    )

    fun width(text: String, scale: Int): Int = if (text.isEmpty()) 0 else (text.length * (GLYPH_WIDTH + 1) - 1) * scale

    /** Draws [text] with its top-left at ([x0], [y0]); characters without a glyph leave a gap. */
    fun draw(frame: Frame, text: String, x0: Int, y0: Int, scale: Int, value: Int = 255) {
        var x = x0
        for (ch in text) {
            GLYPHS[ch]?.let { g ->
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
}
