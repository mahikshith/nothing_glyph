package com.dotforge.toys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.dotforge.AppGraph
import com.dotforge.R

/**
 * Home's always-on line (parity row 65): whether DF Always-on is Nothing's always-on toy, and the way to make it
 * so. Nothing's toy provider names the active AOD toy (read-only, unsupported, so any failure counts as "can't
 * tell"); the toy's own once-a-minute AOD tick is the fallback.
 */
object AlwaysOn {
    private val ACTIVE = Uri.parse("content://com.nothing.glyphtoyprovider/active_aod_toy_name")
    private const val THIRDPARTY = "com.nothing.thirdparty"

    /** An AOD tick this recent means DF Always-on is showing. */
    const val TICK_MS = 3 * 60_000L

    /** True when DF Always-on is the always-on toy, false when another is (or none), null when the phone can't say. */
    fun ours(ctx: Context, now: Long = System.currentTimeMillis()): Boolean? {
        if (now - AppGraph.toys.aodSeenAt < TICK_MS) return true
        val name = runCatching {
            ctx.contentResolver.query(ACTIVE, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        }.getOrNull() ?: return null
        val ours = ctx.getString(R.string.toy_hub_name)
        return name.contains(ours, ignoreCase = true) || name.contains(HubToy::class.java.name)
    }

    /** Nothing's "always-on toy" chooser, else its toy manager, else null (not a Nothing phone). */
    fun setUpIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        val activities = runCatching { pm.getPackageInfo(THIRDPARTY, PackageManager.GET_ACTIVITIES).activities }.getOrNull().orEmpty()
        val pick = activities.firstOrNull { it.name.endsWith("AodToySelectActivity") && it.exported }
            ?: activities.firstOrNull { it.name.endsWith("ToysManagerActivity") && it.exported }
            ?: return null
        return Intent().setComponent(ComponentName(THIRDPARTY, pick.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
