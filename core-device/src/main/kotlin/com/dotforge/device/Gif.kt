package com.dotforge.device

import java.io.ByteArrayOutputStream

/** A decoded GIF frame: the full canvas after this frame, and how long it shows. */
class GifFrame(val picture: Picture, val delayMs: Int)

/**
 * GIF reading (for "From a photo or file") and writing (for Share > Animated GIF). Written from the GIF89a
 * specification: LZW, global and local colour tables, transparency and the three disposal methods.
 */
object Gif {
    class GifException(message: String) : Exception(message)

    /** How many frames the GIF has (for "Converting… 12 of 48 frames"), found without decoding; 0 if it can't be read. */
    fun count(bytes: ByteArray): Int = runCatching {
        var pos = 13
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun skipBlocks() { while (true) { val n = u8(); if (n == 0) break; pos += n } }
        val flags = bytes[10].toInt() and 0xFF
        if (flags and 0x80 != 0) pos += 3 * (2 shl (flags and 7))
        var n = 0
        while (pos < bytes.size) {
            when (u8()) {
                0x21 -> { u8(); skipBlocks() }
                0x2C -> {
                    pos += 8
                    val lf = u8()
                    if (lf and 0x80 != 0) pos += 3 * (2 shl (lf and 7))
                    u8()
                    skipBlocks()
                    n++
                }
                else -> break
            }
        }
        n
    }.getOrDefault(0)

    /** Decodes up to [maxFrames] frames; [onFrame] reports progress (frames done) and returns false to stop. */
    fun decode(bytes: ByteArray, maxFrames: Int = 300, onFrame: (Int) -> Boolean = { true }): List<GifFrame> {
        var pos = 0
        fun u8(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else throw GifException("The GIF ends early")
        fun u16(): Int = u8() or (u8() shl 8)
        val sig = String(bytes.copyOfRange(0, minOf(6, bytes.size)), Charsets.US_ASCII)
        if (sig != "GIF87a" && sig != "GIF89a") throw GifException("Not a GIF")
        pos = 6
        val w = u16()
        val h = u16()
        if (w <= 0 || h <= 0 || w.toLong() * h > 16_000_000) throw GifException("The GIF is too big")
        val flags = u8()
        val bgIndex = u8()
        u8()
        fun table(size: Int) = IntArray(size) { (0xFF shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }
        val global = if (flags and 0x80 != 0) table(2 shl (flags and 7)) else null
        val canvas = IntArray(w * h)
        val out = ArrayList<GifFrame>()
        var delay = 100
        var transparent = -1
        var disposal = 0
        while (pos < bytes.size && out.size < maxFrames) {
            when (u8()) {
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        u8()
                        val f = u8()
                        delay = u16() * 10
                        val t = u8()
                        u8()
                        disposal = (f shr 2) and 7
                        transparent = if (f and 1 != 0) t else -1
                    } else {
                        // skip the extension's blocks
                        while (true) { val n = u8(); if (n == 0) break; pos += n }
                    }
                }
                0x2C -> {
                    val fx = u16(); val fy = u16(); val fw = u16(); val fh = u16()
                    val lf = u8()
                    val local = if (lf and 0x80 != 0) table(2 shl (lf and 7)) else null
                    val colours = local ?: global ?: throw GifException("The GIF has no colours")
                    val interlaced = lf and 0x40 != 0
                    val minCode = u8()
                    val data = ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; data.write(bytes, pos, minOf(n, bytes.size - pos)); pos += n }
                    val indices = lzwDecode(data.toByteArray(), minCode, fw * fh)
                    val before = if (disposal == 3) canvas.copyOf() else null
                    val rows = if (interlaced) interlaceOrder(fh) else IntArray(fh) { it }
                    for (row in 0 until fh) {
                        val y = fy + rows[row]
                        if (y !in 0 until h) continue
                        for (x in 0 until fw) {
                            val idx = indices[row * fw + x].toInt() and 0xFF
                            if (idx == transparent || fx + x !in 0 until w) continue
                            canvas[y * w + fx + x] = colours.getOrElse(idx) { 0 }
                        }
                    }
                    out += GifFrame(Picture(w, h, canvas.copyOf()), delay.takeIf { it >= 20 } ?: 100)
                    if (!onFrame(out.size)) return out
                    when (disposal) {
                        2 -> for (y in fy until minOf(h, fy + fh)) for (x in fx until minOf(w, fx + fw)) {
                            canvas[y * w + x] = if (transparent >= 0 || global == null) 0 else global.getOrElse(bgIndex) { 0 }
                        }
                        3 -> before?.copyInto(canvas)
                    }
                    transparent = -1
                    disposal = 0
                    delay = 100
                }
                0x3B -> break
                else -> break
            }
        }
        if (out.isEmpty()) throw GifException("The GIF has no pictures")
        return out
    }

    private fun interlaceOrder(h: Int): IntArray {
        val order = ArrayList<Int>()
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) for (y in start until h step step) order += y
        return order.toIntArray()
    }

    private fun lzwDecode(data: ByteArray, minCode: Int, count: Int): ByteArray {
        val out = ByteArray(count)
        if (minCode !in 2..8) return out
        val clear = 1 shl minCode
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); length[i] = 1 }
        var size = minCode + 1
        var next = end + 1
        var prev = -1
        var bits = 0
        var buf = 0
        var o = 0
        val stack = ByteArray(4097)
        var i = 0
        while (o < count) {
            while (bits < size) {
                if (i >= data.size) return out
                buf = buf or ((data[i++].toInt() and 0xFF) shl bits)
                bits += 8
            }
            val code = buf and ((1 shl size) - 1)
            buf = buf ushr size
            bits -= size
            if (code == clear) { size = minCode + 1; next = end + 1; prev = -1; continue }
            if (code == end) break
            var c = code
            var sp = 0
            val first: Byte
            if (code >= next) {
                if (prev < 0) break
                // the KwKwK case: previous string plus its own first byte
                var t = prev
                while (t >= clear) { stack[sp++] = suffix[t]; t = prefix[t] }
                first = suffix[t]
                stack[sp++] = first
                // reverse into the output after the trailing first byte
                val tmp = ByteArray(sp)
                for (k in 0 until sp) tmp[k] = stack[sp - 1 - k]
                for (b in tmp) { if (o < count) out[o++] = b }
                if (o < count) out[o++] = first
                if (next < 4096) { prefix[next] = prev; suffix[next] = first; length[next] = length[prev] + 1; next++ }
            } else {
                while (c >= clear) { stack[sp++] = suffix[c]; c = prefix[c] }
                first = suffix[c]
                stack[sp++] = first
                while (sp > 0 && o < count) out[o++] = stack[--sp]
                if (prev >= 0 && next < 4096) { prefix[next] = prev; suffix[next] = first; length[next] = length[prev] + 1; next++ }
            }
            prev = code
            if (next == (1 shl size) && size < 12) size++
        }
        return out
    }

    // ---- writing

    /**
     * An animated GIF of [a] as it looks on the Glyph: round dots on black, [cell] pixels per dot, looping.
     * 16 grey levels; dots without light show as a faint ring of the grid.
     */
    fun encode(a: Animation, cell: Int = 10): ByteArray {
        val p = a.profile
        val side = p.size * cell
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        fun w16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        w16(side); w16(side)
        out.write(0x80 or 0x70 or 3) // global table of 16 colours
        out.write(0); out.write(0)
        // 0 = black, 1 = unlit dot, 2..15 = brightness
        val palette = IntArray(16) { i -> when (i) { 0 -> 0; 1 -> 0x26; else -> 0x40 + (i - 2) * (0xFF - 0x40) / 13 } }
        palette.forEach { v -> out.write(v); out.write(v); out.write(v) }
        // loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 1, 0, 0, 0))
        val r = cell * 0.42
        val mask = BooleanArray(cell * cell) { val dx = it % cell - (cell - 1) / 2.0; val dy = it / cell - (cell - 1) / 2.0; dx * dx + dy * dy <= r * r }
        for (f in a.frames) {
            out.write(0x21); out.write(0xF9); out.write(4); out.write(0)
            w16((f.durationMs / 10).coerceAtLeast(2)); out.write(0); out.write(0)
            out.write(0x2C); w16(0); w16(0); w16(side); w16(side); out.write(0)
            val idx = ByteArray(side * side)
            for (cy in 0 until p.size) for (cx in 0 until p.size) {
                val c = cy * p.size + cx
                if (!p.mask[c]) continue
                val v = f.frame.pixels[c]
                val colour = if (v <= 0) 1 else 2 + ((v - 1) * 13 / 254)
                for (k in mask.indices) if (mask[k]) idx[(cy * cell + k / cell) * side + cx * cell + k % cell] = colour.toByte()
            }
            out.write(4)
            val data = lzwEncode(idx, 4)
            var i = 0
            while (i < data.size) { val n = minOf(255, data.size - i); out.write(n); out.write(data, i, n); i += n }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun lzwEncode(pixels: ByteArray, minCode: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val clear = 1 shl minCode
        val end = clear + 1
        var size = minCode + 1
        var next = end + 1
        val dict = HashMap<Int, Int>()
        var buf = 0
        var bits = 0
        fun emit(code: Int) {
            buf = buf or (code shl bits)
            bits += size
            while (bits >= 8) { out.write(buf and 0xFF); buf = buf ushr 8; bits -= 8 }
        }
        emit(clear)
        var prefix = pixels.firstOrNull()?.toInt()?.and(0xFF) ?: run { emit(end); return out.toByteArray() }
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) {
                dict[key] = next++
                if (next > (1 shl size) && size < 12) size++
            } else {
                emit(clear)
                dict.clear()
                size = minCode + 1
                next = end + 1
            }
            prefix = k
        }
        emit(prefix)
        emit(end)
        if (bits > 0) out.write(buf and 0xFF)
        return out.toByteArray()
    }
}
