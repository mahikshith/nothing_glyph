package com.dotforge.ui.design

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.dotforge.device.Direction
import com.dotforge.device.Fade
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.system.NothingSettings
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** What a timing panel is for; it decides which parts and chips show. */
enum class TimingKind {
    /** A one-frame design: Show for, Brightness; More: Start delay, Fade. */
    STILL,
    /** An animation: Show for, Speed, Repeat, Brightness; More: Direction, Start delay, Fade. */
    ANIMATION,
    /** Scrolling text: Show for ("Until scrolled"), Speed, Repeat, Brightness. */
    TEXT,
    /** A design used for an app or person: the same parts, each with a "Design's own" chip first. */
    USE,
}

/**
 * The one timing panel: Show for, Speed, Repeat and Brightness always; Direction, Start delay and Fade under
 * More. Each part has preset chips, and a slider where an exact value matters. [onChange] fires on every change
 * so the caller can preview live (on screen and, with Show on phone, on the Glyph).
 *
 * For a place that uses a design, pass the design's own timing as [base] and the place's name as [placeName]:
 * each part then starts with a "Design's own (…)" chip, and a "Changed for WhatsApp · Reset" line appears once
 * anything differs. The caller stores [com.dotforge.device.TimingOverride.diff] of base and the result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingPanel(
    timing: Timing,
    kind: TimingKind,
    modifier: Modifier = Modifier,
    base: Timing? = null,
    placeName: String? = null,
    animated: Boolean = kind != TimingKind.STILL,
    onChange: (Timing) -> Unit,
) {
    val t = timing.clamped()
    val b = base?.clamped()
    val ctx = LocalContext.current
    var more by rememberSaveable { mutableStateOf(t.moreChanged) }

    Column(modifier.fillMaxWidth()) {
        if (b != null && placeName != null && b != t) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Changed for $placeName", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                TextButton(onClick = { onChange(b) }) { Text("Reset", style = MaterialTheme.typography.labelLarge, color = Color.White) }
            }
        }

        // ---- Show for
        Part("Show for", showForText(t, kind))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            if (b != null) OwnChip(showForText(b, kind), sameShowFor(b, t)) { onChange(t.copy(showFor = b.showFor, seconds = b.seconds, remindMinutes = b.remindMinutes)) }
            val own = b != null && sameShowFor(b, t)
            if (kind != TimingKind.USE) {
                Chip(wholeLabel(kind), !own && t.showFor == ShowFor.WHOLE) { onChange(t.copy(showFor = ShowFor.WHOLE)) }
            }
            Timing.SECONDS_CHIPS.filterNot { b?.showFor == ShowFor.SECONDS && b.seconds == it }.forEach { s ->
                Chip("$s s", !own && t.showFor == ShowFor.SECONDS && t.seconds == s) { onChange(t.copy(showFor = ShowFor.SECONDS, seconds = s)) }
            }
            Chip("Until I check", !own && t.showFor == ShowFor.UNTIL_CHECKED) { onChange(t.copy(showFor = ShowFor.UNTIL_CHECKED)) }
        }
        when (t.showFor) {
            ShowFor.SECONDS -> ExactSlider("Seconds", t.seconds.toFloat(), 1f..60f, 58) { onChange(t.copy(seconds = it.roundToInt())) }
            ShowFor.UNTIL_CHECKED -> {
                Text("Remind me", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Chip("Off", t.remindMinutes == 0) { onChange(t.copy(remindMinutes = 0)) }
                    Timing.REMIND_CHIPS.forEach { m -> Chip("Every $m min", t.remindMinutes == m) { onChange(t.copy(remindMinutes = m)) } }
                }
            }
            ShowFor.WHOLE -> Unit
        }

        // ---- Speed
        if (animated) {
            Part("Speed", speedText(t.speed))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                if (b != null) OwnChip(speedText(b.speed), b.speed == t.speed) { onChange(t.copy(speed = b.speed)) }
                val own = b != null && b.speed == t.speed
                Timing.SPEED_CHIPS.forEachIndexed { i, s ->
                    if (b?.speed == s) return@forEachIndexed
                    Chip("${Timing.SPEED_NAMES[i]} ${speedText(s)}", !own && t.speed == s) { onChange(t.copy(speed = s)) }
                }
            }
            ExactSlider("Speed", t.speed, Timing.MIN_SPEED..Timing.MAX_SPEED, 14, speedText(t.speed)) { onChange(t.copy(speed = (it * 4).roundToInt() / 4f)) }

            // ---- Repeat
            Part("Repeat", repeatText(t))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                if (b != null) OwnChip(repeatText(b), b.repeat == t.repeat && b.times == t.times) { onChange(t.copy(repeat = b.repeat, times = b.times)) }
                val own = b != null && b.repeat == t.repeat && b.times == t.times
                if (b?.repeat != Repeat.ONCE) Chip("Once", !own && t.repeat == Repeat.ONCE) { onChange(t.copy(repeat = Repeat.ONCE)) }
                Chip("${t.times} times", !own && t.repeat == Repeat.TIMES) { onChange(t.copy(repeat = Repeat.TIMES)) }
                if (b?.repeat != Repeat.FOREVER) Chip("Forever", !own && t.repeat == Repeat.FOREVER) { onChange(t.copy(repeat = Repeat.FOREVER)) }
            }
            if (t.repeat == Repeat.TIMES) ExactSlider("Times", t.times.toFloat(), 2f..10f, 7) { onChange(t.copy(times = it.roundToInt())) }
            if (t.untilChecked) {
                Text("While it waits", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Chip("Rest after ${Timing.REST_LOOPS} loops", t.rest) { onChange(t.copy(rest = true)) }
                    Chip("Keep moving", !t.rest) { onChange(t.copy(rest = false)) }
                }
            }
        }

        // ---- Brightness
        Part("Brightness", "${t.brightness}%")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            if (b != null) OwnChip("${b.brightness}%", b.brightness == t.brightness) { onChange(t.copy(brightness = b.brightness)) }
            val own = b != null && b.brightness == t.brightness
            Timing.BRIGHTNESS_CHIPS.filterNot { it == b?.brightness }.forEach { v -> Chip("$v%", !own && t.brightness == v) { onChange(t.copy(brightness = v)) } }
        }
        ExactSlider("Brightness", t.brightness.toFloat(), 10f..100f, 0, "${t.brightness}%") { onChange(t.copy(brightness = it.roundToInt())) }
        Hint("100% is your Glyph brightness in Nothing settings.")
        ListRow("Phone's Glyph brightness", inset = 0.dp, trailing = { Chevron() }) {
            runCatching { ctx.startActivity(NothingSettings.glyphSettings(ctx.packageManager).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        // ---- More
        ListRow("More", subtitle = moreText(t, animated), inset = 0.dp, trailing = { Text(if (more) "Hide" else "Show", style = MaterialTheme.typography.labelLarge) }) { more = !more }
        if (more) {
            if (animated) {
                Text("Direction", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Direction.entries.forEach { d -> Chip(d.label, t.direction == d) { onChange(t.copy(direction = d)) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Tokens.Small)) {
                Text("Start delay", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                Text(delayText(t.startDelayMs), style = MaterialTheme.typography.bodyMedium)
            }
            ExactSlider("Start delay", t.startDelayMs / 1000f, 0f..5f, 9, delayText(t.startDelayMs)) { onChange(t.copy(startDelayMs = (it * 2).roundToInt() * 500)) }
            Text("Fade in and out", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Fade.entries.forEach { f -> Chip(f.label, t.fade == f) { onChange(t.copy(fade = f)) } }
            }
        }
    }
}

private fun sameShowFor(a: Timing, b: Timing) =
    a.showFor == b.showFor && (a.showFor != ShowFor.SECONDS || a.seconds == b.seconds) && (a.showFor != ShowFor.UNTIL_CHECKED || a.remindMinutes == b.remindMinutes)

@Composable
private fun OwnChip(value: String, selected: Boolean, onClick: () -> Unit) = Chip("Design's own ($value)", selected, onClick = onClick)

@Composable
private fun Part(title: String, value: String) =
    Row(Modifier.fillMaxWidth().padding(top = Tokens.Gutter, bottom = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
    }

@Composable
private fun ExactSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, readout: String? = null, onChange: (Float) -> Unit) =
    Slider(
        value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            readout?.let { stateDescription = it }
        },
        colors = SliderDefaults.colors(
            thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Tokens.Raised,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        ),
    )

/** "1x", "1.5x", "0.25x". */
fun speedText(s: Float): String = if (s == s.roundToInt().toFloat()) "${s.roundToInt()}x" else "${s}x"

fun delayText(ms: Int): String = if (ms == 0) "None" else if (ms % 1000 == 0) "${ms / 1000} s" else "${ms / 1000f} s"

private fun wholeLabel(kind: TimingKind) = when (kind) {
    TimingKind.TEXT -> "Until scrolled"
    TimingKind.STILL -> "A moment"
    else -> "Whole animation"
}

fun showForText(t: Timing, kind: TimingKind): String = when (t.showFor) {
    ShowFor.WHOLE -> wholeLabel(kind)
    ShowFor.SECONDS -> "${t.seconds} s"
    ShowFor.UNTIL_CHECKED -> if (t.remindMinutes > 0) "Until I check · remind every ${t.remindMinutes} min" else "Until I check"
}

fun repeatText(t: Timing): String = when (t.repeat) {
    Repeat.ONCE -> "Once"
    Repeat.TIMES -> "${t.times} times"
    Repeat.FOREVER -> "Forever"
}

private fun moreText(t: Timing, animated: Boolean): String =
    listOfNotNull(
        t.direction.label.takeIf { animated },
        "Delay ${delayText(t.startDelayMs).lowercase()}",
        "Fade ${t.fade.label.lowercase()}",
    ).joinToString(" · ")

/** "1x · 10 s · 100%": the status chip next to the Timing button, and the line in rows. */
fun timingSummary(t: Timing, animated: Boolean): String =
    listOfNotNull(speedText(t.speed).takeIf { animated }, showForText(t.clamped(), if (animated) TimingKind.ANIMATION else TimingKind.STILL), "${t.brightness}%")
        .joinToString(" · ")
