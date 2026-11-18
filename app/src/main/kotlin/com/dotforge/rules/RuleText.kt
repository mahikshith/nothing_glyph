package com.dotforge.rules

/** How a rule reads in the app: "When [Apps] from [People] mention [Keywords], show [Glyph] until [read]". */
object RuleText {
    const val ANY_APP = "Any app"
    const val ANYONE = "Anyone"
    const val ANYTHING = "Anything"
    const val STARRED = "Starred"
    const val UNKNOWN = "Unknown senders"

    /** First item, then "+N" for the rest; [empty] when there are none. */
    private fun short(items: List<String>, empty: String): String = when (items.size) {
        0 -> empty
        1 -> items[0]
        else -> "${items[0]} +${items.size - 1}"
    }

    fun apps(rule: Rule, label: (String) -> String): String = short(rule.packages.map(label), ANY_APP)

    fun people(rule: Rule): String =
        short(rule.people.filter { it.isNotBlank() } + listOfNotNull(STARRED.takeIf { rule.starred }, UNKNOWN.takeIf { rule.unknown }), ANYONE)

    fun keywords(rule: Rule): String {
        val words = rule.words
        if (words.isEmpty()) return ANYTHING
        val listed = if (rule.regex) {
            if (words.size == 1) "a pattern" else "${words.size} patterns"
        } else {
            when (rule.keywordMode) {
                KeywordMode.ALL -> if (words.size == 1) "\"${words[0]}\"" else "all of ${words.size} words"
                else -> short(words.map { "\"$it\"" }, ANYTHING)
            }
        }
        return when {
            rule.keywordMode == KeywordMode.NONE -> "not $listed"
            rule.regex && rule.keywordMode == KeywordMode.ALL && words.size > 1 -> "all of $listed"
            else -> listed
        }
    }

    /** [designName] names the saved design a [GlyphStyle.DESIGN] rule shows. */
    fun glyph(rule: Rule, designName: (String?) -> String?): String =
        if (rule.glyph == GlyphStyle.DESIGN) designName(rule.designId) ?: rule.glyph.label else rule.glyph.label

    fun sentence(rule: Rule, label: (String) -> String, designName: (String?) -> String? = { null }): String =
        "When ${apps(rule, label)} from ${people(rule)} mention ${keywords(rule)}, " +
            "show ${glyph(rule, designName)} until ${rule.until.label}"
}
