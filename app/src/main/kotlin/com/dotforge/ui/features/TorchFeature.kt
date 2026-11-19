package com.dotforge.ui.features

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.device.Timing
import com.dotforge.system.FeatureSettings
import com.dotforge.system.FeatureTiming
import com.dotforge.tile.TorchLight
import com.dotforge.tile.TorchTileService
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SmallButton
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.paceName
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.home.torchLine
import kotlinx.coroutines.delay

/**
 * Features > Torch: the switch (its picture bright while on, grey while off, with the time left and "+5 min"),
 * Brightness right on the page, the On the Glyph row (Turns off after, Fade in, a note for Repeat, Brightness),
 * and "Shake twice for the torch", the same setting as on Battery and volume.
 */
@Composable
fun TorchFeature(nav: Nav, setup: Setup) {
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val on = active.any { it.id == TorchTileService.ID }
    val timing by AppGraph.features.timing(FeatureTiming.TORCH).collectAsStateWithLifecycle()
    val minutes by AppGraph.features.torchMinutes.collectAsStateWithLifecycle()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) { while (on) { clock = System.currentTimeMillis(); delay(1_000) } }
    var glyph by rememberSaveable { mutableStateOf(false) }
    // the sheet plays the plain fade; the rows show the light itself
    val fade = remember { torchPreview() }
    val lit = remember { Frame.blank(AppGraph.profile).fill(255) }

    FeatureLayout(Feature.TORCH, nav) {
        item(key = "main") {
            RowGroup {
                SwitchRow(
                    "Torch", if (on) torchLine(clock, offMs = TorchTileService.autoOffMs()) else "Off", on,
                    leading = { GlyphPreview(if (on) lit else Frame.blank(AppGraph.profile), if (on) "Torch on" else "Torch off") },
                ) { TorchTileService.set(it) }
                if (on && minutes > 0) {
                    RowDivider(leading = true)
                    ListRow(
                        "Keep it on longer", subtitle = "Adds 5 minutes before it turns off", inset = Tokens.Gutter,
                        trailing = { SmallButton("+5 min", "Keep the torch on 5 minutes longer") { TorchTileService.extend(); clock = System.currentTimeMillis() } },
                    )
                }
                RowDivider()
                OnTheGlyphRow(torchSummary(timing, minutes), Animation.single(lit), "The torch at ${timing.brightness}%") { glyph = true }
            }
        }
        item(key = "brightness") {
            SectionLabel("Brightness")
            ChipChoices(Timing.BRIGHTNESS_CHIPS, Timing.BRIGHTNESS_CHIPS.minBy { kotlin.math.abs(it - timing.brightness) }, { "$it%" }) {
                AppGraph.features.setTiming(FeatureTiming.TORCH, timing.copy(brightness = it))
                TorchTileService.restyle()
            }
            PartNote("Of your phone's Glyph brightness. On the Glyph sets it exactly.")
        }
        item(key = "shake") {
            RowGroup(Modifier.padding(top = Tokens.Section)) { ShakeTorchRows() }
        }
    }

    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, fade, "The torch fading on", { glyph = false },
        title = "On the Glyph", plainLine = torchSheetLine(timing, minutes),
        parts = TimingParts(
            showForTitle = "Turns off after",
            showForSlot = {
                ChipChoices(FeatureSettings.TORCH_CHIPS, minutes, ::minutesLabel) {
                    AppGraph.features.setTorchMinutes(it)
                    TorchTileService.restyle()
                }
            },
            speedTitle = "Fade in",
            speedSlot = {
                ChipChoices(Timing.SPEED_CHIPS, Timing.SPEED_CHIPS.minBy { kotlin.math.abs(it - timing.speed) }, { paceName(it, FADE_NAMES) }) {
                    AppGraph.features.setTiming(FeatureTiming.TORCH, timing.copy(speed = it))
                    TorchTileService.restyle()
                }
            },
            repeatSlot = { PartNote("It stays on until it turns off, or you turn it off.") },
            more = false,
            compactBrightness = true,
        ),
        onChange = {
            AppGraph.features.setTiming(FeatureTiming.TORCH, it)
            TorchTileService.restyle()
        },
    )
}

/** The fade's chips, in words. */
internal val FADE_NAMES = listOf("Slow", "Normal", "Quick")

/**
 * "Shake twice for the torch" and how hard the shake is (Gentle, Firm): one setting, shown on the Torch page and
 * on Battery and volume.
 */
@Composable
internal fun ShakeTorchRows() {
    val s = AppGraph.system
    val shake by s.shakeTorch.collectAsStateWithLifecycle()
    val firm by s.shakeTorchFirm.collectAsStateWithLifecycle()
    SwitchRow("Shake twice for the torch", "Shake the phone twice, quickly, to turn the torch on or off", shake) { s.setShakeTorch(it) }
    if (shake) androidx.compose.foundation.layout.Column(Modifier.padding(start = Tokens.Gutter, end = Tokens.Gutter, bottom = Tokens.Gap)) {
        Text("How hard", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(bottom = Tokens.Tiny))
        ChipChoices(listOf(false, true), firm, { if (it) "Firm" else "Gentle" }) { s.setShakeTorchFirm(it) }
        PartNote(if (firm) "Fewer torches by accident in a bag or pocket." else "Any quick double shake.")
    }
}

private fun minutesLabel(m: Int) = if (m == 0) "Never" else "$m min"

/** The row's line: "100% · off after 5 min", "100% · stays on". */
internal fun torchSummary(t: Timing, minutes: Int): String =
    listOf("${t.clamped().brightness}%", if (minutes == 0) "stays on" else "off after $minutes min").joinToString(" · ")

/** The sheet's plain line. */
internal fun torchSheetLine(t: Timing, minutes: Int): String {
    val c = t.clamped()
    val off = if (minutes == 0) "stays on until you turn it off" else "turns itself off after $minutes min"
    val fade = when {
        c.speed < 1f -> " slowly"
        c.speed > 1f -> " quickly"
        else -> ""
    }
    return "Fades on$fade to ${c.brightness}% and $off."
}

/** The fade at full brightness and 1x, a moment of light, then dark, so the sheet shows the fade each time round. */
private fun torchPreview(): Animation {
    val a = TorchLight.animation(AppGraph.profile, 100, 1f, 1_500)
    return Animation(a.profile, a.frames + com.dotforge.device.AnimFrame(com.dotforge.device.Frame.blank(a.profile), 600))
}
