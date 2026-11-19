package com.dotforge.rules

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What happened to an alert: it lit, a spam rule blocked it, or something held it back. */
@Serializable
enum class Outcome(val label: String) { LIT("Lit"), BLOCKED("Blocked"), SKIPPED("Skipped") }

/**
 * One line of Alerts > History (row 52): a call or notification and what happened to it, at [at] (when it
 * arrived). [detail] finishes the outcome line: "Chat glyph · 30 s", "starts with 140", "Quiet", "phone face
 * down". [channel] and [locked] let "Would have lit" count a rule against it.
 */
@Serializable
data class HistoryEntry(
    val key: String,
    val at: Long,
    val outcome: Outcome,
    val detail: String = "",
    val appLabel: String = "",
    val packageName: String = "",
    val title: String = "",
    val text: String = "",
    /** The sender's name, or empty. */
    val sender: String = "",
    /** The sender's normalised number, or empty. */
    val number: String = "",
    val call: Boolean = false,
    /** The rule that decided, by id and name. */
    val ruleId: String? = null,
    val rule: String? = null,
    val action: Action? = null,
    /** The notification's type (its channel), for counts; empty before it was stored. */
    val channel: String = "",
    /** Whether the phone was locked, for counts of "Only when locked"; null when not stored. */
    val locked: Boolean? = null,
) {
    /** Who it was from: the sender, the number, the title, or the app. */
    val who: String get() = sender.ifBlank { title.ifBlank { number.ifBlank { appLabel } } }

    /** What "Not spam" adds to Always let through: the number, else the sender's name. */
    val senderKey: String get() = number.ifBlank { sender.ifBlank { title } }.trim()

    /** "Lit · Chat glyph · 30 s", "Blocked · starts with 140", "Skipped · Quiet". */
    val line: String get() = if (detail.isBlank()) outcome.label else "${outcome.label} · ${detail.replace("until checked", "until you check")}"
}

/** History filter chips. */
enum class HistoryFilter(val label: String, val outcome: Outcome?) {
    ALL("All", null), LIT("Lit", Outcome.LIT), BLOCKED("Blocked", Outcome.BLOCKED), SKIPPED("Skipped", Outcome.SKIPPED);

    companion object {
        fun of(name: String?): HistoryFilter = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALL
    }
}

/** Someone who wrote recently: a sender, or a chat or group by its name ([chat]). */
data class RecentSender(val name: String, val chat: Boolean, val number: String = "")

/** A spam rule's count for its row: "Blocked 12 · last today 09:14". */
data class RuleCount(val blocked: Int, val lastAt: Long?)

object HistoryText {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT)

    /** "09:14": the time alone, under a day's header. */
    fun timeText(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = time.format(Instant.ofEpochMilli(at).atZone(zone))

    /** "09:14", "Yesterday 21:02", "12 Nov 08:30". */
    fun whenText(at: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val d = t.toLocalDate()
        return when (d) {
            today -> time.format(t)
            today.minusDays(1) -> "Yesterday ${time.format(t)}"
            else -> "${day.format(t)} ${time.format(t)}"
        }
    }

    /** "last today 09:14" style, for rule rows. */
    fun lastText(at: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return when (t.toLocalDate()) {
            today -> "last today ${time.format(t)}"
            today.minusDays(1) -> "last yesterday ${time.format(t)}"
            else -> "last ${day.format(t)}"
        }
    }

    /** "Blocked 12 · last today 09:14", or "Nothing blocked yet". */
    fun count(c: RuleCount, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        if (c.blocked == 0 || c.lastAt == null) "Nothing blocked yet" else "Blocked ${c.blocked} · ${lastText(c.lastAt, today, zone)}"

    /** Section titles for History: "Today", "Yesterday", "12 Nov". */
    fun dayTitle(at: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(d)
        }
    }

    /** The detail for a blocking rule: its simple pattern ("starts with 140"), else its name. */
    fun blockedDetail(rule: Rule): String =
        SimpleRules.pattern(rule)?.label?.replaceFirstChar { it.lowercase() } ?: rule.name.replaceFirstChar { it.lowercase() }

    /** The detail for an alert that lit: "Chat glyph · 30 s", "Chat glyph · until you check". */
    fun litDetail(glyphName: String, rule: Rule): String {
        val secs = SimpleRules.showSeconds(rule)
        return "$glyphName · " + if (secs == null) "until you check" else "$secs s"
    }
}

/**
 * Alerts > History: every alert that lit, was blocked or was skipped, newest first, kept [KEEP_DAYS] days and
 * at most [MAX] entries, in one JSON file. The old Spam log ([legacy]) is moved in on first load. Safe to call
 * from any thread.
 */
class HistoryRepository(private val file: File, legacy: List<SpamCatch> = emptyList(), private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val serializer = ListSerializer(HistoryEntry.serializer())

    private val _entries = MutableStateFlow(load(legacy))
    /** Newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private fun load(legacy: List<SpamCatch>): List<HistoryEntry> {
        if (file.exists()) return prune(runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()))
        val moved = prune(legacy.map(::fromCatch).sortedByDescending { it.at })
        if (moved.isNotEmpty()) write(moved)
        return moved
    }

    private fun prune(list: List<HistoryEntry>): List<HistoryEntry> {
        val cutoff = now() - KEEP_DAYS * DAY_MS
        return list.filter { it.at >= cutoff }.take(MAX)
    }

    private fun write(list: List<HistoryEntry>) {
        runCatching { file.writeText(json.encodeToString(serializer, list)) }
    }

    private fun save(list: List<HistoryEntry>) {
        val kept = prune(list)
        _entries.value = kept
        write(kept)
    }

    fun add(e: HistoryEntry) = synchronized(this) {
        save((listOf(e) + _entries.value.filterNot { it.key == e.key }).sortedByDescending { it.at })
    }

    /** Clears History and returns what was there, for Undo. */
    fun clear(): List<HistoryEntry> = synchronized(this) {
        val was = _entries.value
        save(emptyList())
        was
    }

    /** Puts back [list] (Undo of Clear history), merged with anything that arrived since. */
    fun restore(list: List<HistoryEntry>) = synchronized(this) {
        save((_entries.value + list).distinctBy { it.key }.sortedByDescending { it.at })
    }

    fun remove(key: String) = synchronized(this) { save(_entries.value.filterNot { it.key == key }) }

    /** Blocked entries per rule (by id, else by name), for "Blocked 12 · last today 09:14". */
    fun count(rule: Rule): RuleCount = counts(_entries.value, rule)

    companion object {
        const val KEEP_DAYS = 30L
        const val MAX = 500
        private const val DAY_MS = 86_400_000L

        fun counts(entries: List<HistoryEntry>, rule: Rule): RuleCount {
            val mine = entries.filter { it.outcome == Outcome.BLOCKED && (it.ruleId == rule.id || (it.ruleId == null && it.rule == rule.name)) }
            return RuleCount(mine.size, mine.maxOfOrNull { it.at })
        }

        /** An old Spam log line as a Blocked entry. */
        fun fromCatch(c: SpamCatch) = HistoryEntry(
            key = c.key, at = c.at, outcome = Outcome.BLOCKED, detail = c.why.ifBlank { c.rule.orEmpty() }.replaceFirstChar { it.lowercase() },
            appLabel = c.appLabel, packageName = c.packageName, title = c.title, text = c.text, sender = c.sender, number = c.number,
            call = c.call, rule = c.rule, action = c.action,
        )

        /**
         * Who wrote recently in [pkg] (any app when null), newest first: each sender, and each chat or group
         * whose name isn't the sender's ([RecentSender.chat]). Calls are left out.
         */
        fun recentSenders(entries: List<HistoryEntry>, pkg: String?, limit: Int = 12): List<RecentSender> {
            val out = LinkedHashMap<String, RecentSender>()
            for (e in entries) {
                if (e.call || (pkg != null && e.packageName != pkg)) continue
                val sender = e.sender.trim()
                if (sender.isNotEmpty()) out.putIfAbsent(sender.lowercase(), RecentSender(sender, chat = false, number = e.number))
                val title = e.title.trim()
                if (sender.isNotEmpty() && title.isNotEmpty() && !title.equals(sender, ignoreCase = true) && !title.startsWith(sender, ignoreCase = true)) {
                    out.putIfAbsent(title.lowercase(), RecentSender(title, chat = true))
                }
                if (out.size >= limit) break
            }
            return out.values.take(limit)
        }

        /** How many calls and messages were blocked in the week before [nowMs] (Home's spam line). */
        fun blockedThisWeek(entries: List<HistoryEntry>, nowMs: Long): Int =
            entries.count { it.outcome == Outcome.BLOCKED && nowMs - it.at <= 7 * DAY_MS }
    }
}
