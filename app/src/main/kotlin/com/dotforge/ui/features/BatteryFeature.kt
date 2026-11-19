package com.dotforge.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.dotforge.system.VolumeStyle
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.paceName
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens

/** The battery level the examples show. */
private const val SAMPLE_LEVEL = 68

const val VOLUME_GLYPH_TAG = "on-the-glyph-volume"

/** What Nothing's own lights may do alongside DotForge's. */
const val NOTHING_LIGHTS = "Nothing's own charging and volume lights may also show."

/**
 * Features > Battery and volume: Battery (when you plug in: the switch and its On the Glyph row, the sheet
 * "Charging lights" with the design first: Fill, Battery level or My design, and the fast-charge pulse), Volume
 * (the same, "Volume lights": the ring or My design), the note on Nothing's own lights, and Also (full and low,
 * the shakes; Shake twice for the torch is the same setting as on the Torch page).
 */
@Composable
fun BatteryFeature(nav: Nav, setup: Setup) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s = AppGraph.system
    val f = AppGraph.features
    val charging by s.chargingMeter.collectAsStateWithLifecycle()
    val volume by s.volume.collectAsStateWithLifecycle()
    val alarms by s.alarms.collectAsStateWithLifecycle()
    val wiggle by s.wiggle.collectAsStateWithLifecycle()
    val chargeTiming by f.timing(FeatureTiming.CHARGING).collectAsStateWithLifecycle()
    val volumeTiming by f.timing(FeatureTiming.VOLUME).collectAsStateWithLifecycle()
    val style by f.chargingStyle.collectAsStateWithLifecycle()
    val volumeStyle by f.volumeStyle.collectAsStateWithLifecycle()
    val chargingDesign by f.chargingDesign.collectAsStateWithLifecycle()
    val volumeDesign by f.volumeDesign.collectAsStateWithLifecycle()
    val pulse by f.fastPulse.collectAsStateWithLifecycle()
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val chargeMine = remember(chargingDesign, designs) { AppGraph.designAnimation(chargingDesign) }
    val volumeMine = remember(volumeDesign, designs) { AppGraph.designAnimation(volumeDesign) }
    val chargeDesign = remember(style, pulse, chargeMine) { chargingPreview(style, pulse, chargeMine) }
    val volumeShown = remember(volumeStyle, volumeMine) { if (volumeStyle == VolumeStyle.MINE && volumeMine != null) volumeMine else volumePreview() }

    FeatureLayout(Feature.BATTERY, nav) {
        item(key = "main") {
            SectionLabel("Battery")
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
                SwitchRow("When the volume changes", "The media volume on the Glyph", volume) { s.setVolume(it) }
                RowDivider()
                OnTheGlyphRow(
                    volumeSummary(volumeTiming, volumeStyle), remember(volumeShown, volumeTiming) { Timings.plan(volumeShown, volumeTiming.copy(showFor = ShowFor.WHOLE)).animation },
                    "The volume design", enabled = volume, tag = VOLUME_GLYPH_TAG,
                ) { sheet = "volume" }
            }
            StatusBanner(NOTHING_LIGHTS, Modifier.padding(top = Tokens.Gap), button = "Turn off", info = true) {
                runCatching { ctx.startActivity(com.dotforge.system.NothingSettings.glyphSettings(ctx.packageManager).intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        item(key = "also") {
            SectionLabel("Also")
            RowGroup {
                SwitchRow("Full and low battery", "The ring flashes at 100% while charging, and at ${BatteryEvents.LOW}%", alarms) { s.setAlarms(it) }
                RowDivider()
                SwitchRow("Shake to see the battery", "While charging, give the phone a shake", wiggle) { s.setWiggle(it) }
                RowDivider()
                ShakeTorchRows()
            }
        }
    }

    when (sheet) {
        "charging" -> TimingSheet(
            chargeTiming, TimingKind.ANIMATION, chargeDesign, "The charging design", { sheet = null },
            title = "Charging lights", plainLine = chargingLine(chargeTiming, style),
            parts = TimingParts(
                whole = false, secondsChips = listOf(5, 10, 30), untilLabel = "While plugged in", exactTime = false,
                speedSlider = false,
                remindAlways = true, remindAfterSeconds = true, remindChips = listOf(5, 15), repeatTitle = "Repeat while charging",
                more = false, compactBrightness = true,
            ),
            header = {
                Text("Design", Modifier.padding(bottom = Tokens.Small).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                val tiles = ChargingStyle.entries
                tiles.chunked(2).forEachIndexed { r, row ->
                    Row(Modifier.padding(top = if (r > 0) Tokens.Gap else 0.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                        row.forEach { st ->
                            val anim = remember(st, chargeMine) { chargingPreview(st, false, chargeMine) }
                            val empty = st == ChargingStyle.MINE && chargeMine == null
                            // the still is the finished design (the filled ring, the level), not a half-drawn fill
                            Middle(st.label, if (empty) "Make a design in Create first" else st.hint, style == st, anim, anim.frames.first().frame, description = "${st.label} design") {
                                if (st != ChargingStyle.MINE || designs.isNotEmpty()) f.setChargingStyle(st)
                                if (st == ChargingStyle.MINE && designs.isNotEmpty()) pick = "charging"
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (style == ChargingStyle.MINE && designs.isNotEmpty()) DesignRow(chargingDesign) { pick = "charging" }
                SwitchRow("Pulse on a fast charger", "15 W or more: the design pulses twice", pulse, inset = 0.dp) { f.setFastPulse(it) }
            },
            onChange = { f.setTiming(FeatureTiming.CHARGING, it) },
        )
        "volume" -> TimingSheet(
            volumeTiming, TimingKind.ANIMATION, volumeShown, "The volume design", { sheet = null },
            title = "Volume lights", plainLine = volumeLine(volumeTiming, volumeStyle),
            parts = TimingParts(
                showForSlot = {
                    ChipChoices(VOLUME_SECONDS, volumeTiming.seconds, { "$it s" }) { f.setTiming(FeatureTiming.VOLUME, volumeTiming.copy(showFor = ShowFor.SECONDS, seconds = it)) }
                },
                speedTitle = if (volumeStyle == VolumeStyle.MINE) "Speed" else "Slide",
                speedSlot = {
                    ChipChoices(Timing.SPEED_CHIPS, Timing.SPEED_CHIPS.minBy { kotlin.math.abs(it - volumeTiming.speed) }, { paceName(it) }) {
                        f.setTiming(FeatureTiming.VOLUME, volumeTiming.copy(speed = it))
                    }
                },
                repeatSlot = { PartNote("It shows once for each change.") },
                more = false, compactBrightness = true,
            ),
            header = {
                Text("Design", Modifier.padding(bottom = Tokens.Small).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                    VolumeStyle.entries.forEach { st ->
                        val anim = remember(st, volumeMine) { if (st == VolumeStyle.MINE) volumeMine ?: Animation.single(com.dotforge.device.Frame.blank(AppGraph.profile)) else volumePreview() }
                        val empty = st == VolumeStyle.MINE && volumeMine == null
                        Middle(st.label, if (empty) "Make a design in Create first" else st.hint, volumeStyle == st, anim, description = "${st.label} design") {
                            if (st != VolumeStyle.MINE || designs.isNotEmpty()) f.setVolumeStyle(st)
                            if (st == VolumeStyle.MINE && designs.isNotEmpty()) pick = "volume"
                        }
                    }
                }
                if (volumeStyle == VolumeStyle.MINE && designs.isNotEmpty()) DesignRow(volumeDesign) { pick = "volume" }
            },
            onChange = { f.setTiming(FeatureTiming.VOLUME, it) },
        )
    }
    when (pick) {
        "charging" -> MyDesignSheet("Design when you plug in", chargingDesign, { pick = null }) { f.setChargingDesign(it); pick = null }
        "volume" -> MyDesignSheet("Design when the volume changes", volumeDesign, { pick = null }) { f.setVolumeDesign(it); pick = null }
    }
}

/** "Design: Heart ›": which of your designs plays, and a way to change it. */
@Composable
private fun DesignRow(id: String?, onClick: () -> Unit) {
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    val d = designs.firstOrNull { it.id == id } ?: designs.firstOrNull() ?: return
    ListRow(
        "Design", Modifier.padding(top = Tokens.Small), subtitle = d.name, inset = 0.dp,
        leading = { GlyphPreview(remember(d.id) { AppGraph.designs.animation(d)?.let(com.dotforge.device.Posters::of) }, d.name) },
        trailing = { Chevron() }, onClick = onClick,
    )
}

/** How long the volume ring shows, in seconds. */
internal val VOLUME_SECONDS = listOf(1, 2, 3, 5)

/**
 * The charging design for the sample level at 1x and full brightness (the sheet applies the timing), starting on
 * its full frame so a still preview shows the level, not the empty ring the fill starts from.
 */
private fun chargingPreview(style: ChargingStyle, fast: Boolean, mine: Animation? = null): Animation {
    val a = BatteryEvents.charging(AppGraph.profile, style, SAMPLE_LEVEL, fast, mine)
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

/** The charging row's line: "Battery level · 5 s · 100%", "Fill · while plugged in · 100% · fast pulse". */
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
    val what = when (style) {
        ChargingStyle.FILL -> "The ring fills to the level"
        ChargingStyle.LEVEL -> "The ring and the number show"
        ChargingStyle.MINE -> "Your design plays"
    }
    val long = if (c.untilChecked) "while it's plugged in" else "for ${c.seconds} s"
    val again = if (!c.untilChecked && c.remindMinutes > 0) " It shows again every ${c.remindMinutes} min while charging." else ""
    return "When you plug in: $what $long.$again"
}

/** The volume row's line: "Ring · 2 s · 100%". */
internal fun volumeSummary(t: Timing, style: VolumeStyle = VolumeStyle.RING): String {
    val c = t.clamped()
    return listOf(style.label, "${c.seconds} s", "${c.brightness}%").joinToString(" · ")
}

internal fun volumeLine(t: Timing, style: VolumeStyle = VolumeStyle.RING): String {
    val c = t.clamped()
    return if (style == VolumeStyle.MINE) "Your design plays for ${c.seconds} s each time the volume changes."
    else "The ring slides to the new level and shows for ${c.seconds} s."
}
