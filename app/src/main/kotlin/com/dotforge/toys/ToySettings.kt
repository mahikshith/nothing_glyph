package com.dotforge.toys

import android.content.SharedPreferences
import com.dotforge.composition.VisualStyle
import com.dotforge.device.ClockStyle

/** The few options the toys have, set from each toy's settings page. */
class ToySettings(private val prefs: SharedPreferences) {

    var clock24h: Boolean
        get() = prefs.getBoolean(CLOCK_24H, true)
        set(v) = prefs.edit().putBoolean(CLOCK_24H, v).apply()

    var batteryChargeAnimation: Boolean
        get() = prefs.getBoolean(BATTERY_CHARGE, true)
        set(v) = prefs.edit().putBoolean(BATTERY_CHARGE, v).apply()

    var diceShake: Boolean
        get() = prefs.getBoolean(DICE_SHAKE, true)
        set(v) = prefs.edit().putBoolean(DICE_SHAKE, v).apply()

    /** The design DF Player loops; null = the newest design. */
    var playerDesignId: String?
        get() = prefs.getString(PLAYER_DESIGN, null)
        set(v) = prefs.edit().putString(PLAYER_DESIGN, v).apply()

    /** What the Live visualiser and the DF Music toy draw. */
    var visualStyle: VisualStyle
        get() = VisualStyle.entries.firstOrNull { it.name == prefs.getString(VISUAL_STYLE, null) } ?: VisualStyle.BARS
        set(v) = prefs.edit().putString(VISUAL_STYLE, v.name).apply()

    var clockStyle: ClockStyle
        get() = ClockStyle.entries.firstOrNull { it.name == prefs.getString(CLOCK_STYLE, null) } ?: ClockStyle.DIGITAL
        set(v) = prefs.edit().putString(CLOCK_STYLE, v.name).apply()

    /** Designs DF Gallery cycles through; empty = all of them. */
    var galleryIds: Set<String>
        get() = prefs.getStringSet(GALLERY, emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet(GALLERY, v).apply()

    /** What DF Always-on shows. */
    var hubFace: HubFace
        get() = HubFace.entries.firstOrNull { it.name == prefs.getString(HUB_FACE, null) } ?: HubFace.CLOCK
        set(v) = prefs.edit().putString(HUB_FACE, v.name).apply()

    var hubBattery: Boolean
        get() = prefs.getBoolean(HUB_BATTERY, true)
        set(v) = prefs.edit().putBoolean(HUB_BATTERY, v).apply()

    /** When DF Always-on last got an always-on tick, epoch ms (Home's always-on line). */
    var aodSeenAt: Long
        get() = prefs.getLong(AOD_SEEN, 0L)
        set(v) = prefs.edit().putLong(AOD_SEEN, v).apply()

    /** DF Timer's countdown in minutes; 0 = stopwatch. */
    var timerMinutes: Int
        get() = prefs.getInt(TIMER_MINUTES, 0)
        set(v) = prefs.edit().putInt(TIMER_MINUTES, v).apply()

    /** Calls [onChange] when any toy option changes (a toy redraws at once); returns the way to stop. */
    fun listen(onChange: () -> Unit): () -> Unit {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(l)
        return { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }

    companion object {
        private const val CLOCK_STYLE = "clock_style"
        private const val GALLERY = "gallery"
        private const val HUB_FACE = "hub_face"
        private const val AOD_SEEN = "aod_seen_at"
        private const val HUB_BATTERY = "hub_battery"
        private const val TIMER_MINUTES = "timer_minutes"
        private const val VISUAL_STYLE = "visual_style"
        private const val CLOCK_24H = "clock_24h"
        private const val BATTERY_CHARGE = "battery_charge_animation"
        private const val DICE_SHAKE = "dice_shake"
        private const val PLAYER_DESIGN = "player_design"

        /** Hour as shown on the clock: 0-23, or 1-12 on a 12-hour clock. */
        fun displayHour(hour: Int, use24h: Boolean): Int = if (use24h) hour else (hour % 12).let { if (it == 0) 12 else it }
    }
}

/** The faces of the always-on toy, switched by long press, its settings or the Quick Settings tile. */
enum class HubFace(val label: String, val hint: String) {
    CLOCK("Clock", "The time"),
    GALLERY("Gallery", "Your designs, one each minute"),
    MOON("Moon", "Tonight's moon"),
    NOW_PLAYING("Now playing", "What's playing, or the time"),
    ;

    fun next(): HubFace = entries[(ordinal + 1) % entries.size]
}
