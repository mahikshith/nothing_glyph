package com.dotforge.ui.library

import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import com.dotforge.device.library.Category
import com.dotforge.ui.design.EmptyState
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
import kotlinx.coroutines.flow.first
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

/** Which tiles the browser shows: Featured, everything, favourites, recently used or one category. */
@Stable
class LibraryBrowserState(chip: String, query: String) {
    var chip by mutableStateOf(chip)
    var query by mutableStateOf(query)

    companion object {
        val FEATURED = Categories.Featured.id
        const val ALL = "all"
        const val FAVOURITES = "favourites"
        const val RECENT = "recent"
    }
}

@Composable
fun rememberLibraryBrowserState(chip: String = LibraryBrowserState.FEATURED, query: String = ""): LibraryBrowserState {
    var savedChip by rememberSaveable { mutableStateOf(chip) }
    var savedQuery by rememberSaveable { mutableStateOf(query) }
    val s = remember { LibraryBrowserState(savedChip, savedQuery) }
    LaunchedEffect(s.chip, s.query) { savedChip = s.chip; savedQuery = s.query }
    return s
}

/** One row of the grid: a count or tip line, a group heading (Text's Greetings, Love...) or a tile. */
private sealed interface Cell {
    data class Note(val text: String) : Cell
    data class Heading(val title: String) : Cell
    data class Tile(val item: LibraryItem) : Cell
}

/** The category chips' sheet groups: pictures, words, then the rest. */
private val WORDS = setOf(Categories.Text, Categories.GlyphText)

/**
 * The one library picker: a search field and one chip row (Featured, All, Favourites, Recently used and
 * Categories, which opens a grid of every category with its count), then a grid of tiles, each on its fixed
 * poster. The Library page, the Alerts glyph picker, the editor's Insert and Background, the Text screen's
 * background and Home all use it. Long-press a tile to add it to Favourites. [extra] goes before Featured's
 * tiles. [selectedId] is outlined. [gutter] pads the field and grid; the chip row runs to the edges. [header]
 * (a picker's None and From a photo) is the grid's first line, so it scrolls away with the tiles. In a [picker]
 * the count shows only for a search and there is no long-press tip, so the tiles get the room.
 *
 * Typing in Featured moves to All (Featured is a showcase, not a scope) and clearing goes back; in any other
 * chip the search stays inside it, with "Search everything" when nothing there matches.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryBrowser(
    modifier: Modifier = Modifier,
    state: LibraryBrowserState = rememberLibraryBrowserState(),
    filter: (LibraryItem) -> Boolean = { true },
    selectedId: String? = null,
    actionLabel: String = "Open",
    gutter: Dp = 0.dp,
    picker: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    extra: (LazyGridScope.() -> Unit)? = null,
    onOpen: (LibraryItem) -> Unit,
) {
    val snacks = rememberSnacks()
    val haptics = rememberHaptics()
    val categories = remember { Categories.all.filter { c -> c != Categories.Featured && AnimationLibrary.items(c).any(filter) } }
    val favourites = LibraryPrefs.favourites.toList()
    val recent = LibraryPrefs.recent.toList()
    val chip = state.chip
    val query = state.query.trim()
    var choosing by remember { mutableStateOf(false) }
    // typing in Featured searches everything, and the All chip says so; clearing the search goes back
    var autoAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query.isEmpty()) {
        if (query.isNotEmpty() && state.chip == LibraryBrowserState.FEATURED) { state.chip = LibraryBrowserState.ALL; autoAll = true }
        else if (query.isEmpty() && autoAll) { if (state.chip == LibraryBrowserState.ALL) state.chip = LibraryBrowserState.FEATURED; autoAll = false }
    }
    val cells: List<Cell> = remember(chip, query, favourites, recent) {
        val base = when {
            chip == LibraryBrowserState.ALL || (chip == LibraryBrowserState.FEATURED && query.isNotEmpty()) -> allInOrder
            chip == LibraryBrowserState.FAVOURITES -> favourites.mapNotNull(AnimationLibrary::item)
            chip == LibraryBrowserState.RECENT -> recent.mapNotNull(AnimationLibrary::item)
            else -> AnimationLibrary.items(Categories.byId(chip))
        }.filter(filter)
        val list = if (query.isEmpty()) base else base.toSet().let { inScope -> AnimationLibrary.search(query).filter { it in inScope } }
        if (list.isEmpty()) return@remember emptyList()
        // the count follows the filter; the long-press tip shows until the first favourite
        val count = "${list.size} ${if (list.size == 1) "design" else "designs"}" +
            if (!picker && favourites.isEmpty() && chip != LibraryBrowserState.FAVOURITES) " · Long-press one to keep it in Favourites" else ""
        val tiles = if (chip == Categories.Text.id && query.isEmpty()) {
            val groups = list.groupBy { it.group ?: "More" }
            (AnimationLibrary.textGroups + "More").flatMap { g -> groups[g]?.let { listOf(Cell.Heading(g)) + it.map(Cell::Tile) }.orEmpty() }
        } else list.map(Cell::Tile)
        if (picker && query.isEmpty()) tiles else listOf(Cell.Note(count)) + tiles
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            SearchField(state.query, { state.query = it.take(40) }, Modifier.weight(1f))
        }
        val chosen = categories.firstOrNull { it.id == chip }
        val chips = listOf(
            LibraryBrowserState.FEATURED to "Featured", LibraryBrowserState.ALL to "All",
            LibraryBrowserState.FAVOURITES to "Favourites", LibraryBrowserState.RECENT to "Recently used",
        )
        val chipRow = rememberLazyListState()
        // the selected chip scrolls into view (Favourites and Recently used start off the edge at 360 dp)
        LaunchedEffect(chip) {
            val index = if (chosen != null || chip !in chips.map { it.first }) 0 else 1 + chips.indexOfFirst { it.first == chip }
            androidx.compose.runtime.snapshotFlow { chipRow.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            val seen = chipRow.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val end = chipRow.layoutInfo.viewportEndOffset - chipRow.layoutInfo.afterContentPadding
            if (seen == null || seen.offset < 0 || seen.offset + seen.size > end) chipRow.animateScrollToItem(index)
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = Tokens.Tiny).testTag("library-chips"), state = chipRow,
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            // the chosen category leads, so it is never scrolled out of sight
            item(key = "categories") {
                Chip(chosen?.let { "${it.title} ▾" } ?: "Categories ▾", chosen != null, Modifier.semantics { contentDescription = chosen?.let { "Category: ${it.title}. Choose another" } ?: "Categories" }) { choosing = true }
            }
            items(chips, key = { it.first }) { (id, label) -> Chip(label, chip == id) { state.chip = id } }
        }
        val grid = rememberLazyGridState()
        LaunchedEffect(chip, query) { grid.scrollToItem(0) }
        if (cells.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = gutter)) {
                header?.invoke()
                val scope = chosen?.title ?: when (chip) {
                    LibraryBrowserState.FAVOURITES -> "Favourites"
                    LibraryBrowserState.RECENT -> "Recently used"
                    else -> null
                }
                when {
                    query.isNotEmpty() && scope != null -> EmptyState("Nothing in $scope matches “$query”.", "Search everything", glyph = "question", primary = false) { autoAll = false; state.chip = LibraryBrowserState.ALL }
                    query.isNotEmpty() -> EmptyState("Nothing matches “$query”.", "Clear search", glyph = "question", primary = false) { state.query = "" }
                    chip == LibraryBrowserState.FAVOURITES -> EmptyState("Long-press any design to keep it here.", "Browse Featured", glyph = "g-heart", primary = false) { state.chip = LibraryBrowserState.FEATURED }
                    chip == LibraryBrowserState.RECENT -> EmptyState("What you try, use or edit shows here.", "Browse Featured", glyph = "g-hourglass", primary = false) { state.chip = LibraryBrowserState.FEATURED }
                    else -> EmptyState("Nothing here yet.", "Browse Featured", primary = false) { state.chip = LibraryBrowserState.FEATURED }
                }
            }
        } else LazyVerticalGrid(
            GridCells.Adaptive(if (picker) PICKER_TILE_MIN else TILE_MIN), Modifier.fillMaxWidth().weight(1f).padding(top = Tokens.Tiny), state = grid,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Tokens.Gutter),
        ) {
            if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            if (chip == LibraryBrowserState.FEATURED && query.isEmpty()) extra?.invoke(this)
            items(cells, key = { c ->
                when (c) { is Cell.Tile -> c.item.id; is Cell.Heading -> "h:" + c.title; is Cell.Note -> "note" }
            }, span = { c -> if (c is Cell.Tile) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { c ->
                when (c) {
                    is Cell.Note -> Text(c.text, style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, maxLines = 2)
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
    if (choosing) CategoriesSheet(categories, filter, chip, onDismiss = { choosing = false }) { c -> choosing = false; state.chip = c.id }
}

/** The search field: one line, 48 dp, with a clear button while there is text. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value, onChange, modifier.heightIn(min = Tokens.Target).semantics { contentDescription = "Search the library" },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Target).background(Tokens.Card, RoundedCornerShape(24.dp)).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (value.isEmpty()) Text("Search the library", style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Color.White) }
                else Spacer(Modifier.width(14.dp))
            }
        },
    )
}

/** Every category as a tile with its picture and count: pictures first, then words, then everything else. */
@Composable
private fun CategoriesSheet(categories: List<Category>, filter: (LibraryItem) -> Boolean, current: String, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    val glyphs = categories.filter { it in GLYPH_CATEGORIES }
    val words = categories.filter { it in WORDS }
    val more = categories - glyphs.toSet() - words.toSet()
    FullSheet(onDismiss, "Categories", confirm = null) {
        for ((title, list) in listOf("Glyphs" to glyphs, "Words" to words, "More" to more)) {
            if (list.isEmpty()) continue
            com.dotforge.ui.design.SectionLabel(title)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                // tiles grow with the font so a one-word title ("Celebrations") never breaks mid-word
                val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
                val cols = (maxWidth / (104.dp * scale)).toInt().coerceIn(2, 4)
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    list.chunked(cols).forEach { row ->
                        // one height per row, so a two-line title doesn't make its card taller than the others
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                            row.forEach { c ->
                                val on = c.id == current
                                val items = remember(c) { AnimationLibrary.items(c).filter(filter) }
                                val icon = remember(c) { items.firstOrNull { it.form == ItemForm.STILL || it.form == ItemForm.LAYERED } ?: items.first() }
                                Column(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .background(if (on) Color.White.copy(alpha = 0.14f) else Tokens.Card, RoundedCornerShape(14.dp))
                                        .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
                                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show ${c.title}") { onPick(c) }
                                        .semantics(mergeDescendants = true) { contentDescription = "${c.title}, ${items.size}"; selected = on; if (on) stateDescription = "Showing" }
                                        .padding(Tokens.Small),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    DotMatrix(remember(icon.id) { AnimationLibrary.thumbnail(icon, AppGraph.profile) }.pixels, AppGraph.profile, Modifier.size(56.dp))
                                    Text(c.title, style = MaterialTheme.typography.bodyMedium.capped(1.5f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Tokens.Tiny))
                                    Spacer(Modifier.weight(1f))
                                    Text(if (on) "Showing · ${items.size}" else "${items.size}", style = MaterialTheme.typography.bodySmall, color = if (on) Color.White else Tokens.Secondary)
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Tags every item of a kind has: they find nothing in particular, so the item sheet leaves them out. */
private val PLAIN_TAGS = setOf("glyph", "text", "words", "word", "still", "animated", "animation", "moving", "glyph+text", "layered")

/** The glyph-art categories, listed first in the Categories sheet. */
private val GLYPH_CATEGORIES = with(Categories) { setOf(Love, Heartbreak, Moods, Anime, Seasonal, NatureWeather, Time, Utility, Food, Travel, Work, Sports) }

/** "All": Featured first, then each category in chip order. */
private val allInOrder: List<LibraryItem> by lazy {
    (AnimationLibrary.items(Categories.Featured) + Categories.all.filter { it != Categories.Featured }.flatMap(AnimationLibrary::items)).distinct()
}

/** The narrowest a tile gets; tiles grow to fill the row. */
private val TILE_MIN = 92.dp

/** A picker's tiles are smaller (4 across at 360 dp), so three rows show under its header. */
internal val PICKER_TILE_MIN = 76.dp

/**
 * A grid tile: the item's fixed poster (the whole design, never a fragment), a play badge when it moves, a heart
 * when it is a favourite, and the name on up to 2 lines.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryTile(item: LibraryItem, favourite: Boolean, selected: Boolean, actionLabel: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val moves = remember(item.id) { item.animated }
    val kind = when (item.form) {
        ItemForm.STILL, ItemForm.ANIMATION -> if (moves) "animated" else "still"
        ItemForm.TEXT -> if (moves) "moving text" else "text"
        ItemForm.LAYERED -> "glyph and text"
    }
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
            Poster(item, Modifier.fillMaxSize())
            if (moves) PlayBadge(Modifier.align(Alignment.BottomStart))
            if (favourite) Icon(
                Icons.Filled.Favorite, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape).padding(3.dp),
            )
        }
        TileName(item.name, if (selected) Color.White else Tokens.Secondary)
    }
}

/**
 * A tile's name on up to 2 lines, broken only between words: when a long word ("Fireworks") would split, the
 * text steps down in size until it fits whole.
 */
@Composable
fun TileName(name: String, color: Color, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.bodySmall.capped(1.5f)
    var shrink by remember(name) { mutableStateOf(1f) }
    Text(
        name, style = base.copy(fontSize = base.fontSize * shrink, lineHeight = base.lineHeight * shrink), color = color,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = modifier,
        onTextLayout = { r -> if (shrink > 0.7f && splitsAWord(r, name)) shrink -= 0.1f },
    )
}

/** True when a line of [r] ends inside a word of [text]. */
internal fun splitsAWord(r: androidx.compose.ui.text.TextLayoutResult, text: String): Boolean {
    for (i in 0 until r.lineCount) {
        val end = r.getLineEnd(i, visibleEnd = true)
        if (end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() && text[end - 1] != '-') return true
        if (i == r.lineCount - 1 && r.isLineEllipsized(i)) return true
    }
    return false
}

/** The one "it moves" badge, on every tile that animates: Library, pickers and your designs. */
@Composable
fun PlayBadge(modifier: Modifier = Modifier) = Icon(
    Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White,
    modifier = modifier.size(20.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape).padding(2.dp),
)

/** [item]'s fixed poster: the whole design held still. */
@Composable
fun Poster(item: LibraryItem, modifier: Modifier = Modifier) {
    val p = AppGraph.profile
    val poster = remember(item.id) { AnimationLibrary.thumbnail(item, p) }
    DotMatrix(poster.pixels, p, modifier)
}

/**
 * The item sheet's big preview: the poster first, then the loop from there; a play / pause button under it.
 * Under Remove animations it waits for Play.
 */
@Composable
private fun SheetPreview(item: LibraryItem) {
    val p = AppGraph.profile
    val anim = remember(item.id) { item.load(p) }
    val poster = remember(item.id) { AnimationLibrary.thumbnail(item, p) }
    val moves = anim.frames.size > 1
    val reduce = LocalReduceMotion.current
    var playing by remember(item.id) { mutableStateOf(moves && !reduce) }
    var frame by remember(item.id) { mutableStateOf(poster) }
    LaunchedEffect(item.id, playing) {
        if (!playing) { frame = poster; return@LaunchedEffect }
        delay(POSTER_HOLD_MS)
        val start = anim.frames.indexOfFirst { it.frame.pixels.contentEquals(poster.pixels) }.coerceAtLeast(0)
        var i = start
        while (true) {
            val f = anim.frames[i]
            frame = f.frame
            delay(f.durationMs.coerceAtLeast(Animation.MIN_FRAME_MS).toLong())
            i = (i + 1) % anim.frames.size
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DotMatrix(frame.pixels, p, Modifier.size(PreviewSize.LARGE.dp).semantics { contentDescription = com.dotforge.ui.design.previewLabel(item.name, anim) })
        if (moves) IconButton(
            onClick = { playing = !playing },
            modifier = Modifier.align(Alignment.BottomEnd).background(Tokens.Raised, CircleShape),
        ) {
            Icon(if (playing) DfIcons.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White)
        }
    }
}

/**
 * Tapping a tile: a big preview with play / pause, Try on Glyph (it turns into Stop while it shows), Use for…
 * (alerts, people, Home's player), Edit a copy and Favourite, with the credit line where the item needs one.
 * The buttons scroll under the preview, with a fade while more is below.
 */
@Composable
fun LibraryItemSheet(item: LibraryItem, nav: Nav, onDismiss: () -> Unit, onUseFor: (Design) -> Unit) {
    val fav = item.id in LibraryPrefs.favourites
    val active by AppGraph.arbiter.active.collectAsState()
    val trying = active.any { it.id == LibraryActions.TRY_ID && it.label == "Library: ${item.name}" }
    // always "Category · group · Still / Animated", the same words for every kind
    val subtitle = remember(item.id) { listOfNotNull(item.category.title, item.group, if (item.animated) "Animated" else "Still").joinToString(" · ") }
    FullSheet(onDismiss, item.name, subtitle = subtitle, pinned = { SheetPreview(item) }) {
        val snacks = rememberSnacks()
        val opens = when (item.form) {
            ItemForm.TEXT -> "Edit a copy opens it in the Text screen."
            ItemForm.LAYERED -> "Edit a copy opens it in the editor with its glyph and text layers."
            else -> null
        }
        opens?.let { Hint(it) }
        item.credit?.let { Text("Credit: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Tiny)) }
        PrimaryButton(if (trying) "Stop on Glyph" else "Try on Glyph", Modifier.fillMaxWidth().padding(top = Tokens.Gutter).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
            if (trying) AppGraph.arbiter.remove(LibraryActions.TRY_ID) else LibraryActions.tryOn(item)
        }
        SecondaryButton("Use for…", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { LibraryPrefs.used(item.id); onUseFor(LibraryActions.design(item)) }
        SecondaryButton("Edit a copy", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { onDismiss(); LibraryActions.editCopy(item, nav) }
        SecondaryButton(if (fav) "Remove from Favourites" else "Add to Favourites", Modifier.fillMaxWidth().padding(top = Tokens.Small).semantics { stateDescription = if (fav) "Favourite" else "Not a favourite" }) {
            val now = LibraryPrefs.toggleFavourite(item.id)
            snacks?.show(if (now) "Added to Favourites" else "Removed from Favourites") { LibraryPrefs.toggleFavourite(item.id) }
        }
        val tags = item.displayTags.filter { it.isNotBlank() && it != item.name.lowercase() && it !in PLAIN_TAGS }.take(6)
        if (tags.isNotEmpty()) Text(
            "Find it with: " + tags.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary,
            modifier = Modifier.padding(top = Tokens.Gutter),
        )
    }
}

/**
 * The library picker in a sheet, for hosts: a tap picks. It opens at full height, with the Library / Yours switch
 * (with [onPickYours]) on the title line, so at 360 dp three rows of tiles show. [filter] limits what shows;
 * [top] (a photo option, App icon) is the grid's first line.
 */
@Composable
fun LibraryPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    filter: (LibraryItem) -> Boolean = { true },
    selectedId: String? = null,
    top: (@Composable () -> Unit)? = null,
    extra: (LazyGridScope.() -> Unit)? = null,
    onPickYours: ((Design) -> Unit)? = null,
    onPick: (LibraryItem) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    FullSheet(onDismiss, title, confirm = null, scrolls = false, fill = true, titleEnd = onPickYours?.let { { LibraryYoursSwitch(tab) { tab = it } } }) {
        if (tab == 0 || onPickYours == null) {
            LibraryBrowser(Modifier.fillMaxWidth().weight(1f), filter = filter, selectedId = selectedId, actionLabel = "Use", picker = true, header = top, extra = extra) { item ->
                LibraryPrefs.used(item.id)
                onPick(item)
            }
        } else YoursGrid(Modifier.fillMaxWidth().weight(1f).padding(top = Tokens.Small), onPickYours)
    }
}

/** A picker sheet's Library / Yours switch, on the title line: as narrow as its two words. */
@Composable
fun LibraryYoursSwitch(tab: Int, onSelect: (Int) -> Unit) {
    val style = MaterialTheme.typography.labelLarge
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val size = if (scale > 1.5f) style.fontSize * (1.5f / scale) else style.fontSize
    Row(
        Modifier.background(Tokens.Card, RoundedCornerShape(14.dp)).padding(Tokens.Tiny),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        listOf("Library", "Yours").forEachIndexed { i, label ->
            val on = i == tab
            androidx.compose.material3.Surface(
                onClick = { onSelect(i) }, selected = on, shape = RoundedCornerShape(10.dp),
                color = if (on) Color.White else Color.Transparent, contentColor = if (on) Color.Black else Tokens.Secondary,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(label, style = style.copy(fontSize = size), maxLines = 1) }
            }
        }
    }
}

/** A picker's own action (None, From a photo) as a pill on the grid's first line. */
@Composable
fun PickerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = Tokens.Target).clip(RoundedCornerShape(24.dp)).background(Tokens.Raised)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, modifier = Modifier.padding(start = Tokens.Small))
    }
}

/** A picker's actions on one line, wrapping when the text is large. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PickerActions(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(bottom = Tokens.Tiny),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) { content() }

/** Your saved designs as a grid of tiles; a tap picks one. */
@Composable
fun YoursGrid(modifier: Modifier, onPick: (Design) -> Unit) {
    val designs by AppGraph.designs.designs.collectAsState()
    if (designs.isEmpty()) {
        EmptyState("Nothing saved yet. Designs you make show here.", modifier = modifier)
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(PICKER_TILE_MIN), modifier,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        items(designs, key = { it.id }) { d ->
            val thumb = remember(d.id) { AppGraph.designs.animation(d)?.let(com.dotforge.device.Posters::of) }
            Column(
                Modifier.clickable(onClickLabel = "Use ${d.name}") { onPick(d) }.padding(Tokens.Tiny),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    DotMatrix(thumb?.pixels, AppGraph.profile, Modifier.fillMaxSize().semantics { contentDescription = d.name })
                    if (d.animated) PlayBadge(Modifier.align(Alignment.BottomStart))
                }
                TileName(d.name, Tokens.Secondary)
            }
        }
    }
}

/**
 * The Library page: back and search on one line, one chip row, then the grid (the header stays under a quarter
 * of the screen at 360 dp and large text); a tile opens its sheet.
 */
@Composable
fun LibraryScreen(nav: Nav, state: LibraryBrowserState = rememberLibraryBrowserState()) {
    var open by remember { mutableStateOf<String?>(null) }
    var useFor by remember { mutableStateOf<Design?>(null) }
    androidx.activity.compose.BackHandler(onBack = nav::pop)
    Column(
        Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = Tokens.Tiny)
            .semantics { paneTitle = "Library" },
    ) {
        LibraryBrowser(Modifier.fillMaxWidth().weight(1f), state = state, gutter = Tokens.Gutter, leading = {
            IconButton(onClick = nav::pop, modifier = Modifier.padding(end = Tokens.Tiny)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }) { open = it.id }
    }
    open?.let { id -> AnimationLibrary.item(id) }?.let { item ->
        LibraryItemSheet(item, nav, onDismiss = { open = null }) { d -> open = null; useFor = d }
    }
    useFor?.let { d -> UseForSheet(d, nav, fresh = true, unsaved = true) { useFor = null } }
}

/** Fixed sizes for a compact strip of tiles (Home). */
internal val HOME_TILE: Dp = 84.dp
