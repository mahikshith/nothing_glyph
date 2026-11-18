package com.dotforge.system

import android.content.ContentResolver
import android.provider.Settings
import com.dotforge.engine.AmbientGate
import java.time.LocalDateTime

data class SettingRow(val key: String, val global: String, val secure: String, val system: String)

/**
 * Nothing's Glyph settings. They live in Settings.Global; whether a third-party app may read them depends on
 * the firmware, so every read is optional and an unreadable value never blocks anything.
 */
object NothingSettings {
    /** Glyph Interface on/off. The firmware also sets it to 0 while bedtime is active. */
    const val LED_EFFECT_ENABLE = "led_effect_enable"
    /** Bedtime schedule switch (Nothing's own spelling). */
    const val BEDTIME_ENABLE = "led_effect_schedule_time_ebable"
    const val BEDTIME_START = "led_bed_time_custom_start_time"
    const val BEDTIME_END = "led_bed_time_custom_end_time"
    const val BEDTIME_WEEK = "led_bed_time_custom_week"

    /** Keys shown on the Diagnostics screen. */
    val KEYS = listOf(
        LED_EFFECT_ENABLE,
        "led_effect_glyph_toys_enable",
        BEDTIME_ENABLE,
        BEDTIME_START,
        BEDTIME_END,
        BEDTIME_WEEK,
        "led_notification_mode",
        "led_default_ringtone_sound_mode",
        "glyph_pocket_enable",
        "glyph_toy_timeout",
    )

    private const val NOT_READABLE = "not readable"

    private fun read(block: () -> String?): String? = try {
        block()
    } catch (_: RuntimeException) { // SecurityException for keys hidden from third-party apps
        null
    }

    private fun global(cr: ContentResolver, key: String): String? = read { Settings.Global.getString(cr, key) }

    fun readAll(cr: ContentResolver): List<SettingRow> = KEYS.map { k ->
        SettingRow(
            k,
            global(cr, k) ?: NOT_READABLE,
            read { Settings.Secure.getString(cr, k) } ?: NOT_READABLE,
            read { Settings.System.getString(cr, k) } ?: NOT_READABLE,
        )
    }

    /** true/false when readable, null otherwise. */
    fun glyphLightsEnabled(cr: ContentResolver): Boolean? = when (global(cr, LED_EFFECT_ENABLE)) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** The bedtime schedule when it is switched on and readable, null otherwise. */
    fun bedtime(cr: ContentResolver): BedtimeSchedule? {
        if (global(cr, BEDTIME_ENABLE) != "1") return null
        return BedtimeSchedule.parse(global(cr, BEDTIME_START), global(cr, BEDTIME_END), global(cr, BEDTIME_WEEK))
    }
}

/** Ambient lights are allowed unless Nothing's Glyph switch is readably off or a readable bedtime is active. */
class NothingSettingsGate(private val cr: ContentResolver) : AmbientGate {
    override fun ambientAllowed(): Boolean =
        NothingSettings.glyphLightsEnabled(cr) != false &&
            NothingSettings.bedtime(cr)?.isActive(LocalDateTime.now()) != true
}
