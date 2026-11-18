package com.dotforge.composition

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Vorbis comment / OpusTags block. Keys are upper-cased; a key may repeat. */
data class OggTags(val vendor: String, val comments: List<Pair<String, String>>) {
    fun first(key: String): String? = comments.firstOrNull { it.first == key.uppercase() }?.second
    fun all(key: String): List<String> = comments.filter { it.first == key.uppercase() }.map { it.second }
}

object VorbisComments {
    private val VORBIS_ID = byteArrayOf(0x03) + "vorbis".toByteArray()
    private val OPUS_ID = "OpusTags".toByteArray()

    /** Locate and parse the comment packet of an Ogg Vorbis or Ogg Opus file; null if none. */
    fun parseOgg(data: ByteArray): OggTags? {
        val packets = OggReader.readPackets(data, maxPackets = 2)
        val p = packets.getOrNull(1) ?: return null
        return when {
            p.startsWith(VORBIS_ID) -> parseBlock(p, VORBIS_ID.size)
            p.startsWith(OPUS_ID) -> parseBlock(p, OPUS_ID.size)
            else -> null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun parseBlock(p: ByteArray, start: Int): OggTags? = try {
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(start)
        val vendor = str(bb, bb.int)
        val n = bb.int
        val list = ArrayList<Pair<String, String>>()
        for (i in 0 until n) {
            val s = str(bb, bb.int)
            val eq = s.indexOf('=')
            if (eq > 0) list += s.substring(0, eq).uppercase() to s.substring(eq + 1)
        }
        OggTags(vendor, list)
    } catch (e: Exception) {
        null
    }

    private fun str(bb: ByteBuffer, len: Int): String {
        require(len >= 0 && len <= bb.remaining()) { "bad length" }
        val b = ByteArray(len)
        bb.get(b)
        return String(b, Charsets.UTF_8)
    }

    /** Build comment packets (used by tests / fixtures). */
    fun buildVorbisPacket(tags: OggTags): ByteArray = VORBIS_ID + block(tags) + byteArrayOf(1)
    fun buildOpusPacket(tags: OggTags): ByteArray = OPUS_ID + block(tags)

    private fun block(t: OggTags): ByteArray {
        val o = ByteArrayOutputStream()
        fun w32(v: Int) { for (i in 0 until 4) o.write((v ushr (8 * i)) and 0xFF) }
        val v = t.vendor.toByteArray(); w32(v.size); o.write(v)
        w32(t.comments.size)
        for ((k, value) in t.comments) { val b = "$k=$value".toByteArray(); w32(b.size); o.write(b) }
        return o.toByteArray()
    }
}
