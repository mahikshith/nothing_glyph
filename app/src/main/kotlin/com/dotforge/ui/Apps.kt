package com.dotforge.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val packageName: String, val label: String)

/** App name for a package, cached; the last part of the package name when the app is not installed. */
@Composable
fun rememberAppLabel(): (String) -> String {
    val pm = LocalContext.current.packageManager
    return remember(pm) {
        val cache = HashMap<String, String>()
        val label: (String) -> String = { pkg ->
            cache.getOrPut(pkg) {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
            }
        }
        label
    }
}

/** Apps with a launcher icon (visible through the manifest's <queries>), sorted by name. */
suspend fun launcherApps(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(main, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != ctx.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
