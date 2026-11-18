package com.dotforge.rules

import com.dotforge.engine.Until
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File

@Serializable
private data class RulesFile(val version: Int = VERSION, val rules: List<Rule>) {
    companion object {
        const val VERSION = 2
    }
}

/** A rule as saved by version 1 (a bare JSON array), read once to migrate. */
@Serializable
private data class LegacyRule(
    val id: String,
    val name: String = "Rule",
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
    val senders: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val keywordMode: String = "ANY",
    val effect: String = "PULSE",
    val hold: Boolean = false,
    val suppress: Boolean = false,
) {
    fun migrate() = Rule(
        id = id,
        name = name,
        enabled = enabled,
        packages = packages,
        people = senders,
        keywords = keywords,
        keywordMode = KeywordMode.entries.firstOrNull { it.name == keywordMode } ?: KeywordMode.ANY,
        regex = keywordMode == "REGEX",
        glyph = when (effect) {
            "RING" -> GlyphStyle.RING
            "LETTER" -> GlyphStyle.LETTER
            "FLASH" -> GlyphStyle.FLASH
            "MARQUEE" -> GlyphStyle.NAME
            "SPARKLE" -> GlyphStyle.SPARKLE
            "CUSTOM" -> GlyphStyle.DESIGN
            else -> GlyphStyle.PULSE
        },
        until = if (hold) Until.READ else Until.TIMEOUT,
    )
}

/**
 * Rules saved as JSON in app storage, `{"version":2,"rules":[...]}`. Version 1 files (a bare array) are
 * migrated on first load; their spam rules are dropped, as the spam filter has its own settings.
 * Call from one thread (the main thread).
 */
class RulesRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private fun load(): List<Rule> {
        if (!file.exists()) return RuleEngine.presets()
        return runCatching {
            val element = json.parseToJsonElement(file.readText())
            if (element is JsonArray) {
                json.decodeFromJsonElement(ListSerializer(LegacyRule.serializer()), element)
                    .filterNot { it.suppress }.map { it.migrate() }
                    .also(::write)
            } else {
                json.decodeFromJsonElement(RulesFile.serializer(), element).rules
            }
        }.getOrElse { RuleEngine.presets() }
    }

    private fun write(list: List<Rule>) = file.writeText(json.encodeToString(RulesFile.serializer(), RulesFile(rules = list)))

    private fun save(list: List<Rule>) {
        _rules.value = list
        write(list)
    }

    /** Replaces the rule with the same id, or adds a new one at the top. */
    fun upsert(rule: Rule) {
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == rule.id }
        if (i >= 0) list[i] = rule else list.add(0, rule)
        save(list)
    }

    fun delete(id: String) = save(_rules.value.filterNot { it.id == id })

    /** Presets that are not in the list. */
    fun missingPresets(): List<Rule> {
        val have = _rules.value.map { it.id }.toSet()
        return RuleEngine.presets().filterNot { it.id in have }
    }

    /** Adds back [presets] (default: every missing one) at the end. */
    fun addPresets(presets: List<Rule> = missingPresets()) {
        val have = _rules.value.map { it.id }.toSet()
        save(_rules.value + presets.filterNot { it.id in have })
    }
}
