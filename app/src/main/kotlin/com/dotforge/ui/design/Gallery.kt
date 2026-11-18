package com.dotforge.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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

    Page("Components", onBack) {
        item { SectionLabel("Section label"); Hint("A hint: one quiet line of help or status.") }
        item {
            SectionLabel("Rows")
            RowGroup {
                ListRow("List row", subtitle = "One line under the title", leading = { LibraryGlyph("chat") }, trailing = { Chevron() }) {}
                HorizontalDivider(color = Tokens.Hairline)
                SwitchRow("Switch row", "The whole row toggles", on) { on = it }
                HorizontalDivider(color = Tokens.Hairline)
                ListRow("Disabled row", subtitle = "Greyed out", enabled = false) {}
                HorizontalDivider(color = Tokens.Hairline)
                ListRow("Mom", subtitle = "Avatar for a person", leading = { Avatar("Mom") })
                HorizontalDivider(color = Tokens.Hairline)
                AddRow("Add an app") {}
            }
        }
        item {
            SectionLabel("Equal tiles")
            TilePair(
                { EqualTile("Torch", if (on) "On · off in 4:12" else "Off", it, checked = on, onToggle = { v -> on = v }, onOpen = {}) },
                { EqualTile("Music visualiser", "Needs Phone (3)", it, checked = false, enabled = false, onOpen = {}) },
            )
            TilePair(
                { EqualTile("Toys", "3 suggested", it, height = Tokens.FeatureCard, icon = { LibraryGlyph("game") }, onOpen = {}) },
                { EqualTile("Progress rings", "Off", it, height = Tokens.FeatureCard, icon = { LibraryGlyph("download") }, onOpen = {}) },
            )
        }
        item {
            SectionLabel("Glyph previews 40 / 96 / 200")
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                GlyphPreview(GlyphLibrary.get("heart")?.still(profile), "Heart", size = PreviewSize.SMALL)
                GlyphPreview(GlyphLibrary.get("pulse")?.animation(profile), "Pulse", size = PreviewSize.MEDIUM)
            }
            GlyphPreview(GlyphLibrary.get("ripple")?.animation(profile), "Ripple", Modifier.padding(top = Tokens.Gap), size = PreviewSize.LARGE)
        }
        item {
            SectionLabel("Buttons")
            PrimaryButton("Primary", Modifier.fillMaxWidth()) {}
            SecondaryButton("Secondary", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {}
            OutlineButton("Outline", Modifier.padding(top = Tokens.Small)) {}
        }
        item {
            SectionLabel("Chips and segments")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                listOf("Slow", "Normal", "Fast").forEachIndexed { i, s -> Chip(s, chip == i) { chip = i } }
            }
            Segmented(listOf("Apps", "People", "Spam"), seg, Modifier.padding(top = Tokens.Small)) { seg = it }
        }
        item {
            SectionLabel("Sheets, timing and snackbars")
            RowGroup {
                ListRow("Open a sheet", trailing = { Chevron() }) { open = true }
                TimingKind.entries.forEach { k ->
                    HorizontalDivider(color = Tokens.Hairline)
                    ListRow("Timing panel: ${k.name.lowercase()}", trailing = { Chevron() }) { timingOpen = k }
                }
                HorizontalDivider(color = Tokens.Hairline)
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
    timingOpen?.let { k ->
        val base = remember { Timing(showFor = com.dotforge.device.ShowFor.SECONDS, seconds = 3) }
        var t by remember(k) { mutableStateOf(if (k == TimingKind.USE) base.copy(brightness = 50) else Timing()) }
        val animated = k != TimingKind.STILL
        FullSheet({ timingOpen = null }, "Timing", timingSummary(t, animated)) {
            if (k == TimingKind.USE) TimingPanel(t, k, base = base, placeName = "WhatsApp", animated = true) { t = it }
            else TimingPanel(t, k) { t = it }
        }
    }
}
