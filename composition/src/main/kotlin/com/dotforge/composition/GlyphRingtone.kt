package com.dotforge.composition

/** Writes and recognises Nothing glyph ringtones (Ogg with the light track in the AUTHOR comment). */
object GlyphRingtone {
    /** What Phone (3) Glyph Composer writes; the OS looks for "Glyph Composer" in it. */
    const val COMPOSER = "v1-Metroid Glyph Composer"
    const val ALBUM = "DotForge"

    /** Phone (3) matrix: 25x25 row-major columns. */
    const val PHONE3_COLUMNS = 625

    private val OURS = setOf("COMPOSER", "AUTHOR", "CUSTOM1", "CUSTOM2", "TITLE", "ALBUM")

    /** The comments of a Phone (3) glyph ringtone. [rows] are 625 values 0-4095 per 60 fps frame. */
    fun comments(title: String, rows: List<IntArray>): List<Pair<String, String>> {
        val cols = rows.firstOrNull()?.size ?: PHONE3_COLUMNS
        return listOf(
            "TITLE" to title,
            "ALBUM" to ALBUM,
            "COMPOSER" to COMPOSER,
            "CUSTOM1" to GlyphAuthor.EMPTY_ZLIB,
            "CUSTOM2" to "${cols}cols",
            "AUTHOR" to GlyphAuthor.encodeComposer(rows),
        )
    }

    /**
     * [ogg] (Opus or Vorbis) with our glyph comments in place of any old ones. Other comments (artist, ...) and
     * the vendor string are kept; the audio pages are copied unchanged.
     */
    fun write(ogg: ByteArray, title: String, rows: List<IntArray>): ByteArray =
        OggRepager.retag(ogg) { old ->
            OggTags(old.vendor.ifEmpty { "DotForge" }, old.comments.filterNot { it.first in OURS } + comments(title, rows))
        }

    /** Which phone a column count was made for, for the import screen. */
    fun phoneFor(columns: Int): String? = when (columns) {
        5 -> "Phone (1) or (2)"
        15 -> "Phone (1)"
        33 -> "Phone (2)"
        26 -> "Phone (2a)"
        36 -> "Phone (3a)"
        PHONE3_COLUMNS -> "Phone (3)"
        else -> null
    }
}

/** What an imported file holds. */
sealed interface ImportKind {
    /** Lights for the Phone (3) matrix: kept as they are. */
    data class Phone3(val rows: List<IntArray>) : ImportKind

    /** Lights for another phone: [columns] zones, converted to the matrix. */
    data class OtherPhone(val rows: List<IntArray>, val columns: Int) : ImportKind

    /** No usable light track: lights are composed from the audio. */
    data object AudioOnly : ImportKind

    companion object {
        /** [info] is null for files that are not Ogg. A decodable AUTHOR with at least one column counts as lights. */
        fun of(info: ComposerInfo?): ImportKind {
            val rows = info?.rows?.filter { it.isNotEmpty() }
            if (rows.isNullOrEmpty()) return AudioOnly
            val cols = rows.first().size
            val even = rows.map { r -> if (r.size == cols) r else IntArray(cols) { r.getOrElse(it) { 0 } } }
            return if (cols == GlyphRingtone.PHONE3_COLUMNS) Phone3(even) else OtherPhone(even, cols)
        }
    }
}
