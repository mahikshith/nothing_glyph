package com.dotforge.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import com.dotforge.rules.ContactStatus
import com.dotforge.rules.NotificationInfo

/**
 * Whether a notification's sender is in Contacts (and starred), for the spam filter's "Always allow".
 * Uses the contact URIs apps attach to messaging notifications, else the title as a number or exact name.
 * Without the Contacts permission everyone is [ContactStatus.UNKNOWN].
 */
class ContactLookup(private val context: Context) {

    fun lookup(info: NotificationInfo): ContactStatus {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ContactStatus.UNKNOWN
        }
        val queries = info.people.mapNotNull(::queryFor) + listOfNotNull(titleQuery(info.title.trim()))
        var best = ContactStatus.UNKNOWN
        for (q in queries) {
            when (runCatching { starred(q) }.getOrNull()) {
                true -> return ContactStatus.STARRED
                false -> best = ContactStatus.CONTACT
                null -> Unit
            }
        }
        return best
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

    /** true = starred contact, false = contact, null = not found. */
    private fun starred(q: Query): Boolean? =
        context.contentResolver.query(q.uri, arrayOf(ContactsContract.Contacts.STARRED), q.selection, q.args, null)?.use { c ->
            var found: Boolean? = null
            while (c.moveToNext()) {
                if (c.getInt(0) == 1) return true
                found = false
            }
            found
        }
}
