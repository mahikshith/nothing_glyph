package com.dotforge.rules

import com.dotforge.engine.Until
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Group
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class RulesFile(val version: Int = VERSION, val rules: List<Rule>, val lists: List<NamedList> = emptyList()) {
    companion object {
        const val VERSION = 3
    }
}

/** A rule as saved by version 2: fixed slots for apps, people and keywords. */
@Serializable
internal data class RuleV2(
    val id: String,
    val name: String = "New rule",
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val starred: Boolean = false,
    val unknown: Boolean = false,
    val keywords: List<String> = emptyList(),
    val keywordMode: KeywordMode = KeywordMode.ANY,
    val regex: Boolean = false,
    val glyph: GlyphStyle = GlyphStyle.PULSE,
    val designId: String? = null,
    val until: Until = Until.READ,
    val priority: Int = 0,
    val soundUri: String? = null,
    val soundName: String? = null,
) {
    /**
     * The same rule in the filter engine: apps stay the rule's apps; people, starred and unknown senders (any
     * of them) become one group; keywords become "contains" (or "matches regex") conditions, all of them for
     * All, none of them for None.
     */
    fun migrate(): Rule {
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        val op = if (regex) Op.REGEX else Op.CONTAINS
        val wordConditions = when {
            words.isEmpty() -> emptyList()
            keywordMode == KeywordMode.ANY -> listOf(Condition(Field.MESSAGE, op, words))
            keywordMode == KeywordMode.ALL -> words.map { Condition(Field.MESSAGE, op, listOf(it)) }
            regex -> listOf(Condition(Field.MESSAGE, Op.REGEX, words, not = true))
            else -> listOf(Condition(Field.MESSAGE, Op.NOT_CONTAINS, words))
        }
        val who = buildList {
            people.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { add(Condition(Field.SENDER, Op.CONTAINS, it)) }
            if (starred) add(Condition.flag(Field.STARRED))
            if (unknown) add(Condition.flag(Field.CONTACT, yes = false))
        }
        val (conditions, groups) = when (who.size) {
            0 -> wordConditions to emptyList()
            1 -> (who + wordConditions) to emptyList()
            else -> wordConditions to listOf(Group(all = false, conditions = who))
        }
        return Rule(
            id = id, name = name, enabled = enabled, source = Source.APPS, packages = packages,
            match = Match(all = true, conditions = conditions, groups = groups),
            action = Action.SHOW, glyph = glyph, designId = designId, until = until, priority = priority,
            soundUri = soundUri, soundName = soundName,
        )
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
    fun migrate() = RuleV2(
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
 * Rules and named lists, saved as JSON in app storage: `{"version":3,"rules":[...],"lists":[...]}`.
 *
 * Migrations run once on load and keep every setting:
 * - version 1 (a bare array) and version 2 (apps / people / keywords slots) rules become filter-engine rules;
 * - the old spam filter's settings ([legacySpam]: words, patterns, unknown senders, allowed senders) become
 *   the spam rules and the "Spam words" and "Allowed senders" lists.
 * Safe to call from any thread.
 */
class RulesRepository(private val file: File, private val legacySpam: SpamSettings? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val lock = Any()

    private val initial = load()
    private val _rules = MutableStateFlow(initial.rules)
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()
    private val _lists = MutableStateFlow(initial.lists)
    val lists: StateFlow<List<NamedList>> = _lists.asStateFlow()

    /** A new rules file: the presets, with the old spam filter's settings when there are any to keep. */
    private fun fresh(): RulesFile {
        if (legacySpam?.let { it.enabled || it.allowedSenders.isNotEmpty() } != true) {
            return RulesFile(rules = RuleEngine.presets(), lists = RuleEngine.presetLists())
        }
        val migrated = fromV2(emptyList())
        return migrated.copy(rules = RuleEngine.presets().filterNot(RuleEngine::isSpamRule) + migrated.rules).also(::write)
    }

    private fun fromV2(old: List<RuleV2>): RulesFile {
        val spam = legacySpam
        val spamRules = RuleEngine.spamPresets(
            spamWords = spam?.enabled == true && (spam.keywords.isNotEmpty() || spam.patterns.isNotEmpty()),
            unknown = spam?.enabled == true && spam.blockUnknown,
            patterns = spam?.patterns.orEmpty(),
        )
        val lists = RuleEngine.presetLists(
            allowed = spam?.allowedSenders.orEmpty(),
            words = spam?.keywords ?: SpamSettings.DEFAULT_KEYWORDS,
        )
        return RulesFile(rules = old.map { it.migrate() } + spamRules, lists = lists)
    }

    private fun load(): RulesFile {
        if (!file.exists()) return fresh()
        val loaded = runCatching {
            val element = json.parseToJsonElement(file.readText())
            if (element is JsonArray) {
                fromV2(json.decodeFromJsonElement(ListSerializer(LegacyRule.serializer()), element).filterNot { it.suppress }.map { it.migrate() })
                    .also(::write)
            } else {
                val version = element.jsonObject["version"]?.jsonPrimitive?.int ?: RulesFile.VERSION
                if (version < RulesFile.VERSION) {
                    val old = element.jsonObject["rules"]?.let { json.decodeFromJsonElement(ListSerializer(RuleV2.serializer()), it) }.orEmpty()
                    fromV2(old).also(::write)
                } else {
                    json.decodeFromJsonElement(RulesFile.serializer(), element)
                }
            }
        }.getOrNull() ?: return fresh()
        return loaded
    }

    private fun write(f: RulesFile) {
        runCatching { file.writeText(json.encodeToString(RulesFile.serializer(), f)) }
    }

    private fun save(rules: List<Rule> = _rules.value, lists: List<NamedList> = _lists.value) = synchronized(lock) {
        _rules.value = rules
        _lists.value = lists
        write(RulesFile(rules = rules, lists = lists))
    }

    /** Replaces the rule with the same id, or adds a new one at the top. */
    fun upsert(rule: Rule) = synchronized(lock) {
        val list = _rules.value.toMutableList()
        val i = list.indexOfFirst { it.id == rule.id }
        if (i >= 0) list[i] = rule else list.add(0, rule)
        save(rules = list)
    }

    fun delete(id: String) = synchronized(lock) { save(rules = _rules.value.filterNot { it.id == id }) }

    /** Presets that are not in the list. */
    fun missingPresets(): List<Rule> {
        val have = _rules.value.map { it.id }.toSet()
        return RuleEngine.presets().filterNot { it.id in have }
    }

    /** Adds back [presets] (default: every missing one) at the end. */
    fun addPresets(presets: List<Rule> = missingPresets()) = synchronized(lock) {
        val have = _rules.value.map { it.id }.toSet()
        val names = _lists.value.map { it.name.lowercase() }.toSet()
        save(
            rules = _rules.value + presets.filterNot { it.id in have },
            lists = _lists.value + RuleEngine.presetLists().filterNot { it.name.lowercase() in names },
        )
    }

    /** Makes sure the preset [id] is there and switched on (Allow / Always block from the Spam log use it). */
    fun ensurePreset(id: String) = synchronized(lock) {
        val have = _rules.value.firstOrNull { it.id == id }
        when {
            have == null -> RuleEngine.presets().firstOrNull { it.id == id }?.let { addPresets(listOf(it.copy(enabled = true))) }
            !have.enabled -> upsert(have.copy(enabled = true))
            else -> Unit
        }
    }

    fun list(name: String): NamedList? = _lists.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Replaces (or adds) the list called [list].name; [oldName] renames, and rules that use it follow. */
    fun putList(list: NamedList, oldName: String? = null) = synchronized(lock) {
        val key = (oldName ?: list.name).lowercase()
        val lists = _lists.value.filterNot { it.name.lowercase() == key || it.name.equals(list.name, ignoreCase = true) } + list
        val rules = if (oldName != null && !oldName.equals(list.name, ignoreCase = true)) {
            _rules.value.map { r -> r.copy(match = renameList(r.match, oldName, list.name)) }
        } else _rules.value
        save(rules, lists.sortedBy { it.name.lowercase() })
    }

    fun deleteList(name: String) = synchronized(lock) { save(lists = _lists.value.filterNot { it.name.equals(name, ignoreCase = true) }) }

    /** Adds [entry] to the list [name] (made when missing) and takes it off [removeFrom]. */
    fun addToList(name: String, entry: String, removeFrom: String? = null) = synchronized(lock) {
        val e = entry.trim()
        if (e.isEmpty()) return@synchronized
        val lists = _lists.value.map { l ->
            when {
                l.name.equals(name, ignoreCase = true) -> l.copy(entries = l.entries.filterNot { it.equals(e, ignoreCase = true) } + e)
                removeFrom != null && l.name.equals(removeFrom, ignoreCase = true) -> l.copy(entries = l.entries.filterNot { it.equals(e, ignoreCase = true) })
                else -> l
            }
        }
        save(lists = if (lists.any { it.name.equals(name, ignoreCase = true) }) lists else lists + NamedList(name, listOf(e)))
    }

    private fun renameList(m: Match, from: String, to: String): Match {
        fun c(x: Condition) = if (x.list?.equals(from, ignoreCase = true) == true) x.copy(list = to) else x
        return m.copy(conditions = m.conditions.map(::c), groups = m.groups.map { g -> g.copy(conditions = g.conditions.map(::c)) })
    }
}
