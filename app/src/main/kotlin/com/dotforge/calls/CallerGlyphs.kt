package com.dotforge.calls

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.dotforge.ringtones.RingtoneActions

/**
 * Applies caller glyphs: the contact's (or each group member's) ringtone becomes the composition, through the
 * same Set as code as the Ringtones page, so the firmware plays its lights when they call.
 */
object CallerGlyphs {
    /** Sets the ringtone; returns how many contacts changed (0 without WRITE_CONTACTS). Blocking. */
    fun apply(context: Context, g: CallerGlyph): Int {
        if (!RingtoneActions.canWriteContacts(context) || g.ringtoneUri.isBlank()) return 0
        val ringtone = Uri.parse(g.ringtoneUri)
        return targets(context, g).count { RingtoneActions.setContactRingtone(context, it, ringtone) != null }
    }

    /** Puts back the default ringtone for contacts that still have this glyph's composition. Blocking. */
    fun clear(context: Context, g: CallerGlyph) {
        if (!RingtoneActions.canWriteContacts(context)) return
        val cr = context.contentResolver
        for (uri in targets(context, g)) {
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 } ?: continue
            val values = ContentValues().apply { putNull(ContactsContract.Contacts.CUSTOM_RINGTONE) }
            runCatching {
                cr.update(
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), values,
                    "${ContactsContract.Contacts.CUSTOM_RINGTONE} = ?", arrayOf(g.ringtoneUri),
                )
            }
        }
    }

    private fun targets(context: Context, g: CallerGlyph): List<Uri> = when (g.target) {
        GlyphTarget.CONTACT -> listOfNotNull(g.key.takeIf { it.isNotBlank() }?.let(Uri::parse))
        GlyphTarget.GROUP -> CallerLookup(context).members(g.key)
    }
}
