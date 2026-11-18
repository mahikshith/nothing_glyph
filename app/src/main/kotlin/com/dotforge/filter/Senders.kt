package com.dotforge.filter

import com.dotforge.calls.PhoneNumbers

/** One message of a MessagingStyle notification. [sender] is null for the phone's own messages. */
data class NotificationMessage(val sender: String?, val text: String, val senderUri: String? = null)

/** The text fields of a notification, read from its extras. */
data class NotificationFields(
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val subText: String = "",
    val summary: String = "",
    val conversationTitle: String = "",
    val isGroupConversation: Boolean = false,
    val messages: List<NotificationMessage> = emptyList(),
    /** Person URIs: tel:, mailto: or content://. */
    val people: List<String> = emptyList(),
    val textLines: List<String> = emptyList(),
)

/** Who sent a notification: a name, an email and a phone number when they can be found. */
data class SenderInfo(val name: String, val email: String = "", val number: String? = null, val group: Boolean = false)

/**
 * Reads notifications the same way for every app: SMS (Google Messages and others), WhatsApp (direct and
 * group), Gmail and anything else. Keys are the platform's extra names ("android.title", ...); the Android
 * side copies a notification's extras into a map, so the rules here are plain and testable.
 */
object Senders {
    const val TITLE = "android.title"
    const val TEXT = "android.text"
    const val BIG_TEXT = "android.bigText"
    const val SUB_TEXT = "android.subText"
    const val SUMMARY = "android.summaryText"
    const val CONVERSATION_TITLE = "android.conversationTitle"
    const val IS_GROUP = "android.isGroupConversation"
    /** A list of maps with "sender", "text" and (from sender_person) "uri". */
    const val MESSAGES = "android.messages"
    /** Person URIs (EXTRA_PEOPLE_LIST, or the older EXTRA_PEOPLE strings). */
    const val PEOPLE = "android.people.list"
    const val TEXT_LINES = "android.textLines"

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    private fun str(v: Any?): String = v?.toString()?.trim().orEmpty()

    fun fields(extras: Map<String, Any?>): NotificationFields {
        val messages = (extras[MESSAGES] as? List<*>).orEmpty().mapNotNull { m ->
            val map = m as? Map<*, *> ?: return@mapNotNull null
            NotificationMessage(str(map["sender"]).ifEmpty { null }, str(map["text"]), str(map["uri"]).ifEmpty { null })
        }
        return NotificationFields(
            title = str(extras[TITLE]),
            text = str(extras[TEXT]),
            bigText = str(extras[BIG_TEXT]),
            subText = str(extras[SUB_TEXT]),
            summary = str(extras[SUMMARY]),
            conversationTitle = str(extras[CONVERSATION_TITLE]),
            isGroupConversation = extras[IS_GROUP] == true,
            messages = messages,
            people = (extras[PEOPLE] as? List<*>).orEmpty().map(::str).filter { it.isNotEmpty() },
            textLines = (extras[TEXT_LINES] as? List<*>).orEmpty().map(::str).filter { it.isNotEmpty() },
        )
    }

    /**
     * The sender:
     * - MessagingStyle (SMS, WhatsApp, Telegram, Signal): the newest message with a sender; in a group that is
     *   the person who wrote, not the group;
     * - otherwise the title, with WhatsApp's "Group: Name" and "Name @ Group" forms taken apart;
     * - the email from a mailto: person (Gmail adds one) or an address in the title;
     * - the number from a tel: person or message URI, or a title that is a phone number, normalised in [region].
     * Gmail's subtext is the user's own account, so it is never taken as the sender.
     */
    fun sender(f: NotificationFields, region: String?): SenderInfo {
        val group = f.isGroupConversation ||
            (f.conversationTitle.isNotEmpty() && f.messages.mapNotNull { it.sender }.distinct().size > 1)
        val fromMessages = f.messages.lastOrNull { it.sender != null }
        var name = fromMessages?.sender ?: f.title
        if (fromMessages == null && f.conversationTitle.isNotEmpty()) {
            val ct = f.conversationTitle
            name = when {
                name.startsWith("$ct: ") -> name.removePrefix("$ct: ")
                name.endsWith(" @ $ct") -> name.removeSuffix(" @ $ct")
                else -> name
            }
        }
        // titles like "Sam (2 messages)"
        name = name.replace(Regex("""\s*\(\d+ (new )?messages?\)$"""), "").trim()

        val uris = listOfNotNull(fromMessages?.senderUri) + f.people
        val email = uris.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }?.substringAfter(':')
            ?: EMAIL.find(f.title)?.value
            ?: ""
        val tel = uris.firstOrNull { it.startsWith("tel:", ignoreCase = true) }?.substringAfter(':')
        val rawNumber = tel ?: name.takeIf { PhoneNumbers.looksLikeNumber(it) }
        val number = rawNumber?.let { PhoneNumbers.key(it, region) }?.takeIf { it.isNotEmpty() }
        return SenderInfo(name, email, number, group)
    }
}
