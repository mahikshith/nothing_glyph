package com.dotforge.ui.features

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.composition.VisualStyle
import com.dotforge.composition.VisualiserRenderer
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.device.Posters
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
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.paceName
import com.dotforge.visualiser.LiveVisualiser
import com.dotforge.visualiser.VisualiserDesign

/** Why the visualiser needs the microphone, in plain, honest words: shown before Android asks. */
const val MIC_WHY = "Listens to the sound this phone plays. If Android won't share it, DotForge uses the microphone instead. Nothing is recorded or saved."

/** After a refusal Android won't ask again: only settings can turn it on. */
const val MIC_OFF = "Microphone is off, so the visualiser can't hear anything. Turn it on in settings."

/** One frame of the demo at 1x; the previews keep this pace whatever the smoothing. */
private const val DEMO_FRAME_MS = 66

/** Smoothing's chips: how quickly the lights drop after a beat, as a speed. */
internal val SMOOTHING = listOf(0.5f, 1f, 2f)
internal val SMOOTHING_NAMES = listOf("Smooth", "Normal", "Snappy")

/**
 * Features > Music visualiser: the switch (asking for the microphone with a plain explanation first, and
 * pointing to settings after a refusal), Shake to skip, the On the Glyph row (when it shows, smoothing, a note
 * for Repeat, brightness and sensitivity), and the styles as tiles, each playing a made-up beat: the three
 * built-in ones and One of my designs.
 */
@Composable
fun MusicFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val f = AppGraph.features
    val running by LiveVisualiser.running.collectAsStateWithLifecycle()
    val mic by LiveVisualiser.usingMic.collectAsStateWithLifecycle()
    val music by LiveVisualiser.music.collectAsStateWithLifecycle()
    val timing by f.timing(FeatureTiming.MUSIC).collectAsStateWithLifecycle()
    val mediaOnly by f.mediaOnly.collectAsStateWithLifecycle()
    val sensitivity by f.sensitivity.collectAsStateWithLifecycle()
    val mine by f.musicMine.collectAsStateWithLifecycle()
    val designId by f.musicDesign.collectAsStateWithLifecycle()
    val skip by f.shakeSkip.collectAsStateWithLifecycle()
    val asked by f.micAsked.collectAsStateWithLifecycle()
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    var style by remember { mutableStateOf(AppGraph.toys.visualStyle) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var notStarted by rememberSaveable { mutableStateOf(false) }
    var glyph by rememberSaveable { mutableStateOf(false) }
    var pick by rememberSaveable { mutableStateOf(false) }
    // Android stops asking after a refusal or two; then only settings can allow it
    val refused = !granted && asked && (ctx as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) != true
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        f.setMicAsked(true)
        if (ok) notStarted = !LiveVisualiser.start(ctx)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val poster = remember(designId, designs) { AppGraph.designAnimation(designId)?.let(Posters::of) }
    val design = remember(style, timing.speed, sensitivity, mine, poster) { if (mine && poster != null) mineDemo(poster, timing.speed, sensitivity) else demo(style, timing.speed, sensitivity) }
    val styleName = if (mine) MINE_LABEL else style.label

    FeatureLayout(Feature.MUSIC, nav) {
        item(key = "main") {
            if (!granted) StatusBanner(
                if (refused) MIC_OFF else MIC_WHY, Modifier.padding(bottom = Tokens.Gap), button = if (refused) "Open settings" else "Allow",
            ) {
                if (refused) openAppSettings(ctx) else ask.launch(Manifest.permission.RECORD_AUDIO)
            }
            RowGroup {
                SwitchRow("Music visualiser", musicStatus(running, granted, mic, music, mediaOnly), running) { want ->
                    when {
                        !want -> LiveVisualiser.stop(ctx)
                        refused -> openAppSettings(ctx)
                        !granted -> ask.launch(Manifest.permission.RECORD_AUDIO)
                        else -> notStarted = !LiveVisualiser.start(ctx)
                    }
                }
                RowDivider()
                OnTheGlyphRow(
                    musicSummary(timing, mediaOnly, sensitivity), remember(design, timing) { Timings.plan(design, timing.copy(showFor = ShowFor.WHOLE, repeat = Repeat.ONCE)).animation },
                    "$styleName moving with the music",
                ) { glyph = true }
                RowDivider()
                SwitchRow("Shake to skip", "While it's on, a firm shake plays the next song", skip) { f.setShakeSkip(it) }
            }
            if (notStarted && !running) Hint("Android didn't let it start. Try again with DotForge open.")
        }
        item(key = "styles") {
            SectionLabel("Style")
            val tiles: List<Pair<VisualStyle?, String>> = VisualStyle.entries.map { it to it.hint } + (null to "Brighter on the beat")
            tiles.chunked(2).forEachIndexed { r, row ->
                Row(Modifier.padding(top = if (r > 0) Tokens.Gap else 0.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                    row.forEach { (s, hint) ->
                        if (s != null) {
                            val a = remember(s, timing.speed, sensitivity) { demo(s, timing.speed, sensitivity) }
                            Middle(s.label, hint, !mine && style == s, a, description = "${s.label} style") {
                                style = s
                                AppGraph.toys.visualStyle = s
                                f.setMusicMine(false)
                            }
                        } else {
                            val a = remember(poster, timing.speed, sensitivity) { poster?.let { mineDemo(it, timing.speed, sensitivity) } }
                            Middle(MINE_LABEL, if (poster == null) "Make a design in Create first" else hint, mine, a ?: Animation.single(Frame.blank(AppGraph.profile)), description = "Your design with the music") {
                                if (designs.isNotEmpty()) {
                                    f.setMusicMine(true)
                                    pick = true
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (mine && designs.isNotEmpty()) RowGroup(Modifier.padding(top = Tokens.Gap)) {
                ListRow(
                    "Design", subtitle = designs.firstOrNull { it.id == designId }?.name ?: designs.first().name,
                    leading = { GlyphPreview(poster, "Your design") }, trailing = { Chevron() },
                ) { pick = true }
            }
            Hint("The DF Music toy uses Bars, Ring pulse or Radial spectrum, whichever you picked last.")
        }
    }

    if (pick) MyDesignSheet("Design for the music", designId, { pick = false }) { f.setMusicDesign(it); pick = false }
    if (glyph) TimingSheet(
        timing, TimingKind.ANIMATION, design, styleName, { glyph = false },
        title = "On the Glyph", plainLine = musicLine(timing, mediaOnly),
        parts = TimingParts(
            showForSlot = {
                ChipChoices(listOf(true, false), mediaOnly, { if (it) "While music plays" else "Until I turn it off" }) { f.setMediaOnly(it) }
                PartNote(if (mediaOnly) "Dark between songs and while paused." else "A faint ring shows while it waits for sound.")
            },
            speedTitle = "Smoothing",
            speedSlot = {
                ChipChoices(SMOOTHING, SMOOTHING.minBy { kotlin.math.abs(it - timing.speed) }, { SMOOTHING_NAMES[SMOOTHING.indexOf(it)] }) {
                    f.setTiming(FeatureTiming.MUSIC, timing.copy(speed = it))
                }
                PartNote("Smooth glides down after a beat; Snappy drops at once.")
            },
            repeatSlot = { PartNote("It follows the music.") },
            more = false,
            compactBrightness = true,
        ),
        extra = {
            Text("Sensitivity", Modifier.padding(top = Tokens.Section, bottom = Tokens.Tiny).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            ChipChoices(Sensitivity.entries, sensitivity, { it.label }) { f.setSensitivity(it) }
            PartNote("High lights the quiet parts too; Low lights only the loud ones.")
        },
        onChange = { f.setTiming(FeatureTiming.MUSIC, it) },
    )
}

/** The style tile and the On the Glyph line for your own design. */
private const val MINE_LABEL = "One of my designs"

/**
 * The made-up beat in [style], falling back at [speed] with [sensitivity]. Frames are stretched by [speed] so the
 * timing sheet (which plays the design at that speed) keeps the beat's pace; only the smoothing changes.
 */
private fun demo(style: VisualStyle, speed: Float, sensitivity: Sensitivity): Animation =
    VisualiserRenderer.demo(style, AppGraph.profile, sensitivity.rangeDb, speed, frameMs = (DEMO_FRAME_MS * speed).toInt().coerceAtLeast(Animation.MIN_FRAME_MS))

/** Your design with the same made-up beat: brighter on each beat, dim between. */
private fun mineDemo(poster: Frame, speed: Float, sensitivity: Sensitivity): Animation {
    val beat = demo(VisualStyle.BARS, speed, sensitivity)
    return Animation(beat.profile, beat.frames.map { AnimFrame(VisualiserDesign.frame(poster, VisualiserDesign.loudness(it.frame)), it.durationMs) })
}

/** The switch's line: "On · moving with the music", "On · waiting for music", "Needs the microphone". */
internal fun musicStatus(running: Boolean, granted: Boolean, mic: Boolean, music: Boolean, mediaOnly: Boolean): String = when {
    !granted -> "Needs microphone access"
    !running -> "Off"
    !music -> if (mediaOnly) "On · dark until music plays" else "On · waiting for music"
    mic -> "On · listening through the microphone"
    else -> "On · moving with the music"
}

/** The row's line: "While music plays · Smooth · 100% · Normal sensitivity". */
internal fun musicSummary(t: Timing, mediaOnly: Boolean, sensitivity: Sensitivity): String {
    val c = t.clamped()
    return listOf(if (mediaOnly) "While music plays" else "Until turned off", paceName(c.speed, SMOOTHING_NAMES), "${c.brightness}%", "${sensitivity.label} sensitivity").joinToString(" · ")
}

internal fun musicLine(t: Timing, mediaOnly: Boolean): String {
    val c = t.clamped()
    val pace = when {
        c.speed < 1f -> "glide down after each beat"
        c.speed > 1f -> "drop at once after each beat"
        else -> "drop after each beat at the usual pace"
    }
    return (if (mediaOnly) "Lights while music plays; they $pace." else "Lights until you turn it off; they $pace.")
}

private fun openAppSettings(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Picks one of your designs (the music, the charging or the volume design): each with its still, the chosen one checked. */
@Composable
internal fun MyDesignSheet(title: String, selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    com.dotforge.ui.design.FullSheet(onDismiss, title) {
        if (designs.isEmpty()) Hint("Nothing saved yet. Designs you make in Create show here.")
        else com.dotforge.ui.design.ChoiceRows(
            designs, designs.firstOrNull { it.id == selected } ?: designs.first(), { it.name }, { if (it.animated) "Animated" else "Still" },
            preview = { d -> GlyphPreview(remember(d.id) { AppGraph.designs.animation(d)?.let(Posters::of) }, d.name) },
        ) { onPick(it.id) }
    }
}
