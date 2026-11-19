package com.dotforge.ui.features

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Effects
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.FeatureTiming
import com.dotforge.toys.DayUsage
import com.dotforge.toys.InstalledToy
import com.dotforge.toys.InstalledToys
import com.dotforge.toys.ToyComponents
import com.dotforge.toys.ToyList
import com.dotforge.toys.ToyStatus
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.Toy
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.speedText
import com.dotforge.ui.design.switchColors
import com.dotforge.widget.GlyphWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Features > Toys: Nothing's toy settings (where the Glyph Button's toys are added and ordered) and the On the
 * Glyph row (every DotForge toy's brightness and speed); a row per DotForge toy with its switch and where it
 * stands in Nothing's list ("Added in Glyph Toys"), opening its options; other apps' toys; Get more; the widget.
 */
@Composable
fun ToysFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val timing by AppGraph.features.timing(FeatureTiming.TOYS).collectAsStateWithLifecycle()
    val on = remember { mutableStateMapOf<Toy, Boolean>() }
    var list by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var others by remember { mutableStateOf<List<InstalledToy>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var glyph by rememberSaveable { mutableStateOf(false) }
    // back from Nothing's toy settings: read the list again
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    LaunchedEffect(version) {
        Toy.entries.forEach { on[it] = runCatching { ToyComponents.isOn(ctx, it.cls) }.getOrDefault(false) }
        list = withContext(Dispatchers.IO) { ToyList.read(ctx) }
        others = withContext(Dispatchers.IO) { runCatching { InstalledToys.query(ctx) }.getOrDefault(emptyList()) }.filterNot { it.ours }
    }
    val sample = remember { toySample() }

    FeatureLayout(Feature.TOYS, nav, extra = {
        item(key = "widget") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                ListRow("Add the widget", subtitle = "A design or the live Glyph on your home screen", subtitleLines = 3, trailing = { Chevron() }) {
                    manual = !GlyphWidgetProvider.requestPin(ctx)
                }
            }
            if (manual) Hint("Your launcher can't add it from here. Touch and hold your home screen, then Widgets > DotForge.")
        }
    }) {
        item(key = "main") {
            RowGroup {
                ListRow(
                    "Nothing's toy settings", subtitle = "Add, remove and order the toys the Glyph Button switches to", subtitleLines = 3,
                    trailing = { Chevron() },
                ) { InstalledToys.openManager(ctx) }
                RowDivider()
                OnTheGlyphRow(toysSummary(timing), remember(timing) { Timings.plan(sample, timing.copy(showFor = com.dotforge.device.ShowFor.WHOLE)).animation }, "A toy at these settings") { glyph = true }
            }
        }
        item(key = "ours") {
            SectionLabel("DotForge toys")
            val count = Toy.entries.count { on[it] == true }
            Hint("$count on. Switch a toy on here, then add it in Nothing's toy settings.", Modifier.padding(bottom = Tokens.Small))
            RowGroup {
                Toy.entries.forEachIndexed { i, toy ->
                    if (i > 0) RowDivider(leading = true)
                    val isOn = on[toy] == true
                    val status = ToyStatus.of(isOn, list?.get(toy.cls) ?: list?.let { false })
                    ToyRow(toy, isOn, status, onOpen = { nav.push(Page.ToySettings(toy)) }) { want ->
                        runCatching { ToyComponents.set(ctx, toy.cls, want) }
                        on[toy] = want
                        // the toys that need something first open their page, which asks for it
                        if (want && needsSetUp(ctx, toy)) nav.push(Page.ToySettings(toy))
                    }
                }
            }
        }
        if (others.isNotEmpty()) item(key = "others") {
            SectionLabel("Other apps' toys")
            RowGroup {
                others.forEachIndexed { i, t ->
                    if (i > 0) RowDivider(leading = true)
                    ListRow(
                        t.name, subtitle = listOf(t.appLabel, t.summary).filter { it.isNotBlank() }.joinToString(" · "),
                        leading = {
                            t.image?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp)) }
                        },
                        trailing = { Chevron() },
                    ) { InstalledToys.openManager(ctx) }
                }
            }
        }
        item(key = "more") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                ListRow("Get more toys", subtitle = "Toys from other makers, free", trailing = { Chevron() }) { nav.push(Page.Toys) }
            }
        }
    }

    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, sample, "A toy", { glyph = false },
        title = "On the Glyph", plainLine = toysLine(timing),
        parts = TimingParts(
            showForSlot = { PartNote("Set by each toy: it shows while you pick it with the Glyph Button. The Glyph rests after 30 s without a press.") },
            speedTitle = "Animation speed",
            repeatSlot = { PartNote("Set by each toy: animations loop while the toy shows.") },
            more = false,
            compactBrightness = true,
        ),
        onChange = { AppGraph.features.setTiming(FeatureTiming.TOYS, it) },
    )
}

/** One DotForge toy: its face, its name and where it stands, and its switch. The row opens its options. */
@Composable
private fun ToyRow(toy: Toy, on: Boolean, status: ToyStatus, onOpen: () -> Unit, onSwitch: (Boolean) -> Unit) {
    val name = stringResource(toy.nameRes)
    ListRow(
        name, subtitle = status.label,
        leading = { GlyphPreview(remember(toy) { runCatching { toy.preview(AppGraph.profile) }.getOrNull() }, "$name toy") },
        trailing = {
            Switch(
                on, onSwitch, colors = switchColors(),
                modifier = Modifier.semantics { contentDescription = name },
            )
        },
        onClick = onOpen,
    )
}

/** Day dots without Usage access, and Weather with neither a typed place nor location, open their page first. */
private fun needsSetUp(ctx: android.content.Context, toy: Toy): Boolean = when (toy) {
    Toy.DAY_DOTS -> !DayUsage.allowed(ctx)
    Toy.WEATHER -> AppGraph.toys.weatherPlace == null && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    else -> false
}

/** The row's line: "100% · 1x · set by each toy". */
internal fun toysSummary(t: Timing): String {
    val c = t.clamped()
    return listOf("${c.brightness}%", speedText(c.speed), "shows while picked").joinToString(" · ")
}

internal fun toysLine(t: Timing): String {
    val c = t.clamped()
    return "Every DotForge toy shows at ${c.brightness}% and moves at ${speedText(c.speed)}."
}

/** A toy for the On the Glyph row and sheet: the clock with a comet going round it. */
private fun toySample(): com.dotforge.device.Animation {
    val p = AppGraph.profile
    val clock = com.dotforge.device.ToyFaces.clock(p, com.dotforge.device.ClockStyle.DIGITAL, 10, 8, true)
    val lap = Effects.ringChase(p, tail = 10, stepMs = 40)
    return com.dotforge.device.Animation(p, lap.frames.map { f ->
        com.dotforge.device.AnimFrame(com.dotforge.device.Frame(p, IntArray(p.cellCount) { maxOf(clock.pixels[it], f.frame.pixels[it]) }), f.durationMs)
    })
}
