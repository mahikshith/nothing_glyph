@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dotforge.ui.features

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Effects
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.FeatureTiming
import com.dotforge.toys.InstalledToy
import com.dotforge.toys.InstalledToys
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
import com.dotforge.ui.design.SmallButton
import com.dotforge.ui.design.Tag
import com.dotforge.ui.addToGlyphToys
import com.dotforge.ui.design.paceName
import com.dotforge.widget.GlyphWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Features > Toys: Nothing's toy settings (where the Glyph Button's toys are added and ordered) and the On the
 * Glyph row (every DotForge toy's brightness and speed); a row per DotForge toy (new ones first, tagged New) with
 * its icon, where it stands in Glyph Toys and a chevron to its page, and Add where it isn't added; other apps'
 * toys; Get more; the widget. Nothing here switches a toy on: Glyph Toys does, and every row says so.
 */
@Composable
fun ToysFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val timing by AppGraph.features.timing(FeatureTiming.TOYS).collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var others by remember { mutableStateOf<List<InstalledToy>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var glyph by rememberSaveable { mutableStateOf(false) }
    // back from Glyph Toys: read the list again
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    LaunchedEffect(version) {
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
                    "Glyph Toys", subtitle = "Nothing's list of the toys the Glyph Button switches between", subtitleLines = 3,
                    trailing = { Chevron() },
                ) { InstalledToys.openManager(ctx) }
                RowDivider()
                OnTheGlyphRow(toysSummary(timing), remember(timing) { Timings.plan(sample, timing.copy(showFor = com.dotforge.device.ShowFor.WHOLE)).animation }, "A toy at these settings") { glyph = true }
            }
        }
        item(key = "ours") {
            SectionLabel("DotForge toys")
            Hint(toysHint(list), Modifier.padding(bottom = Tokens.Small))
            RowGroup {
                Toy.listed.forEachIndexed { i, toy ->
                    if (i > 0) RowDivider(leading = true)
                    ToyRow(toy, ToyStatus.of(list, toy.cls), onAdd = { addToGlyphToys(ctx, toy) }) { nav.push(Page.ToySettings(toy)) }
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

/** The line over the list: how many are in Glyph Toys, and the one instruction. */
internal fun toysHint(list: Map<String, Boolean>?): String {
    val step = "Tap Add, then turn the toy on in Glyph Toys. Press the Glyph Button on the back to switch between them."
    if (list == null) return step
    val n = Toy.entries.count { list[it.cls] == true }
    return (if (n == 1) "1 in Glyph Toys. " else "$n in Glyph Toys. ") + step
}

/** A DotForge toy: its bright icon, its name (with New), where it stands, Add when it isn't added, a chevron. */
@Composable
private fun ToyRow(toy: Toy, status: ToyStatus, onAdd: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val name = stringResource(toy.nameRes)
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.RowTall).clickable(onClick = onOpen)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        Box(Modifier.size(Tokens.Avatar), contentAlignment = Alignment.Center) {
            GlyphPreview(remember(toy) { toy.icon(AppGraph.profile, ctx) }, "$name icon")
        }
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.Center) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (toy.isNew) Tag("New", Modifier.align(Alignment.CenterVertically))
            }
            Text(status.label, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        }
        if (status == ToyStatus.NOT_ADDED) SmallButton("Add", "Add $name to Glyph Toys", onClick = onAdd)
        Chevron()
    }
}

/** The row's line: "100% · Normal speed". */
internal fun toysSummary(t: Timing): String {
    val c = t.clamped()
    return listOf("${c.brightness}%", paceName(c.speed) + " speed").joinToString(" · ")
}

internal fun toysLine(t: Timing): String {
    val c = t.clamped()
    return "Every DotForge toy shows at ${c.brightness}% and moves at ${paceName(c.speed).lowercase()} speed."
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
