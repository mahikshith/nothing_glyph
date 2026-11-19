package com.dotforge.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dotforge.rules.Action
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RuleText

/** App label for a package, or the package name when Android doesn't show us the app. */
private fun label(ctx: Context, pkg: String): String = runCatching {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Media and progress on the ring, and other apps driving DotForge: the automation API, the Tasker plugin. */
@Composable
fun IntegrationsPage(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val settings = AppGraph.automation
    val allowed by settings.allowed.collectAsStateWithLifecycle()
    val asked by settings.asked.collectAsStateWithLifecycle()
    val plugin by settings.plugin.collectAsStateWithLifecycle()
    val last by AutomationRunner.last.collectAsStateWithLifecycle()
    var howTo by rememberToggle()

    val mediaOn by AppGraph.media.on.collectAsStateWithLifecycle()
    val onlyAway by AppGraph.media.onlyLockedOrFaceDown.collectAsStateWithLifecycle()
    val progressOn by AppGraph.progressRings.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()

    PageScaffold("INTEGRATIONS", onBack = nav::pop) {
        if (mediaOn || progressOn) AccessBanner(setup, "Media and progress rings need notification access.")
        GroupTitle("MEDIA")
        SwitchRow("Media progress ring", "While music or a video plays, the outer ring shows how far along it is.", mediaOn) { on ->
            AppGraph.media.setOn(on)
            if (on) setup.needNotificationAccess()
        }
        if (mediaOn) {
            SwitchRow("Only when the phone is locked", "Keeps the matrix dark while you use the phone.", onlyAway, AppGraph.media::setOnlyLockedOrFaceDown)
        }

        GroupTitle("PROGRESS BARS")
        SwitchRow("Progress rings", "Downloads, deliveries, timers and Live Updates on the outer ring.", progressOn) { on ->
            AppGraph.setProgressRings(on)
            if (on) setup.needNotificationAccess()
        }
        val progressRules = rules.filter { it.action == Action.PROGRESS }
        if (progressOn) {
            if (progressRules.isEmpty()) {
                EmptyState("No apps chosen", "A \"Show progress\" rule picks the apps.", "Add for any app") { AppGraph.rules.ensurePreset(RuleEngine.PROGRESS_PRESET) }
            }
            progressRules.forEach { r ->
                ValueRow(r.name, if (r.enabled) "On" else "Off", subtitle = RuleText.sentence(r, { label(ctx, it) })) {
                    nav.push(Page.RuleEditor(r.id))
                }
            }
            SecondaryButton("Pick apps for progress", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                nav.push(Page.RuleEditor(null, RuleTemplates.encode(Rule(name = "", action = Action.PROGRESS))))
            }
        }

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

        GroupTitle("ESSENTIAL KEY")
        Text(
            "Nothing gives apps no supported way to use the Essential Key yet, so DotForge leaves it alone. " +
                "The Glyph Button reaches Glyph Toys only; DotForge's toys use it.",
            color = Muted, fontSize = 13.sp,
        )
    }
}
