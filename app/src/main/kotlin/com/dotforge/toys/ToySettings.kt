package com.dotforge.toys

import android.content.SharedPreferences

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

    companion object {
        private const val CLOCK_24H = "clock_24h"
        private const val BATTERY_CHARGE = "battery_charge_animation"
        private const val DICE_SHAKE = "dice_shake"
        private const val PLAYER_DESIGN = "player_design"

        /** Hour as shown on the clock: 0-23, or 1-12 on a 12-hour clock. */
        fun displayHour(hour: Int, use24h: Boolean): Int = if (use24h) hour else (hour % 12).let { if (it == 0) 12 else it }
    }
}
