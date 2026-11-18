package com.dotforge.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dotforge.device.GlyphLibrary
import com.dotforge.device.Timing
import com.dotforge.AppGraph

/**
 * Temporary: every shared component on one page (Profile > Old screens > Component gallery), for reviewing the
 * design system at any size and font scale. [sheet] opens the sample sheet at start (for screenshots).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentGallery(onBack: () -> Unit, sheet: Boolean = false, timingKind: TimingKind? = null) {
    var open by rememberSaveable { mutableStateOf(sheet) }
    var timingOpen by rememberSaveable { mutableStateOf(timingKind) }
    var on by remember { mutableStateOf(true) }
    var chip by remember { mutableIntStateOf(1) }
    var seg by remember { mutableIntStateOf(0) }
    val snacks = rememberSnacks()
    val profile = AppGraph.profile

    var slider by remember { mutableStateOf(50f) }
    var confirm by remember { mutableStateOf(false) }
    Page("Components", onBack) {
        item { SectionLabel("Section label"); Hint("A hint: one quiet line of help or status.") }
        item {
            SectionLabel("Rows")
            RowGroup {
                ListRow("List row", subtitle = "One line under the title", leading = { LibraryGlyph("chat") }, trailing = { Chevron() }) {}
                RowDivider(leading = true)
                ListRow("Mom", subtitle = "Avatar for a person", leading = { Avatar("Mom") }, trailing = { Chevron() }) {}
                RowDivider(leading = true)
                AddRow("Add an app") {}
            }
            RowGroup(Modifier.padding(top = Tokens.Gap)) {
                SwitchRow("Switch row", "The whole row toggles", on) { on = it }
                RowDivider()
                ListRow("Disabled row", subtitle = "Greyed out", enabled = false) {}
                RowDivider()
                ListRow("Plain row", trailing = { Chevron() }) {}
            }
        }
        item {
            SectionLabel("Home tiles, 104")
            TilePair(
                { EqualTile("Torch", if (on) "On · off in 4:12" else "Off", it, checked = on, onToggle = { v -> on = v }, onOpen = {}) },
                { EqualTile("Music visualiser", "Needs Phone (3)", it, checked = false, enabled = false, onOpen = {}) },
            )
            SectionLabel("Features cards, 120")
            TilePair(
                { EqualTile("Toys", "3 of 15 on", it, height = Tokens.FeatureCard, icon = { LibraryGlyph("game") }, onOpen = {}) },
                { EqualTile("Progress rings", "Off", it, height = Tokens.FeatureCard, icon = { LibraryGlyph("download") }, onOpen = {}) },
            )
        }
        item {
            SectionLabel("Glyph previews 40 / 96 / 200")
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Gap), verticalAlignment = Alignment.CenterVertically) {
                GlyphPreview(GlyphLibrary.get("heart")?.still(profile), "Heart", size = PreviewSize.SMALL)
                GlyphPreview(GlyphLibrary.get("pulse")?.animation(profile), "Pulse", size = PreviewSize.MEDIUM)
            }
            GlyphPreview(GlyphLibrary.get("ripple")?.animation(profile), "Ripple", Modifier.padding(top = Tokens.Gap), size = PreviewSize.LARGE)
        }
        item {
            SectionLabel("Slider")
            DotSlider("Brightness", slider, 10f..100f, readout = "${slider.toInt()}%", mark = 50f) { slider = it }
        }
        item {
            SectionLabel("Buttons")
            PrimaryButton("Primary", Modifier.fillMaxWidth()) {}
            SecondaryButton("Secondary", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {}
        }
        item {
            SectionLabel("Chips and segments")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                listOf("Slow 0.5x", "Normal 1x", "Fast 2x", "Until I check", "Every 15 min").forEachIndexed { i, s -> Chip(s, chip == i) { chip = i } }
            }
            Segmented(listOf("Apps", "People", "Spam", "History"), seg, Modifier.padding(top = Tokens.Small)) { seg = it }
        }
        item {
            SectionLabel("States")
            StatusBanner("Alerts are off. Notification access was turned off.", button = "Turn on")
            StatusBanner("Needs Phone (3). On this phone you can still design and block spam.", Modifier.padding(top = Tokens.Small))
            StatusBanner("Contacts not allowed. Type a number instead.", Modifier.padding(top = Tokens.Small), button = "Allow")
            LoadingRows(3, Modifier.padding(top = Tokens.Gap))
        }
        item {
            SectionLabel("Sheets, dialogs, timing and snackbars")
            RowGroup {
                ListRow("Open a sheet", trailing = { Chevron() }) { open = true }
                RowDivider()
                ListRow("Open a confirm dialog", trailing = { Chevron() }) { confirm = true }
                TimingKind.entries.forEach { k ->
                    RowDivider()
                    ListRow("Timing panel: ${k.name.lowercase()}", trailing = { Chevron() }) { timingOpen = k }
                }
                RowDivider()
                ListRow("Show a snackbar with Undo", trailing = { Chevron() }) { snacks?.show("Rule deleted") { snacks.show("Rule restored") } }
            }
        }
        item { SectionLabel("Empty state"); EmptyState("No designs yet", "New design", glyph = "star") }
    }

    if (open) {
        FullSheet({ open = false }, "A sheet", "Options go in sheets, never in the page") {
            OptionRow("Copy of frame 2", "Starts with the dots of frame 2", preview = { LibraryGlyph("heart") }) { open = false }
            OptionRow("Blank frame", "Starts empty", preview = { LibraryGlyph("dot") }) { open = false }
            OptionRow("Selected option", "Shows a check", selected = true) {}
            OptionRow("Clear all 5 frames", "Start over with one blank frame.", destructive = true) { open = false }
        }
    }
    if (confirm) {
        ConfirmDialog("Delete this design?", "It's used by WhatsApp. WhatsApp goes back to its app icon.", "Delete", { confirm = false }) { confirm = false }
    }
    timingOpen?.let { k ->
        val base = remember { Timing(showFor = com.dotforge.device.ShowFor.SECONDS, seconds = 3) }
        var t by remember(k) { mutableStateOf(if (k == TimingKind.USE) base.copy(brightness = 50) else Timing(repeat = com.dotforge.device.Repeat.TIMES, times = 2)) }
        val anim = remember(k) {
            when (k) {
                TimingKind.STILL -> GlyphLibrary.get("heart")?.still(profile)?.let { com.dotforge.device.Animation.single(it) }
                TimingKind.TEXT -> com.dotforge.device.Effects.marquee(profile, "Hello")
                else -> GlyphLibrary.get("ripple")?.animation(profile)
            }
        }
        if (k == TimingKind.USE) TimingSheet(t, k, anim, "Ripple", { timingOpen = null }, base = base, placeName = "WhatsApp") { t = it }
        else TimingSheet(t, k, anim, "Sample", { timingOpen = null }) { t = it }
    }
}
