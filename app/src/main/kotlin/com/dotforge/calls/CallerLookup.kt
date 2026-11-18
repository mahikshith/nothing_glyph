package com.dotforge.calls

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.dotforge.filter.Caller
import java.util.Locale

/** Contacts and the phone's region, as call screening needs them. Contact reads return null without access. */
class CallerLookup(private val context: Context) {
    private val cr get() = context.contentResolver

    fun canRead(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The phone's two-letter region (network, then SIM, then locale). */
    fun region(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { it.length == 2 }?.uppercase()
    }

    /** [raw] in E.164 (Android's own formatter first, then ours), else its digits. */
    fun key(raw: String): String {
        val region = region()
        val platform = region?.let { runCatching { PhoneNumberUtils.formatNumberToE164(raw, it) }.getOrNull() }
        return platform ?: PhoneNumbers.key(raw, region)
    }

    /** Who is calling from [raw], or null when Contacts can't be read. */
    fun caller(raw: String): Caller? {
        if (!canRead() || raw.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(raw))
        val cols = arrayOf(ContactsContract.PhoneLookup.CONTACT_ID, ContactsContract.PhoneLookup.STARRED, ContactsContract.PhoneLookup.DISPLAY_NAME)
        return runCatching {
            cr.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use Caller(contact = false)
                val id = c.getLong(0)
                Caller(contact = true, starred = c.getInt(1) == 1, name = c.getString(2), groups = groupsOf(id))
            }
        }.getOrNull()
    }

    private fun groupsOf(contactId: Long): Set<String> {
        val ids = mutableSetOf<Long>()
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        if (ids.isEmpty()) return emptySet()
        return groups().filter { it.first in ids }.map { it.second }.toSet()
    }

    /** Contacts groups as (id, title), visible ones only. */
    private fun groups(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        runCatching {
            cr.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups.DELETED} = 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += c.getLong(0) to it }
            }
        }
        return out
    }

    /** Group titles for the pickers; empty without Contacts. */
    fun groupTitles(): List<String> = if (canRead()) groups().map { it.second }.distinct().sortedBy { it.lowercase() } else emptyList()

    /** Content URIs of the contacts in the group called [title]. */
    fun members(title: String): List<Uri> {
        if (!canRead()) return emptyList()
        val ids = groups().filter { it.second.equals(title, ignoreCase = true) }.map { it.first }
        if (ids.isEmpty()) return emptyList()
        val contacts = mutableSetOf<Long>()
        runCatching {
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} IN (${ids.joinToString(",")})",
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
                null,
            )?.use { c -> while (c.moveToNext()) contacts += c.getLong(0) }
        }
        return contacts.map { ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it) }
    }
}
