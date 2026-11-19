package com.dotforge.ui.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Timing
import com.dotforge.system.FeatureSettings
import com.dotforge.system.FeatureTiming
import com.dotforge.tile.TorchLight
import com.dotforge.tile.TorchTileService
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.speedText
import com.dotforge.ui.home.torchLine
import kotlinx.coroutines.delay

/**
 * Features > Torch: the switch (with the time left before it turns itself off) and the On the Glyph row:
 * Turns off after (1, 5, 10 or 30 min, or Never), Fade in, Repeat (a note: it stays on) and Brightness.
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
    // the sheet applies the timing to the plain fade; the row shows it applied
    val fade = remember { torchPreview() }
    val preview = remember(timing) { com.dotforge.device.Timings.plan(fade, timing).animation }

    FeatureLayout(Feature.TORCH, nav) {
        item(key = "main") {
            RowGroup {
                SwitchRow("Torch", if (on) torchLine(clock, offMs = TorchTileService.autoOffMs()) else "Off", on) { TorchTileService.set(it) }
                RowDivider()
                OnTheGlyphRow(torchSummary(timing, minutes), preview, "The torch at ${timing.brightness}%") { glyph = true }
            }
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
            speedSlider = false,
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

private fun minutesLabel(m: Int) = if (m == 0) "Never" else "$m min"

/** The row's line: "100% · off after 5 min · fade 1x". */
internal fun torchSummary(t: Timing, minutes: Int): String =
    listOf("${t.clamped().brightness}%", if (minutes == 0) "stays on" else "off after $minutes min", "fade ${speedText(t.clamped().speed)}").joinToString(" · ")

/** The sheet's plain line. */
internal fun torchSheetLine(t: Timing, minutes: Int): String {
    val c = t.clamped()
    val off = if (minutes == 0) "stays on until you turn it off" else "turns itself off after $minutes min"
    return "Fades on at ${speedText(c.speed)} to ${c.brightness}% and $off."
}

/** The fade at full brightness and 1x, a moment of light, then dark, so the preview shows the fade each time round. */
private fun torchPreview(): Animation {
    val a = TorchLight.animation(AppGraph.profile, 100, 1f, 1_500)
    return Animation(a.profile, a.frames + com.dotforge.device.AnimFrame(com.dotforge.device.Frame.blank(a.profile), 600))
}
