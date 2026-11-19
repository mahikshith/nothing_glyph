package com.dotforge.ui.library

import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.device.library.Categories
import com.dotforge.device.library.ItemForm
import com.dotforge.device.library.LibraryItem
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.ui.Nav
import com.dotforge.ui.Page as Route
import com.dotforge.ui.design.Chip
import com.dotforge.ui.design.DfIcons
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.LocalPreviewsPaused
import com.dotforge.ui.design.LocalReduceMotion
import com.dotforge.ui.design.POSTER_HOLD_MS
import com.dotforge.ui.design.PREVIEW_MIN_FRAME_MS
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.TopBar
import com.dotforge.ui.design.capped
import com.dotforge.ui.design.rememberHaptics
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.editor.Drafts
import com.dotforge.ui.studio.TextStarts
import com.dotforge.ui.studio.UseForSheet
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Favourites (long-press a tile) and Recently used, kept in SharedPreferences. Without [init] (tests, previews)
 * they live in memory only.
 */
object LibraryPrefs {
    const val MAX_RECENT = 24
    private var prefs: SharedPreferences? = null

    /** Favourite ids, newest first. */
    val favourites = mutableStateListOf<String>()

    /** Recently used ids, newest first. */
    val recent = mutableStateListOf<String>()

    fun init(p: SharedPreferences) {
        prefs = p
        favourites.clear(); favourites += read(p, FAV)
        recent.clear(); recent += read(p, RECENT)
    }

    private fun read(p: SharedPreferences, key: String) = p.getString(key, null).orEmpty().split('\n').filter { it.isNotBlank() }

    private fun write() {
        prefs?.edit()?.putString(FAV, favourites.joinToString("\n"))?.putString(RECENT, recent.joinToString("\n"))?.apply()
    }

    fun isFavourite(id: String) = id in favourites

    /** Adds or removes [id]; true when it is a favourite now. */
    fun toggleFavourite(id: String): Boolean {
        val now = id !in favourites
        if (now) favourites.add(0, id) else favourites.remove(id)
        write()
        return now
    }

    /** Marks [id] used: it moves to the front of Recently used. */
    fun used(id: String) {
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        write()
    }

    /** Forgets everything (tests). */
    fun reset() {
        favourites.clear(); recent.clear(); write()
    }

    private const val FAV = "favourites"
    private const val RECENT = "recent"
}

/** What the Library does with an item: play it on the Glyph, save a copy, open a copy to edit. */
object LibraryActions {
    const val TRY_ID = "library-try"

    fun animation(item: LibraryItem): Animation = item.load(AppGraph.profile)

    /** Plays [item] on the Glyph for a few seconds (or one loop of a long one). */
    fun tryOn(item: LibraryItem) {
        val a = animation(item)
        AppGraph.arbiter.post(
            DisplayRequest(TRY_ID, Priority.STUDIO_PREVIEW, a, loop = true, timeoutMs = max(6_000L, a.totalMs.toLong()), label = "Library: ${item.name}"),
        )
        LibraryPrefs.used(item.id)
    }

    /** [item] as one of your designs: text stays editable text, glyph + text keeps its layers. */
    fun design(item: LibraryItem): Design {
        val p = AppGraph.profile
        val a = animation(item)
        val now = System.currentTimeMillis()
        val layers = item.layers(p)
        return when {
            item.form == ItemForm.TEXT && item.text != null ->
                Design(name = item.name, animated = a.frames.size > 1, frames = a.toMuseumJson(), updatedAt = now, kind = DesignKind.TEXT, text = item.text)
            layers != null -> Design(
                name = item.name, animated = true, frames = a.toMuseumJson(), updatedAt = now, layers = layers,
                drawing = Animation.single(Frame.blank(p), 1000).toMuseumJson(),
            )
            else -> Design(name = item.name, animated = a.frames.size > 1, frames = a.toMuseumJson(), updatedAt = now)
        }
    }

    /** Saves a copy into your designs (for Use for…) and returns it. */
    fun saveCopy(item: LibraryItem): Design = design(item).also { AppGraph.designs.save(it); LibraryPrefs.used(item.id) }

    /** Opens a copy to edit: text in the Text screen, glyph + text in the editor with both layers, the rest in the editor. */
    fun editCopy(item: LibraryItem, nav: Nav) {
        val p = AppGraph.profile
        LibraryPrefs.used(item.id)
        val spec = item.text
        val layers = item.layers(p)
        when {
            item.form == ItemForm.TEXT && spec != null -> nav.push(Route.TextDesign(null, start = TextStarts.put(spec, item.name)))
            layers != null -> nav.push(Route.Editor(null, true, Drafts.put(item.name, Animation.single(Frame.blank(p), 1000), layers = layers)))
            else -> {
                val a = animation(item)
                nav.push(Route.Editor(null, a.frames.size > 1, Drafts.put(item.name, a)))
            }
        }
    }
}

/** Which tiles the browser shows: everything, favourites, recently used or one category. */
@Stable
class LibraryBrowserState(chip: String, query: String) {
    var chip by mutableStateOf(chip)
    var query by mutableStateOf(query)

    companion object {
        const val ALL = "all"
        const val FAVOURITES = "favourites"
        const val RECENT = "recent"
    }
}

@Composable
fun rememberLibraryBrowserState(chip: String = LibraryBrowserState.ALL, query: String = ""): LibraryBrowserState {
    var savedChip by rememberSaveable { mutableStateOf(chip) }
    var savedQuery by rememberSaveable { mutableStateOf(query) }
    val s = remember { LibraryBrowserState(savedChip, savedQuery) }
    LaunchedEffect(s.chip, s.query) { savedChip = s.chip; savedQuery = s.query }
    return s
}

/** One row of the grid: a group heading (Text's Greetings, Love...) or a tile. */
private sealed interface Cell {
    data class Heading(val title: String) : Cell
    data class Tile(val item: LibraryItem) : Cell
}

/**
 * The one library picker: search, then "All", Favourites, Recently used and the category chips, then a grid of
 * live tiles. The Library page, the Alerts glyph picker, the editor's Insert and Background, the Text screen's
 * background and Home all use it. Long-press a tile to add it to Favourites. [extra] goes before the tiles
 * (the alerts picker's App icon and Their name). [selectedId] is outlined.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryBrowser(
    modifier: Modifier = Modifier,
    state: LibraryBrowserState = rememberLibraryBrowserState(),
    filter: (LibraryItem) -> Boolean = { true },
    selectedId: String? = null,
    actionLabel: String = "Open",
    extra: (LazyGridScope.() -> Unit)? = null,
    onOpen: (LibraryItem) -> Unit,
) {
    val snacks = rememberSnacks()
    val haptics = rememberHaptics()
    val categories = remember { Categories.all.filter { c -> AnimationLibrary.items(c).any(filter) } }
    val favourites = LibraryPrefs.favourites.toList()
    val recent = LibraryPrefs.recent.toList()
    val chip = state.chip
    val query = state.query.trim()
    val cells: List<Cell> = remember(chip, query, favourites, recent) {
        val base = when (chip) {
            LibraryBrowserState.ALL -> allInOrder
            LibraryBrowserState.FAVOURITES -> favourites.mapNotNull(AnimationLibrary::item)
            LibraryBrowserState.RECENT -> recent.mapNotNull(AnimationLibrary::item)
            else -> AnimationLibrary.items(Categories.byId(chip))
        }.filter(filter)
        val list = if (query.isEmpty()) base else base.toSet().let { inScope -> AnimationLibrary.search(query).filter { it in inScope } }
        if (chip == Categories.Text.id && query.isEmpty()) {
            val groups = list.groupBy { it.group ?: "More" }
            (com.dotforge.device.library.AnimationLibrary.textGroups + "More").flatMap { g -> groups[g]?.let { listOf(Cell.Heading(g)) + it.map(Cell::Tile) }.orEmpty() }
        } else list.map(Cell::Tile)
    }
    Column(modifier) {
        OutlinedTextField(
            state.query, { state.query = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search the library", color = Tokens.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Tokens.Secondary) },
            trailingIcon = if (state.query.isNotEmpty()) ({
                IconButton(onClick = { state.query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Color.White) }
            }) else null,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Tokens.Hairline, cursorColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        )
        val chips = listOf(LibraryBrowserState.ALL to "All", LibraryBrowserState.FAVOURITES to "Favourites", LibraryBrowserState.RECENT to "Recently used") +
            categories.map { it.id to it.title }
        val chipState = rememberLazyListState(initialFirstVisibleItemIndex = chips.indexOfFirst { it.first == chip }.coerceAtLeast(0).let { if (it < 2) 0 else it - 1 })
        LazyRow(
            Modifier.fillMaxWidth().padding(top = Tokens.Small).testTag("library-chips"), state = chipState,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            items(chips, key = { it.first }) { (id, label) -> Chip(label, chip == id) { state.chip = id } }
        }
        val grid = rememberLazyGridState()
        LaunchedEffect(chip, query) { grid.scrollToItem(0) }
        val empty = cells.isEmpty()
        if (empty) {
            Column(Modifier.fillMaxWidth().padding(top = Tokens.Section), horizontalAlignment = Alignment.CenterHorizontally) {
                val (title, line) = when {
                    query.isNotEmpty() -> "No results for “$query”" to
                        (if (chip != LibraryBrowserState.ALL) "Nothing here matches. Try All, or other words." else "Try other words, like heart, rain or birthday.")
                    chip == LibraryBrowserState.FAVOURITES -> "No favourites yet" to "Long-press any tile to add it here."
                    chip == LibraryBrowserState.RECENT -> "Nothing used yet" to "What you try, use or edit shows here."
                    else -> "Nothing here" to "Pick another category."
                }
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Tiny))
                if (query.isNotEmpty() && chip != LibraryBrowserState.ALL) {
                    SecondaryButton("Search everything", Modifier.padding(top = Tokens.Gutter)) { state.chip = LibraryBrowserState.ALL }
                }
            }
        }
        CompositionLocalProvider(LocalPreviewsPaused provides grid.isScrollInProgress) {
            LazyVerticalGrid(
                GridCells.Adaptive(TILE_MIN), Modifier.fillMaxWidth().weight(1f, fill = !empty).padding(top = Tokens.Small), state = grid,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                contentPadding = PaddingValues(bottom = Tokens.Gutter),
            ) {
                if (chip == LibraryBrowserState.ALL && query.isEmpty()) extra?.invoke(this)
                items(cells, key = { c -> if (c is Cell.Tile) c.item.id else "h:" + (c as Cell.Heading).title }, span = { c ->
                    if (c is Cell.Heading) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                }) { c ->
                    when (c) {
                        is Cell.Heading -> Text(
                            c.title, style = MaterialTheme.typography.titleSmall, color = Tokens.Secondary,
                            modifier = Modifier.padding(top = Tokens.Small).semantics { heading() },
                        )
                        is Cell.Tile -> {
                            val item = c.item
                            val fav = item.id in favourites
                            LibraryTile(item, fav, item.id == selectedId, actionLabel, onClick = { onOpen(item) }, onLongClick = {
                                haptics.confirm()
                                val now = LibraryPrefs.toggleFavourite(item.id)
                                snacks?.show(if (now) "Added ${item.name} to Favourites" else "Removed ${item.name} from Favourites") { LibraryPrefs.toggleFavourite(item.id) }
                            })
                        }
                    }
                }
            }
        }
    }
}

/** "All": Featured first, then each category in chip order. */
private val allInOrder: List<LibraryItem> by lazy {
    (AnimationLibrary.items(Categories.Featured) + Categories.all.filter { it != Categories.Featured }.flatMap(AnimationLibrary::items)).distinct()
}

/** The narrowest a tile gets; tiles grow to fill the row. */
private val TILE_MIN = 92.dp

/** A grid tile: the live preview, a heart badge when it is a favourite, and the name on up to 2 lines. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryTile(item: LibraryItem, favourite: Boolean, selected: Boolean, actionLabel: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val kind = when (item.form) { ItemForm.STILL -> "still"; ItemForm.ANIMATION -> "animated"; ItemForm.TEXT -> "text"; ItemForm.LAYERED -> "glyph and text" }
    Column(
        Modifier
            .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClickLabel = "$actionLabel ${item.name}", onLongClickLabel = if (favourite) "Remove from Favourites" else "Add to Favourites", onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.name}, $kind"
                if (favourite) stateDescription = "Favourite"
                this.selected = selected
                customActions = listOf(CustomAccessibilityAction(if (favourite) "Remove from Favourites" else "Add to Favourites") { onLongClick(); true })
            }
            .padding(Tokens.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            LivePreview(item, Modifier.fillMaxSize())
            if (favourite) Icon(
                Icons.Filled.Favorite, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape).padding(3.dp),
            )
        }
        Text(
            item.name, style = MaterialTheme.typography.bodySmall.capped(1.5f), color = if (selected) Color.White else Tokens.Secondary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/**
 * [item] playing in a square: its brightest frame first, then the loop on from there (never faster than 3 frames a second),
 * paused while the grid scrolls and still under Remove animations.
 */
@Composable
fun LivePreview(item: LibraryItem, modifier: Modifier = Modifier) {
    val p = AppGraph.profile
    val anim = remember(item.id) { item.load(p) }
    val poster = remember(item.id) { AnimationLibrary.thumbnail(item, p) }
    val run = anim.frames.size > 1 && !LocalPreviewsPaused.current && !LocalReduceMotion.current
    var frame by remember(item.id) { mutableStateOf(poster) }
    LaunchedEffect(item.id, run) {
        if (!run) { frame = poster; return@LaunchedEffect }
        delay(POSTER_HOLD_MS)
        // carry on from the poster's frame, so scrolling words don't jump back to an empty start
        val start = anim.frames.indexOfFirst { it.frame.pixels.contentEquals(poster.pixels) }.coerceAtLeast(0)
        var i = start
        while (true) {
            val f = anim.frames[i]
            frame = f.frame
            delay(f.durationMs.coerceAtLeast(PREVIEW_MIN_FRAME_MS).toLong())
            i = (i + 1) % anim.frames.size
        }
    }
    DotMatrix(frame.pixels, p, modifier)
}

/**
 * Tapping a tile: a big preview, Try on Glyph, Use for… (alerts, the caller glyph, Home's player), Edit a copy and
 * Favourite, with the credit line where the item needs one.
 */
@Composable
fun LibraryItemSheet(item: LibraryItem, nav: Nav, onDismiss: () -> Unit, onUseFor: (Design) -> Unit) {
    val anim = remember(item.id) { LibraryActions.animation(item) }
    val fav = item.id in LibraryPrefs.favourites
    val subtitle = when {
        item.category.title != item.form.label -> "${item.category.title} · ${item.form.label}"
        item.group != null -> "${item.category.title} · ${item.group}"
        else -> item.category.title
    }
    FullSheet(onDismiss, item.name, subtitle = subtitle, pinned = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GlyphPreview(anim, item.name, size = PreviewSize.LARGE, userPlay = true) }
    }) {
        // the sheet's own snackbar host, so "Showing … on the Glyph" shows on the sheet
        val snacks = rememberSnacks()
        val opens = when (item.form) {
            ItemForm.TEXT -> "Edit a copy opens it in the Text screen."
            ItemForm.LAYERED -> "Edit a copy opens it in the editor with its glyph and text layers."
            else -> null
        }
        opens?.let { Hint(it) }
        item.credit?.let { Text("Credit: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Tiny)) }
        PrimaryButton("Try on Glyph", Modifier.fillMaxWidth().padding(top = Tokens.Gutter)) {
            LibraryActions.tryOn(item)
            snacks?.show("Showing ${item.name} on the Glyph", action = "Stop") { AppGraph.arbiter.remove(LibraryActions.TRY_ID) }
        }
        SecondaryButton("Use for…", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { onUseFor(LibraryActions.saveCopy(item)) }
        SecondaryButton("Edit a copy", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { onDismiss(); LibraryActions.editCopy(item, nav) }
        Row(
            Modifier.fillMaxWidth().padding(top = Tokens.Small), verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(if (fav) "Remove from Favourites" else "Add to Favourites", Modifier.fillMaxWidth().semantics { stateDescription = if (fav) "Favourite" else "Not a favourite" }) {
                val now = LibraryPrefs.toggleFavourite(item.id)
                snacks?.show(if (now) "Added to Favourites" else "Removed from Favourites") { LibraryPrefs.toggleFavourite(item.id) }
            }
        }
        if (item.tags.isNotEmpty()) Text(
            item.tags.take(8).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary,
            modifier = Modifier.padding(top = Tokens.Gutter),
        )
    }
}

/**
 * The library picker in a sheet, for hosts: a tap picks. [filter] limits what shows; [top] sits above (a photo
 * option, App icon and Their name).
 */
@Composable
fun LibraryPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    filter: (LibraryItem) -> Boolean = { true },
    selectedId: String? = null,
    top: (@Composable () -> Unit)? = null,
    extra: (LazyGridScope.() -> Unit)? = null,
    onPick: (LibraryItem) -> Unit,
) {
    val height = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    FullSheet(onDismiss, title, confirm = null, scrolls = false) {
        top?.invoke()
        LibraryBrowser(Modifier.fillMaxWidth().height(height), filter = filter, selectedId = selectedId, actionLabel = "Use", extra = extra) { item ->
            LibraryPrefs.used(item.id)
            onPick(item)
        }
    }
}

/** The Library page: the browser over the whole screen; a tile opens its sheet. */
@Composable
fun LibraryScreen(nav: Nav, state: LibraryBrowserState = rememberLibraryBrowserState()) {
    var open by remember { mutableStateOf<String?>(null) }
    var useFor by remember { mutableStateOf<Design?>(null) }
    androidx.activity.compose.BackHandler(onBack = nav::pop)
    Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar("Library", nav::pop)
        Text(
            "${AnimationLibrary.all.size} glyphs, texts and animations. Long-press to add a favourite.",
            style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary,
            modifier = Modifier.padding(horizontal = Tokens.Gutter).padding(bottom = Tokens.Small),
        )
        LibraryBrowser(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Tokens.Gutter), state = state) { open = it.id }
    }
    open?.let { id -> AnimationLibrary.item(id) }?.let { item ->
        LibraryItemSheet(item, nav, onDismiss = { open = null }) { d -> open = null; useFor = d }
    }
    useFor?.let { d -> UseForSheet(d, nav, fresh = true) { useFor = null } }
}

/** Fixed sizes for a compact strip of tiles (Home). */
internal val HOME_TILE: Dp = 84.dp
