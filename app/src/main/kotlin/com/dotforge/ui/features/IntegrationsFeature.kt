package com.dotforge.ui.features

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.automation.Automation
import com.dotforge.automation.AutomationRunner
import com.dotforge.automation.GlyphEvents
import com.dotforge.device.Effects
import com.dotforge.device.GlyphLibrary
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.FeatureTiming
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.ConfirmDialog
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.showForText
import com.dotforge.ui.design.speedText

/**
 * Features > Integrations (parity row 98): "Let other apps show designs" and its On the Glyph row (the timing a
 * design gets when the app doesn't say how long); the Tasker plugin and the key for Tasker's Send intent (Copy
 * key, Make a new key); "Tell other apps when something lights"; the apps that asked; and For app makers.
 */
@Composable
fun IntegrationsFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val f = AppGraph.features
    val a = AppGraph.automation
    val show by f.showDesigns.collectAsStateWithLifecycle()
    val tell by f.tellOthers.collectAsStateWithLifecycle()
    val key by f.key.collectAsStateWithLifecycle()
    val timing by f.timing(FeatureTiming.APPS).collectAsStateWithLifecycle()
    val plugin by a.plugin.collectAsStateWithLifecycle()
    val allowed by a.allowed.collectAsStateWithLifecycle()
    val asked by a.asked.collectAsStateWithLifecycle()
    val last by AutomationRunner.last.collectAsStateWithLifecycle()
    var glyph by rememberSaveable { mutableStateOf(false) }
    var renew by rememberSaveable { mutableStateOf(false) }
    var makers by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val sample = remember { GlyphLibrary.get("pulse")?.animation(AppGraph.profile) ?: Effects.pulse(AppGraph.profile) }
    val apps = remember(allowed, asked) { (allowed + asked).map { it to label(ctx, it) }.sortedBy { it.second.lowercase() } }

    FeatureLayout(Feature.INTEGRATIONS, nav) {
        item(key = "main") {
            RowGroup {
                SwitchRow("Let other apps show designs", "Tasker, MacroDroid and apps you allow", show) { f.setShowDesigns(it) }
                RowDivider()
                OnTheGlyphRow(appsSummary(timing), remember(timing) { Timings.plan(sample, timing).animation }, "A design another app asked for", enabled = show) { glyph = true }
            }
        }
        item(key = "tasker") {
            SectionLabel("Tasker")
            RowGroup {
                SwitchRow("Tasker plugin", "In Tasker or MacroDroid: add an action, Plugin, DotForge", plugin && show, enabled = show) { a.setPlugin(it) }
                RowDivider()
                ListRow(
                    "Key", subtitle = (if (copied) "Copied · " else "") + "For Tasker's Send intent: ${maskKey(key)}", enabled = show, subtitleLines = 4,
                    trailing = {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(key))
                            copied = true
                        }, enabled = show) {
                            Text("Copy key", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.semantics { contentDescription = "Copy key" })
                        }
                    },
                )
                RowDivider()
                ListRow("Make a new key", subtitle = "Apps using the old key stop working", enabled = show, trailing = { Chevron() }) { renew = true }
            }
        }
        item(key = "tell") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                SwitchRow("Tell other apps when something lights", "Only what lit it (an alert, a call, a timer), never what it said", tell) { f.setTellOthers(it) }
            }
        }
        item(key = "apps") {
            SectionLabel("Apps")
            if (apps.isEmpty()) Hint("An app shows here once it sends DotForge a command without the key. Allow it, and DotForge obeys it.")
            else RowGroup {
                apps.forEachIndexed { i, (pkg, name) ->
                    if (i > 0) RowDivider()
                    SwitchRow(name, if (pkg in allowed) "Allowed" else "Asked · not allowed", pkg in allowed, enabled = show) { a.allow(pkg, it) }
                }
            }
            last?.let { Hint("Last: $it", Modifier.padding(top = Tokens.Small)) }
        }
        item(key = "makers") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                ListRow("For app makers", subtitle = "The intents, extras and the broadcast", trailing = { Chevron() }) { makers = true }
            }
        }
    }

    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, sample, "Pulse", { glyph = false },
        title = "On the Glyph", plainLine = appsLine(timing),
        parts = TimingParts(secondsChips = listOf(5, 10, 30), more = false, compactBrightness = true),
        onChange = { f.setTiming(FeatureTiming.APPS, it) },
    )
    if (renew) ConfirmDialog(
        "Make a new key?", "Tasker tasks and apps using the old key stop working until you paste the new one in.", "Make a new key", { renew = false },
    ) {
        f.renewKey()
        copied = false
        renew = false
    }
    if (makers) FullSheet({ makers = false }, "For app makers") {
        Text(makersText(ctx.packageName), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = Tokens.Secondary)
        Text("The key", Modifier.padding(top = Tokens.Section, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("Copy key puts it on the clipboard. Paste it into Tasker's Send intent as the extra key:<the key>.", style = MaterialTheme.typography.bodyMedium)
    }
}

/** "•••• 3fa1": the key's last four letters. */
internal fun maskKey(key: String): String = "•••• " + key.takeLast(4)

/** The row's line: "10 s · 1x · 100% when the app doesn't say". */
internal fun appsSummary(t: Timing): String {
    val c = t.clamped()
    return listOf(showForText(c, TimingKind.ANIMATION), speedText(c.speed), "${c.brightness}%").joinToString(" · ") + " unless the app says"
}

internal fun appsLine(t: Timing): String =
    "When an app doesn't say how long: " + com.dotforge.ui.design.timingLine(t, TimingKind.ANIMATION, true, null).replaceFirstChar { it.lowercase() }

/** The For app makers sheet: the actions and extras, the key and the outgoing broadcast. */
internal fun makersText(pkg: String): String = """
Send a broadcast to $pkg with one of these actions.
With the permission ${Automation.PERMISSION}: to .automation.AutomationReceiver, with a PendingIntent of yours as "${Automation.EXTRA_CALLER}"; allow your app here.
Without it (Tasker's Send intent): to .automation.KeyReceiver, with the key as "${Automation.EXTRA_KEY}".

${Automation.ACTION_GLYPH}
  glyph: a style or design name, or text: up to 64 letters
  seconds: 1 to 60, or loops: 1 to ${Automation.MAX_LOOPS}
  (neither: On the Glyph above)
${Automation.ACTION_PLAY}
  composition: title or content URI
${Automation.ACTION_VISUALISER}
  on: true or false
${Automation.ACTION_RULE}
  rule: name or id, on: true or false

Example: glyph Heart, seconds 10; or glyph Heart, loops 3.

When something lights (with Tell other apps on), DotForge sends ${GlyphEvents.ACTION_LIT} to apps holding the permission, with "${GlyphEvents.EXTRA_EVENT}": ${GlyphEvents.NAMES.joinToString(", ")}. Register for it while your app runs (Tasker's Intent Received does).
""".trim()

private fun label(ctx: Context, pkg: String): String = runCatching {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
