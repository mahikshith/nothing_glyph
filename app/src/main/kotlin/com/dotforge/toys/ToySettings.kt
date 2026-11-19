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

    /** DF Weather: a typed place, or null for where the phone is. */
    var weatherPlace: String?
        get() = prefs.getString(WEATHER_PLACE, null)
        set(v) { prefs.edit().putString(WEATHER_PLACE, v?.trim()?.ifBlank { null }).remove(WEATHER_LATLON).remove(WEATHER_JSON).apply() }

    /** The typed place's latitude and longitude, once looked up. */
    var weatherPlaceAt: Pair<Double, Double>?
        get() = prefs.getString(WEATHER_LATLON, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        set(v) = prefs.edit().putString(WEATHER_LATLON, v?.let { "${it.first},${it.second}" }).apply()

    var weatherFahrenheit: Boolean
        get() = prefs.getBoolean(WEATHER_F, false)
        set(v) = prefs.edit().putBoolean(WEATHER_F, v).apply()

    /** A press shows today's chance of rain. */
    var weatherRain: Boolean
        get() = prefs.getBoolean(WEATHER_RAIN, true)
        set(v) = prefs.edit().putBoolean(WEATHER_RAIN, v).apply()

    /** The last Open-Meteo answer and when it came. */
    var weatherJson: String?
        get() = prefs.getString(WEATHER_JSON, null)
        set(v) = prefs.edit().putString(WEATHER_JSON, v).apply()
    var weatherAt: Long
        get() = prefs.getLong(WEATHER_AT, 0L)
        set(v) = prefs.edit().putLong(WEATHER_AT, v).apply()

    /** When the last try to fetch failed for want of a connection (0: the last try worked). */
    var weatherOfflineAt: Long
        get() = prefs.getLong(WEATHER_OFFLINE, 0L)
        set(v) = prefs.edit().putLong(WEATHER_OFFLINE, v).apply()

    /** DotForge has asked for rough location for DF Weather once. */
    var weatherAskedLocation: Boolean
        get() = prefs.getBoolean(WEATHER_ASKED, false)
        set(v) = prefs.edit().putBoolean(WEATHER_ASKED, v).apply()

    /** The user went to Usage access for DF Day dots once. */
    var dayAskedUsage: Boolean
        get() = prefs.getBoolean(DAY_ASKED, false)
        set(v) = prefs.edit().putBoolean(DAY_ASKED, v).apply()

    /** DF Day dots' daily goal, in minutes of screen time. */
    var dayGoalMinutes: Int
        get() = prefs.getInt(DAY_GOAL, 180)
        set(v) = prefs.edit().putInt(DAY_GOAL, v).apply()

    /** The hour DF Day dots starts a new day. */
    var dayResetHour: Int
        get() = prefs.getInt(DAY_RESET, 0)
        set(v) = prefs.edit().putInt(DAY_RESET, v).apply()

    var mazeLevel: com.dotforge.device.TiltMaze.Level
        get() = com.dotforge.device.TiltMaze.Level.entries.firstOrNull { it.name == prefs.getString(MAZE_LEVEL, null) } ?: com.dotforge.device.TiltMaze.Level.EASY
        set(v) = prefs.edit().putString(MAZE_LEVEL, v.name).apply()

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
        private const val WEATHER_PLACE = "weather_place"
        private const val WEATHER_LATLON = "weather_latlon"
        private const val WEATHER_F = "weather_f"
        private const val WEATHER_RAIN = "weather_rain"
        private const val WEATHER_JSON = "weather_json"
        private const val WEATHER_AT = "weather_at"
        private const val WEATHER_OFFLINE = "weather_offline"
        private const val WEATHER_ASKED = "weather_asked"
        private const val DAY_ASKED = "day_asked"
        private const val DAY_GOAL = "day_goal"
        private const val DAY_RESET = "day_reset"
        private const val MAZE_LEVEL = "maze_level"

        /** DF Day dots' goal chips, in hours. */
        val DAY_GOALS = listOf(2, 3, 4, 6)

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
