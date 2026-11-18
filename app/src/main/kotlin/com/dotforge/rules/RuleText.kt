package com.dotforge.rules

import com.dotforge.filter.FilterEngine

/**
 * How a rule reads in the app: "When [WhatsApp] and [sender starts with "+44"], [mark as spam]", or for a rule
 * that shows: "When [Any app] and [title or text contains "urgent"], show [Pulse] until [read] and play [Bells]".
 */
object RuleText {
    const val ANY_APP = "Any app"
    const val CALLS = "Phone calls"
    const val EVERYTHING = "Calls and any app"
    const val ANYTHING = "anything"

    /** First item, then "+N" for the rest; [empty] when there are none. */
    private fun short(items: List<String>, empty: String): String = when (items.size) {
        0 -> empty
        1 -> items[0]
        else -> "${items[0]} +${items.size - 1}"
    }

    fun source(rule: Rule, label: (String) -> String): String = when (rule.source) {
        Source.CALLS -> CALLS
        Source.APPS -> short(rule.packages.map(label), ANY_APP)
        Source.EVERYTHING -> if (rule.packages.isEmpty()) EVERYTHING else "Calls and " + short(rule.packages.map(label), ANY_APP)
    }

    fun conditions(rule: Rule, label: (String) -> String = { it }): String =
        FilterEngine.describe(rule.match, label).ifEmpty { ANYTHING }

    /** [designName] names the saved design a [GlyphStyle.DESIGN] rule shows. */
    fun glyph(rule: Rule, designName: (String?) -> String?): String =
        if (rule.glyph == GlyphStyle.DESIGN) designName(rule.designId) ?: rule.glyph.label else rule.glyph.label

    /** The rule's sound, or null when it plays none. */
    fun sound(rule: Rule): String? = rule.soundUri?.let { rule.soundName?.takeIf { n -> n.isNotBlank() } ?: "a ringtone" }

    fun action(rule: Rule, designName: (String?) -> String? = { null }): String = when (rule.action) {
        Action.SHOW -> buildString {
            append(if (rule.glyph == GlyphStyle.NONE) "show no glyph" else "show ${glyph(rule, designName)} until ${rule.until.label}")
            sound(rule)?.let { append(" and play $it") }
            if (rule.vibrate) append(", vibrate")
        }
        else -> rule.action.label
    }

    fun sentence(rule: Rule, label: (String) -> String, designName: (String?) -> String? = { null }): String {
        val cond = rule.match.takeIf { !it.isEmpty }?.let { " and " + FilterEngine.describe(it, label) }.orEmpty()
        return "When ${source(rule, label)}$cond, ${action(rule, designName)}"
    }
}
