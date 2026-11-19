package com.dotforge.ui.features

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.composition.VisualStyle
import com.dotforge.composition.VisualiserRenderer
import com.dotforge.device.Animation
import com.dotforge.device.Repeat
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.Timings
import com.dotforge.system.FeatureTiming
import com.dotforge.system.Sensitivity
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PartNote
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.TimingParts
import com.dotforge.ui.design.TimingSheet
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.speedText
import com.dotforge.visualiser.LiveVisualiser

/** Why the visualiser needs the microphone, in plain words: shown before Android asks. */
const val MIC_WHY = "DotForge listens to what this phone plays, which Android calls microphone access. Nothing is recorded or sent."

/** One frame of the demo at 1x; the previews keep this pace whatever the fall-back speed. */
private const val DEMO_FRAME_MS = 66

/**
 * Features > Music visualiser: the switch (asking for the microphone with a plain explanation first, and
 * pointing to settings after a refusal), the On the Glyph row (when it shows, fall-back speed, a note for
 * Repeat, brightness and sensitivity), and the three styles, each playing a made-up beat.
 */
@Composable
fun MusicFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val running by LiveVisualiser.running.collectAsStateWithLifecycle()
    val mic by LiveVisualiser.usingMic.collectAsStateWithLifecycle()
    val music by LiveVisualiser.music.collectAsStateWithLifecycle()
    val timing by AppGraph.features.timing(FeatureTiming.MUSIC).collectAsStateWithLifecycle()
    val mediaOnly by AppGraph.features.mediaOnly.collectAsStateWithLifecycle()
    val sensitivity by AppGraph.features.sensitivity.collectAsStateWithLifecycle()
    var style by remember { mutableStateOf(AppGraph.toys.visualStyle) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var notStarted by rememberSaveable { mutableStateOf(false) }
    var glyph by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) notStarted = !LiveVisualiser.start(ctx)
        // Android stops asking after two refusals; then only settings can allow it
        else refused = (ctx as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) != true
    }
    val design = remember(style, timing.speed, sensitivity) { demo(style, timing.speed, sensitivity) }

    FeatureLayout(Feature.MUSIC, nav) {
        item(key = "main") {
            if (!granted) StatusBanner(
                if (refused) "$MIC_WHY Allow it in settings, under Permissions." else MIC_WHY,
                Modifier.padding(bottom = Tokens.Gap), button = if (refused) "Open settings" else "Allow",
            ) {
                if (refused) openAppSettings(ctx) else ask.launch(Manifest.permission.RECORD_AUDIO)
            }
            RowGroup {
                SwitchRow("Music visualiser", musicStatus(running, granted, mic, music, mediaOnly), running) { want ->
                    when {
                        !want -> LiveVisualiser.stop(ctx)
                        !granted && refused -> openAppSettings(ctx)
                        !granted -> ask.launch(Manifest.permission.RECORD_AUDIO)
                        else -> notStarted = !LiveVisualiser.start(ctx)
                    }
                }
                RowDivider()
                OnTheGlyphRow(
                    musicSummary(timing, mediaOnly, sensitivity), remember(design, timing) { Timings.plan(design, timing.copy(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE)).animation },
                    style.label + " moving with the music",
                ) { glyph = true }
            }
            if (notStarted && !running) Hint("Android didn't let it start. Try again with DotForge open.")
        }
        item(key = "styles") {
            SectionLabel("Style")
            RowGroup {
                VisualStyle.entries.forEachIndexed { i, s ->
                    if (i > 0) RowDivider(leading = true)
                    ListRow(
                        s.label, Modifier.selectable(style == s, role = Role.RadioButton) { style = s; AppGraph.toys.visualStyle = s },
                        subtitle = s.hint,
                        leading = { GlyphPreview(remember(s, timing.speed, sensitivity) { demo(s, timing.speed, sensitivity) }, s.label + " style") },
                        trailing = { if (style == s) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) },
                    )
                }
            }
            Hint("The DF Music toy uses the same style.")
        }
    }

    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, design, style.label, { glyph = false },
        title = "On the Glyph", plainLine = musicLine(timing, mediaOnly),
        parts = TimingParts(
            showForSlot = {
                ChipChoices(listOf(true, false), mediaOnly, { if (it) "While music plays" else "Until I turn it off" }) { AppGraph.features.setMediaOnly(it) }
                PartNote(if (mediaOnly) "Dark between songs and while paused." else "A faint ring shows while it waits for sound.")
            },
            speedTitle = "Fall-back speed",
            speedSlider = false,
            repeatSlot = { PartNote("It follows the music.") },
            more = false,
            compactBrightness = true,
        ),
        extra = {
            Text("Sensitivity", Modifier.padding(top = Tokens.Section, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            ChipChoices(Sensitivity.entries, sensitivity, { it.label }) { AppGraph.features.setSensitivity(it) }
            PartNote("High lights the quiet parts too; Low lights only the loud ones.")
        },
        onChange = { AppGraph.features.setTiming(FeatureTiming.MUSIC, it) },
    )
}

/**
 * The made-up beat in [style], falling back at [speed] with [sensitivity]. Frames are stretched by [speed] so the
 * timing sheet (which plays the design at that speed) keeps the beat's pace; only the fall-back changes.
 */
private fun demo(style: VisualStyle, speed: Float, sensitivity: Sensitivity): Animation =
    VisualiserRenderer.demo(style, AppGraph.profile, sensitivity.rangeDb, speed, frameMs = (DEMO_FRAME_MS * speed).toInt().coerceAtLeast(Animation.MIN_FRAME_MS))

/** The switch's line: "On · moving with the music", "On · waiting for music", "Needs the microphone". */
internal fun musicStatus(running: Boolean, granted: Boolean, mic: Boolean, music: Boolean, mediaOnly: Boolean): String = when {
    !granted -> "Needs microphone access"
    !running -> "Off"
    !music -> if (mediaOnly) "On · dark until music plays" else "On · waiting for music"
    mic -> "On · listening through the microphone"
    else -> "On · moving with the music"
}

/** The row's line: "While music plays · fall-back 1x · 100% · Normal". */
internal fun musicSummary(t: Timing, mediaOnly: Boolean, sensitivity: Sensitivity): String {
    val c = t.clamped()
    return listOf(if (mediaOnly) "While music plays" else "Until turned off", "fall-back ${speedText(c.speed)}", "${c.brightness}%", sensitivity.label).joinToString(" · ")
}

internal fun musicLine(t: Timing, mediaOnly: Boolean): String {
    val c = t.clamped()
    val pace = when {
        c.speed < 1f -> "fall back slowly and smoothly"
        c.speed > 1f -> "fall back quickly"
        else -> "fall back at the usual pace"
    }
    return (if (mediaOnly) "Lights while music plays; they $pace." else "Lights until you turn it off; they $pace.")
}

private fun openAppSettings(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
