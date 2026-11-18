package com.dotforge.rules

import com.dotforge.system.BedtimeSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** DotForge's own quiet hours: from [start] to [end] (minutes after midnight) on [days] (1 = Monday). */
@Serializable
data class QuietHours(
    val enabled: Boolean = false,
    val start: Int = 22 * 60,
    val end: Int = 7 * 60,
    val days: List<Int> = (1..7).toList(),
    /** Also pause during Nothing's Glyph bedtime (when the firmware lets us read it). */
    val followBedtime: Boolean = true,
) {
    val startTime: LocalTime get() = LocalTime.of(start / 60 % 24, start % 60)
    val endTime: LocalTime get() = LocalTime.of(end / 60 % 24, end % 60)

    /** Quiet at [now]. A range that ends at or before its start runs past midnight into the next day. */
    fun isActive(now: LocalDateTime): Boolean {
        if (!enabled) return false
        val d = days.filter { it in 1..7 }.map(DayOfWeek::of).toSet()
        return d.isNotEmpty() && BedtimeSchedule(startTime, endTime, d).isActive(now)
    }
}

@Serializable
data class FilterSettings(
    val quiet: QuietHours = QuietHours(),
    val respectDnd: Boolean = true,
    val onlyLocked: Boolean = false,
    val onlyFaceDown: Boolean = false,
)

/** What the phone is doing when a notification arrives. [faceDown] is null when unknown. */
data class Conditions(val now: LocalDateTime, val dnd: Boolean, val locked: Boolean, val faceDown: Boolean?)

/** What happened to one notification. */
sealed interface Verdict {
    /** The spam filter caught it. */
    data class Blocked(val reason: String) : Verdict
    data object NoRule : Verdict
    /** A rule matched but a filter held it back. */
    data class Quiet(val match: RuleMatch, val reason: String) : Verdict
    data class Show(val match: RuleMatch) : Verdict
}

/** One line of the rule tester. [cooldown]: a rule matched but the conversation alerted moments ago. */
data class LogEntry(val info: NotificationInfo, val verdict: Verdict, val cooldown: Boolean = false)

object Delivery {
    /**
     * Spam filter first, then the best rule, then quiet hours, Do Not Disturb, locked and face down.
     * [sender] reads Contacts; it runs at most once and only when a setting or rule needs it.
     */
    fun decide(
        info: NotificationInfo,
        rules: List<Rule>,
        spam: SpamSettings,
        filters: FilterSettings,
        conditions: () -> Conditions,
        sender: () -> Sender?,
    ): Verdict {
        val lookup = lazy(LazyThreadSafetyMode.NONE, sender)
        val who = { lookup.value }
        SpamFilter.check(info, spam, who)?.let { return Verdict.Blocked(it) }
        val match = RuleEngine.match(info, rules, who) ?: return Verdict.NoRule
        val c = conditions()
        val quiet = when {
            filters.quiet.isActive(c.now) -> "quiet hours"
            filters.respectDnd && c.dnd -> "Do Not Disturb"
            filters.onlyLocked && !c.locked -> "phone unlocked"
            filters.onlyFaceDown && c.faceDown == false -> "not face down"
            else -> null
        }
        return if (quiet == null) Verdict.Show(match) else Verdict.Quiet(match, quiet)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class FilterState(
    // version 1 saved the spam filter alone in spam.json as {"settings":…, "catches":…}
    @JsonNames("settings") val spam: SpamSettings = SpamSettings(),
    val catches: List<SpamCatch> = emptyList(),
    val filters: FilterSettings = FilterSettings(),
)

/**
 * The Filters section: spam filter settings and recent catches, quiet hours and the condition switches, in
 * one JSON file. The spam.json of version 1 is moved in on first load. Call from one thread (the main thread).
 */
class FiltersRepository(private val file: File, legacySpam: File? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private var state = load(legacySpam)

    private val _spam = MutableStateFlow(state.spam)
    val spam: StateFlow<SpamSettings> = _spam.asStateFlow()

    private val _catches = MutableStateFlow(state.catches)
    /** Newest first. */
    val catches: StateFlow<List<SpamCatch>> = _catches.asStateFlow()

    private val _filters = MutableStateFlow(state.filters)
    val filters: StateFlow<FilterSettings> = _filters.asStateFlow()

    private fun read(f: File): FilterState? =
        runCatching { json.decodeFromString(FilterState.serializer(), f.readText()) }.getOrNull()

    private fun load(legacy: File?): FilterState {
        if (file.exists()) return read(file) ?: FilterState()
        val migrated = legacy?.takeIf { it.exists() }?.let(::read) ?: return FilterState()
        file.writeText(json.encodeToString(FilterState.serializer(), migrated))
        legacy.delete()
        return migrated
    }

    private fun save(next: FilterState) {
        state = next
        _spam.value = next.spam
        _catches.value = next.catches
        _filters.value = next.filters
        file.writeText(json.encodeToString(FilterState.serializer(), next))
    }

    fun updateSpam(transform: (SpamSettings) -> SpamSettings) = save(state.copy(spam = transform(state.spam)))

    fun updateFilters(transform: (FilterSettings) -> FilterSettings) = save(state.copy(filters = transform(state.filters)))

    fun record(info: NotificationInfo) {
        val c = SpamCatch(info.key, info.appLabel, info.title, info.text, info.postedAt)
        save(state.copy(catches = (listOf(c) + state.catches.filterNot { it.key == c.key }).take(MAX_CATCHES)))
    }

    /** Never block this sender again, and forget their catches. */
    fun allow(c: SpamCatch) {
        val who = c.title.trim()
        if (who.isEmpty()) {
            save(state.copy(catches = state.catches - c))
            return
        }
        val s = state.spam
        val allowed = if (s.allowedSenders.any { it.equals(who, ignoreCase = true) }) s.allowedSenders else s.allowedSenders + who
        save(
            state.copy(
                spam = s.copy(allowedSenders = allowed),
                catches = state.catches.filterNot { it.title.trim().equals(who, ignoreCase = true) },
            ),
        )
    }

    private companion object {
        const val MAX_CATCHES = 50
    }
}
