package com.dotforge.toys

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Which DF toys Nothing's toy manager sees. Each toy is a service component; a disabled one is gone from the
 * Glyph Button carousel. Only [DEFAULT_ON] start enabled (the manifest says so); the Toys page switches the rest.
 */
object ToyComponents {
    val DEFAULT_ON = setOf("ClockToy", "BatteryToy", "NowPlayingToy")

    fun component(ctx: Context, simpleName: String) = ComponentName(ctx.packageName, "com.dotforge.toys.$simpleName")

    /** On or off from the component's state; the manifest default when the user never changed it. */
    fun isOn(state: Int, simpleName: String): Boolean = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> simpleName in DEFAULT_ON
        else -> false
    }

    /** The state to write for [on]: back to the manifest default when that matches, so updates keep working. */
    fun stateFor(on: Boolean, simpleName: String): Int = when {
        on == simpleName in DEFAULT_ON -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        on -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun isOn(ctx: Context, simpleName: String): Boolean =
        isOn(ctx.packageManager.getComponentEnabledSetting(component(ctx, simpleName)), simpleName)

    fun set(ctx: Context, simpleName: String, on: Boolean) =
        ctx.packageManager.setComponentEnabledSetting(component(ctx, simpleName), stateFor(on, simpleName), PackageManager.DONT_KILL_APP)
}
