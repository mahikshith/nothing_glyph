package com.dotforge.device

/**
 * The Text screen's fonts. Regular is the 5x5 [PixelFont]; Small is a 3x5 font drawn here; Bold, Tall, Big and
 * Pixel serif are made from Regular (thickened, the stem rows doubled to 7 rows, doubled in size, and Tall with
 * feet on the stems). Symbols (hearts, stars, arrows…) are 5x5 and work in every font.
 */
enum class DotFont(val label: String) {
    SMALL("Small"), REGULAR("Regular"), BOLD("Bold"), TALL("Tall"), BIG("Big"), SERIF("Pixel serif");
}

/** A 1-bit picture: [w] x [h], row by row. */
class Strip(val w: Int, val h: Int, val px: BooleanArray = BooleanArray(w * h)) {
    fun lit(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && px[y * w + x]
    operator fun set(x: Int, y: Int, on: Boolean) {
        if (x in 0 until w && y in 0 until h) px[y * w + x] = on
    }

    companion object {
        fun of(rows: List<String>): Strip {
            val w = rows.maxOf { it.length }
            val s = Strip(w, rows.size)
            rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == '#') s[x, y] = true } }
            return s
        }
    }
}

/** A symbol for the Text screen's picker: the character it is typed as and a name for TalkBack and the grid. */
class DotSymbol(val char: Char, val name: String, val rows: List<String>)

object DotFonts {
    private fun g(vararg rows: String) = rows.toList()

    /** 3x5 letters and digits; anything missing falls back to Regular. */
    private val SMALL: Map<Char, List<String>> = mapOf(
        'A' to g(".#.", "#.#", "###", "#.#", "#.#"), 'B' to g("##.", "#.#", "##.", "#.#", "##."),
        'C' to g(".##", "#..", "#..", "#..", ".##"), 'D' to g("##.", "#.#", "#.#", "#.#", "##."),
        'E' to g("###", "#..", "##.", "#..", "###"), 'F' to g("###", "#..", "##.", "#..", "#.."),
        'G' to g(".##", "#..", "#.#", "#.#", ".##"), 'H' to g("#.#", "#.#", "###", "#.#", "#.#"),
        'I' to g("#", "#", "#", "#", "#"), 'J' to g("..#", "..#", "..#", "#.#", ".#."),
        'K' to g("#.#", "#.#", "##.", "#.#", "#.#"), 'L' to g("#..", "#..", "#..", "#..", "###"),
        // M and N need room for their diagonals: 3 dots wide they read as H and as a Π
        'M' to g("#...#", "##.##", "#.#.#", "#...#", "#...#"), 'N' to g("#..#", "##.#", "#.##", "#..#", "#..#"),
        'O' to g("###", "#.#", "#.#", "#.#", "###"), 'P' to g("##.", "#.#", "##.", "#..", "#.."),
        'Q' to g(".#.", "#.#", "#.#", "##.", ".##"), 'R' to g("##.", "#.#", "##.", "#.#", "#.#"),
        'S' to g(".##", "#..", ".#.", "..#", "##."), 'T' to g("###", ".#.", ".#.", ".#.", ".#."),
        'U' to g("#.#", "#.#", "#.#", "#.#", "###"), 'V' to g("#.#", "#.#", "#.#", "#.#", ".#."),
        'W' to g("#.#", "#.#", "###", "###", "#.#"), 'X' to g("#.#", "#.#", ".#.", "#.#", "#.#"),
        'Y' to g("#.#", "#.#", ".#.", ".#.", ".#."), 'Z' to g("###", "..#", ".#.", "#..", "###"),
        '0' to g(".#.", "#.#", "#.#", "#.#", ".#."), '1' to g(".#.", "##.", ".#.", ".#.", "###"),
        '2' to g("##.", "..#", ".#.", "#..", "###"), '3' to g("##.", "..#", ".#.", "..#", "##."),
        '4' to g("#.#", "#.#", "###", "..#", "..#"), '5' to g("###", "#..", "##.", "..#", "##."),
        '6' to g(".##", "#..", "###", "#.#", "###"), '7' to g("###", "..#", ".#.", ".#.", ".#."),
        '8' to g(".#.", "#.#", ".#.", "#.#", ".#."), '9' to g("###", "#.#", "###", "..#", "##."),
        '!' to g("#", "#", "#", ".", "#"), '.' to g(".", ".", ".", ".", "#"), ',' to g(".", ".", ".", "#", "#"),
        '?' to g("##.", "..#", ".#.", "...", ".#."), '-' to g("...", "...", "###", "...", "..."),
        ':' to g(".", "#", ".", "#", "."), '\'' to g("#", "#", ".", ".", "."), ' ' to g("..", "..", "..", "..", ".."),
        '+' to g("...", ".#.", "###", ".#.", "..."), '/' to g("..#", "..#", ".#.", "#..", "#.."),
    )

    /** Bold letters whose diagonals would fill in when thickened (N would read as H): stems doubled, diagonal kept thin. */
    private val BOLD: Map<Char, List<String>> = mapOf(
        'M' to g("##...##", "###.###", "##.#.##", "##...##", "##...##"),
        'N' to g("##...##", "###..##", "##.#.##", "##..###", "##...##"),
        'W' to g("##...##", "##...##", "##.#.##", "###.###", "##...##"),
    )

    /** The symbols, in picker order. Each is original 5x5 artwork. */
    val SYMBOLS: List<DotSymbol> = listOf(
        DotSymbol('♥', "Heart", g(".#.#.", "#####", "#####", ".###.", "..#..")),
        DotSymbol('★', "Star", g("..#..", "#####", ".###.", ".#.#.", "#...#")),
        DotSymbol('✓', "Tick", g("....#", "...#.", "#.#..", ".#...", ".....")),
        DotSymbol('✗', "Cross", g("#...#", ".#.#.", "..#..", ".#.#.", "#...#")),
        DotSymbol('⚡', "Lightning", g("..##.", ".##..", "#####", "..##.", ".##..")),
        DotSymbol('☀', "Sun", g("#.#.#", ".###.", "#####", ".###.", "#.#.#")),
        DotSymbol('☾', "Moon", g(".###.", "##...", "##...", "##...", ".###.")),
        DotSymbol('♪', "Note", g("..##.", "..#.#", "..#..", "###..", "##...")),
        DotSymbol('♫', "Notes", g(".####", ".#..#", ".#..#", "##.##", "##.##")),
        DotSymbol('←', "Left arrow", g("..#..", ".#...", "#####", ".#...", "..#..")),
        DotSymbol('→', "Right arrow", g("..#..", "...#.", "#####", "...#.", "..#..")),
        DotSymbol('↑', "Up arrow", g("..#..", ".###.", "#.#.#", "..#..", "..#..")),
        DotSymbol('↓', "Down arrow", g("..#..", "..#..", "#.#.#", ".###.", "..#..")),
        DotSymbol('☺', "Smile", g(".#.#.", ".#.#.", ".....", "#...#", ".###.")),
        DotSymbol('☹', "Frown", g(".#.#.", ".#.#.", ".....", ".###.", "#...#")),
        DotSymbol('✉', "Mail", g("#####", "##.##", "#.#.#", "#...#", "#####")),
        DotSymbol('☎', "Phone", g(".###.", "#.#.#", ".###.", "#####", "#####")),
        DotSymbol('☕', "Coffee", g(".#.#.", ".....", "####.", "####.", ".##..")),
        DotSymbol('♨', "Food", g(".#.#.", ".....", "#####", ".###.", ".....")),
        DotSymbol('⛟', "Car", g(".....", ".##..", "#####", "#####", ".#.#.")),
        DotSymbol('✈', "Plane", g("..#..", "..#..", "#####", "..#..", ".###.")),
        DotSymbol('⌂', "Home", g("..#..", ".###.", "#####", ".#.#.", ".#.#.")),
        DotSymbol('☂', "Umbrella", g(".###.", "#####", "..#..", "..#..", ".##..")),
        DotSymbol('❄', "Snow", g("..#..", "#.#.#", ".###.", "#.#.#", "..#..")),
        DotSymbol('⚽', "Ball", g(".###.", "#.#.#", "##.##", "#.#.#", ".###.")),
        DotSymbol('⏰', "Alarm", g(".###.", "#.#.#", "#.###", "#...#", ".###.")),
        DotSymbol('♛', "Crown", g("#.#.#", "#.#.#", "#####", "#####", "#####")),
        DotSymbol('✿', "Flower", g(".#.#.", "##.##", ".#.#.", "..#..", ".###.")),
        DotSymbol('•', "Dot", g(".....", ".###.", ".###.", ".###.", ".....")),
        DotSymbol('%', "Percent", g("##..#", "##.#.", "..#..", ".#.##", "#..##")),
    )
    private val SYMBOL_MAP = SYMBOLS.associate { it.char to Strip.of(it.rows) }

    private fun regular(ch: Char): Strip {
        SYMBOL_MAP[ch]?.let { return it }
        val rows = PixelFont.getGlyph(ch)
        val s = Strip(rows[0].size, rows.size)
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, v -> if (v != 0) s[x, y] = true } }
        return s
    }

    /** The 5x5 rows doubled where the stems are (1 and 3), so horizontal strokes stay one dot: 7 rows. */
    private val TALL_ROWS = intArrayOf(0, 1, 1, 2, 3, 3, 4)

    private val cache = HashMap<Pair<DotFont, Char>, Strip>()

    /** The picture of [ch] in [font]. */
    fun glyph(font: DotFont, ch: Char): Strip = synchronized(cache) {
        cache.getOrPut(font to ch.uppercaseChar()) { make(font, ch.uppercaseChar()) }
    }

    private fun make(font: DotFont, ch: Char): Strip = when (font) {
        DotFont.REGULAR -> regular(ch)
        DotFont.SMALL -> SMALL[ch]?.let { Strip.of(it) } ?: regular(ch)
        DotFont.BOLD -> BOLD[ch]?.let { Strip.of(it) } ?: regular(ch).let { r ->
            if (ch == ' ') r else Strip(r.w + 1, r.h).also { s -> for (y in 0 until r.h) for (x in 0..r.w) s[x, y] = r.lit(x, y) || r.lit(x - 1, y) }
        }
        DotFont.TALL -> tall(regular(ch))
        DotFont.BIG -> regular(ch).let { r -> Strip(r.w * 2, r.h * 2).also { s -> for (y in 0 until s.h) for (x in 0 until s.w) s[x, y] = r.lit(x / 2, y / 2) } }
        DotFont.SERIF -> tall(regular(ch)).let { t -> if (ch.isLetterOrDigit() && ch.code < 128) serif(t) else t }
    }

    private fun tall(r: Strip): Strip {
        if (r.h != 5) return r
        return Strip(r.w, TALL_ROWS.size).also { s -> for (y in TALL_ROWS.indices) for (x in 0 until r.w) s[x, y] = r.lit(x, TALL_ROWS[y]) }
    }

    /** Feet and caps: where a stem ends at the top or bottom row with nothing beside it, a dot each side. */
    private fun serif(t: Strip): Strip {
        val s = Strip(t.w + 2, t.h)
        for (y in 0 until t.h) for (x in 0 until t.w) s[x + 1, y] = t.lit(x, y)
        for ((edge, inner) in listOf(0 to 1, t.h - 1 to t.h - 2)) {
            for (x in 0 until t.w) {
                if (t.lit(x, edge) && t.lit(x, inner) && !t.lit(x - 1, edge) && !t.lit(x + 1, edge)) {
                    s[x, edge] = true
                    s[x + 2, edge] = true
                }
            }
        }
        return s
    }

    /** Height in rows of [font]. */
    fun height(font: DotFont): Int = when (font) {
        DotFont.TALL, DotFont.SERIF -> 7
        DotFont.BIG -> 10
        else -> 5
    }

    /** Width of [text] with [spacing] dots between letters (Big doubles the spacing). */
    fun width(font: DotFont, text: String, spacing: Int = 1): Int {
        if (text.isEmpty()) return 0
        val gap = if (font == DotFont.BIG) spacing * 2 else spacing
        return text.sumOf { glyph(font, it).w } + gap * (text.length - 1)
    }

    /** [text] as one line, [height] rows tall. */
    fun strip(font: DotFont, text: String, spacing: Int = 1): Strip {
        val h = height(font)
        val s = Strip(width(font, text, spacing).coerceAtLeast(1), h)
        val gap = if (font == DotFont.BIG) spacing * 2 else spacing
        var x0 = 0
        for (ch in text) {
            val g = glyph(font, ch)
            val dy = h - g.h
            for (y in 0 until g.h) for (x in 0 until g.w) if (g.lit(x, y)) s[x0 + x, y + dy] = true
            x0 += g.w + gap
        }
        return s
    }
}
