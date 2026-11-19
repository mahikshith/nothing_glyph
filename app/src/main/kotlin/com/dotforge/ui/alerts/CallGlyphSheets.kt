package com.dotforge.ui.alerts

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.calls.CallActions
import com.dotforge.calls.CallSettings
import com.dotforge.device.Animation
import com.dotforge.device.Effects
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.TimingOverride
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens

/** A call glyph's animation: the picked style, or [built] for the built-in one. */
private fun callAnimation(style: GlyphStyle?, designId: String?, built: () -> Animation): Animation {
    if (style == null) return built()
    val info = NotificationInfo("preview", "", "Phone", SAMPLE_NAME, "", 0, sender = SAMPLE_NAME)
    return RuleEngine.animation(style, designId, info, AppGraph.profile, AppGraph::designAnimation)
}

private fun glyphName(style: GlyphStyle?, designId: String?, builtIn: String): String = when (style) {
    null -> builtIn
    GlyphStyle.ICON -> com.dotforge.device.GlyphLibrary.get(designId)?.name?.let { "$it glyph" } ?: "Glyph"
    GlyphStyle.DESIGN -> AppGraph.designs.get(designId)?.name ?: "My design"
    GlyphStyle.NAME -> "The caller's name"
    else -> style.label
}

/** "Until you check · again every 5 min", "Until you check", "For 10 s". */
fun missedTimingText(s: CallSettings): String {
    val t = s.missedTiming ?: CallActions.MISSED_TIMING
    if (t.showFor == ShowFor.SECONDS) return "For ${t.seconds ?: 5} s"
    val m = t.remindMinutes ?: 0
    return if (m > 0) "Until you check · again every $m min" else "Until you check"
}

/** The Phone calls sheet's "Missed calls" line: "Off", or the glyph and timing. */
fun missedLine(s: CallSettings): String =
    if (!s.missedGlyph) "Off" else glyphName(s.missedStyle, s.missedDesign, "Missed call glyph") + " · " + missedTimingText(s).replaceFirstChar { it.lowercase() }

/** The Phone calls sheet's "Screened calls" line. */
fun screenedLine(s: CallSettings): String =
    if (!s.screenedGlyph) "Off" else glyphName(s.screenedStyle, s.screenedDesign, "Silenced and Declined glyphs")

/**
 * Phone calls > Missed calls: a switch, the glyph (the built-in one, or any from the picker), and the shared
 * timing controls. By default it waits until you check it and lights again every 5 min.
 */
@Composable
fun MissedCallSheet(onDismiss: () -> Unit) {
    val s by AppGraph.calls.settings.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    val rule = CallActions.missedRule(s)
    val anim = remember(s.missedStyle, s.missedDesign) { callAnimation(s.missedStyle, s.missedDesign) { Effects.missedCall(AppGraph.profile) } }
    fun set(t: (CallSettings) -> CallSettings) = AppGraph.calls.updateSettings(t)
    FullSheet(onDismiss, "Missed calls") {
        SwitchRow("Light for missed calls", "It waits until you check it, and lights again while it waits.", s.missedGlyph, inset = 0.dp) { on -> set { it.copy(missedGlyph = on) } }
        if (s.missedGlyph) {
            ListRow(
                "Glyph", subtitle = glyphName(s.missedStyle, s.missedDesign, "Missed call glyph"), inset = 0.dp,
                leading = { GlyphPreview(remember(anim) { stillOf(anim) }, "Missed call glyph") }, trailing = { Chevron() },
            ) { picker = true }
            ListRow("Timing", subtitle = missedTimingText(s), inset = 0.dp, trailing = { Chevron() }) { timing = true }
            BuiltInButton(s.missedStyle != null) { set { it.copy(missedStyle = null, missedDesign = null) } }
        }
    }
    if (picker) GlyphPicker(rule, "Missed call", isApp = false, onDismiss = { picker = false }) { c ->
        set { it.copy(missedStyle = c.style, missedDesign = c.id) }
        picker = false
    }
    if (timing) {
        val base = Timing(showFor = ShowFor.UNTIL_CHECKED)
        val now = (s.missedTiming ?: CallActions.MISSED_TIMING).over(base)
        TimingSheet(now, TimingKind.USE, anim, "Missed call", onDismiss = { timing = false }, placeName = "Missed calls") { t ->
            set { it.copy(missedTiming = TimingOverride.diff(Timing(), t).takeUnless { d -> d.isEmpty }) }
        }
    }
}

/** Phone calls > Screened calls: a switch and the glyph for a call that was silenced or declined. */
@Composable
fun ScreenedCallSheet(onDismiss: () -> Unit) {
    val s by AppGraph.calls.settings.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    val anim = remember(s.screenedStyle, s.screenedDesign) { callAnimation(s.screenedStyle, s.screenedDesign) { Effects.callBlocked(AppGraph.profile) } }
    fun set(t: (CallSettings) -> CallSettings) = AppGraph.calls.updateSettings(t)
    FullSheet(onDismiss, "Screened calls") {
        SwitchRow("Light when a call is screened", "A short glyph when a rule silences or declines a call. Spam calls use the spam alert.", s.screenedGlyph, inset = 0.dp) { on ->
            set { it.copy(screenedGlyph = on) }
        }
        if (s.screenedGlyph) {
            ListRow(
                "Glyph", subtitle = glyphName(s.screenedStyle, s.screenedDesign, "Silenced and Declined glyphs"), inset = 0.dp,
                leading = { GlyphPreview(remember(anim) { stillOf(anim) }, "Screened call glyph") }, trailing = { Chevron() },
            ) { picker = true }
            BuiltInButton(s.screenedStyle != null) { set { it.copy(screenedStyle = null, screenedDesign = null) } }
        }
        Hint("It stays until you unlock the phone.")
    }
    if (picker) GlyphPicker(Rule(id = "screened-call", name = "Screened call"), "Screened call", isApp = false, onDismiss = { picker = false }) { c ->
        set { it.copy(screenedStyle = c.style, screenedDesign = c.id) }
        picker = false
    }
}

@Composable
private fun BuiltInButton(show: Boolean, onClick: () -> Unit) {
    if (show) TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Tokens.Target).padding(top = Tokens.Tiny), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text("Use the built-in glyph", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
