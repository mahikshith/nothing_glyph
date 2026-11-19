package com.dotforge.ui.features

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.FeatureTiming
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Avatar
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.SmallButton
import com.dotforge.ui.design.rememberSnacks
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

/**
 * Features > Integrations (parity row 98), in three plain parts: the Tasker plugin (no key needed); "Let other
 * apps send designs" with its On the Glyph row and the key for Tasker's Send intent (Copy key, Make a new key,
 * undoable); "Tell other apps when something lights", heard only by the apps you allow. Then the apps you allow,
 * by name and icon, and For app makers.
 */
@Composable
fun IntegrationsFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snacks = rememberSnacks()
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
    var makers by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val sample = remember { GlyphLibrary.get("pulse")?.animation(AppGraph.profile) ?: Effects.pulse(AppGraph.profile) }
    val apps = remember(allowed, asked) {
        (allowed + asked + installedAutomationApps(ctx)).distinct().map { it to appName(ctx, it) }.sortedBy { it.second.lowercase() }
    }

    FeatureLayout(Feature.INTEGRATIONS, nav) {
        item(key = "plugin") {
            RowGroup {
                SwitchRow("Tasker plugin", "In Tasker or MacroDroid: add an action, then Plugin, then DotForge", plugin) { a.setPlugin(it) }
            }
        }
        item(key = "send") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                SwitchRow("Let other apps send designs", "The apps you allow below, and Tasker's Send intent with the key", show) { f.setShowDesigns(it) }
                RowDivider()
                OnTheGlyphRow(appsSummary(timing), remember(timing) { Timings.plan(sample, timing).animation }, "A design another app asked for", enabled = show) { glyph = true }
                RowDivider()
                ListRow(
                    "Key", subtitle = (if (copied) "Copied · " else "") + "For Tasker's Send intent: ${maskKey(key)}", enabled = show, subtitleLines = 3,
                    trailing = {
                        if (show) SmallButton("Copy key", "Copy key") {
                            clipboard.setText(AnnotatedString(key))
                            copied = true
                        }
                    },
                )
                RowDivider()
                ListRow("Make a new key", subtitle = "Tasks using the old key stop working", enabled = show) {
                    val old = key
                    f.renewKey()
                    copied = false
                    snacks?.show("New key made. Paste it into your tasks.") { f.restoreKey(old) }
                }
            }
        }
        item(key = "tell") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                SwitchRow("Tell other apps when something lights", "Only apps you allow can hear this. They hear what lit it (an alert, a call, a timer), never what it said.", tell) { f.setTellOthers(it) }
            }
        }
        item(key = "apps") {
            SectionLabel("Apps you allow")
            if (apps.isEmpty()) Hint("An app shows here once it sends DotForge a design. Allow it, and DotForge shows its designs and tells it when something lights.")
            else {
                Hint("They can send designs, and hear when something lights.", Modifier.padding(bottom = Tokens.Small))
                RowGroup {
                    apps.forEachIndexed { i, (pkg, name) ->
                        if (i > 0) RowDivider(leading = true)
                        SwitchRow(
                            name, if (pkg in allowed) "Allowed" else if (pkg in asked) "Asked · not allowed" else "Not allowed", pkg in allowed,
                            leading = { AppIcon(pkg, name) },
                        ) { a.allow(pkg, it) }
                    }
                }
            }
            last?.let { line -> Hint("Last: " + apps.fold(line) { acc, (pkg, name) -> acc.replace(pkg, name) }, Modifier.padding(top = Tokens.Small)) }
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
        parts = TimingParts(
            showForSlot = {
                ChipChoices(APPS_SHOW, appsShow(timing), { if (it == 0) "Whole design" else "$it s" }) { s ->
                    f.setTiming(FeatureTiming.APPS, if (s == 0) timing.copy(showFor = ShowFor.WHOLE) else timing.copy(showFor = ShowFor.SECONDS, seconds = s))
                }
            },
            repeatSlot = {
                ChipChoices(APPS_REPEAT, appsRepeat(timing), { it }) { r ->
                    f.setTiming(
                        FeatureTiming.APPS,
                        when (r) {
                            "Once" -> timing.copy(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE)
                            "3 times" -> timing.copy(showFor = ShowFor.WHOLE, repeat = Repeat.TIMES, times = 3)
                            else -> timing.copy(showFor = ShowFor.UNTIL_CHECKED)
                        },
                    )
                }
                PartNote(if (timing.showFor == ShowFor.SECONDS) "With a time above, it plays over and over for that time." else "Unless the app says how many times.")
            },
            more = false, compactBrightness = true,
        ),
        onChange = { f.setTiming(FeatureTiming.APPS, it) },
    )
    if (makers) FullSheet({ makers = false }, "For app makers") {
        Text(MAKERS_INTRO, style = MaterialTheme.typography.bodyMedium)
        CodeBlock(makersCode(ctx.packageName), Modifier.padding(top = Tokens.Gap)) { clipboard.setText(AnnotatedString(it)) }
        Text("When something lights", Modifier.padding(top = Tokens.Section, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("With Tell other apps on, DotForge sends this to each app you allow. Register for it while your app runs (Tasker's Intent Received does).", style = MaterialTheme.typography.bodyMedium)
        CodeBlock(eventsCode(), Modifier.padding(top = Tokens.Gap)) { clipboard.setText(AnnotatedString(it)) }
        Text("The key", Modifier.padding(top = Tokens.Section, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("Copy key puts it on the clipboard. Paste it into Tasker's Send intent as the extra key:<the key>.", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Show for: the whole design, or seconds (0 = whole). */
internal val APPS_SHOW = listOf(0, 5, 10, 30)
internal val APPS_REPEAT = listOf("Once", "3 times", "Until I check")

private fun appsShow(t: Timing): Int? = when (t.showFor) {
    ShowFor.WHOLE -> 0
    ShowFor.SECONDS -> t.seconds.takeIf { it in APPS_SHOW }
    ShowFor.UNTIL_CHECKED -> null
}

private fun appsRepeat(t: Timing): String? = when {
    t.showFor == ShowFor.UNTIL_CHECKED -> "Until I check"
    t.showFor != ShowFor.WHOLE -> null
    t.repeat == Repeat.ONCE -> "Once"
    t.repeat == Repeat.TIMES && t.times == 3 -> "3 times"
    else -> null
}

/** Code that keeps its lines: it scrolls sideways in its box, never breaking a word, with Copy. */
@Composable
private fun CodeBlock(code: String, modifier: Modifier = Modifier, onCopy: (String) -> Unit) {
    androidx.compose.material3.Surface(color = Tokens.Raised, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column {
            Text(
                code, Modifier.horizontalScroll(rememberScrollState()).padding(Tokens.Gutter),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Color.White, softWrap = false,
            )
            Box(Modifier.fillMaxWidth().padding(end = Tokens.Tiny), contentAlignment = Alignment.CenterEnd) { SmallButton("Copy", "Copy the code") { onCopy(code) } }
        }
    }
}

/** An app's icon, or its first letter when it isn't installed. */
@Composable
private fun AppIcon(pkg: String, name: String) {
    val ctx = LocalContext.current
    val icon = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { d ->
            android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                val c = android.graphics.Canvas(b)
                d.setBounds(0, 0, 96, 96)
                d.draw(c)
            }.asImageBitmap()
        }
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(Tokens.Avatar))
    else Avatar(name.take(1).uppercase())
}

/** "•••• 3fa1": the key's last four letters. */
internal fun maskKey(key: String): String = "•••• " + key.takeLast(4)

/** The row's line: "10 s · 100% · unless the app says". */
internal fun appsSummary(t: Timing): String {
    val c = t.clamped()
    val long = when (c.showFor) {
        ShowFor.WHOLE -> if (c.repeat == Repeat.TIMES) "${c.times} times" else if (c.repeat == Repeat.FOREVER) "Over and over" else "Once"
        ShowFor.SECONDS -> "${c.seconds} s"
        ShowFor.UNTIL_CHECKED -> "Until I check"
    }
    return listOf(long, "${c.brightness}%", "unless the app says").joinToString(" · ")
}

internal fun appsLine(t: Timing): String =
    "When an app doesn't say how long: " + com.dotforge.ui.design.timingLine(t, TimingKind.ANIMATION, true, null).replaceFirstChar { it.lowercase() }

private const val MAKERS_INTRO =
    "Send DotForge a broadcast with one of the actions below. If your app holds DotForge's permission, send it to " +
        "AutomationReceiver with a PendingIntent of yours as \"caller\", and the user allows your app here. Tasker's Send " +
        "intent sends it to KeyReceiver with the key instead."

/** The For app makers code: the receivers, the actions and their extras. */
internal fun makersCode(pkg: String): String = """
package    $pkg
permission ${Automation.PERMISSION}
receivers  .automation.AutomationReceiver  (extra "${Automation.EXTRA_CALLER}")
           .automation.KeyReceiver         (extra "${Automation.EXTRA_KEY}")

${Automation.ACTION_GLYPH}
  glyph    a style or design name
  text     up to 64 letters
  seconds  1 to 60
  loops    1 to ${Automation.MAX_LOOPS}
${Automation.ACTION_PLAY}
  composition  title or content URI
${Automation.ACTION_VISUALISER}
  on       true or false
${Automation.ACTION_RULE}
  rule     name or id
  on       true or false

Example: glyph Heart, seconds 10
     or: glyph Heart, loops 3
""".trim()

/** The outgoing broadcast and its one extra. */
internal fun eventsCode(): String = """
${GlyphEvents.ACTION_LIT}
  ${GlyphEvents.EXTRA_EVENT}  ${GlyphEvents.NAMES.joinToString(", ")}
""".trim()

/** Kept for tests and docs: everything on the For app makers sheet as one text. */
internal fun makersText(pkg: String): String = MAKERS_INTRO + "\n\n" + makersCode(pkg) + "\n\n" + eventsCode()

/** Automation apps people use with DotForge, by package, with their names for when they aren't installed. */
internal val KNOWN_APPS = mapOf(
    "net.dinglisch.android.taskerm" to "Tasker",
    "com.arlosoft.macrodroid" to "MacroDroid",
    "com.llamalab.automate" to "Automate",
    "com.joaomgcd.autoapps" to "AutoApps",
)

/** The known automation apps installed on this phone. */
private fun installedAutomationApps(ctx: Context): List<String> =
    KNOWN_APPS.keys.filter { runCatching { ctx.packageManager.getApplicationInfo(it, 0) }.isSuccess }

/** An app's name, never its package: the phone's label, a known name, or "An app no longer installed". */
internal fun appName(ctx: Context, pkg: String): String =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != pkg } ?: KNOWN_APPS[pkg] ?: "An app no longer installed"
