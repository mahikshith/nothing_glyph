package com.dotforge.calls

/** Where a caller's name came from. Lower [rank] wins. */
enum class NameSource(val rank: Int) {
    /** A saved contact. */
    CONTACT(0),
    /** The default phone app's ringing notification (Google Phone caller ID, business names). */
    DIALER(1),
    /** Another caller ID app (Truecaller and the like), or a VoIP app's ringing call (WhatsApp). */
    OTHER_APP(2),
    /** Only the number, formatted for the phone's region. */
    NUMBER(3),
}

data class NameCandidate(val name: String, val source: NameSource)

/**
 * Picks the name to scroll for a ringing call. The best source wins ([NameSource]); a better one that arrives
 * later replaces it live. Generic titles ("Incoming call", "Unknown", "Private number") and bare numbers are
 * never names; a spam label ("Suspected spam", "Spam likely") marks the call as spam instead.
 */
object CallerNames {
    private val GENERIC = listOf(
        "incoming call", "incoming voice call", "incoming video call", "incoming audio call", "incoming", "ringing",
        "calling", "calling…", "calling...", "call", "voice call", "video call", "phone", "phone call", "ongoing call",
        "unknown", "unknown caller", "unknown number", "private", "private number", "private caller", "no caller id",
        "no caller id available", "restricted", "restricted number", "blocked number", "hidden number", "anonymous",
        "withheld", "number withheld", "caller", "dialing", "wi-fi call", "wifi call",
    )
    private val SPAM = Regex("""\b(suspected spam|spam likely|likely spam|spam risk|potential spam|spam call|spam caller|scam likely|fraud risk|spam)\b""", RegexOption.IGNORE_CASE)
    private val NUMBERISH = Regex("""^[+()\d\s.\-/]+$""")

    /** True for a title that says nothing about who is calling. */
    fun isGeneric(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('.', '…', '!').trim()
        if (t.isEmpty()) return true
        if (t in GENERIC) return true
        // "Incoming call from …" without a name, "Incoming call · SIM 1", "Incoming WhatsApp voice call"
        return t.startsWith("incoming ") && (t.endsWith(" call") || t.contains(" call ·") || t.contains(" call -"))
    }

    /** True for a number with no name ("+44 7700 900123", "(555) 010-9999"). */
    fun isNumberOnly(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && NUMBERISH.matches(t) && t.count(Char::isDigit) >= 3
    }

    /** True when the text is a spam label from a caller ID service. */
    fun isSpamLabel(text: String): Boolean = SPAM.containsMatchIn(text)

    /** A usable name in [text], or null for generic, number-only, spam-label or blank text. */
    fun clean(text: String?): String? {
        val t = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty() || isGeneric(t) || isNumberOnly(t) || isSpamLabel(t)) return null
        return t
    }

    /**
     * The name a ringing call notification gives: its title, or its text when the title is generic (VoIP apps put
     * "Incoming voice call" in the title on some versions). Null when neither names anyone.
     */
    fun fromNotification(title: String?, text: String?): String? = clean(title) ?: clean(text)
}

/**
 * The name for one call, cached while it rings: candidates from every source, the best kept. [offer] says
 * whether the shown name changed (a live upgrade), [spam] whether any source labelled the call as spam.
 * Not thread-safe; [CallSession] guards it.
 */
class CallerName {
    private val candidates = HashMap<NameSource, String>()
    var spam: Boolean = false
        private set

    val best: NameCandidate?
        get() = candidates.minByOrNull { it.key.rank }?.let { NameCandidate(it.value, it.key) }

    /** Adds a name from [source]. Returns true when the best name changed. */
    fun offer(name: String?, source: NameSource): Boolean {
        val n = (if (source == NameSource.NUMBER) name?.trim()?.takeIf { it.isNotEmpty() } else CallerNames.clean(name)) ?: return false
        val before = best
        candidates[source] = n
        return best != before
    }

    /** A spam label seen in a notification's title or text. Returns true when that is new. */
    fun markSpam(vararg texts: String?): Boolean {
        if (spam || texts.none { it != null && CallerNames.isSpamLabel(it) }) return false
        spam = true
        return true
    }

    fun clear() {
        candidates.clear()
        spam = false
    }
}
