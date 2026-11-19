package com.dotforge.rules

import com.dotforge.filter.Caller
import com.dotforge.filter.NamedList
import com.dotforge.filter.PhoneState
import com.dotforge.filter.Subject
import com.dotforge.system.BedtimeSchedule
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

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

/** "During Quiet": don't light, or light at 20%. */
@Serializable
enum class QuietMode(val label: String) { OFF("Don't light"), DIM("Dim to 20%") }

/** "Light alerts": always, or only while the screen is off. (Never "only face down": the firmware hides app lights then.) */
@Serializable
enum class LightWhen(val label: String) { ALWAYS("Always"), SCREEN_OFF("Only when the screen is off") }

/**
 * Alerts > Alert settings: Quiet ([quiet] schedule, [respectDnd], bedtime, [whileUsing], [quietMode], who is let
 * through), when alerts light, what counts as checking, how long waiting alerts wait, and the Who wins order.
 */
@Serializable
data class FilterSettings(
    val quiet: QuietHours = QuietHours(),
    val respectDnd: Boolean = true,
    val onlyLocked: Boolean = false,
    val onlyFaceDown: Boolean = false,
    /** "While I'm using the phone": alerts made while the screen is on and unlocked show briefly once it locks. */
    val whileUsing: Boolean = true,
    val quietMode: QuietMode = QuietMode.OFF,
    /** Let through during Quiet: starred contacts. */
    val letFavourites: Boolean = true,
    /** Let through during Quiet: the same number twice within 3 min. */
    val letRepeat: Boolean = true,
    val lightWhen: LightWhen = LightWhen.ALWAYS,
    /** "Unlocking counts as checking" (off). */
    val unlockChecks: Boolean = false,
    /** "Stop waiting after": 1 or 4 hours, 0 = never. */
    val waitCapHours: Int = 4,
    /** Who wins: rule ids, people then apps, first wins. */
    val order: List<String> = emptyList(),
    /** Quiet switched on by hand (Home tile) until this time, epoch ms; 0 = not. */
    val quietUntil: Long = 0,
    /** Home's Alerts switch is off: no app or person alert lights (calls and spam blocking carry on). */
    val alertsPaused: Boolean = false,
) {
    /** The cap in ms for the alert queue, null = never. */
    val capMs: Long? get() = waitCapHours.takeIf { it > 0 }?.let { it * 3_600_000L }
}

/** What the phone is doing when a notification arrives. Null fields are unknown. */
data class Conditions(
    val now: LocalDateTime, val dnd: Boolean, val locked: Boolean, val faceDown: Boolean?, val charging: Boolean? = null,
    /** The screen is on. */
    val screenOn: Boolean? = null,
    /** Nothing's Glyph bedtime is on now. */
    val bedtime: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

/** What happened to one notification. */
sealed interface Verdict {
    /** A spam-filter rule silenced, hid or marked it as spam. */
    data class Blocked(val match: RuleMatch) : Verdict
    data object NoRule : Verdict
    /** A rule matched but a filter held it back. */
    data class Quiet(val match: RuleMatch, val reason: String) : Verdict
    /** [dim]: Quiet is on with "Dim to 20%" (or the sender was let through dimmed). */
    data class Show(val match: RuleMatch, val dim: Boolean = false) : Verdict
}

/** Why an alert waits or is skipped, as History says it ("Skipped · Quiet"). */
object QuietReason {
    const val QUIET = "Quiet"
    const val DND = "Do Not Disturb"
    const val BEDTIME = "Nothing's bedtime"
    const val IN_USE = "phone in use"
    const val SCREEN_ON = "screen on"
    const val FACE_DOWN = "phone face down"
    const val PAUSED = "Alerts off"
}

/** One line of the rule tester. [cooldown]: a rule matched but the conversation alerted moments ago. */
data class LogEntry(val info: NotificationInfo, val verdict: Verdict, val cooldown: Boolean = false)

object Delivery {
    /** [info] as the filter engine sees it. Contacts, the spam list and the phone's state are read lazily. */
    fun subject(
        info: NotificationInfo,
        filters: FilterSettings,
        conditions: () -> Conditions,
        sender: () -> Sender?,
        region: String? = null,
        spamList: (String) -> Boolean = { false },
    ): Subject {
        val c = lazy(LazyThreadSafetyMode.NONE, conditions)
        return Subject(
            isCall = false,
            app = info.packageName,
            appLabel = info.appLabel,
            channel = info.channel,
            category = info.category,
            sender = info.sender.ifBlank { info.title.takeIf { info.fromPerson }.orEmpty() },
            email = info.email,
            number = info.number,
            title = info.title,
            text = info.text,
            bigText = info.bigText,
            subText = info.subText,
            summary = info.summary,
            fromPerson = info.fromPerson,
            groupChat = info.groupChat,
            ongoing = info.ongoing,
            at = c.value.now,
            region = region,
            contact = { sender()?.let { Caller(it.status != ContactStatus.UNKNOWN, it.status == ContactStatus.STARRED, it.name) } },
            spamList = spamList,
            state = { PhoneState(c.value.locked, c.value.dnd, c.value.charging, filters.quiet.isActive(c.value.now)) },
        )
    }

    /**
     * Spam filter first (the allow and blocking rules), then the best rule that shows, then quiet hours, Do Not
     * Disturb, locked and face down. Contacts are read at most once, and only when a rule needs them.
     */
    fun decide(
        info: NotificationInfo,
        rules: List<Rule>,
        lists: List<NamedList>,
        filters: FilterSettings,
        conditions: () -> Conditions,
        sender: () -> Sender?,
        region: String? = null,
        spamList: (String) -> Boolean = { false },
    ): Verdict {
        val cond = lazy(LazyThreadSafetyMode.NONE, conditions)
        val s = subject(info, filters, { cond.value }, sender, region, spamList)
        val d = RuleEngine.decide(s, rules, lists)
        if (d.blocked) return Verdict.Blocked(d.gate!!)
        val match = d.show ?: return Verdict.NoRule
        val c = cond.value
        return gate(match, filters, c, starred = { sender()?.status == ContactStatus.STARRED }, repeated = false)
    }

    /**
     * Quiet and the other filters for an alert a rule wants to show (row 7, row 54): Quiet (schedule, by hand,
     * Do Not Disturb, Nothing's bedtime) lets favourites and repeat callers through, otherwise skips the alert
     * or dims it to 20%; "Only when the screen is off"; "While I'm using the phone"; face down (the firmware
     * hides app lights). [starred] is read only during Quiet.
     */
    fun gate(match: RuleMatch, filters: FilterSettings, c: Conditions, starred: () -> Boolean, repeated: Boolean): Verdict {
        if (filters.alertsPaused) return Verdict.Quiet(match, QuietReason.PAUSED)
        val quiet = quietReason(filters, c)
        if (quiet != null) {
            val through = (filters.letFavourites && starred()) || (filters.letRepeat && repeated)
            if (!through) return if (filters.quietMode == QuietMode.DIM) Verdict.Show(match, dim = true) else Verdict.Quiet(match, quiet)
        }
        val screenOn = c.screenOn ?: !c.locked
        return when {
            filters.lightWhen == LightWhen.SCREEN_OFF && screenOn -> Verdict.Quiet(match, QuietReason.SCREEN_ON)
            filters.whileUsing && screenOn && !c.locked -> Verdict.Quiet(match, QuietReason.IN_USE)
            filters.onlyLocked && !c.locked -> Verdict.Quiet(match, QuietReason.IN_USE)
            c.faceDown == true -> Verdict.Quiet(match, QuietReason.FACE_DOWN)
            else -> Verdict.Show(match)
        }
    }

    /** Why Quiet is on now, or null. */
    fun quietReason(filters: FilterSettings, c: Conditions): String? = when {
        filters.quietUntil > c.nowMs -> QuietReason.QUIET
        filters.quiet.isActive(c.now) -> QuietReason.QUIET
        filters.respectDnd && c.dnd -> QuietReason.DND
        filters.quiet.followBedtime && c.bedtime -> QuietReason.BEDTIME
        else -> null
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
 * one JSON file. The spam.json of version 1 is moved in on first load. Safe to call from any thread.
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

    fun updateSpam(transform: (SpamSettings) -> SpamSettings) = synchronized(this) { save(state.copy(spam = transform(state.spam))) }

    fun updateFilters(transform: (FilterSettings) -> FilterSettings) = synchronized(this) { save(state.copy(filters = transform(state.filters))) }

    /** Adds a call or notification to the Spam log (newest first). */
    fun record(c: SpamCatch) = synchronized(this) {
        save(state.copy(catches = (listOf(c) + state.catches.filterNot { it.key == c.key }).take(MAX_CATCHES)))
    }

    /** Drops the log entries of one sender (after "Allow this sender"). */
    fun forget(senderKey: String) = synchronized(this) {
        save(state.copy(catches = state.catches.filterNot { it.senderKey.equals(senderKey, ignoreCase = true) }))
    }

    fun clearCatches() = synchronized(this) { save(state.copy(catches = emptyList())) }

    private companion object {
        const val MAX_CATCHES = 50
    }
}

/** Quiet's status for the Home tile and Alert settings (row 7): "Off", "On until 07:00", "Scheduled 23:00". */
object QuietText {
    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60 % 24, m % 60)

    fun status(f: FilterSettings, c: Conditions): String {
        val nowMin = c.now.hour * 60 + c.now.minute
        return when {
            f.quietUntil > c.nowMs -> {
                val left = ((f.quietUntil - c.nowMs) / 60_000L).toInt()
                "On until " + hhmm(nowMin + left)
            }
            f.quiet.isActive(c.now) -> "On until " + hhmm(f.quiet.end)
            f.quiet.enabled -> "Scheduled " + hhmm(f.quiet.start)
            else -> "Off"
        }
    }

    /** Turning Quiet on by hand: until the schedule's next end when one is set, else for an hour. */
    fun untilMs(f: FilterSettings, c: Conditions): Long {
        if (!f.quiet.enabled) return c.nowMs + 3_600_000L
        val nowMin = c.now.hour * 60 + c.now.minute
        var wait = f.quiet.end - nowMin
        if (wait <= 0) wait += 24 * 60
        return c.nowMs + wait * 60_000L
    }
}
