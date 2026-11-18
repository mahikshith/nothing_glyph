package com.dotforge.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.composition.ComposeStyle
import com.dotforge.composition.ConvertLayout
import com.dotforge.composition.GlyphRingtone
import com.dotforge.composition.ImportKind
import com.dotforge.ringtones.ImportMode
import com.dotforge.ringtones.ImportSession
import com.dotforge.ringtones.ImportSessions
import com.dotforge.ringtones.RingtoneActions
import com.dotforge.ringtones.RingtoneImporter
import com.dotforge.ringtones.RingtoneSource
import com.dotforge.ringtones.SavedRingtone
import com.dotforge.rules.RuleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** MIME types for "Import a file": any audio, and Ogg files that some pickers type as application/ogg. */
private val IMPORT_TYPES = arrayOf("audio/*", "application/ogg")
private val SONG_TYPES = arrayOf("audio/*")

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** The user's DotForge ringtones, and the way to add one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RingtonesPage(nav: Nav) {
    val lib = AppGraph.ringtones
    val items by lib.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var choose by rememberSaveable { mutableStateOf(false) }
    var getMore by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedRingtone?>(null) }
    LifecycleResumeEffect(Unit) {
        scope.launch(Dispatchers.IO) { lib.refresh() }
        onPauseOrDispose { }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { nav.push(Page.RingtoneImport(it.toString(), ImportMode.FILE)) }
    }
    val openSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { nav.push(Page.RingtoneImport(it.toString(), ImportMode.SONG)) }
    }

    PageScaffold(
        "RINGTONES",
        onBack = nav::pop,
        actions = { IconButton(onClick = { choose = true }) { Icon(Icons.Filled.Add, "Add ringtone") } },
    ) {
        if (items.isEmpty()) {
            Spacer(Modifier.height(56.dp))
            EmptyState("No glyph ringtones yet", "Import one, or make lights for a song.", "Add ringtone") { choose = true }
        } else {
            Text("Tap to play and set. Hold to delete.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            items.forEach { r ->
                Surface(color = Surface1, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(
                        Modifier
                            .combinedClickable(
                                onClick = { nav.push(Page.RingtoneImport(r.uri.toString(), ImportMode.SAVED)) },
                                onLongClick = { deleting = r },
                            )
                            .heightIn(min = 64.dp)
                            .padding(16.dp),
                    ) {
                        Text(r.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(clock(r.durationMs), color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        if (items.isNotEmpty()) SecondaryButton("Get more", Modifier.fillMaxWidth().padding(top = 8.dp)) { getMore = true }
        Surface(color = Surface2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Text(
                "For the lights to play while ringing, keep Nothing's Glyph ringtone setting on.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            containerColor = Surface2,
            title = { Text("Add ringtone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceCard("Import a file", "A glyph ringtone from any Nothing phone, or any sound", false, Modifier.fillMaxWidth()) {
                        choose = false
                        openFile.launch(IMPORT_TYPES)
                    }
                    ChoiceCard("From a song", "Lights made from the music", false, Modifier.fillMaxWidth()) {
                        choose = false
                        openSong.launch(SONG_TYPES)
                    }
                    ChoiceCard("Get more", "Free ringtones shared on GitHub and Glyphtones", false, Modifier.fillMaxWidth()) {
                        choose = false
                        getMore = true
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = false }) { Text("Cancel", color = Color.White) } },
        )
    }
    if (getMore) GetMoreSheet(nav) { getMore = false }
    deleting?.let { r ->
        ConfirmDialog("Delete \"${r.title}\"?", "It's removed from the phone's ringtones.", "Delete", onConfirm = {
            scope.launch(Dispatchers.IO) { lib.delete(r.uri) }
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

/**
 * Import, convert or compose, preview (sound with the lights on the screen and the real matrix), Save, then
 * Set as. [mode] SAVED previews a ringtone from the library.
 */
@Composable
fun RingtoneImportPage(uri: String, mode: ImportMode, nav: Nav) {
    val ctx = LocalContext.current
    val session = remember(uri, mode) { ImportSessions.open(ctx, Uri.parse(uri), mode) }
    var setAs by rememberSaveable { mutableStateOf(false) }
    val close = {
        ImportSessions.close(session)
        nav.pop()
    }
    LaunchedEffect(session.error) {
        session.error?.let { toast(ctx, it); session.error = null }
    }
    val ready = session.state as? ImportSession.State.Ready
    val saved = session.savedUri

    PageScaffold(
        when (mode) {
            ImportMode.SAVED -> "RINGTONE"
            ImportMode.SONG -> "FROM A SONG"
            ImportMode.FILE -> "IMPORT"
        },
        onBack = close,
        actions = {
            if (ready != null && session.rows.isNotEmpty()) TextButton(onClick = { nav.push(Page.RingtoneEdit) }) { Text("Edit", color = Color.White) }
        },
        bottomBar = if (ready == null) null else {
            {
                if (saved != null) {
                    PrimaryButton("Set as", Modifier.fillMaxWidth()) { setAs = true }
                } else {
                    PrimaryButton(
                        if (session.saving) "Saving..." else "Save",
                        Modifier.fillMaxWidth(),
                        enabled = !session.saving && session.rows.isNotEmpty(),
                    ) {
                        session.save(AppGraph.ringtones) {
                            toast(ctx, "Saved to Ringtones")
                            setAs = true
                        }
                    }
                }
            }
        },
    ) {
        when (val s = session.state) {
            ImportSession.State.Loading -> Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    if (mode == ImportMode.SONG) "Listening to the song..." else "Reading the file...",
                    color = Muted, modifier = Modifier.padding(top = 16.dp),
                )
            }
            is ImportSession.State.Failed -> {
                Spacer(Modifier.height(56.dp))
                EmptyState("Can't use this file", s.message, "Go back") { close() }
            }
            is ImportSession.State.Ready -> ReadyContent(session, s.source, mode)
        }
    }

    if (setAs && saved != null) {
        SetAsSheet(saved, session.title.trim().ifEmpty { ready?.source?.name ?: "this ringtone" }, nav, onDismiss = { setAs = false })
    }
}

@Composable
private fun ColumnScope.ReadyContent(session: ImportSession, src: RingtoneSource, mode: ImportMode) {
    val profile = AppGraph.profile
    val part = session.part
    val preview = rememberRingtonePreview(
        src.playFile,
        offsetMs = part?.window?.startMs ?: 0L,
        lengthMs = part?.window?.lengthMs,
        rows = session.rows,
        fadeMs = if (part?.window?.cutsEnd == true) 1_500 else 0,
    )
    MatrixView(preview.pixels, profile, Modifier.fillMaxWidth(0.78f).align(Alignment.CenterHorizontally).padding(top = 8.dp))
    PreviewTransport(preview)
    Text(describe(src), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

    val song = src.song
    val window = session.window
    if (song != null && window != null && mode != ImportMode.SAVED) {
        GroupTitle("PART OF THE SONG")
        SongWindowBar(song.scan.envelope, window, session::changeWindow)
        Text(
            "${clock(window.startMs)} - ${clock(window.endMs)} · ${window.lengthMs / 1000} s" +
                if (session.updatingPart) " · Updating lights..." else "",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text("Drag the part to move it, its right edge to change the length.", color = Muted, fontSize = 13.sp)
    }

    when (src.kind) {
        is ImportKind.OtherPhone -> if (mode != ImportMode.SAVED) {
            GroupTitle("LAYOUT")
            Cards(ConvertLayout.entries, { it.label }, { it.hint }, session.layout, session::choose)
        }
        ImportKind.AudioOnly -> if (mode != ImportMode.SAVED) {
            GroupTitle("STYLE")
            Cards(ComposeStyle.entries, { it.label }, { it.hint }, session.style, session::choose)
        }
        is ImportKind.Phone3 -> Unit
    }

    if (mode != ImportMode.SAVED) {
        LabeledSlider("Intensity", session.intensity, 0.25f..2f, "${(session.intensity * 100).toInt()}%", session::changeIntensity)
        if (src.kind == ImportKind.AudioOnly) {
            LabeledSlider("Speed", session.speed, 0.5f..2f, "${"%.1f".format(session.speed)}x", session::changeSpeed)
        }
        OutlinedTextField(
            value = session.title,
            onValueChange = { session.title = it },
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

private fun describe(src: RingtoneSource): String = when (val k = src.kind) {
    is ImportKind.Phone3 -> "Glyph ringtone for Phone (3). Its lights are kept."
    is ImportKind.OtherPhone ->
        "Glyph ringtone for ${GlyphRingtone.phoneFor(k.columns) ?: "another phone"} (${k.columns} zones). Pick how the zones sit on the matrix."
    ImportKind.AudioOnly -> "Lights made from the music, from the part you pick (up to ${RingtoneImporter.MAX_SONG_MS / 1000} s)."
}

@Composable
private fun <T> Cards(options: List<T>, title: (T) -> String, hint: (T) -> String, selected: T, onPick: (T) -> Unit) {
    options.chunked(2).forEach { pair ->
        TileRow {
            pair.forEach { o -> ChoiceCard(title(o), hint(o), o == selected, Modifier.weight(1f)) { onPick(o) } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f), colors = ringSliderColors())
        Text(shown, color = Muted, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ringSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Surface2)

private enum class SetStep { MENU, RINGTONE_ASK, CONTACT_ASK, RULES }

/**
 * "Set as": phone ringtone, a contact, or a rule's notification sound. Special access (Modify system settings)
 * and WRITE_CONTACTS are only asked for when that row is picked, always with Not now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetAsSheet(ringtone: Uri, name: String, nav: Nav, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(SetStep.MENU) }
    var waitingForSettings by rememberSaveable { mutableStateOf(false) }
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()

    fun setPhone() {
        if (RingtoneActions.setPhoneRingtone(ctx, ringtone)) {
            toast(ctx, "Phone ringtone set")
            onDismiss()
        } else {
            toast(ctx, "Couldn't set the ringtone")
        }
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { contact ->
        if (contact == null) return@rememberLauncherForActivityResult
        val who = RingtoneActions.setContactRingtone(ctx, contact, ringtone)
        if (who != null) {
            toast(ctx, "$who now rings with $name")
            onDismiss()
        } else {
            toast(ctx, "Couldn't change that contact")
        }
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        step = SetStep.MENU
        if (granted) pickContact.launch(null) else toast(ctx, "Contacts editing is off. You can allow it in App info.")
    }
    LifecycleResumeEffect(waitingForSettings) {
        if (waitingForSettings && RingtoneActions.canWriteSettings(ctx)) {
            waitingForSettings = false
            setPhone()
        }
        onPauseOrDispose { }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            when (step) {
                SetStep.MENU -> {
                    Text("Set \"$name\" as", fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SheetRow("Phone ringtone", "Every incoming call") {
                        if (RingtoneActions.canWriteSettings(ctx)) setPhone() else step = SetStep.RINGTONE_ASK
                    }
                    SheetRow("A contact", "Only when this person calls") {
                        if (RingtoneActions.canWriteContacts(ctx)) pickContact.launch(null) else step = SetStep.CONTACT_ASK
                    }
                    SheetRow("Notification sound", "Plays with one of your rules") { step = SetStep.RULES }
                }
                SetStep.RINGTONE_ASK -> Ask(
                    "Allow changing the ringtone",
                    "Android needs your OK before DotForge can change the phone ringtone. Turn on Allow modifying system settings, then come back.",
                    "Open settings",
                    onAllow = {
                        waitingForSettings = true
                        runCatching { ctx.startActivity(RingtoneActions.writeSettingsIntent(ctx)) }
                    },
                    onNotNow = { step = SetStep.MENU },
                )
                SetStep.CONTACT_ASK -> Ask(
                    "Allow editing contacts",
                    "DotForge only changes the ringtone of the person you pick. Nothing else is touched.",
                    "Allow",
                    onAllow = { askContacts.launch(Manifest.permission.WRITE_CONTACTS) },
                    onNotNow = { step = SetStep.MENU },
                )
                SetStep.RULES -> {
                    Text("Play with which rule?", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    if (rules.isEmpty()) {
                        EmptyState("No rules yet", "Make a rule in Notifications first.", "Open Notifications") {
                            onDismiss()
                            nav.push(Page.Notifications)
                        }
                    }
                    rules.forEach { r ->
                        SheetRow(r.name, RuleText.sound(r)?.let { "Plays $it now" } ?: if (r.enabled) "No sound" else "Off · No sound") {
                            AppGraph.rules.upsert(r.copy(soundUri = ringtone.toString(), soundName = name))
                            toast(ctx, "\"${r.name}\" will play $name")
                            onDismiss()
                        }
                    }
                    TextButton(onClick = { step = SetStep.MENU }) { Text("Back", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, fontSize = 16.sp)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Ask(title: String, body: String, allow: String, onAllow: () -> Unit, onNotNow: () -> Unit) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Text(body, color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
    PrimaryButton(allow, Modifier.fillMaxWidth(), onClick = onAllow)
    TextButton(onClick = onNotNow, modifier = Modifier.padding(top = 8.dp)) { Text("Not now", color = Color.White) }
}
