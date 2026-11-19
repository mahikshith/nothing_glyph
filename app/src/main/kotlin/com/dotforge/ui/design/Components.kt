package com.dotforge.ui.design

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.GlyphLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- screens

/**
 * A top-level tab screen: a large title in the dot font, optional actions on the right (a gear), and one lazy
 * list below. The list is the only scroller on the screen.
 */
@Composable
fun Screen(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    CompositionLocalProvider(LocalPreviewsPaused provides state.isScrollInProgress) { LazyColumn(
        modifier.fillMaxSize().background(Tokens.Background),
        state = state,
        contentPadding = PaddingValues(start = Tokens.Gutter, end = Tokens.Gutter, bottom = listBottom()),
    ) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                actions()
            }
        }
        content()
    } }
}

/**
 * A page pushed over a tab: back arrow, title, optional actions, one lazy list and an optional pinned bottom
 * bar. System back calls [onBack].
 */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(title, onBack, actions = actions)
        CompositionLocalProvider(LocalPreviewsPaused provides state.isScrollInProgress) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = state,
                contentPadding = PaddingValues(start = Tokens.Gutter, end = Tokens.Gutter, bottom = listBottom()),
                content = content,
            )
        }
        bottomBar?.let { Box(Modifier.fillMaxWidth().padding(Tokens.Gutter)) { it() } }
    }
}

/** Space under a list: the section gap, plus room for the snackbar while one shows, so it never covers the last row. */
@Composable
private fun listBottom(): Dp = Tokens.Section + if (LocalSnacks.current?.visible == true) SNACKBAR_ROOM else 0.dp

private val SNACKBAR_ROOM = 72.dp

/** A 64 dp bar: back (or close), a title, actions. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, close: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            if (close) Icon(Icons.Filled.Close, contentDescription = "Close")
            else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title, Modifier.weight(1f).padding(start = Tokens.Tiny).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

// ---------------------------------------------------------------- text

/** A label above a section: 24 dp above it, 8 dp below. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(
        text,
        modifier.fillMaxWidth().padding(top = Tokens.Section, bottom = Tokens.Small).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = Tokens.Secondary,
    )

/** One quiet line of help or status. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.fillMaxWidth().padding(vertical = Tokens.Small), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)

// ---------------------------------------------------------------- rows

/** A card that groups rows (20 dp corners, #141414). */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Surface(color = Tokens.Card, shape = Tokens.CardShape, modifier = modifier.fillMaxWidth()) { Column(content = content) }

/**
 * A list row: leading preview or avatar, a title and one line, a trailing control. At least 64 dp (72 with a
 * subtitle); the whole row is the touch target when it has an action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    inset: Dp = Tokens.Gutter,
    subtitleLines: Int = 2,
    titleLines: Int = 2,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val click = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(enabled = enabled, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else Modifier
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (subtitle != null) Tokens.RowTall else Tokens.Row)
            .then(click)
            .padding(horizontal = inset, vertical = Tokens.Small)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        leading?.let { Box(Modifier.size(Tokens.Avatar), contentAlignment = Alignment.Center) { it() } }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

/**
 * The divider between rows in a [RowGroup]. It starts where the row text starts: 16 dp in, or 72 dp when the
 * rows have a leading preview, avatar or icon ([leading]).
 */
@Composable
fun RowDivider(leading: Boolean = false) =
    androidx.compose.material3.HorizontalDivider(Modifier.padding(start = if (leading) TEXT_AFTER_ICON else Tokens.Gutter), color = Tokens.Hairline)

/** Where text starts after a 40 dp leading slot: 16 + 40 + 16. */
val TEXT_AFTER_ICON = 72.dp

/** A row whose whole surface toggles a switch; TalkBack reads it as one switch. A light tick on change. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    inset: Dp = Tokens.Gutter,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = rememberHaptics()
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (subtitle != null) Tokens.RowTall else Tokens.Row)
            .toggleable(checked, enabled = enabled, role = Role.Switch) { haptic.toggle(it); onCheckedChange(it) }
            .padding(horizontal = inset, vertical = Tokens.Small)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        leading?.let { Box(Modifier.size(Tokens.Avatar), contentAlignment = Alignment.Center) { it() } }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
}

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Tokens.Accent,
    checkedBorderColor = Tokens.Accent,
    uncheckedThumbColor = Tokens.Secondary,
    uncheckedTrackColor = Color(0xFF333333),
    uncheckedBorderColor = Color(0xFF555555),
)

/** The chevron at the end of a row that opens something. */
@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Tokens.Secondary)

/** A 40 dp round avatar with a letter (apps without an icon, people without a photo). */
@Composable
fun Avatar(letter: String, modifier: Modifier = Modifier) =
    Box(modifier.size(Tokens.Avatar).background(Tokens.Raised, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }

/** The account avatar: a person icon in a 40 dp circle (no initials until there is an account). */
@Composable
fun PersonAvatar(modifier: Modifier = Modifier, size: Dp = Tokens.Avatar) =
    Box(modifier.size(size).background(Tokens.Raised, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }

/** "+ Add an app": a row with a round plus. */
@Composable
fun AddRow(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    ListRow(
        text, modifier,
        leading = {
            Box(Modifier.size(Tokens.Avatar).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Black)
            }
        },
        onClick = onClick,
    )

// ---------------------------------------------------------------- tiles

/**
 * An equal tile for a 2-column grid. Tiles in a [TilePair] share one height: at least [height] (104 dp for Home
 * tiles, 120 dp for Features cards), or the tallest one needs at this font scale. Slots, top to bottom: an
 * optional icon; the name (up to 2 lines) with the switch, when there is one, on its right; the status on one
 * line across the full width. The whole tile opens [onOpen]; the switch is its own target, and TalkBack also gets
 * an "Open settings" action on it.
 */
@Composable
fun EqualTile(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    enabled: Boolean = true,
    height: Dp = Tokens.Tile,
    icon: (@Composable () -> Unit)? = null,
    /** Home lets the status wrap to a second line at large text rather than cut it. */
    statusLines: Int = 1,
    onToggle: (Boolean) -> Unit = {},
    onOpen: (() -> Unit)? = null,
) {
    val haptic = rememberHaptics()
    Surface(color = Tokens.Card, shape = Tokens.CardShape, modifier = modifier.heightIn(min = height)) {
        Column(
            Modifier.fillMaxSize()
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open settings", role = Role.Button, onClick = onOpen) else Modifier)
                .padding(start = Tokens.Gutter, end = if (checked != null) Tokens.Tiny else Tokens.Gutter, top = Tokens.Gap, bottom = Tokens.Gap),
        ) {
            if (icon != null) Box(Modifier.padding(bottom = Tokens.Small).then(if (enabled) Modifier else Modifier.alpha(0.5f))) { icon() }
            // the name sits beside the switch, unless a word would break there: then it goes under the switch
            var under by remember(title) { mutableStateOf(false) }
            val name: @Composable (Modifier) -> Unit = { m ->
                Text(
                    title, m, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (enabled) Tokens.Text else Tokens.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r -> if (checked != null && !under && breaksWord(r, title)) under = true },
                )
            }
            if (checked != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (under) Spacer(Modifier.weight(1f)) else name(Modifier.weight(1f).padding(top = Tokens.Gap))
                run {
                    Box(
                        Modifier.size(width = 64.dp, height = Tokens.Target)
                            .toggleable(checked, enabled = enabled, role = Role.Switch) { haptic.toggle(it); onToggle(it) }
                            .semantics {
                                contentDescription = title
                                if (onOpen != null) customActions = listOf(CustomAccessibilityAction("Open settings") { onOpen(); true })
                            },
                        contentAlignment = Alignment.Center,
                    ) { Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors()) }
                }
            }
            if (checked == null || under) name(Modifier.padding(end = if (checked != null) Tokens.Gap else 0.dp))
            Spacer(Modifier.weight(1f).heightIn(min = Tokens.Tiny))
            Text(status, Modifier.padding(end = if (checked != null) Tokens.Gap else 0.dp), style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = statusLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** True when a line of [r] ends inside a word of [text] (so it was broken mid-word). */
private fun breaksWord(r: androidx.compose.ui.text.TextLayoutResult, text: String): Boolean =
    (0 until r.lineCount).any { i ->
        val end = r.getLineEnd(i, visibleEnd = true)
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }

/** Two equal tiles side by side, 12 dp apart, as tall as the taller one; a lone tile keeps half the width. */
@Composable
fun TilePair(first: @Composable (Modifier) -> Unit, second: (@Composable (Modifier) -> Unit)?) =
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(bottom = Tokens.Gap),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gap),
    ) {
        first(Modifier.weight(1f).fillMaxHeight())
        if (second != null) second(Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
    }

// ---------------------------------------------------------------- glyph previews

/** Spec preview sizes. */
enum class PreviewSize(val dp: Dp) { SMALL(Tokens.PreviewSmall), MEDIUM(Tokens.PreviewMedium), LARGE(Tokens.PreviewLarge) }

/** True while the list around a preview scrolls: animated previews pause (set by [Screen] and [Page]). */
val LocalPreviewsPaused = compositionLocalOf { false }

/** On-screen previews never flash more than 3 times a second unless the user pressed Play. */
const val PREVIEW_MIN_FRAME_MS = 167

/** What TalkBack reads for a preview: "Heart, 12 frames, 2.4 seconds", or "Heart" for one frame. */
fun previewLabel(name: String, animation: Animation?): String {
    val n = animation?.frames?.size ?: 0
    if (n < 2) return name
    val s = animation!!.totalMs / 1000f
    val secs = if (s == s.toInt().toFloat()) "${s.toInt()}" else "%.1f".format(java.util.Locale.ROOT, s)
    return "$name, $n frames, $secs seconds"
}

/**
 * A live matrix preview of [animation] or of one frame. It plays while on screen, pauses while its list
 * scrolls, and never flashes faster than 3 times a second. With Remove animations on it shows the first frame
 * and a play button; tapping plays it ([userPlay] also counts as pressing Play).
 */
@Composable
fun GlyphPreview(
    animation: Animation?,
    name: String,
    modifier: Modifier = Modifier,
    size: PreviewSize = PreviewSize.SMALL,
    playing: Boolean = true,
    userPlay: Boolean = false,
    posterFrame: Frame? = null,
) {
    val reduce = LocalReduceMotion.current
    val paused = LocalPreviewsPaused.current
    var tapped by remember(animation) { mutableStateOf(false) }
    val animated = (animation?.frames?.size ?: 0) > 1
    val run = animated && playing && !paused && (!reduce || tapped || userPlay)
    val poster = remember(animation, posterFrame) { posterFrame ?: animation?.let(com.dotforge.device.Posters::of) }
    var frame by remember(animation) { mutableStateOf(poster) }
    LaunchedEffect(animation, run, userPlay || tapped) {
        val frames = animation?.frames ?: return@LaunchedEffect
        if (!run) { frame = poster; return@LaunchedEffect }
        // the poster first, so a preview never opens on a blank or half-drawn frame
        if (!userPlay && !tapped) delay(POSTER_HOLD_MS)
        val min = if (userPlay || tapped) Animation.MIN_FRAME_MS else PREVIEW_MIN_FRAME_MS
        while (true) for (f in frames) {
            frame = f.frame
            delay(f.durationMs.coerceAtLeast(min).toLong())
        }
    }
    val label = previewLabel(name, animation)
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        DotMatrix(frame?.pixels, animation?.profile ?: DeviceProfile.Phone3, Modifier.fillMaxSize().semantics { contentDescription = label })
        if (reduce && animated && !tapped && !userPlay && size != PreviewSize.SMALL) {
            IconButton(onClick = { tapped = true }, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play $name", tint = Color.White)
            }
        }
    }
}

/** How long a playing preview shows its poster before it moves. */
const val POSTER_HOLD_MS = 1_200L

/** A still preview of [frame]. */
@Composable
fun GlyphPreview(frame: Frame?, name: String, modifier: Modifier = Modifier, size: PreviewSize = PreviewSize.SMALL) =
    DotMatrix(frame?.pixels, frame?.profile ?: DeviceProfile.Phone3, modifier.size(size.dp).semantics { contentDescription = name })

/** A library glyph by id, playing if it is animated ([still]: its poster, for rows and picker tiles). */
@Composable
fun LibraryGlyph(id: String, modifier: Modifier = Modifier, size: PreviewSize = PreviewSize.SMALL, still: Boolean = false) {
    val g = remember(id) { GlyphLibrary.get(id) }
    val anim = remember(id) { g?.animation(DeviceProfile.Phone3) }
    if (still) GlyphPreview(remember(anim) { anim?.let(com.dotforge.device.Posters::of) }, g?.name ?: id, modifier, size)
    else GlyphPreview(anim, g?.name ?: id, modifier, size)
}

/** A 40 dp circle with an icon: the leading slot of a row that has no app icon (Phone calls, Any app, a block). */
@Composable
fun IconCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) =
    Box(modifier.size(Tokens.Avatar).background(Tokens.Raised, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }

/** The round Glyph, drawn only (no input): the 489 real LEDs as circles, off ones in 20% grey; corners never drawn. */
@Composable
fun DotMatrix(pixels: IntArray?, profile: DeviceProfile, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / profile.size
        for (i in profile.ledCells) {
            val v = pixels?.getOrNull(i)?.coerceIn(0, 255) ?: 0
            drawCircle(
                color = if (v == 0) Tokens.DotOff else Color.White.copy(alpha = 0.25f + 0.75f * v / 255f),
                radius = cell * 0.4f,
                center = Offset((i % profile.size + 0.5f) * cell, (i / profile.size + 0.5f) * cell),
            )
        }
    }
}

// ---------------------------------------------------------------- sheets

/**
 * The one sheet: full width, 28 dp top corners, a drag handle and a header with [title] and Done (or [confirm]).
 * Everything inside keeps one 16 dp margin; the header's button text ends on the same line as the content. The
 * content scrolls inside; [pinned] (a live preview, say) stays under the header with a divider. [half] opens it
 * at half height (it can be dragged up). Swipe down, the scrim and back all dismiss. [fill] opens it at full
 * height with a slim handle, and the content gets all the room left (a picker's grid takes it with a weight);
 * [titleEnd] sits at the end of the header line (a Library / Yours switch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    confirm: String? = "Done",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    scrolls: Boolean = true,
    half: Boolean = false,
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    fill: Boolean = false,
    titleEnd: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !half),
        containerColor = Tokens.Sheet,
        tonalElevation = 0.dp,
        shape = Tokens.SheetShape,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = if (fill) {
            { Box(Modifier.padding(vertical = 10.dp).size(32.dp, 4.dp).background(Tokens.Secondary, RoundedCornerShape(2.dp))) }
        } else {
            { androidx.compose.material3.BottomSheetDefaults.DragHandle() }
        },
    ) {
        // snackbars from inside a sheet ("Blocked AX-LOANS · Undo") show on the sheet, not under it
        UndoSnackbarHost(fill = false) {
        Column(Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier).navigationBarsPadding()) {
            val scroll = rememberScrollState()
            if (title != null && titleEnd != null) {
                // the title and a switch share the line when the title fits whole beside it; otherwise the switch
                // goes under it, so a one-word title never breaks mid-word
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter).padding(bottom = Tokens.Tiny),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                ) {
                    Column(Modifier.align(Alignment.CenterVertically).heightIn(min = Tokens.Target).padding(end = Tokens.Small), verticalArrangement = Arrangement.Center) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary) }
                    }
                    Box(Modifier.align(Alignment.CenterVertically)) { titleEnd() }
                }
            } else if (title != null) {
                // the text button has 12 dp of its own padding: 4 + 12 puts its text 16 dp from the edge
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Tokens.Target + Tokens.Small)
                        .padding(start = Tokens.Gutter, end = if (confirm != null) Tokens.Tiny else Tokens.Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = Tokens.Tiny)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary) }
                    }
                    if (confirm != null) TextButton(onClick = onConfirm ?: onDismiss, enabled = confirmEnabled, modifier = Modifier.heightIn(min = Tokens.Target)) {
                        // a disabled action is plainly disabled: 38% white, not the grey of secondary text
                        Text(confirm, style = MaterialTheme.typography.labelLarge, color = if (confirmEnabled) Color.White else Color.White.copy(alpha = DISABLED_ALPHA))
                    }
                }
            }
            if (pinned != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small), content = pinned)
                androidx.compose.material3.HorizontalDivider(color = Tokens.Hairline)
            } else if (title != null && scrolls && scroll.value > 0) {
                // the header stays put: a hairline once content has scrolled under it
                androidx.compose.material3.HorizontalDivider(color = Tokens.Hairline)
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = fill)) {
                Column(
                    (if (scrolls) Modifier.verticalScroll(scroll) else Modifier)
                        .fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier)
                        .padding(horizontal = Tokens.Gutter).padding(top = Tokens.Small, bottom = if (fill) 0.dp else Tokens.Gutter),
                    content = content,
                )
                // a scroll cue: the last lines fade while more is below
                if (scrolls && scroll.canScrollForward) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Tokens.Sheet))),
                )
            }
            // the arrow has its own line under the content, so it never sits on a button or a title
            if (scrolls && scroll.canScrollForward) Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(24.dp))
            }
        }
        }
    }
}

/**
 * A centred confirm dialog for something that can't be undone: 312 dp wide, #1F1F1F, 28 dp corners over a 60%
 * black scrim. Cancel is a text button; [confirm] is filled red. The two sit right-aligned on one line, or stack
 * (confirm on top) when they don't fit.
 */
@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onDismiss: () -> Unit,
    /** Extra content under the text: a field, for Rename. */
    content: (@Composable () -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val w = ((view as? androidx.compose.ui.window.DialogWindowProvider) ?: (view.parent as? androidx.compose.ui.window.DialogWindowProvider))?.window
            w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w?.setDimAmount(SCRIM_ALPHA)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(color = Tokens.Raised, shape = RoundedCornerShape(28.dp), modifier = Modifier.width(312.dp)) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = Tokens.Gutter)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, modifier = Modifier.padding(top = Tokens.Gutter))
                    content?.let { Box(Modifier.padding(top = Tokens.Gutter)) { it() } }
                    DialogButtons(
                        Modifier.fillMaxWidth().padding(top = Tokens.Section),
                        cancel = {
                            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Tokens.Target)) {
                                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                        },
                        confirm = {
                            Button(
                                onClick = onConfirm, modifier = Modifier.heightIn(min = Tokens.Target),
                                colors = ButtonDefaults.buttonColors(containerColor = Tokens.Accent, contentColor = Color.White),
                            ) { Text(confirm, style = MaterialTheme.typography.labelLarge) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A dialog's two buttons, right-aligned: Cancel then the confirm button on one line when both fit; otherwise
 * stacked, the confirm button on top.
 */
@Composable
private fun DialogButtons(modifier: Modifier, cancel: @Composable () -> Unit, confirm: @Composable () -> Unit) =
    androidx.compose.ui.layout.Layout(contents = listOf(cancel, confirm), modifier = modifier) { (c, k), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val pc = c.first().measure(loose)
        val pk = k.first().measure(loose)
        val gap = Tokens.Small.roundToPx()
        val w = constraints.maxWidth
        if (pc.width + gap + pk.width <= w) {
            val h = maxOf(pc.height, pk.height)
            layout(w, h) {
                pk.place(w - pk.width, (h - pk.height) / 2)
                pc.place(w - pk.width - gap - pc.width, (h - pc.height) / 2)
            }
        } else {
            layout(w, pk.height + gap + pc.height) {
                pk.place(w - pk.width, 0)
                pc.place(w - pc.width, pk.height + gap)
            }
        }
    }

/** How dark the scrim behind a dialog is. */
const val SCRIM_ALPHA = 0.6f

/** Disabled controls and their text: 38% of white. */
const val DISABLED_ALPHA = 0.38f

/**
 * A sheet option: at least 76 dp, a 40 dp preview (title at 72 dp from the screen edge), a 17 sp title, a description that wraps, a chevron (or a
 * check when [selected]). [destructive] titles are red.
 */
@Composable
fun OptionRow(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
    selected: Boolean? = null,
    destructive: Boolean = false,
    /** False for a row that acts at once (Delete, Duplicate): the chevron means "opens something". */
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Tokens.OptionRow)
            .combinedClickableCompat(onClick)
            .padding(vertical = Tokens.Gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        preview?.let { Box(Modifier.size(Tokens.Avatar), contentAlignment = Alignment.Center) { it() } }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.0625f),
                color = if (destructive) Tokens.AccentText else Color.White,
            )
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary) }
        }
        when (selected) {
            true -> Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White)
            false -> Spacer(Modifier.width(24.dp))
            null -> if (chevron) Chevron()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = combinedClickable(onClick = onClick)

// ---------------------------------------------------------------- buttons and chips

/** The one main button: red, fully rounded, 52 dp. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Accent, contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

/** A secondary button: dark grey, fully rounded, 48 dp. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Tokens.Target),
        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Raised, contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

/**
 * A 36 dp chip, 44 dp tall in layout (at least 48 wide) so wrapped rows sit 8 dp apart. The whole 44 dp pill is
 * the target, and Compose widens a target smaller than 48 dp to 48 when touched (minimum touch target), so every
 * chip still takes a 48 dp touch. Selected is white with black text.
 */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = CHIP_ROW).widthIn(min = Tokens.Target)
            .clip(RoundedCornerShape(CHIP_ROW / 2))
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 36.dp).background(if (selected) Color.White else Tokens.Raised, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) Color.Black else Color.White, maxLines = 1)
        }
    }
}

/** A chip's height in layout and touch: 36 visual + 6 above and below, a full 48 dp target. */
private val CHIP_ROW = 48.dp

/** A segmented control: equal segments, the selected one white. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Tokens.Card, RoundedCornerShape(14.dp)).padding(Tokens.Tiny),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                selected = on,
                shape = RoundedCornerShape(10.dp),
                color = if (on) Color.White else Color.Transparent,
                contentColor = if (on) Color.Black else Tokens.Secondary,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.Target),
            ) {
                // Four short labels share one row, so the text stops growing at 1.5x font size instead of truncating.
                val style = MaterialTheme.typography.labelLarge
                val scale = LocalDensity.current.fontScale
                val size = if (scale > 1.5f) style.fontSize * (1.5f / scale) else style.fontSize
                Box(contentAlignment = Alignment.Center) { Text(label, style = style.copy(fontSize = size), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---------------------------------------------------------------- sliders

/**
 * The app's slider: a white active track, a #616161 inactive track, a 20 dp round thumb and a 48 dp touch
 * height. [mark] draws a small tick at that value (1x on a speed slider). TalkBack reads [label] and [readout].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    readout: String? = null,
    mark: Float? = null,
    onChange: (Float) -> Unit,
) {
    val colors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = SLIDER_TRACK,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    )
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.material3.Slider(
        value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = Tokens.Target).semantics {
            contentDescription = label
            readout?.let { stateDescription = it }
        },
        colors = colors,
        thumb = { Box(Modifier.size(20.dp).background(Color.White, CircleShape)) },
        track = { state ->
            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val f = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                    val y = size.height / 2
                    val r = size.height / 2
                    drawLine(SLIDER_TRACK, Offset(r, y), Offset(size.width - r, y), strokeWidth = size.height, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Color.White, Offset(r, y), Offset(r + (size.width - 2 * r) * f, y), strokeWidth = size.height, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                if (mark != null) Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val f = ((mark - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val x = 2.dp.toPx() + (size.width - 4.dp.toPx()) * f
                    drawLine(Tokens.Secondary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
        },
    )
}

/** A slider's inactive track: light enough to see on a card or a sheet. */
val SLIDER_TRACK = Color(0xFF616161)

// ---------------------------------------------------------------- states

/**
 * A warning card for a state the user must fix or know about: an icon and words (never colour alone), and one
 * action. The action sits beside the text when it takes at most 35% of the card's width; otherwise (a long
 * label, large text) it moves to its own line under the text, right-aligned, as a 48 dp text button. Every
 * warning in the app uses this one card.
 */
@Composable
fun StatusBanner(text: String, modifier: Modifier = Modifier, button: String? = null, onClick: () -> Unit = {}) =
    Surface(color = Tokens.Raised, shape = Tokens.CardShape, modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints {
            val labelStyle = MaterialTheme.typography.labelLarge
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = LocalDensity.current
            // the label plus the text button's own 12 dp padding on each side
            val actionWidth = if (button == null) 0.dp else with(density) { measurer.measure(button, labelStyle).size.width.toDp() } + 24.dp
            val beside = button == null || actionWidth <= maxWidth * 0.35f
            Column(Modifier.padding(start = Tokens.Gutter, end = Tokens.Small, top = Tokens.Small, bottom = if (beside) Tokens.Small else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = Color.White, modifier = Modifier.size(Tokens.Icon))
                    Text(text, Modifier.weight(1f).padding(horizontal = Tokens.Gutter, vertical = Tokens.Small), style = MaterialTheme.typography.bodyMedium)
                    if (button != null && beside) TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Tokens.Target)) {
                        Text(button, style = labelStyle, color = Color.White)
                    }
                }
                if (button != null && !beside) TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End).heightIn(min = Tokens.Target)) {
                    Text(button, style = labelStyle, color = Color.White, textAlign = TextAlign.End)
                }
            }
        }
    }

/** Grey placeholder rows while a list loads (a skeleton, so the page doesn't jump when it arrives). */
@Composable
fun LoadingRows(count: Int = 4, modifier: Modifier = Modifier) =
    RowGroup(modifier.semantics { contentDescription = "Loading" }) {
        repeat(count) { i ->
            if (i > 0) RowDivider(leading = true)
            Row(Modifier.fillMaxWidth().height(Tokens.RowTall).padding(horizontal = Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(Tokens.Avatar).background(Tokens.Raised, CircleShape))
                Column(Modifier.padding(start = Tokens.Gutter).weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.5f).height(14.dp).background(Tokens.Raised, RoundedCornerShape(7.dp)))
                    Box(Modifier.padding(top = Tokens.Small).fillMaxWidth(0.8f).height(12.dp).background(Tokens.Raised, RoundedCornerShape(6.dp)))
                }
            }
        }
    }

// ---------------------------------------------------------------- empty state

/** A dot illustration, one line, at most one button. */
@Composable
fun EmptyState(line: String, button: String? = null, glyph: String = "dot", modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = Tokens.Section), horizontalAlignment = Alignment.CenterHorizontally) {
        LibraryGlyph(glyph, size = PreviewSize.MEDIUM)
        Text(line, style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Gutter))
        if (button != null) {
            if (primary) PrimaryButton(button, Modifier.padding(top = Tokens.Gutter), onClick = onClick)
            else SecondaryButton(button, Modifier.padding(top = Tokens.Gutter), onClick = onClick)
        }
    }
}

// ---------------------------------------------------------------- snackbars with Undo

/**
 * The app's snackbar: one message, an optional Undo, at least 8 s (longer when Android's "Time to take action"
 * asks for it), read politely by TalkBack. Everything Undo can reverse calls [show] with an undo instead of
 * asking first.
 */
class Snacks(private val host: SnackbarHostState, private val scope: CoroutineScope, private val timeoutMs: Long = UNDO_MS) {
    /** True while a snackbar shows: lists add room under their last row ([Screen], [Page]). */
    val visible: Boolean get() = host.currentSnackbarData != null

    fun show(message: String, action: String = "Undo", undo: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val job = launch {
                val r = host.showSnackbar(message, actionLabel = if (undo != null) action else null, duration = SnackbarDuration.Indefinite)
                if (r == SnackbarResult.ActionPerformed) undo?.invoke()
            }
            delay(timeoutMs)
            if (job.isActive) host.currentSnackbarData?.dismiss()
        }
    }

    companion object {
        const val UNDO_MS = 8_000L

        /** [UNDO_MS], or the user's accessibility timeout when that is longer. */
        fun timeoutMs(ctx: android.content.Context): Long {
            val am = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            val flags = android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
            return (am?.getRecommendedTimeoutMillis(UNDO_MS.toInt(), flags)?.toLong() ?: UNDO_MS).coerceAtLeast(UNDO_MS)
        }
    }
}

val LocalSnacks = compositionLocalOf<Snacks?> { null }

/**
 * Provides [LocalSnacks] to [content] and shows the snackbars 16 dp from the sides and the bottom, above the
 * bottom bar when [bottomBar] is showing.
 */
@Composable
fun UndoSnackbarHost(modifier: Modifier = Modifier, bottomBar: Boolean = false, fill: Boolean = true, above: Dp = 0.dp, content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val snacks = remember { Snacks(host, scope, Snacks.timeoutMs(ctx)) }
    CompositionLocalProvider(LocalSnacks provides snacks) {
        Box(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            content()
            SnackbarHost(host, modifier.align(Alignment.BottomCenter).then(if (fill) Modifier.navigationBarsPadding() else Modifier).padding(bottom = (if (bottomBar) Tokens.BottomBar else 0.dp) + above + Tokens.Gutter)) { data ->
                androidx.compose.material3.Snackbar(
                    data,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    actionColor = Color.Black,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(horizontal = Tokens.Gutter),
                )
            }
        }
    }
}

/** The snackbars, from anywhere under [UndoSnackbarHost]. */
@Composable
fun rememberSnacks(): Snacks? = LocalSnacks.current

// ---------------------------------------------------------------- haptics

/**
 * Haptics only on state changes: switches, adding a frame. Never while drawing. Android's own touch-feedback
 * setting is honoured by the view; Profile > Haptics can turn ours off as well.
 */
class Haptics(private val feedback: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    fun toggle(on: Boolean) { if (HapticsSetting.on) feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
    fun confirm() { if (HapticsSetting.on) feedback.performHapticFeedback(HapticFeedbackType.Confirm) }
}

/** Profile > Haptics: on (follows the system) or off. */
object HapticsSetting {
    private const val KEY = "haptics"
    private var prefs: android.content.SharedPreferences? = null
    var on by mutableStateOf(true)
        private set

    fun init(p: android.content.SharedPreferences) {
        prefs = p
        on = p.getBoolean(KEY, true)
    }

    fun set(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        on = value
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val f = LocalHapticFeedback.current
    return remember(f) { Haptics(f) }
}

/** Status bar padding for tab screens drawn edge to edge. */
fun Modifier.statusBarSpace() = statusBarsPadding()

/**
 * This style with its size capped at [max] times the normal size: text on screens that must never scroll (the
 * editor's pinned rows, the Photo screen) grows with the font setting up to 1.3x, then stays.
 */
@Composable
fun androidx.compose.ui.text.TextStyle.capped(max: Float = 1.3f): androidx.compose.ui.text.TextStyle {
    val fs = LocalDensity.current.fontScale
    if (fs <= max) return this
    val k = max / fs
    return copy(fontSize = fontSize * k, lineHeight = if (lineHeight.isSp) lineHeight * k else lineHeight)
}

/**
 * Fades the left and/or right edge of a sideways-scrolling row to the background, so it reads as "more this way"
 * instead of a word cut off at the screen edge.
 */
fun Modifier.edgeFade(left: Boolean, right: Boolean, width: Dp = 24.dp): Modifier =
    if (!left && !right) this else this
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width / 3)
            if (left) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
                size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (right) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

/** [edgeFade] for a [ScrollState] row: each edge fades while there is more to scroll that way. */
fun Modifier.edgeFade(scroll: androidx.compose.foundation.ScrollState): Modifier =
    edgeFade(scroll.value > 0, scroll.value < scroll.maxValue)

/** What the Studio's file screens open, for the shared error below. */
object Supported {
    const val PHOTOS = "DotForge opens photos (JPG, PNG, WebP, HEIC), GIFs and videos (MP4, WebM, 3GP)."
    const val SONGS = "DotForge opens songs in MP3, M4A, AAC, OGG, FLAC and WAV."
}

/**
 * The one error screen for a file that can't be opened (photo, GIF, video or song): what went wrong, the files
 * DotForge opens, a button to choose another and Close.
 */
@Composable
fun LoadFailed(
    icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, supported: String, choose: String,
    onChoose: (() -> Unit)?, onClose: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(Tokens.Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(48.dp))
        Text(
            text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Tokens.Gap).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
        Text(supported, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Small))
        if (onChoose != null) PrimaryButton(choose, Modifier.padding(top = Tokens.Section), onClick = onChoose)
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Tokens.Target)) { Text("Close", color = Color.White) }
    }
}

/**
 * The app's spinner: a ring of eight dots with a bright one going round. Every dot is always drawn, so a still
 * frame reads as a loader, not a stray dot. [label] goes beside it and is read by TalkBack.
 */
@Composable
fun DotSpinner(label: String?, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spinner")
    val turn by t.animateFloat(
        0f, 8f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(960, easing = androidx.compose.animation.core.LinearEasing)),
        label = "turn",
    )
    Row(modifier.semantics(mergeDescendants = true) { if (label != null) contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val dot = r * 0.16f
            val head = turn.toInt() % 8
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0 - 90)
                val behind = (head - i + 8) % 8
                drawCircle(
                    Color.White.copy(alpha = (1f - behind * 0.11f).coerceAtLeast(0.25f)), dot,
                    androidx.compose.ui.geometry.Offset(center.x + (r - dot) * kotlin.math.cos(a).toFloat(), center.y + (r - dot) * kotlin.math.sin(a).toFloat()),
                )
            }
        }
        if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Tokens.Gap))
    }
}
