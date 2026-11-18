package com.dotforge.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import com.dotforge.rules.ContactStatus
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Sender

/** A contact for the people picker. */
data class ContactName(val name: String, val starred: Boolean)

/**
 * Who sent a notification, as Contacts knows them: for rules that name people, starred contacts or unknown
 * senders, and for the spam filter. Uses the contact URIs apps attach to messaging notifications, else the
 * title as a number or exact name. Returns null without the Contacts permission.
 */
class ContactLookup(private val context: Context) {

    private fun granted() =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun lookup(info: NotificationInfo): Sender? {
        if (!granted()) return null
        val queries = info.people.mapNotNull(::queryFor) + listOfNotNull(titleQuery(info.title.trim()))
        var best = Sender(ContactStatus.UNKNOWN)
        for (q in queries) {
            val found = runCatching { find(q) }.getOrNull() ?: continue
            if (found.status == ContactStatus.STARRED) return found
            if (best.status == ContactStatus.UNKNOWN) best = found
        }
        return best
    }

    /** Every contact with a name, starred first, for the people picker. Empty without the permission. */
    fun names(): List<ContactName> {
        if (!granted()) return emptyList()
        val cols = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED)
        val out = mutableListOf<ContactName>()
        runCatching {
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    if (name.isNotEmpty()) out += ContactName(name, c.getInt(1) == 1)
                }
            }
        }
        return out.distinctBy { it.name.lowercase() }.sortedWith(compareBy({ !it.starred }, { it.name.lowercase() }))
    }

    private class Query(val uri: Uri, val selection: String? = null, val args: Array<String>? = null)

    private fun queryFor(person: String): Query? {
        val uri = Uri.parse(person)
        return when (uri.scheme) {
            "tel" -> phone(uri.schemeSpecificPart)
            "mailto" -> Query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(uri.schemeSpecificPart)))
            "content" -> if (uri.authority == ContactsContract.AUTHORITY) Query(uri) else null
            else -> null
        }
    }

    private fun titleQuery(title: String): Query? = when {
        title.isEmpty() -> null
        title.all { it.isDigit() || it in "+-() " } && title.count { it.isDigit() } >= 3 -> phone(title)
        else -> Query(ContactsContract.Contacts.CONTENT_URI, "${ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(title))
    }

    private fun phone(number: String) =
        Query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)))

    /** The first starred match, else the first match, else null. */
    private fun find(q: Query): Sender? {
        val cols = arrayOf(ContactsContract.Contacts.STARRED, ContactsContract.Contacts.DISPLAY_NAME)
        return context.contentResolver.query(q.uri, cols, q.selection, q.args, null)?.use { c ->
            var found: Sender? = null
            while (c.moveToNext()) {
                val s = Sender(if (c.getInt(0) == 1) ContactStatus.STARRED else ContactStatus.CONTACT, c.getString(1))
                if (s.status == ContactStatus.STARRED) return s
                if (found == null) found = s
            }
            found
        }
    }
}
