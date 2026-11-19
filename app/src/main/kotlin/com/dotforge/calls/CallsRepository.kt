package com.dotforge.calls

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Who a caller glyph is for. */
@Serializable
enum class GlyphTarget { CONTACT, GROUP }

/**
 * "When [Mum] calls, ring with [composition]": the contact's (or every group member's) ringtone is set to a
 * DotForge composition, so the firmware plays its lights.
 */
@Serializable
data class CallerGlyph(
    val id: String = UUID.randomUUID().toString(),
    val target: GlyphTarget = GlyphTarget.CONTACT,
    /** The contact's content URI, or the group title. */
    val key: String = "",
    val name: String = "",
    val ringtoneUri: String = "",
    val ringtoneName: String = "",
    /** Contacts changed the last time it was applied. */
    val applied: Int = 0,
)

@Serializable
data class CallSettings(
    /** Two calls from one number within this many minutes make a repeated caller. */
    val repeatMinutes: Int = 5,
    /** A short glyph on the matrix when a call is silenced or rejected (spam uses the spam alert). */
    val screenedGlyph: Boolean = true,
    /** A missed call glyph that waits until you check it and lights again every few minutes ([CallActions.missedRule]). */
    val missedGlyph: Boolean = false,
    /** The missed call's glyph: null for the built-in one, else a style and its [missedDesign] (a library glyph or design). */
    val missedStyle: com.dotforge.rules.GlyphStyle? = null,
    val missedDesign: String? = null,
    /** The missed call's timing from the shared timing controls; null is [CallActions.MISSED_TIMING]. */
    val missedTiming: com.dotforge.device.TimingOverride? = null,
    /** The glyph for a silenced or declined call: null for the built-in Silenced and Declined glyphs. */
    val screenedStyle: com.dotforge.rules.GlyphStyle? = null,
    val screenedDesign: String? = null,
)

/** A call seen recently, for repeated callers. */
@Serializable
data class RecentCall(val number: String, val atMs: Long)

@Serializable
private data class CallsState(
    val version: Int = 1,
    val settings: CallSettings = CallSettings(),
    val recent: List<RecentCall> = emptyList(),
    val glyphs: List<CallerGlyph> = emptyList(),
)

/**
 * Call settings, recent calls (for repeated callers) and caller glyphs, in one JSON file. Call rules live with
 * every other rule in [com.dotforge.rules.RulesRepository]; blocked calls go to the Spam log. Safe to call
 * from any thread.
 */
class CallsRepository(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val lock = Any()
    private var state = load()

    private val _settings = MutableStateFlow(state.settings)
    val settings: StateFlow<CallSettings> = _settings.asStateFlow()
    private val _glyphs = MutableStateFlow(state.glyphs)
    val glyphs: StateFlow<List<CallerGlyph>> = _glyphs.asStateFlow()

    private fun load(): CallsState =
        runCatching { json.decodeFromString(CallsState.serializer(), file.readText()) }.getOrElse { CallsState() }

    private fun update(transform: (CallsState) -> CallsState) = synchronized(lock) {
        val next = transform(state)
        state = next
        _settings.value = next.settings
        _glyphs.value = next.glyphs
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(CallsState.serializer(), next))
        }
    }

    val recent: List<RecentCall> get() = synchronized(lock) { state.recent }

    /** Calls DotForge silenced or declined lately, so their missed call glyph is skipped. */
    val screened = ScreenedCalls()

    fun updateSettings(transform: (CallSettings) -> CallSettings) = update { it.copy(settings = transform(it.settings)) }

    /** Remembers an incoming call for repeated-caller checks (the last hour is kept). */
    fun noteCall(number: String?, atMs: Long = now()) {
        if (number.isNullOrBlank()) return
        update { s -> s.copy(recent = (s.recent.filter { atMs - it.atMs < RECENT_MS } + RecentCall(number, atMs)).takeLast(MAX_RECENT)) }
    }

    fun upsertGlyph(g: CallerGlyph) = update { s ->
        val i = s.glyphs.indexOfFirst { it.id == g.id }
        s.copy(glyphs = if (i >= 0) s.glyphs.toMutableList().also { it[i] = g } else s.glyphs + g)
    }

    fun deleteGlyph(id: String) = update { s -> s.copy(glyphs = s.glyphs.filterNot { it.id == id }) }

    companion object {
        const val MAX_RECENT = 100
        const val RECENT_MS = 60 * 60 * 1000L
    }
}

/** True when the same number called within [windowMs] before [atMs] (hidden numbers never repeat). */
fun isRepeatedCall(number: String?, atMs: Long, recent: List<RecentCall>, windowMs: Long): Boolean {
    val n = number?.takeIf { it.isNotBlank() } ?: return false
    return recent.any { it.number == n && it.atMs < atMs && atMs - it.atMs <= windowMs }
}
