package com.dotforge.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.BatteryEvents
import com.dotforge.system.ChargingStyle
import com.dotforge.system.FeatureTiming
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.speedText

/** The battery level the examples show. */
private const val SAMPLE_LEVEL = 68

const val VOLUME_GLYPH_TAG = "on-the-glyph-volume"

/**
 * Features > Battery and volume: when you plug in (the switch and its On the Glyph row, with the Fill or Level %
 * design and the fast-charge pulse in the sheet), when the volume changes (the same), and the shakes and
 * battery flashes under Also.
 */
@Composable
fun BatteryFeature(nav: Nav, setup: Setup) {
    val s = AppGraph.system
    val f = AppGraph.features
    val charging by s.chargingMeter.collectAsStateWithLifecycle()
    val volume by s.volume.collectAsStateWithLifecycle()
    val alarms by s.alarms.collectAsStateWithLifecycle()
    val wiggle by s.wiggle.collectAsStateWithLifecycle()
    val shakeTorch by s.shakeTorch.collectAsStateWithLifecycle()
    val firm by s.shakeTorchFirm.collectAsStateWithLifecycle()
    val chargeTiming by f.timing(FeatureTiming.CHARGING).collectAsStateWithLifecycle()
    val volumeTiming by f.timing(FeatureTiming.VOLUME).collectAsStateWithLifecycle()
    val style by f.chargingStyle.collectAsStateWithLifecycle()
    val pulse by f.fastPulse.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val chargeDesign = remember(style, pulse) { chargingPreview(style, pulse) }
    val volumeDesign = remember { volumePreview() }

    FeatureLayout(Feature.BATTERY, nav) {
        item(key = "main") {
            RowGroup {
                SwitchRow("When you plug in", "The battery level on the Glyph", charging) { s.setChargingMeter(it) }
                RowDivider()
                OnTheGlyphRow(
                    chargingSummary(chargeTiming, style, pulse), remember(chargeDesign, chargeTiming) { Timings.plan(chargeDesign, chargeTiming.copy(rest = false)).animation },
                    "The ${style.label} charging design", enabled = charging,
                ) { sheet = "charging" }
            }
        }
        item(key = "volume") {
            SectionLabel("Volume")
            RowGroup {
                SwitchRow("When the volume changes", "The media volume on the ring", volume) { s.setVolume(it) }
                RowDivider()
                OnTheGlyphRow(
                    volumeSummary(volumeTiming), remember(volumeTiming) { Timings.plan(volumeDesign, volumeTiming.copy(showFor = ShowFor.WHOLE)).animation },
                    "The volume ring", enabled = volume, tag = VOLUME_GLYPH_TAG,
                ) { sheet = "volume" }
            }
        }
        item(key = "also") {
            SectionLabel("Also")
            RowGroup {
                SwitchRow("Full and low battery", "The ring flashes at 100% while charging, and at ${BatteryEvents.LOW}%", alarms) { s.setAlarms(it) }
                RowDivider()
                SwitchRow("Shake to see the battery", "While charging, give the phone a shake", wiggle) { s.setWiggle(it) }
                RowDivider()
                SwitchRow("Shake twice for the torch", "Shake the phone twice, quickly, to turn the torch on or off", shakeTorch) { s.setShakeTorch(it) }
                if (shakeTorch) {
                    RowDivider()
                    SwitchRow("Needs a harder shake", "Fewer torches by accident in a bag or pocket", firm) { s.setShakeTorchFirm(it) }
                }
            }
        }
    }

    when (sheet) {
        "charging" -> TimingSheet(
            chargeTiming, TimingKind.ANIMATION, chargeDesign, "The charging design", { sheet = null },
            title = "On the Glyph", plainLine = chargingLine(chargeTiming, style),
            parts = TimingParts(
                whole = false, secondsChips = listOf(5, 10, 30), untilLabel = "While plugged in", exactTime = false,
                speedSlider = false,
                remindAlways = true, remindAfterSeconds = true, remindChips = listOf(5, 15), repeatTitle = "Repeat while charging",
                more = false, compactBrightness = true,
            ),
            extra = {
                Text("Design", Modifier.padding(top = Tokens.Section, bottom = Tokens.Small).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                    ChargingStyle.entries.forEach { st ->
                        val anim = remember(st) { chargingPreview(st, false) }
                        // the still is the held, finished design (a full ring, the level), not a half-drawn fill
                        Middle(st.label, st.hint, style == st, anim, anim.frames.first().frame) { f.setChargingStyle(st) }
                    }
                }
                SwitchRow("Pulse on a fast charger", "15 W or more: the design pulses twice", pulse, inset = 0.dp) { f.setFastPulse(it) }
            },
            onChange = { f.setTiming(FeatureTiming.CHARGING, it) },
        )
        "volume" -> TimingSheet(
            volumeTiming, TimingKind.ANIMATION, volumeDesign, "The volume ring", { sheet = null },
            title = "On the Glyph", plainLine = volumeLine(volumeTiming),
            parts = TimingParts(
                showForSlot = {
                    ChipChoices(VOLUME_SECONDS, volumeTiming.seconds, { "$it s" }) { f.setTiming(FeatureTiming.VOLUME, volumeTiming.copy(showFor = ShowFor.SECONDS, seconds = it)) }
                },
                speedTitle = "Slide speed",
                speedSlider = false,
                repeatSlot = { PartNote("It shows once for each change.") },
                more = false, compactBrightness = true,
            ),
            onChange = { f.setTiming(FeatureTiming.VOLUME, it) },
        )
    }
}

/** How long the volume ring shows, in seconds. */
internal val VOLUME_SECONDS = listOf(1, 2, 3, 5)

/**
 * The charging design for the sample level at 1x and full brightness (the sheet applies the timing), starting on
 * its full frame so a still preview shows the level, not the empty ring the fill starts from.
 */
private fun chargingPreview(style: ChargingStyle, fast: Boolean): Animation {
    val a = BatteryEvents.charging(AppGraph.profile, style, SAMPLE_LEVEL, fast)
    val full = a.frames.indexOfFirst { it.durationMs == BatteryEvents.HOLD_MS }.coerceAtLeast(0)
    return Animation(a.profile, a.frames.drop(full) + a.frames.take(full))
}

/** The ring at 11 (of 15), sliding down to 5, holding, then back up, so the preview shows the slide each time round. */
private fun volumePreview(): Animation {
    val p = AppGraph.profile
    val up = BatteryEvents.volumeSlide(p, 5, 11, 15).frames
    val down = BatteryEvents.volumeSlide(p, 11, 5, 15).frames
    // it starts on the raised level, so a still preview shows a level
    return Animation(p, listOf(AnimFrame(up.last().frame, 1_200)) + down.dropLast(1) + AnimFrame(down.last().frame, 1_200) + up.dropLast(1))
}

/** The charging row's line: "Level % · 5 s · 100%", "Fill · while plugged in · again every 5 min · pulse". */
internal fun chargingSummary(t: Timing, style: ChargingStyle, pulse: Boolean): String {
    val c = t.clamped()
    return listOfNotNull(
        style.label,
        if (c.untilChecked) "while plugged in" else "${c.seconds} s",
        "again every ${c.remindMinutes} min".takeIf { !c.untilChecked && c.remindMinutes > 0 },
        "${c.brightness}%",
        "fast pulse".takeIf { pulse },
    ).joinToString(" · ")
}

internal fun chargingLine(t: Timing, style: ChargingStyle): String {
    val c = t.clamped()
    val what = if (style == ChargingStyle.FILL) "The ring fills to the level" else "The ring and the number show"
    val long = if (c.untilChecked) "while it's plugged in" else "for ${c.seconds} s"
    val again = if (!c.untilChecked && c.remindMinutes > 0) " It shows again every ${c.remindMinutes} min while charging." else ""
    return "When you plug in: $what $long, at ${speedText(c.speed)}.$again"
}

/** The volume row's line: "2 s · slide 1x · 100%". */
internal fun volumeSummary(t: Timing): String {
    val c = t.clamped()
    return listOf("${c.seconds} s", "slide ${speedText(c.speed)}", "${c.brightness}%").joinToString(" · ")
}

internal fun volumeLine(t: Timing): String {
    val c = t.clamped()
    return "The ring slides to the new level at ${speedText(c.speed)} and shows for ${c.seconds} s."
}
