package com.dotforge.ui.studio

import android.Manifest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dotforge.AppGraph
import com.dotforge.composition.ComposeStyle
import com.dotforge.composition.ConvertLayout
import com.dotforge.composition.ImportKind
import com.dotforge.composition.SongWindow
import com.dotforge.ringtones.ImportMode
import com.dotforge.ringtones.ImportSession
import com.dotforge.ringtones.ImportSessions
import com.dotforge.ringtones.RingtoneActions
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.PauseIcon
import com.dotforge.ui.SongWindowBar
import com.dotforge.ui.clock
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.ConfirmDialog
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.DotSlider
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.TopBar
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.rememberRingtonePreview
import kotlinx.coroutines.delay

/** Where "Save and set ▾" puts the ringtone. */
enum class SetTarget(val label: String) { RINGTONE("Ringtone"), NOTIFICATION("Notification"), ALARM("Alarm"), CONTACT("For a contact…") }

/**
 * Glyph ringtone (row 35): the song with Change, a waveform with a draggable part and "Starts at", Style,
 * Brightness and Speed, Preview (sound and lights, "Flip your phone over"), then Save and set ▾. A Glyph
 * ringtone file keeps its lights (or, from another phone, picks a layout). "Edit lights" opens the timeline.
 */
@Composable
fun RingtoneScreen(uri: String, nav: Nav) {
    val ctx = LocalContext.current
    val session = remember(uri) { ImportSessions.open(ctx, Uri.parse(uri), ImportMode.FILE) }
    fun close() { ImportSessions.close(session); nav.pop() }
    val change = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult
        ImportSessions.close(session)
        nav.pop()
        nav.push(Page.GlyphRingtone(u.toString()))
    }
    BackHandler(onBack = ::close)
    UndoSnackbarHost {
        val snacks = rememberSnacks()
        LaunchedEffect(session.error) { session.error?.let { snacks?.show(it); session.error = null } }
        Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val ready = session.state as? ImportSession.State.Ready
            TopBar("Glyph ringtone", onBack = ::close, close = true)
            when (val st = session.state) {
                ImportSession.State.Loading -> RingtoneLoading(session.progress, onCancel = ::close)
                is ImportSession.State.Failed -> RingtoneFailed(onChange = { change.launch(arrayOf("audio/*", "application/ogg")) }, onClose = ::close)
                is ImportSession.State.Ready -> {
                    val src = st.source
                    val part = session.part
                    val preview = rememberRingtonePreview(
                        src.playFile, offsetMs = part?.window?.startMs ?: 0L, lengthMs = part?.window?.lengthMs,
                        rows = session.rows, fadeMs = if (part?.window?.cutsEnd == true) 1_500 else 0,
                    )
                    RingtoneReady(
                        RingtoneUi(
                            name = src.name, kind = src.kind, envelope = src.song?.scan?.envelope, window = session.window,
                            updating = session.updatingPart, style = session.style, layout = session.layout,
                            brightness = session.intensity, speed = session.speed, title = session.title,
                            pixels = preview.pixels, playing = preview.playing, saving = session.saving, sync = session.syncMs,
                        ),
                        onChange = { change.launch(arrayOf("audio/*", "application/ogg")) },
                        onWindow = session::changeWindow, onStyle = { session.choose(it) }, onLayout = { session.choose(it) },
                        onBrightness = session::changeIntensity, onSpeed = session::changeSpeed, onTitle = { session.title = it.take(40) },
                        onPreview = preview::toggle,
                        onSet = { target, apply -> session.savedUri?.let { apply(it) } ?: session.save(AppGraph.ringtones) { u -> apply(u) } },
                        onDone = { close() },
                        onSync = session::changeSync,
                        onEditLights = if (ready != null && session.rows.isNotEmpty()) ({ nav.push(Page.RingtoneEdit) }) else null,
                    )
                }
            }
        }
    }
}

/** Everything the ready screen shows, so screenshots can draw it without a real song. */
class RingtoneUi(
    val name: String,
    val kind: ImportKind,
    val envelope: FloatArray?,
    val window: SongWindow?,
    val updating: Boolean,
    val style: ComposeStyle,
    val layout: ConvertLayout,
    val brightness: Float,
    val speed: Float,
    val title: String,
    val pixels: IntArray?,
    val playing: Boolean,
    val saving: Boolean,
    /** "Lights earlier / later", ms. */
    val sync: Int = 0,
)

/** "Finding the beat… 40%" while the song is read through, on a track that shows, with Cancel. */
@Composable
fun RingtoneLoading(progress: Float? = null, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Tokens.Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val pct = progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        Text(
            if (pct != null) "Finding the beat… $pct%" else "Finding the beat…", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        val bar = Modifier.fillMaxWidth().height(6.dp).padding(top = 0.dp)
        Box(Modifier.fillMaxWidth().padding(top = Tokens.Gap)) {
            if (progress != null) LinearProgressIndicator(progress = { progress }, bar, color = Color.White, trackColor = TRACK, gapSize = 0.dp, drawStopIndicator = {})
            else LinearProgressIndicator(bar, color = Color.White, trackColor = TRACK, gapSize = 0.dp)
        }
        Text("Reading the song for its beats and loud parts", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Small))
        SecondaryButton("Cancel", Modifier.padding(top = Tokens.Section), onClick = onCancel)
    }
}

/** A progress track that shows on black. */
private val TRACK = Color(0xFF4A4A4A)

@Composable
fun RingtoneFailed(onChange: () -> Unit, onClose: () -> Unit) =
    com.dotforge.ui.design.LoadFailed(DfIcons.Music, "This song can't be read.", com.dotforge.ui.design.Supported.SONGS, "Choose another song", onChange, onClose)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RingtoneReady(
    ui: RingtoneUi,
    onChange: () -> Unit,
    onWindow: (SongWindow) -> Unit,
    onStyle: (ComposeStyle) -> Unit,
    onLayout: (ConvertLayout) -> Unit,
    onBrightness: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onTitle: (String) -> Unit,
    onPreview: () -> Unit,
    onSet: (SetTarget, (Uri) -> Unit) -> Unit,
    onDone: () -> Unit,
    startMenu: Boolean = false,
    startFlip: Boolean = false,
    onSync: (Int) -> Unit = {},
    /** "Edit lights": the keyframe timeline, for power users; null while there are no lights yet. */
    onEditLights: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val snacks = rememberSnacks()
    var menu by remember { mutableStateOf(startMenu) }
    var flip by remember { mutableStateOf(startFlip) }
    var ask by remember { mutableStateOf<SetTarget?>(null) }
    var waiting by remember { mutableStateOf<SetTarget?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    // "Flip your phone over" for 3 s when the preview starts: the lights are on the back
    LaunchedEffect(ui.playing) { if (ui.playing) { flip = true; delay(3_000); flip = false } }

    fun apply(target: SetTarget, u: Uri) {
        val name = ui.title.trim().ifEmpty { ui.name }
        val ok = when (target) {
            SetTarget.RINGTONE -> RingtoneActions.setPhoneRingtone(ctx, u)
            SetTarget.NOTIFICATION -> RingtoneActions.setNotificationSound(ctx, u)
            SetTarget.ALARM -> RingtoneActions.setAlarmSound(ctx, u)
            SetTarget.CONTACT -> true
        }
        if (target == SetTarget.CONTACT) return
        snacks?.show(if (ok) "$name is your ${target.label.lowercase()} sound" else "Saved $name. Couldn't set it.")
        if (ok) onDone()
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { c ->
        val u = pending ?: return@rememberLauncherForActivityResult
        if (c == null) return@rememberLauncherForActivityResult
        val who = RingtoneActions.setContactRingtone(ctx, c, u)
        snacks?.show(if (who != null) "$who now rings with ${ui.title.trim().ifEmpty { ui.name }}" else "Couldn't change that contact")
        if (who != null) onDone()
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickContact.launch(null) else snacks?.show("Contacts editing is off. You can allow it in App info.")
    }
    LifecycleResumeEffect(waiting) {
        val t = waiting
        if (t != null && RingtoneActions.canWriteSettings(ctx)) {
            waiting = null
            onSet(t) { u -> apply(t, u) }
        }
        onPauseOrDispose { }
    }
    fun choose(t: SetTarget) {
        menu = false
        when {
            t == SetTarget.CONTACT -> onSet(t) { u ->
                pending = u
                if (RingtoneActions.canWriteContacts(ctx)) pickContact.launch(null) else askContacts.launch(Manifest.permission.WRITE_CONTACTS)
            }
            !RingtoneActions.canWriteSettings(ctx) -> ask = t
            else -> onSet(t) { u -> apply(t, u) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Tokens.Gutter)) {
            // the song, with Change
            Row(Modifier.fillMaxWidth().heightIn(min = Tokens.Row), verticalAlignment = Alignment.CenterVertically) {
                Icon(DfIcons.Music, contentDescription = null, tint = Color.White)
                Column(Modifier.weight(1f).padding(horizontal = Tokens.Gap)) {
                    Text(ui.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(describe(ui.kind), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                }
                TextButton(onClick = onChange, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Change", color = Color.White) }
            }
            // the matrix and Preview
            Row(Modifier.fillMaxWidth().padding(vertical = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
                DotMatrix(ui.pixels, AppGraph.profile, Modifier.size(140.dp).semantics { contentDescription = "Lights preview" })
                Column(Modifier.weight(1f).padding(start = Tokens.Gutter)) {
                    IconButton(onClick = onPreview, modifier = Modifier.size(56.dp).background(Color.White, CircleShape)) {
                        Icon(if (ui.playing) PauseIcon else DfIcons.Play, contentDescription = if (ui.playing) "Stop preview" else "Preview", tint = Color.Black)
                    }
                    Text(
                        if (flip) "Flip your phone over" else if (ui.playing) "Playing sound and lights" else "Preview plays the sound and lights",
                        style = MaterialTheme.typography.bodyMedium, color = if (flip) Color.White else Tokens.Secondary,
                        modifier = Modifier.padding(top = Tokens.Small).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            val w = ui.window
            if (ui.envelope != null && w != null) {
                SectionLabel("Part of the song")
                SongWindowBar(ui.envelope, w, onWindow)
                StartsAt(w, onWindow)
                Text(
                    "${clock(w.startMs)} – ${clock(w.endMs)} · ${w.lengthMs / 1000} s" + if (ui.updating) " · Updating lights…" else "",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary,
                )
                Hint("Drag the part to move it, its right edge to change the length.")
            }
            when (ui.kind) {
                ImportKind.AudioOnly -> {
                    SectionLabel("Style")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        ComposeStyle.entries.forEach { s -> Chip(s.label, ui.style == s) { onStyle(s) } }
                    }
                    Text(ui.style.hint, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                }
                is ImportKind.OtherPhone -> {
                    SectionLabel("Layout")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        ConvertLayout.entries.forEach { l -> Chip(l.label, ui.layout == l) { onLayout(l) } }
                    }
                    Text(ui.layout.hint, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
                }
                is ImportKind.Phone3 -> Unit
            }
            SectionLabel("Brightness · ${(ui.brightness * 100).toInt()}%")
            DotSlider("Brightness", ui.brightness, 0.25f..2f, readout = "${(ui.brightness * 100).toInt()}%", mark = 1f, onChange = onBrightness)
            if (ui.kind == ImportKind.AudioOnly) {
                SectionLabel("Speed · ${com.dotforge.ui.design.speedText(ui.speed)}")
                DotSlider("Speed", ui.speed, 0.5f..2f, readout = "${"%.1f".format(ui.speed)}x", mark = 1f, onChange = onSpeed)
            }
            if (ui.kind == ImportKind.AudioOnly) {
                val sync = ui.sync
                SectionLabel("Lights earlier / later · ${if (sync == 0) "in time" else if (sync < 0) "${-sync} ms earlier" else "$sync ms later"}")
                DotSlider(
                    "Lights earlier or later", sync.toFloat(), -com.dotforge.ringtones.SYNC_MAX_MS.toFloat()..com.dotforge.ringtones.SYNC_MAX_MS.toFloat(),
                    readout = if (sync == 0) "in time" else "$sync milliseconds", mark = 0f,
                ) { onSync(((it / 50).toInt() * 50)) }
            }
            if (onEditLights != null) {
                SecondaryButton("Edit lights", Modifier.fillMaxWidth().padding(top = Tokens.Gap), onClick = onEditLights)
                Hint("A timeline of every light, for fine changes.")
            }
            SectionLabel("Name")
            OutlinedTextField(
                ui.title, onTitle, Modifier.fillMaxWidth().padding(bottom = Tokens.Section), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            )
        }
        // more below: the list fades into the pinned bar instead of running under it
        if (scroll.value < scroll.maxValue) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Tokens.Background))),
        )
        }
        // the pinned bar: its own strip with a hairline, so it never sits over a control
        androidx.compose.material3.HorizontalDivider(color = Tokens.Hairline)
        Box(Modifier.fillMaxWidth().background(Tokens.Background).padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
            PrimaryButton(if (ui.saving) "Saving…" else "Save and set ▾", Modifier.fillMaxWidth(), enabled = !ui.saving) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Tokens.Raised) {
                SetTarget.entries.forEach { t ->
                    DropdownMenuItem(text = { Text(t.label) }, onClick = { choose(t) }, modifier = Modifier.heightIn(min = Tokens.Target))
                }
            }
        }
    }
    ask?.let { t ->
        ConfirmDialog(
            "Allow changing sounds", "To set ringtones, Android needs you to allow DotForge to change system settings.", "Open settings",
            onDismiss = { ask = null },
        ) {
            ask = null
            waiting = t
            runCatching { ctx.startActivity(RingtoneActions.writeSettingsIntent(ctx)) }
        }
    }
}

/** "Starts at 0:42": the part's start, typed as m:ss. */
@Composable
private fun StartsAt(w: SongWindow, onWindow: (SongWindow) -> Unit) {
    var text by remember(w.startMs) { mutableStateOf(clock(w.startMs)) }
    Row(Modifier.padding(top = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
        Text("Starts at", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Tokens.Gap))
        OutlinedTextField(
            text, { t ->
                text = t.filter { it.isDigit() || it == ':' }.take(6)
                parseClock(text)?.let { ms -> if (ms != w.startMs) onWindow(w.movedTo(ms)) }
            },
            Modifier.width(110.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        )
    }
}

/** "1:05" → 65000; null when it isn't m:ss. */
fun parseClock(s: String): Long? {
    val parts = s.split(':')
    if (parts.size != 2 || parts[1].length != 2) return null
    val m = parts[0].toLongOrNull() ?: return null
    val sec = parts[1].toLongOrNull()?.takeIf { it < 60 } ?: return null
    return (m * 60 + sec) * 1000
}

private fun describe(kind: ImportKind): String = when (kind) {
    is ImportKind.Phone3 -> "Glyph ringtone for Phone (3). Its lights are kept."
    is ImportKind.OtherPhone -> "Glyph ringtone from another phone. Pick how its lights sit on the Glyph."
    ImportKind.AudioOnly -> "Lights made from the music"
}
