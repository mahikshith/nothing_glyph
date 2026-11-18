package com.dotforge.ui.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.dotforge.device.Gif
import com.dotforge.device.GifFrame
import com.dotforge.device.Picture
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads pictures for Photo to dots and for layer backgrounds. Photos are decoded at full quality and scaled to
 * about [MAX_SIDE] px on the long side (plenty for a 25x25 grid sampled at 4x), with EXIF rotation applied by
 * [ImageDecoder]. GIFs and videos come back as frames with their delays.
 */
object MediaLoader {
    const val MAX_SIDE = 600
    /** A video gives at most this many frames, evenly spaced, 100 ms apart. */
    const val VIDEO_FRAMES = 48
    const val VIDEO_FRAME_MS = 100

    class CantRead(message: String) : Exception(message)

    fun picture(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): Picture {
        val bmp = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val k = maxSide.toFloat() / max(w, h)
                if (k < 1f) d.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
            }
        } catch (t: Exception) {
            throw CantRead("This photo can't be read. Try another one.")
        }
        return bmp.toPicture()
    }

    /** A GIF's frames; [onFrame] gets each frame's number as it is decoded and returns false to stop. */
    fun gif(ctx: Context, uri: Uri, onFrame: (Int) -> Boolean = { true }): List<GifFrame> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw CantRead("This GIF can't be read. Try another file.")
        return try {
            Gif.decode(bytes, onFrame = onFrame)
        } catch (e: Gif.GifException) {
            throw CantRead("This GIF can't be read. Try another file.")
        }
    }

    /** Up to [VIDEO_FRAMES] frames from a video, evenly spaced over its first 10 seconds. */
    fun video(ctx: Context, uri: Uri, onFrame: (Int, Int) -> Boolean = { _, _ -> true }): List<GifFrame> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw CantRead("This video can't be read. Try another file.")
            val span = ms.coerceAtMost(10_000L)
            val count = (span / VIDEO_FRAME_MS).toInt().coerceIn(1, VIDEO_FRAMES)
            val step = span / count
            val out = ArrayList<GifFrame>(count)
            for (i in 0 until count) {
                if (!onFrame(i + 1, count)) break
                val b = r.getScaledFrameAtTime(i * step * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 200, 200) ?: continue
                out += GifFrame(b.toPicture(), step.toInt().coerceIn(20, 1000))
            }
            if (out.isEmpty()) throw CantRead("This video can't be read. Try another file.")
            return out
        } catch (e: CantRead) {
            throw e
        } catch (t: Exception) {
            throw CantRead("This video can't be read. Try another file.")
        } finally {
            runCatching { r.release() }
        }
    }

    /** The file's name without its extension ("Beach"), or [fallback]. */
    fun name(ctx: Context, uri: Uri, fallback: String = "Photo"): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.')?.take(30) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    fun Bitmap.toPicture(): Picture {
        val b = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Picture(b.width, b.height, px)
    }
}
