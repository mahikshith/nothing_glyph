package com.dotforge.ringtones

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings

/** "Set as": the phone ringtone (needs Modify system settings) and one contact's ringtone (needs WRITE_CONTACTS). */
object RingtoneActions {
    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    /** The special-access page where the user lets DotForge change system settings. */
    fun writeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Makes [ringtone] the phone ringtone. Returns false without the special access. */
    fun setPhoneRingtone(context: Context, ringtone: Uri): Boolean {
        if (!canWriteSettings(context)) return false
        return runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, ringtone) }.isSuccess
    }

    fun canWriteContacts(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Gives the contact picked at [contactUri] the ringtone [ringtone]. Returns the contact's name (or "the
     * contact"), or null when it could not be changed.
     */
    fun setContactRingtone(context: Context, contactUri: Uri, ringtone: Uri): String? {
        val cr = context.contentResolver
        val id = runCatching { ContentUris.parseId(contactUri) }.getOrNull()?.takeIf { it >= 0 } ?: return null
        val name = runCatching {
            cr.query(contactUri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val values = ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtone.toString()) }
        val changed = runCatching {
            cr.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), values, null, null)
        }.getOrDefault(0)
        return if (changed > 0) name ?: "the contact" else null
    }
}

/**
 * Plays a rule's sound when its glyph shows: on the notification stream, only in normal ringer mode, and for at
 * most [MAX_MS] (ringtones can be long). Glyph lights in the file are not played here; the rule's glyph is.
 */
object NotificationSound {
    private const val MAX_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private var current: Ringtone? = null

    fun play(context: Context, uri: String) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        main.post {
            current?.stop()
            val r = runCatching { RingtoneManager.getRingtone(context.applicationContext, Uri.parse(uri)) }.getOrNull() ?: return@post
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            runCatching { r.play() }
            current = r
            main.removeCallbacksAndMessages(TOKEN)
            main.postAtTime({ if (current === r) { r.stop(); current = null } }, TOKEN, android.os.SystemClock.uptimeMillis() + MAX_MS)
        }
    }

    private val TOKEN = Any()
}
