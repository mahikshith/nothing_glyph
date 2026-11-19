package com.dotforge.ui.design

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dotforge.device.Animation
import com.dotforge.device.Direction
import com.dotforge.device.Fade
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.NothingSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a timing panel is for; it decides which parts and chips show. */
enum class TimingKind {
    /** A one-frame design: Show for, Brightness; More: Start delay, Fade. */
    STILL,
    /** An animation: Show for, Speed, Repeat, Brightness; More: Direction, Start delay, Fade. */
    ANIMATION,
    /** Scrolling text: Show for ("Until scrolled"), Speed, Repeat, Brightness; direction lives in the Text screen. */
    TEXT,
    /** A design used for an app or person: the same parts, each with a "Design's own" chip first. */
    USE,
}

/**
 * Which parts a timing panel shows and what they are called, for a page that uses the panel for one thing (a
 * timer's finish, a done animation). The defaults are the full panel.
 */
data class TimingParts(
    val showForTitle: String = "Show for",
    /** The "Whole animation" chip. */
    val whole: Boolean = true,
    /** Seconds chips (60 reads "1 min"). */
    val secondsChips: List<Int> = Timing.SECONDS_CHIPS,
    val untilLabel: String = "Until I check",
    /** The exact time slider under Show for. */
    val exactTime: Boolean = true,
    val remindTitle: String = "Remind me",
    val remindChips: List<Int> = Timing.REMIND_CHIPS,
    /** "Remind me" follows a run of seconds (it flashes again) instead of "Until I check". */
    val remindAfterSeconds: Boolean = false,
    /** One "plays" part instead of Show for and Repeat: Once, this many times, Until I check. */
    val playsTimes: Int? = null,
    val speed: Boolean = true,
    val speedTitle: String = "Speed",
    val speedSlider: Boolean = true,
    val forever: Boolean = true,
    val more: Boolean = true,
    /** Brightness heads the panel (it covers more than this one part). */
    val brightnessFirst: Boolean = false,
    /** The first part keeps a full gap above it (the panel follows other controls in its card). */
    val gapAbove: Boolean = false,
)

/**
 * The one timing panel: Show for, Speed, Repeat and Brightness; Direction, Start delay and Fade under More.
 * Each part has preset chips and a slider where an exact value matters. [onChange] fires on every change so the
 * caller can preview live (on screen and, with Show on phone, on the Glyph).
 *
 * Repeat only means something when the whole animation plays (Show for "Whole animation"), so it shows only
 * then. "Until I check" animations rest after 3 loops by themselves (How it works says so).
 *
 * For a place that uses a design, pass the design's own timing as [base] and the place's name as [placeName]:
 * each part then starts with a "Design's own (…)" chip, and a "Changed for WhatsApp · Reset" line appears once
 * anything differs. The caller stores [com.dotforge.device.TimingOverride.diff] of base and the result.
 * [animationMs] (one pass of the design) lets the plain line at the top say how long it plays.
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
    animationMs: Int? = null,
    showLine: Boolean = true,
    parts: TimingParts = TimingParts(),
    onChange: (Timing) -> Unit,
) {
    val t = timing.clamped()
    val b = base?.clamped()
    val ctx = LocalContext.current
    var more by rememberSaveable { mutableStateOf(t.moreChanged) }
    val chips = Arrangement.spacedBy(Tokens.Small)

    Column(modifier.fillMaxWidth()) {
        if (b != null && placeName != null && b != t) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Changed for $placeName", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                TextButton(onClick = { onChange(b) }) { Text("Reset", style = MaterialTheme.typography.labelLarge, color = Color.White) }
            }
        }
        val brightness: @Composable (Boolean) -> Unit = { first ->
            Part("Brightness", first = first)
            FlowRow(horizontalArrangement = chips) {
                if (b != null) OwnChip("${b.brightness}%", b.brightness == t.brightness) { onChange(t.copy(brightness = b.brightness)) }
                val own = b != null && b.brightness == t.brightness
                Timing.BRIGHTNESS_CHIPS.filterNot { it == b?.brightness }.forEach { v -> Chip("$v%", !own && t.brightness == v) { onChange(t.copy(brightness = v)) } }
            }
            SliderLine("Exact brightness", "${t.brightness}%")
            DotSlider("Brightness", t.brightness.toFloat(), 10f..100f, readout = "${t.brightness}%") { onChange(t.copy(brightness = it.roundToInt())) }
            Hint("100% is your Glyph brightness in Nothing settings.")
            ListRow("Phone's Glyph brightness", inset = 0.dp, trailing = { Chevron() }) {
                runCatching { ctx.startActivity(NothingSettings.glyphSettings(ctx.packageManager).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        if (showLine) Text(timingLine(t, kind, animated, animationMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Tiny))
        if (parts.brightnessFirst) brightness(!showLine && !parts.gapAbove)

        // ---- Show for
        Part(parts.showForTitle, first = !showLine && !parts.brightnessFirst && !parts.gapAbove)
        if (parts.playsTimes != null) FlowRow(horizontalArrangement = chips) {
            val n = parts.playsTimes
            Chip("Once", t.showFor == ShowFor.WHOLE && t.repeat == Repeat.ONCE) { onChange(t.copy(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE)) }
            Chip("$n times", t.showFor == ShowFor.WHOLE && t.repeat == Repeat.TIMES) { onChange(t.copy(showFor = ShowFor.WHOLE, repeat = Repeat.TIMES, times = n)) }
            Chip(parts.untilLabel, t.showFor == ShowFor.UNTIL_CHECKED) { onChange(t.copy(showFor = ShowFor.UNTIL_CHECKED)) }
        } else FlowRow(horizontalArrangement = chips) {
            if (b != null) OwnChip(showForText(b, kind), sameShowFor(b, t)) { onChange(t.copy(showFor = b.showFor, seconds = b.seconds, remindMinutes = b.remindMinutes)) }
            val own = b != null && sameShowFor(b, t)
            if (kind != TimingKind.USE && parts.whole) {
                Chip(wholeLabel(kind), !own && t.showFor == ShowFor.WHOLE) { onChange(t.copy(showFor = ShowFor.WHOLE)) }
            }
            parts.secondsChips.filterNot { b?.showFor == ShowFor.SECONDS && b.seconds == it }.forEach { s ->
                Chip(if (s % 60 == 0) "${s / 60} min" else "$s s", !own && t.showFor == ShowFor.SECONDS && t.seconds == s) { onChange(t.copy(showFor = ShowFor.SECONDS, seconds = s)) }
            }
            Chip(parts.untilLabel, !own && t.showFor == ShowFor.UNTIL_CHECKED) { onChange(t.copy(showFor = ShowFor.UNTIL_CHECKED)) }
        }
        val remindAt = if (parts.remindAfterSeconds) ShowFor.SECONDS else ShowFor.UNTIL_CHECKED
        if (parts.playsTimes == null && t.showFor == remindAt) {
            Label(parts.remindTitle)
            FlowRow(horizontalArrangement = chips) {
                Chip("Off", t.remindMinutes == 0) { onChange(t.copy(remindMinutes = 0)) }
                parts.remindChips.forEach { m -> Chip("Every $m min", t.remindMinutes == m) { onChange(t.copy(remindMinutes = m)) } }
            }
        }
        if (parts.playsTimes == null && parts.exactTime && t.showFor != ShowFor.UNTIL_CHECKED) {
            // the slider shows the time it shows for now (the whole design's length too) and follows the chips;
            // moving it picks an exact number of seconds
            val shownMs = if (t.showFor == ShowFor.SECONDS) t.seconds * 1000 else (animationMs ?: 1000).coerceAtLeast(100)
            // "Show for" heads the chips above; the slider is the exact time
            SliderLine("Exact time", secondsText(shownMs))
            DotSlider("Exact time, seconds", (shownMs / 1000f).coerceIn(1f, 60f), 1f..60f, steps = 58, readout = secondsText(shownMs)) {
                onChange(t.copy(showFor = ShowFor.SECONDS, seconds = it.roundToInt()))
            }
        }

        // ---- Speed
        if (animated && parts.speed) {
            Part(parts.speedTitle)
            FlowRow(horizontalArrangement = chips) {
                if (b != null) OwnChip(speedText(b.speed), b.speed == t.speed) { onChange(t.copy(speed = b.speed)) }
                val own = b != null && b.speed == t.speed
                Timing.SPEED_CHIPS.forEachIndexed { i, s ->
                    if (b?.speed == s) return@forEachIndexed
                    Chip("${Timing.SPEED_NAMES[i]} ${speedText(s)}", !own && t.speed == s) { onChange(t.copy(speed = s)) }
                }
            }
            if (parts.speedSlider) {
                SliderLine("Exact speed", speedText(t.speed))
                DotSlider(
                    "Speed", speedStop(t.speed).toFloat(), 0f..(Timing.SPEED_STOPS.size - 1).toFloat(), steps = Timing.SPEED_STOPS.size - 2,
                    readout = speedText(t.speed), mark = Timing.SPEED_STOPS.indexOf(1f).toFloat(),
                ) { onChange(t.copy(speed = Timing.SPEED_STOPS[it.roundToInt().coerceIn(Timing.SPEED_STOPS.indices)])) }
            }
        }
        if (animated) {
            // ---- Repeat: only when the whole animation plays
            if (t.showFor == ShowFor.WHOLE && parts.playsTimes == null) {
                Part("Repeat")
                FlowRow(horizontalArrangement = chips) {
                    if (b != null) OwnChip(repeatText(b), b.repeat == t.repeat && b.times == t.times) { onChange(t.copy(repeat = b.repeat, times = b.times)) }
                    val own = b != null && b.repeat == t.repeat && b.times == t.times
                    if (b?.repeat != Repeat.ONCE) Chip("Once", !own && t.repeat == Repeat.ONCE) { onChange(t.copy(repeat = Repeat.ONCE)) }
                    Chip(if (t.repeat == Repeat.TIMES) "${t.times} times" else "2–10 times", !own && t.repeat == Repeat.TIMES) { onChange(t.copy(repeat = Repeat.TIMES)) }
                    if (b?.repeat != Repeat.FOREVER && parts.forever) Chip("Forever", !own && t.repeat == Repeat.FOREVER) { onChange(t.copy(repeat = Repeat.FOREVER)) }
                }
                if (t.repeat == Repeat.TIMES) {
                    SliderLine("Times", "${t.times}")
                    DotSlider("Repeat, times", t.times.toFloat(), 2f..10f, steps = 7, readout = "${t.times} times") { onChange(t.copy(times = it.roundToInt())) }
                }
            }
        }

        if (!parts.brightnessFirst) brightness(parts.brightnessFirst)

        // ---- More
        if (!parts.more) return@Column
        val turn by animateFloatAsState(if (more) 180f else 0f, Tokens.standard(LocalReduceMotion.current), label = "more")
        ListRow(
            "More", subtitle = moreText(t, animated && kind != TimingKind.TEXT), inset = 0.dp,
            trailing = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = if (more) "Hide" else "Show", tint = Tokens.Secondary, modifier = Modifier.rotate(turn)) },
        ) { more = !more }
        if (more) {
            if (animated && kind != TimingKind.TEXT) {
                Label("Direction")
                FlowRow(horizontalArrangement = chips) {
                    Direction.entries.forEach { d -> Chip(d.label, t.direction == d) { onChange(t.copy(direction = d)) } }
                }
            }
            SliderLine("Start delay", delayText(t.startDelayMs))
            DotSlider("Start delay", t.startDelayMs / 1000f, 0f..5f, steps = 9, readout = delayText(t.startDelayMs)) { onChange(t.copy(startDelayMs = (it * 2).roundToInt() * 500)) }
            Label("Fade in and out")
            FlowRow(horizontalArrangement = chips) {
                Fade.entries.forEach { f -> Chip(f.label, t.fade == f) { onChange(t.copy(fade = f)) } }
            }
        }
    }
}

/**
 * The timing sheet: opens at half height with the design playing live (with this timing) above the panel,
 * under a pinned header. [animation] is the design; null leaves the preview out, when the host already shows one.
 */
@Composable
fun TimingSheet(
    timing: Timing,
    kind: TimingKind,
    animation: Animation?,
    name: String,
    onDismiss: () -> Unit,
    base: Timing? = null,
    placeName: String? = null,
    onChange: (Timing) -> Unit,
) {
    val animated = kind != TimingKind.STILL && (animation?.frames?.size ?: 2) > 1
    val live = animation?.let { Timings.plan(it, timing).animation }
    // on a short screen the preview shrinks and sits beside the plain line, so the first controls show at half height
    val short = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP
    val line = timingLine(timing, kind, animated, animation?.totalMs)
    // no subtitle: the plain line already says it
    FullSheet(
        onDismiss, "Timing", half = true,
        pinned = live?.let { a ->
            {
                if (short) Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphPreview(a, name, Modifier.size(SHORT_PREVIEW), size = PreviewSize.MEDIUM)
                    Text(line, Modifier.padding(start = Tokens.Gutter), style = MaterialTheme.typography.bodyMedium)
                } else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GlyphPreview(a, name, size = PreviewSize.MEDIUM) }
            }
        },
    ) {
        TimingPanel(
            timing, kind, base = base, placeName = placeName, animated = animated, animationMs = animation?.totalMs,
            showLine = !(short && live != null), onChange = onChange,
        )
    }
}

/** Below this screen height (dp) the timing sheet's preview shrinks to [SHORT_PREVIEW]. */
private const val SHORT_SCREEN_DP = 700
private val SHORT_PREVIEW = 64.dp

private fun sameShowFor(a: Timing, b: Timing) =
    a.showFor == b.showFor && (a.showFor != ShowFor.SECONDS || a.seconds == b.seconds) && (a.showFor != ShowFor.UNTIL_CHECKED || a.remindMinutes == b.remindMinutes)

@Composable
private fun OwnChip(value: String, selected: Boolean, onClick: () -> Unit) = Chip("Design's own ($value)", selected, onClick = onClick)

/** A part's title. The chips under it show what is picked, so no value is repeated on the right. */
@Composable
private fun Part(title: String, first: Boolean = false) =
    Text(title, Modifier.fillMaxWidth().padding(top = if (first) Tokens.Tiny else Tokens.Section, bottom = Tokens.Tiny), style = MaterialTheme.typography.titleMedium)

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gap))

/** The line over a slider: what it sets, and its exact value on the right. */
@Composable
private fun SliderLine(label: String, value: String) =
    Row(Modifier.fillMaxWidth().padding(top = Tokens.Gap)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }

/** The slider stop nearest to [speed]. */
fun speedStop(speed: Float): Int = Timing.SPEED_STOPS.indices.minBy { abs(Timing.SPEED_STOPS[it] - speed) }

/** "1x", "1.5x", "0.25x". */
fun speedText(s: Float): String {
    val r = (s * 100).roundToInt() / 100f
    return if (r == r.roundToInt().toFloat()) "${r.roundToInt()}x" else "${r}x"
}

fun delayText(ms: Int): String = if (ms == 0) "None" else if (ms < 1000) "$ms ms" else if (ms % 1000 == 0) "${ms / 1000} s" else "${ms / 1000f} s"

private fun wholeLabel(kind: TimingKind) = when (kind) {
    TimingKind.TEXT -> "Until scrolled"
    TimingKind.STILL -> "1 s"
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

private fun moreText(t: Timing, direction: Boolean): String =
    listOfNotNull(
        t.direction.label.takeIf { direction },
        "Delay ${delayText(t.startDelayMs).lowercase()}",
        "Fade ${t.fade.label.lowercase()}",
    ).joinToString(" · ")

private fun secondsText(ms: Int): String {
    if (ms < 1000) return "$ms ms"
    val s = ms / 1000f
    return if (s == s.roundToInt().toFloat()) "${s.roundToInt()} s" else "%.1f s".format(java.util.Locale.ROOT, s)
}

/**
 * One plain sentence for the panel's current values, e.g. "Plays 2 times at 1x (4.8 s), then turns off." [oneMs]
 * is one pass of the design at 1x (null: no length in brackets).
 */
fun timingLine(t: Timing, kind: TimingKind, animated: Boolean, oneMs: Int?): String {
    val c = t.clamped()
    val verb = if (kind == TimingKind.TEXT) "Scrolls" else "Plays"
    val at = if (animated) " at ${speedText(c.speed)}" else ""
    return when (c.showFor) {
        ShowFor.UNTIL_CHECKED -> {
            val rest = if (animated) ", resting after ${Timing.REST_LOOPS} loops" else ""
            val remind = if (c.remindMinutes > 0) " It reminds you every ${c.remindMinutes} min." else ""
            (if (animated) "$verb$at until you check$rest." else "Stays on until you check.") + remind
        }
        ShowFor.SECONDS -> if (animated) "$verb$at for ${c.seconds} s, then turns off." else "Shows for ${c.seconds} s, then turns off."
        ShowFor.WHOLE -> {
            if (!animated) return "Shows for 1 s, then turns off."
            val pass = oneMs?.let { (it / c.speed).roundToInt() }
            when (c.repeat) {
                Repeat.FOREVER -> "$verb$at until something else shows."
                Repeat.ONCE -> "$verb once$at${pass?.let { " (${secondsText(it)})" }.orEmpty()}, then turns off."
                Repeat.TIMES -> "$verb ${c.times} times$at${pass?.let { " (${secondsText(it * c.times)})" }.orEmpty()}, then turns off."
            }
        }
    }
}

/** "1x · Whole animation · 2 times · 100%": the status chip next to the Timing button, and the line in rows. */
fun timingSummary(t: Timing, kind: TimingKind, animated: Boolean = kind != TimingKind.STILL): String {
    val c = t.clamped()
    return listOfNotNull(
        speedText(c.speed).takeIf { animated },
        showForText(c, kind),
        repeatText(c).takeIf { animated && c.showFor == ShowFor.WHOLE },
        "${c.brightness}%",
    ).joinToString(" · ")
}
