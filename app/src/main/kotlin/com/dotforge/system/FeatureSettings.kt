package com.dotforge.system

import android.content.SharedPreferences
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** A Features page's On the Glyph timing, and its default. */
enum class FeatureTiming(val key: String, val default: Timing) {
    /** Brightness and how fast it fades on; how long is [FeatureSettings.torchMinutes]. */
    TORCH("torch", Timing(showFor = ShowFor.UNTIL_CHECKED, rest = false)),
    /** The charging design: 5 s when plugged in, once. */
    CHARGING("charging", Timing(showFor = ShowFor.SECONDS, seconds = 5)),
    /** The volume design: 2 s after each change. */
    VOLUME("volume", Timing(showFor = ShowFor.SECONDS, seconds = 2)),
    /** The visualiser: speed is how quickly the lights fall back. */
    MUSIC("music", Timing()),
    /** DotForge's toys: their brightness and animation speed. */
    TOYS("toys", Timing()),
    /** A design another app asks for, when it doesn't say how long: 10 s. */
    APPS("apps", Timing(showFor = ShowFor.SECONDS, seconds = 10, repeat = Repeat.FOREVER)),
}

/** How the charging design looks. */
enum class ChargingStyle(val label: String, val hint: String) {
    FILL("Fill", "The ring fills up to the level"),
    LEVEL("Level %", "The ring and the number"),
}

/**
 * How strongly the visualiser reacts: how far below the loudest recent sound a band still lights. High reaches
 * further down (quiet parts light up); Low lights only the loud parts.
 */
enum class Sensitivity(val label: String, val rangeDb: Float) { LOW("Low", 24f), NORMAL("Normal", 36f), HIGH("High", 48f) }

/**
 * The Features pages' settings that aren't a single switch elsewhere: each page's On the Glyph timing, the torch's
 * auto-off, the charging style, the visualiser's mode and sensitivity, and the Integrations key and switches.
 */
class FeatureSettings(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }

    private val timings = FeatureTiming.entries.associateWith { f ->
        MutableStateFlow(
            prefs.getString("timing_" + f.key, null)?.let { runCatching { json.decodeFromString(Timing.serializer(), it) }.getOrNull() } ?: f.default,
        )
    }

    fun timing(f: FeatureTiming): StateFlow<Timing> = timings.getValue(f).asStateFlow()

    fun setTiming(f: FeatureTiming, t: Timing) {
        val c = t.clamped()
        timings.getValue(f).value = c
        prefs.edit().putString("timing_" + f.key, json.encodeToString(Timing.serializer(), c)).apply()
    }

    private fun int(key: String, default: Int) = MutableStateFlow(prefs.getInt(key, default))
    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _torchMinutes = int(TORCH_MINUTES, 5)
    /** The torch turns itself off after this many minutes; 0 = never. */
    val torchMinutes: StateFlow<Int> = _torchMinutes.asStateFlow()
    fun setTorchMinutes(m: Int) = put(_torchMinutes, TORCH_MINUTES, m)

    private val _chargingStyle = MutableStateFlow(ChargingStyle.entries.firstOrNull { it.name == prefs.getString(CHARGING_STYLE, null) } ?: ChargingStyle.LEVEL)
    val chargingStyle: StateFlow<ChargingStyle> = _chargingStyle.asStateFlow()
    fun setChargingStyle(s: ChargingStyle) {
        _chargingStyle.value = s
        prefs.edit().putString(CHARGING_STYLE, s.name).apply()
    }

    private val _fastPulse = bool(FAST_PULSE, true)
    /** A fast charger makes the ring pulse. */
    val fastPulse: StateFlow<Boolean> = _fastPulse.asStateFlow()
    fun setFastPulse(v: Boolean) = put(_fastPulse, FAST_PULSE, v)

    private val _mediaOnly = bool(MEDIA_ONLY, true)
    /** The visualiser lights only while music plays (dark between songs and when paused). */
    val mediaOnly: StateFlow<Boolean> = _mediaOnly.asStateFlow()
    fun setMediaOnly(v: Boolean) = put(_mediaOnly, MEDIA_ONLY, v)

    private val _sensitivity = MutableStateFlow(Sensitivity.entries.firstOrNull { it.name == prefs.getString(SENSITIVITY, null) } ?: Sensitivity.NORMAL)
    val sensitivity: StateFlow<Sensitivity> = _sensitivity.asStateFlow()
    fun setSensitivity(s: Sensitivity) {
        _sensitivity.value = s
        prefs.edit().putString(SENSITIVITY, s.name).apply()
    }

    private val _showDesigns = bool(SHOW_DESIGNS, true)
    /** Other apps (Tasker, MacroDroid, an app the user allowed) may show designs. */
    val showDesigns: StateFlow<Boolean> = _showDesigns.asStateFlow()
    fun setShowDesigns(v: Boolean) = put(_showDesigns, SHOW_DESIGNS, v)

    private val _tellOthers = bool(TELL_OTHERS, false)
    /** A broadcast tells other apps (holding DotForge's permission) when something lights the Glyph. */
    val tellOthers: StateFlow<Boolean> = _tellOthers.asStateFlow()
    fun setTellOthers(v: Boolean) = put(_tellOthers, TELL_OTHERS, v)

    private val _key = MutableStateFlow(prefs.getString(KEY, null) ?: newKey().also { prefs.edit().putString(KEY, it).apply() })
    /** The secret an app sends as the "key" extra so DotForge obeys it without being asked (Tasker's Send intent). */
    val key: StateFlow<String> = _key.asStateFlow()
    fun renewKey() {
        _key.value = newKey()
        prefs.edit().putString(KEY, _key.value).apply()
    }

    private fun put(f: MutableStateFlow<Int>, key: String, v: Int) {
        f.value = v
        prefs.edit().putInt(key, v).apply()
    }

    private fun put(f: MutableStateFlow<Boolean>, key: String, v: Boolean) {
        f.value = v
        prefs.edit().putBoolean(key, v).apply()
    }

    /** Tests and screenshots: every default back. */
    @androidx.annotation.VisibleForTesting
    fun reset() {
        val key = _key.value
        prefs.edit().clear().putString(KEY, key).apply()
        FeatureTiming.entries.forEach { timings.getValue(it).value = it.default }
        _torchMinutes.value = 5
        _chargingStyle.value = ChargingStyle.LEVEL
        _fastPulse.value = true
        _mediaOnly.value = true
        _sensitivity.value = Sensitivity.NORMAL
        _showDesigns.value = true
        _tellOthers.value = false
    }

    companion object {
        /** Auto-off chips, in minutes; 0 = never. */
        val TORCH_CHIPS = listOf(1, 5, 10, 30, 0)
        private const val TORCH_MINUTES = "torch_minutes"
        private const val CHARGING_STYLE = "charging_style"
        private const val FAST_PULSE = "fast_pulse"
        private const val MEDIA_ONLY = "media_only"
        private const val SENSITIVITY = "sensitivity"
        private const val SHOW_DESIGNS = "show_designs"
        private const val TELL_OTHERS = "tell_others"
        private const val KEY = "key"

        /** 16 random bytes as 32 hex letters. */
        fun newKey(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
