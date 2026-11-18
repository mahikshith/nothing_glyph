package com.dotforge.rules

/** How a rule reads in the app: "When [Apps] from [People] mention [Keywords], light up [Glyph]". */
object RuleText {
    const val ANY_APP = "Any app"
    const val ANYONE = "Anyone"
    const val ANYTHING = "Anything"

    /** First item, then "+N" for the rest; [empty] when there are none. */
    private fun short(items: List<String>, empty: String): String = when (items.size) {
        0 -> empty
        1 -> items[0]
        else -> "${items[0]} +${items.size - 1}"
    }

    fun apps(rule: Rule, label: (String) -> String): String = short(rule.packages.map(label), ANY_APP)

    fun people(rule: Rule): String = short(rule.senders, ANYONE)

    fun keywords(rule: Rule): String {
        val words = rule.keywords.filter { it.isNotBlank() }
        if (words.isEmpty()) return ANYTHING
        return when (rule.keywordMode) {
            KeywordMode.REGEX -> if (words.size == 1) "a pattern" else "${words.size} patterns"
            KeywordMode.NONE -> "not " + short(words.map { "\"$it\"" }, ANYTHING)
            KeywordMode.ALL -> if (words.size == 1) "\"${words[0]}\"" else "all of ${words.size} words"
            KeywordMode.ANY -> short(words.map { "\"$it\"" }, ANYTHING)
        }
    }

    fun sentence(rule: Rule, label: (String) -> String): String =
        "When ${apps(rule, label)} from ${people(rule)} mention ${keywords(rule)}, light up ${rule.effect.label}"
}
