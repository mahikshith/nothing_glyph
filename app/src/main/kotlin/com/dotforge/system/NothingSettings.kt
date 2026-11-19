package com.dotforge.system

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.dotforge.engine.AmbientGate
import java.time.LocalDateTime

data class SettingRow(val key: String, val global: String, val secure: String, val system: String)

/** A settings page to open, and the button text for it. */
data class SettingsLink(val intent: Intent, val label: String)

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

    private const val SETTINGS_PACKAGE = "com.android.settings"

    /**
     * Nothing's per-app Glyph notification settings (where an app can be set to a sound without lights), the
     * Glyph Interface page if that is missing, else system Settings. Component and action names come from
     * Phone (3) firmware; the first one that resolves wins.
     */
    fun glyphNotificationSettings(pm: PackageManager): SettingsLink {
        val notifications = "Open Glyph notifications"
        val candidates = listOf(
            SettingsLink(
                Intent().setComponent(ComponentName(SETTINGS_PACKAGE, "com.nothing.settings.NtSettings\$GlyphNotificationRingtonesActivity")),
                notifications,
            ),
            SettingsLink(Intent("com.nothing.settings.ACTION_GLYPH_NOTIFICATION_RINGTONES_LIST").setPackage(SETTINGS_PACKAGE), notifications),
            SettingsLink(Intent("android.settings.GLYPHS_ALL_APPS_NOTIFICATION_SETTINGS").setPackage(SETTINGS_PACKAGE), notifications),
            SettingsLink(Intent("android.settings.ACTION_GLYPHS_SETTINGS").setPackage(SETTINGS_PACKAGE), "Open Glyph Interface"),
        )
        return candidates.firstOrNull { pm.resolveActivity(it.intent, 0) != null }
            ?: SettingsLink(Intent(Settings.ACTION_SETTINGS), "Open settings")
    }

    /** Nothing's Glyph Interface page (where the phone's own Glyph brightness is), else system Settings. */
    fun glyphSettings(pm: PackageManager): SettingsLink =
        SettingsLink(Intent("android.settings.ACTION_GLYPHS_SETTINGS").setPackage(SETTINGS_PACKAGE), "Open Glyph Interface")
            .takeIf { pm.resolveActivity(it.intent, 0) != null }
            ?: SettingsLink(Intent(Settings.ACTION_SETTINGS), "Open settings")

    /**
     * Whether Nothing's own lights for notifications ([NOTIFICATION_MODE]) or the ringtone ([RINGTONE_MODE]) are
     * set to "Sound only", so they don't cover DotForge's frames. Null when the firmware doesn't let us read it.
     * Assumption, to check on the phone: the value is "1" for Sound only.
     * First run treats a true here only as a bonus "Done"; progress never waits on it.
     */
    // TODO: check on a Phone (3) that these keys exist and that "1" means Sound only.
    fun soundOnly(cr: ContentResolver, key: String): Boolean? = global(cr, key)?.takeIf { it != NOT_READABLE }?.let { it.trim() == "1" }

    const val NOTIFICATION_MODE = "led_notification_mode"
    const val RINGTONE_MODE = "led_default_ringtone_sound_mode"

    /** True when the firmware lets us read the bedtime switch, so "Follow Nothing bedtime" can work. */
    fun bedtimeReadable(cr: ContentResolver): Boolean = global(cr, BEDTIME_ENABLE) != null

    /** The bedtime schedule when it is switched on and readable, null otherwise. */
    fun bedtime(cr: ContentResolver): BedtimeSchedule? {
        if (global(cr, BEDTIME_ENABLE) != "1") return null
        return BedtimeSchedule.parse(global(cr, BEDTIME_START), global(cr, BEDTIME_END), global(cr, BEDTIME_WEEK))
    }
}

/**
 * Ambient lights are allowed unless Nothing's Glyph switch is readably off, or a readable bedtime is active
 * while [followBedtime] (Quiet hours > Follow Nothing bedtime) is on.
 */
class NothingSettingsGate(private val cr: ContentResolver, private val followBedtime: () -> Boolean) : AmbientGate {
    override fun ambientAllowed(): Boolean =
        NothingSettings.glyphLightsEnabled(cr) != false &&
            !(followBedtime() && NothingSettings.bedtime(cr)?.isActive(LocalDateTime.now()) == true)
}
