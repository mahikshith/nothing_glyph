package com.dotforge.rules

/** A tappable word of the sheet's sentence (row 47, row 90): what its picker changes. */
enum class WordKind(val action: String) {
    APP("change app"), WHO("change who"), GLYPH("change glyph"), SHOW_FOR("change how long"), SOUND("change sound"),
    CALL_RING("change what happens when they call"),
}

/** One piece of the sentence: plain text, or a word that opens a picker. */
sealed interface SentencePart {
    data class Text(val text: String) : SentencePart
    data class Word(val text: String, val kind: WordKind) : SentencePart {
        /** What TalkBack reads: "Chat glyph, change glyph" (the role adds "button"). */
        val spoken: String get() = "$text, ${kind.action}"
    }
}

/**
 * The one sheet's sentence, whose underlined words are the controls:
 * "When [WhatsApp] sends something from [everyone], the [Chat glyph] shows [until you check]. Sound: [App's own sound]."
 * People, Phone calls, Reminders and the phone moments each read their own way.
 */
object AlertSentence {
    /**
     * "Heart glyph", "app icon", "their name", "your design Sunrise". The app icon is named only when it can
     * really be drawn ([hasIcon]); otherwise the Chat bubble glyph stands in, and the words say so.
     */
    fun glyphWord(r: Rule, designName: (String?) -> String? = { null }, hasIcon: (String) -> Boolean = { true }): String = when (r.glyph) {
        GlyphStyle.ICON -> (com.dotforge.device.GlyphLibrary.get(r.designId)?.name ?: "Dot") + " glyph"
        GlyphStyle.APP_ICON -> if (r.packages.size == 1 && hasIcon(r.packages[0])) "app icon" else CHAT_BUBBLE
        GlyphStyle.NAME -> "their name"
        GlyphStyle.DESIGN -> designName(r.designId) ?: "your design"
        GlyphStyle.BELL -> "bell"
        GlyphStyle.NONE -> "nothing"
        else -> r.glyph.label.lowercase()
    }

    fun whoWord(r: Rule): String {
        val people = SimpleRules.people(r)
        return when (people.size) {
            0 -> "everyone"
            1 -> people[0]
            else -> "${people.size} people"
        }
    }

    /** What the app icon falls back to when the app's icon can't be read. */
    const val CHAT_BUBBLE = "Chat bubble glyph"

    fun parts(r: Rule, appName: String, designName: (String?) -> String? = { null }, hasIcon: (String) -> Boolean = { true }): List<SentencePart> {
        fun t(s: String) = SentencePart.Text(s)
        fun w(s: String, k: WordKind) = SentencePart.Word(s, k)
        val glyph = glyphWord(r, designName, hasIcon)
        val show = SimpleRules.showText(r)
        val sound = listOf(t(". Sound: "), w(r.sound.label.replaceFirstChar { it.lowercase() }.let { if (r.sound == SoundMode.CUSTOM) r.soundName ?: "custom tune" else it }, WordKind.SOUND), t("."))
        return when {
            r.id == SimpleRules.PHONE_CALLS -> when (r.callShow) {
                CallShow.NAME -> listOf(t("When a call rings, the caller's name scrolls while it rings."))
                CallShow.GLYPH -> listOf(t("When a call rings, the "), w(glyph, WordKind.GLYPH), t(" shows while it rings."))
                CallShow.BOTH -> listOf(t("When a call rings, the "), w(glyph, WordKind.GLYPH), t(" shows, then the caller's name scrolls, while it rings."))
            }
            r.moment != null -> listOf(t("When ${r.moment.sentence}, the "), w(glyph, WordKind.GLYPH), t(" shows "), w(show, WordKind.SHOW_FOR), t("."))
            r.id == SimpleRules.REMINDERS -> listOf(t("When a reminder or event arrives, the "), w(glyph, WordKind.GLYPH), t(" shows "), w(show, WordKind.SHOW_FOR)) + sound
            SimpleRules.isPersonRule(r) -> listOf(t("When "), t(r.name), t(" messages, the "), w(glyph, WordKind.GLYPH), t(" shows "), w(show, WordKind.SHOW_FOR)) + sound
            else -> listOf(t("When "), w(appName, WordKind.APP), t(" sends something from "), w(whoWord(r), WordKind.WHO), t(", the "), w(glyph, WordKind.GLYPH), t(" shows "), w(show, WordKind.SHOW_FOR)) + sound
        }
    }

    /** The whole sentence as plain text (for a row's summary and for tests). */
    fun text(r: Rule, appName: String, designName: (String?) -> String? = { null }, hasIcon: (String) -> Boolean = { true }): String =
        parts(r, appName, designName, hasIcon).joinToString("") { p -> if (p is SentencePart.Text) p.text else (p as SentencePart.Word).text }

    /**
     * A row's line, with only what tells rows apart: where it listens when that isn't one app ("Any app",
     * "Chat apps"), who when it isn't everyone, the glyph, and how long unless it waits until you check.
     * "App icon", "Any app · Flash · 5 s", or "Off".
     */
    fun summary(r: Rule, designName: (String?) -> String? = { null }, hasIcon: (String) -> Boolean = { true }): String {
        if (!r.enabled) return "Off"
        val glyph = glyphWord(r, designName, hasIcon).replaceFirstChar { it.uppercase() }
        // "until you check" is how alerts work unless told otherwise: only a time or another end is said
        val show = SimpleRules.showText(r).takeUnless { it == "until you check" && r.moment == null }?.removePrefix("for ")?.replaceFirstChar { it.uppercase() }
        return when {
            r.id == SimpleRules.PHONE_CALLS -> when (r.callShow) { CallShow.NAME -> "Their name"; CallShow.GLYPH -> glyph; CallShow.BOTH -> "$glyph, then name" }
            r.moment != null -> listOfNotNull(glyph, show).joinToString(" · ")
            SimpleRules.isPersonRule(r) -> listOfNotNull(glyph, show, "Glyph ringtone for calls".takeIf { r.callRingtone != null }).joinToString(" · ")
            else -> listOfNotNull(sourceWord(r), whoWord(r).takeIf { it != "everyone" }, glyph, show).joinToString(" · ")
        }
    }

    /** Where a rule listens, when it isn't a single app: "Any app", "Chat apps", "4 apps"; null for one app. */
    fun sourceWord(r: Rule): String? = when {
        r.id == SimpleRules.REMINDERS || r.id == SimpleRules.PHONE_CALLS || r.moment != null || SimpleRules.isPersonRule(r) -> null
        r.packages.isEmpty() -> "Any app"
        r.packages.size == 1 -> null
        r.packages.all { it in RuleEngine.MESSAGE_APPS } -> "Chat apps"
        else -> "${r.packages.size} apps"
    }
}
