package com.dotforge.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.automation.Automation
import com.dotforge.automation.AutomationRunner

/** App label for a package, or the package name when Android doesn't show us the app. */
private fun label(ctx: Context, pkg: String): String = runCatching {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Other apps driving DotForge: the automation API, the Tasker plugin. */
@Composable
fun IntegrationsPage(nav: Nav) {
    val ctx = LocalContext.current
    val settings = AppGraph.automation
    val allowed by settings.allowed.collectAsStateWithLifecycle()
    val asked by settings.asked.collectAsStateWithLifecycle()
    val plugin by settings.plugin.collectAsStateWithLifecycle()
    val last by AutomationRunner.last.collectAsStateWithLifecycle()
    var howTo by rememberToggle()

    PageScaffold("INTEGRATIONS", onBack = nav::pop) {
        GroupTitle("TASKER")
        SwitchRow(
            "Tasker plugin",
            "In Tasker, MacroDroid or Automate: add an action, Plugin, DotForge.",
            plugin, settings::setPlugin,
        )

        GroupTitle("ALLOW APPS")
        val apps = remember(allowed, asked) { (allowed + asked).map { it to label(ctx, it) }.sortedBy { it.second.lowercase() } }
        if (apps.isEmpty()) EmptyState("No apps yet", "An app shows here once it sends DotForge a command.")
        apps.forEach { (pkg, name) ->
            SwitchRow(name, pkg.takeIf { it != name }, pkg in allowed) { settings.allow(pkg, it) }
        }
        last?.let { Text("Last: $it", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }

        Collapsible("For app makers", howTo, { howTo = !howTo }) {
            Text(
                """
                Hold <uses-permission android:name="${Automation.PERMISSION}"/> and send a broadcast to
                com.dotforge/.automation.AutomationReceiver. Add a PendingIntent of yours as "${Automation.EXTRA_CALLER}"
                so DotForge knows who sent it, then allow your app here.

                ${Automation.ACTION_PLAY}
                  composition: title or content URI
                ${Automation.ACTION_GLYPH}
                  glyph: style or design name, or text: up to 64 letters
                  seconds: 1 to 60 (5)
                ${Automation.ACTION_VISUALISER}
                  on: true or false
                ${Automation.ACTION_RULE}
                  rule: name or id, on: true or false
                """.trimIndent(),
                style = MonoSmall, color = Muted,
            )
        }
    }
}
