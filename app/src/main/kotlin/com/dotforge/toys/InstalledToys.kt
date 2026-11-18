package com.dotforge.toys

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap

/**
 * A toy's meta-data as Nothing's toy manager reads it (Phone (3) firmware, see the research notes): the name,
 * image and summary are resource ids (`android:resource`); the introduction is a string; long press and AOD
 * support are 0 / 1. A toy without a name is skipped, as the firmware does. Some toys declare the name or
 * summary as a plain `android:value` string; those are kept too.
 */
data class ToyMeta(
    val nameRes: Int?,
    val name: String?,
    val imageRes: Int?,
    val summaryRes: Int?,
    val summary: String?,
    val introduction: String?,
    val longPress: Boolean,
    val aod: Boolean,
) {
    companion object {
        const val ACTION = "com.nothing.glyph.TOY"
        const val NAME = "com.nothing.glyph.toy.name"
        const val IMAGE = "com.nothing.glyph.toy.image"
        const val SUMMARY = "com.nothing.glyph.toy.summary"
        const val INTRODUCTION = "com.nothing.glyph.toy.introduction"
        const val LONG_PRESS = "com.nothing.glyph.toy.longpress"
        const val AOD = "com.nothing.glyph.toy.aod_support"

        private fun res(v: Any?): Int? = (v as? Int)?.takeIf { it > 0 }
        private fun text(v: Any?): String? = (v as? CharSequence)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        private fun flag(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Int -> v == 1
            is String -> v.trim() == "1" || v.trim().equals("true", ignoreCase = true)
            else -> false
        }

        /** [meta] is the service's meta-data as plain values (a resource id comes as an Int). */
        fun parse(meta: Map<String, Any?>): ToyMeta? {
            val nameRes = res(meta[NAME])
            val name = text(meta[NAME])
            if (nameRes == null && name == null) return null
            return ToyMeta(
                nameRes = nameRes, name = name,
                imageRes = res(meta[IMAGE]),
                summaryRes = res(meta[SUMMARY]), summary = text(meta[SUMMARY]),
                introduction = text(meta[INTRODUCTION]),
                longPress = flag(meta[LONG_PRESS]),
                aod = flag(meta[AOD]),
            )
        }
    }
}

/** An installed toy, ready to show. */
data class InstalledToy(
    val component: ComponentName,
    val name: String,
    val summary: String,
    val appLabel: String,
    val image: Bitmap?,
    val longPress: Boolean,
    val aod: Boolean,
) {
    val ours: Boolean get() = component.packageName == "com.dotforge"
}

object InstalledToys {
    /** Every service that answers [ToyMeta.ACTION], with its name, image and summary. Blocking (reads resources). */
    fun query(ctx: Context): List<InstalledToy> {
        val pm = ctx.packageManager
        val found = runCatching {
            pm.queryIntentServices(Intent(ToyMeta.ACTION), PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        }.getOrDefault(emptyList())
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val bundle = si.metaData ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val meta = ToyMeta.parse(bundle.keySet().associateWith { bundle.get(it) }) ?: return@mapNotNull null
            val res = runCatching { pm.getResourcesForApplication(si.applicationInfo) }.getOrNull()
            fun str(id: Int?) = id?.let { runCatching { res?.getString(it) }.getOrNull() }
            val image = meta.imageRes?.let { id ->
                runCatching { res?.getDrawable(id, null)?.toBitmap(96, 96) }.getOrNull()
            }
            InstalledToy(
                component = ComponentName(si.packageName, si.name),
                name = str(meta.nameRes) ?: meta.name ?: si.name.substringAfterLast('.'),
                summary = str(meta.summaryRes) ?: meta.summary ?: meta.introduction.orEmpty(),
                appLabel = runCatching { pm.getApplicationLabel(si.applicationInfo).toString() }.getOrDefault(si.packageName),
                image = image,
                longPress = meta.longPress,
                aod = meta.aod,
            )
        }.sortedWith(compareBy({ it.ours }, { it.appLabel.lowercase() }, { it.name.lowercase() }))
    }

    /**
     * Nothing's toy manager: the TOYS_MANAGER action, then the manager activity by name, then the Glyph
     * Interface settings, then system Settings. Names come from Phone (3) firmware.
     */
    fun openManager(ctx: Context) {
        val tries = listOf(
            Intent("com.nothing.glyph.TOYS_MANAGER"),
            Intent().setComponent(ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity")),
            Intent("android.settings.ACTION_GLYPHS_SETTINGS").setPackage("com.android.settings"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
