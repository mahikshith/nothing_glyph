package com.dotforge.calls

import java.util.Arrays

/**
 * A downloaded spam number list: E.164 numbers kept as sorted longs (about 8 bytes a number), checked with a
 * binary search. Reads CallShield's `spam_numbers.txt`: "#" comment lines (the first says "database version N
 * (updated YYYY-MM-DD)"), then one E.164 number per line.
 */
class SpamList private constructor(private val sorted: LongArray, val version: Int?, val updated: String?) {
    val size: Int get() = sorted.size

    /** True when [number] (E.164) is on the list. */
    operator fun contains(number: String): Boolean {
        val v = toLong(number) ?: return false
        return Arrays.binarySearch(sorted, v) >= 0
    }

    companion object {
        val EMPTY = SpamList(LongArray(0), null, null)

        private val VERSION = Regex("""version\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val UPDATED = Regex("""updated\s+(\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)

        /** "+447700900123" to 447700900123; null unless it is "+" and 7 to 15 digits not starting with 0. */
        fun toLong(number: String): Long? {
            val n = number.trim()
            if (n.length !in 8..16 || n[0] != '+' || n[1] == '0') return null
            for (i in 1 until n.length) if (!n[i].isDigit()) return null
            return n.substring(1).toLong()
        }

        /** Parses the text; lines that aren't a comment or an E.164 number are skipped. */
        fun parse(lines: Sequence<String>): SpamList {
            var version: Int? = null
            var updated: String? = null
            var out = LongArray(1024)
            var n = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    if (version == null) version = VERSION.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    if (updated == null) updated = UPDATED.find(line)?.groupValues?.get(1)
                    continue
                }
                val v = toLong(line) ?: continue
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = v
            }
            val sorted = out.copyOf(n).also { it.sort() }
            // drop duplicates
            var w = 0
            for (i in sorted.indices) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[w++] = sorted[i]
            return SpamList(sorted.copyOf(w), version, updated)
        }

        fun parse(text: String): SpamList = parse(text.lineSequence())
    }
}
