package com.dotforge.ringtones

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.dotforge.composition.GlyphRingtone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

data class SavedRingtone(val uri: Uri, val title: String, val durationMs: Long)

/**
 * DotForge's ringtones in shared storage: Ringtones/Compositions, where Nothing's own Composer saves, marked
 * IS_RINGTONE so they show in the ringtone pickers. Written through MediaStore, so no storage permission is
 * needed; the list shows the files this app created. Call [refresh], [save] and [delete] off the main thread.
 */
class RingtoneLibrary(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val _items = MutableStateFlow<List<SavedRingtone>>(emptyList())
    val items: StateFlow<List<SavedRingtone>> = _items.asStateFlow()

    fun refresh() {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val list = runCatching {
            resolver.query(collection, projection, selection, arrayOf("$DIR%", context.packageName), "${MediaStore.Audio.Media.DATE_ADDED} DESC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2).orEmpty().substringBeforeLast('.')
                            add(SavedRingtone(ContentUris.withAppendedId(collection, id), title, c.getLong(3)))
                        }
                    }
                }
        }.getOrNull().orEmpty()
        _items.value = list
    }

    /** Stores [ogg] as a ringtone named [title]; returns its content URI. */
    fun save(title: String, ogg: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName(title))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, DIR)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ALBUM, GlyphRingtone.ALBUM)
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the ringtone file.")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(ogg) } ?: throw IOException("Couldn't write the ringtone file.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        refresh()
        return uri
    }

    /** Writes [ogg] over our ringtone at [uri] (after editing it); returns [uri]. */
    fun replace(uri: Uri, title: String, ogg: ByteArray): Uri {
        resolver.openOutputStream(uri, "wt")?.use { it.write(ogg) } ?: throw IOException("Couldn't write the ringtone file.")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.TITLE, title) }, null, null)
        refresh()
        return uri
    }

    fun delete(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
        refresh()
    }

    companion object {
        /** Where Phone (3) Glyph Composer keeps its ringtones. */
        val DIR = "${Environment.DIRECTORY_RINGTONES}/Compositions/"

        fun fileName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifEmpty { "Ringtone" } + ".ogg"
    }
}
