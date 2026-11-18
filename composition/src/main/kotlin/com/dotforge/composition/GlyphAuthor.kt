package com.dotforge.composition

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * The glyph AUTHOR tag codec: base64 -> zlib -> CSV (one row per frame at 60 fps, comma separated values).
 */
object GlyphAuthor {
    const val FPS = 60

    /** Decode tolerant of missing padding, whitespace/line breaks, url-safe alphabet. Returns rows of ints. */
    fun decode(author: String): List<IntArray> {
        val csv = String(inflate(base64Decode(author)), Charsets.UTF_8)
        return parseCsv(csv)
    }

    fun encode(rows: List<IntArray>): String {
        val csv = rows.joinToString("\n") { r -> r.joinToString(",") }
        return Base64.getEncoder().encodeToString(deflate(csv.toByteArray(Charsets.UTF_8)))
    }

    fun base64Decode(s: String): ByteArray {
        val cleaned = buildString {
            for (c in s) when {
                c.isWhitespace() || c == '=' -> {}
                c == '-' -> append('+')
                c == '_' -> append('/')
                else -> append(c)
            }
        }
        return Base64.getDecoder().decode(cleaned) // basic decoder accepts missing padding
    }

    private fun inflate(b: ByteArray): ByteArray = try {
        InflaterInputStream(b.inputStream(), Inflater()).use { it.readBytes() }
    } catch (e: java.util.zip.ZipException) {
        // some encoders emit raw deflate without the zlib header
        InflaterInputStream(b.inputStream(), Inflater(true)).use { it.readBytes() }
    }

    private fun deflate(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        DeflaterOutputStream(o, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(b) }
        return o.toByteArray()
    }

    /** CSV rows: CRLF or LF, trailing commas allowed, blank lines skipped. */
    fun parseCsv(csv: String): List<IntArray> =
        csv.split('\n').map { it.trim('\r', ' ', '\t') }.filter { it.isNotEmpty() }.map { line ->
            line.split(',').map { it.trim() }.dropLastWhile { it.isEmpty() }
                .map { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() ?: 0 }.toIntArray()
        }
}

/** Result of reading a ringtone file. */
data class ComposerInfo(
    val composer: String?,
    val custom1: String?,
    val custom2: String?,
    val tags: OggTags?,
    /** Null when there is no AUTHOR tag; else the decoded rows. */
    val rows: List<IntArray>?,
    val authorError: String? = null,
) {
    val columnCount: Int get() = rows?.firstOrNull()?.size ?: 0
    val rowCount: Int get() = rows?.size ?: 0
    val durationSeconds: Double get() = rowCount.toDouble() / GlyphAuthor.FPS
}

object RingtoneParser {
    fun parse(oggBytes: ByteArray): ComposerInfo {
        val tags = VorbisComments.parseOgg(oggBytes)
            ?: return ComposerInfo(null, null, null, null, null, "no Vorbis/Opus comment header found")
        val author = tags.first("AUTHOR")
        var rows: List<IntArray>? = null
        var err: String? = null
        if (author != null) {
            try { rows = GlyphAuthor.decode(author) } catch (e: Exception) { err = "AUTHOR decode failed: ${e.message}" }
        }
        return ComposerInfo(tags.first("COMPOSER"), tags.first("CUSTOM1"), tags.first("CUSTOM2"), tags, rows, err)
    }
}
