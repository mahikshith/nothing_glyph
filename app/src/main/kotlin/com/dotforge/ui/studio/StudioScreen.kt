package com.dotforge.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.ringtones.SavedRingtone
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.EmptyState
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Screen
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.GetMoreSheet
import com.dotforge.ui.SetAsSheet

/** Designs show "Recent · A–Z" and a search field from this many on (row 42). */
const val STUDIO_SEARCH_FROM = 13

/** What the Studio screen shows over itself, for tests and links. */
sealed interface StudioSheet {
    data object New : StudioSheet
    data class Menu(val id: String) : StudioSheet
    data class Rename(val id: String) : StudioSheet
    data class UseFor(val id: String, val fresh: Boolean = false) : StudioSheet
    data class Share(val id: String) : StudioSheet
    data class SetAs(val uri: String, val name: String) : StudioSheet
    data object GetMore : StudioSheet
}

/** Studio: one red + New, Continue a design left open, My designs, My ringtones. */
@Composable
fun StudioScreen(nav: Nav, state: LazyListState, start: StudioSheet? = null, playing: String? = null) {
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    val editing by AppGraph.designs.editing.collectAsStateWithLifecycle()
    val ringtones by AppGraph.ringtones.items.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(start) }
    var query by rememberSaveable { mutableStateOf("") }
    var az by rememberSaveable { mutableStateOf(false) }
    var playingUri by remember { mutableStateOf(playing) }
    val snacks = rememberSnacks()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppGraph.ringtones.refresh() } }
    val recover = remember(editing, designs) { AppGraph.designs.recoverable() }

    fun open(d: Design) = openDesign(nav, d)

    // what a closed editor, Text or Photo screen asks for: Use for… on a new design, "Saved", or a message
    LaunchedEffect(nav.studioRequest) {
        val r = nav.studioRequest ?: return@LaunchedEffect
        nav.studioRequest = null
        val id = r.substringAfter(':')
        when (r.substringBefore(':')) {
            "usefor" -> if (AppGraph.designs.get(id) != null) sheet = StudioSheet.UseFor(id, fresh = true)
            "saved" -> AppGraph.designs.get(id)?.let { d -> snacks?.show("Saved ${d.name}", action = "Use for…") { sheet = StudioSheet.UseFor(d.id) } }
            "message" -> snacks?.show(id)
        }
    }

    Screen("Studio", state = state) {
        item(key = "new") {
            PrimaryButton("+ New", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { sheet = StudioSheet.New }
        }
        if (recover != null) item(key = "continue") {
            ContinueCard(recover, onContinue = { open(recover) }) {
                val d = recover
                AppGraph.designs.endEditing()
                DesignUse.delete(d, snacks)
            }
        }
        item(key = "designs-label") { SectionLabel("My designs") }
        if (designs.isEmpty()) {
            item(key = "designs-empty") {
                EmptyState("No designs yet. Draw one or pick one from the library.", "Draw", glyph = "star") { openNew(nav, animated = false) }
            }
        } else {
            val many = designs.size >= STUDIO_SEARCH_FROM
            if (many) item(key = "designs-find") {
                Column {
                    OutlinedTextField(
                        query, { query = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Search your designs", color = Tokens.Secondary) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Tokens.Secondary) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(Modifier.padding(top = Tokens.Small), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        Chip("Recent", !az) { az = false }
                        Chip("A–Z", az) { az = true }
                    }
                }
            }
            val shown = designs
                .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                .let { if (az) it.sortedBy { d -> d.name.lowercase() } else it }
            if (shown.isEmpty()) item(key = "designs-none") {
                Text("Nothing matches “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(vertical = Tokens.Gutter))
            }
            shown.chunked(3).forEachIndexed { row, three ->
                item(key = "designs-$row-${three.first().id}") {
                    Row(Modifier.fillMaxWidth().padding(bottom = Tokens.Gap), horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
                        three.forEach { d ->
                            DesignCard(d, Modifier.weight(1f), menuOpen = (sheet as? StudioSheet.Menu)?.id == d.id,
                                onOpen = { open(d) }, onMenu = { sheet = StudioSheet.Menu(d.id) }, onMenuDismiss = { sheet = null },
                                onAction = { a ->
                                    sheet = null
                                    when (a) {
                                        CardAction.USE -> sheet = StudioSheet.UseFor(d.id)
                                        CardAction.EDIT -> open(d)
                                        CardAction.RENAME -> sheet = StudioSheet.Rename(d.id)
                                        CardAction.DUPLICATE -> AppGraph.designs.duplicate(d.id)?.let { snacks?.show("Made ${it.name}") }
                                        CardAction.SHARE -> sheet = StudioSheet.Share(d.id)
                                        CardAction.DELETE -> DesignUse.delete(d, snacks)
                                    }
                                })
                        }
                        repeat(3 - three.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item(key = "ringtones-label") { SectionLabel("My ringtones") }
        item(key = "ringtones") {
            RowGroup {
                if (ringtones.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(Tokens.Gutter)) {
                        Text("No Glyph ringtones yet. Make one from a song.", style = MaterialTheme.typography.bodyLarge)
                        SecondaryButton("Make a ringtone", Modifier.padding(top = Tokens.Gap)) { sheet = StudioSheet.New }
                    }
                } else ringtones.forEachIndexed { i, r ->
                    if (i > 0) RowDivider(leading = true)
                    RingtoneRow(r, playingUri == r.uri.toString(),
                        onPlay = {
                            val u = r.uri.toString()
                            if (playingUri == u) { RingtonePlayer.stop(); playingUri = null } else { RingtonePlayer.play(ctx, r.uri) { playingUri = null }; playingUri = u }
                        },
                        onSet = { sheet = StudioSheet.SetAs(r.uri.toString(), r.title) },
                        onOpen = { nav.push(Page.RingtoneImport(r.uri.toString(), com.dotforge.ringtones.ImportMode.SAVED)) })
                }
                RowDivider()
                ListRow("Get more ringtones", subtitle = "Downloads and other phones' Glyph ringtones", trailing = { com.dotforge.ui.design.Chevron() }) { sheet = StudioSheet.GetMore }
            }
        }
    }

    when (val s = sheet) {
        StudioSheet.New -> NewSheet(nav, onDismiss = { sheet = null })
        is StudioSheet.Rename -> AppGraph.designs.get(s.id)?.let { d -> RenameDialog(d.name, onDismiss = { sheet = null }) { AppGraph.designs.rename(d.id, it); sheet = null } }
        is StudioSheet.UseFor -> AppGraph.designs.get(s.id)?.let { d -> UseForSheet(d, nav, s.fresh) { sheet = null } }
        is StudioSheet.Share -> AppGraph.designs.get(s.id)?.let { d -> ShareSheet(d, onDismiss = { sheet = null }) }
        is StudioSheet.SetAs -> SetAsSheet(android.net.Uri.parse(s.uri), s.name, nav) { sheet = null }
        StudioSheet.GetMore -> GetMoreSheet(nav) { sheet = null }
        is StudioSheet.Menu, null -> Unit
    }
}

/** Opens a design where it can still be changed: text in the Text screen, everything else in the editor. */
fun openDesign(nav: Nav, d: Design) = when (d.kind) {
    DesignKind.TEXT -> nav.push(Page.TextDesign(d.id))
    DesignKind.PHOTO -> nav.push(Page.Photo(null, d.id))
    else -> nav.push(Page.Editor(d.id, d.animated))
}

fun openNew(nav: Nav, animated: Boolean) = nav.push(Page.Editor(null, animated))

enum class CardAction(val label: String) { USE("Use for…"), EDIT("Edit"), RENAME("Rename"), DUPLICATE("Duplicate"), SHARE("Share…"), DELETE("Delete") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignCard(
    d: Design, modifier: Modifier, menuOpen: Boolean,
    onOpen: () -> Unit, onMenu: () -> Unit, onMenuDismiss: () -> Unit, onAction: (CardAction) -> Unit,
) {
    val anim = remember(d.frames) { AppGraph.designs.animation(d) }
    val used = remember(d.id, menuOpen) { DesignUse.names(d.id) }
    val subtitle = StudioText.subtitle(d, anim)
    Column(
        modifier
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .semantics {
                customActions = CardAction.entries.map { a -> CustomAccessibilityAction(a.label) { onAction(a); true } }
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Tokens.Card, Tokens.CardShape), contentAlignment = Alignment.Center) {
            GlyphPreview(anim, d.name, Modifier.fillMaxWidth().aspectRatio(1f).padding(14.dp), size = PreviewSize.MEDIUM)
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = onMenu, modifier = Modifier.size(Tokens.Target)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for ${d.name}", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss, containerColor = Tokens.Raised) {
                    if (used.isNotEmpty()) {
                        Text("Used by ${StudioText.list(used)}", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary,
                            modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small))
                    }
                    CardAction.entries.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.label, color = if (a == CardAction.DELETE) Tokens.AccentText else Color.White) },
                            onClick = { onAction(a) },
                            modifier = Modifier.heightIn(min = Tokens.Target),
                        )
                    }
                }
            }
            if (used.isNotEmpty()) {
                Row(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                    Text("In use", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Tokens.Small))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContinueCard(d: Design, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Tokens.Gap).background(Tokens.Card, Tokens.CardShape).padding(Tokens.Gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphPreview(remember(d.frames) { AppGraph.designs.animation(d) }, d.name, size = PreviewSize.SMALL)
            Column(Modifier.weight(1f).padding(start = Tokens.Gutter)) {
                Text("Continue ${d.name}?", style = MaterialTheme.typography.bodyLarge)
                Text("Edited ${StudioText.ago(d.updatedAt)}", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Tokens.Small), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Discard", color = Color.White) }
            TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Continue", color = Color.White) }
        }
    }
}

@Composable
private fun RingtoneRow(r: SavedRingtone, playing: Boolean, onPlay: () -> Unit, onSet: () -> Unit, onOpen: () -> Unit) {
    ListRow(
        r.title, subtitle = StudioText.clock(r.durationMs),
        leading = {
            IconButton(onClick = onPlay, modifier = Modifier.size(Tokens.Target).background(Tokens.Raised, CircleShape)) {
                Icon(if (playing) DfIcons.Pause else DfIcons.Play, contentDescription = if (playing) "Stop ${r.title}" else "Play ${r.title}", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        },
        trailing = { TextButton(onClick = onSet, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Set as", color = Color.White) } },
        onClick = onOpen,
    )
}

/** Plays one saved ringtone at a time from the list. */
object RingtonePlayer {
    private var player: android.media.MediaPlayer? = null

    fun play(ctx: android.content.Context, uri: android.net.Uri, onEnd: () -> Unit) {
        stop()
        player = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(ctx, uri)
                setOnCompletionListener { onEnd(); stop() }
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) onEnd()
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, current.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Raised,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value, { value = it.copy(text = it.text.take(30)) }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    supportingText = { Text("${value.text.length} / 30", color = Tokens.Secondary) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, cursorColor = Color.White),
                )
            }
        },
        confirmButton = {
            // an empty name keeps the old one
            TextButton(onClick = { onRename(value.text.trim().ifEmpty { current }) }, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Rename", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Cancel", color = Color.White) } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

