package com.dotforge.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        contentPadding = PaddingValues(start = Tokens.Gutter, end = Tokens.Gutter, bottom = Tokens.Section),
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
                contentPadding = PaddingValues(start = Tokens.Gutter, end = Tokens.Gutter, bottom = Tokens.Section),
                content = content,
            )
        }
        bottomBar?.let { Box(Modifier.fillMaxWidth().padding(Tokens.Gutter)) { it() } }
    }
}

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
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

/** A row whose whole surface toggles a switch; TalkBack reads it as one switch. A light tick on change. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = rememberHaptics()
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (subtitle != null) Tokens.RowTall else Tokens.Row)
            .toggleable(checked, enabled = enabled, role = Role.Switch) { haptic.toggle(it); onCheckedChange(it) }
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        leading?.invoke()
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
 * An equal tile for a 2-column grid: an optional icon, a name, one status line and an optional switch. With a
 * switch the tile has two targets: the switch (top right) toggles, the rest of the tile opens [onOpen] (shown by
 * a chevron after the name); TalkBack also gets an "Open settings" action on the switch. Heights are minimums,
 * so a 2x font grows the tile instead of clipping it.
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
    onToggle: (Boolean) -> Unit = {},
    onOpen: (() -> Unit)? = null,
) {
    val haptic = rememberHaptics()
    Surface(
        color = Tokens.Card, shape = Tokens.CardShape,
        modifier = modifier.heightIn(min = height).then(if (enabled || onOpen != null) Modifier else Modifier.alpha(0.5f)),
    ) {
        Column(
            Modifier.heightIn(min = height)
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open settings", role = Role.Button, onClick = onOpen) else Modifier)
                .padding(start = Tokens.Gutter, end = Tokens.Small, bottom = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(top = 14.dp)) { icon?.invoke() }
                if (checked != null) {
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
            Row(Modifier.padding(top = Tokens.Tiny, end = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
                // long names ("Music visualiser") shrink a little at large font sizes instead of breaking mid-word
                val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = Tokens.Text)
                androidx.compose.foundation.text.BasicText(
                    title, Modifier.weight(1f, fill = false).then(if (enabled) Modifier else Modifier.alpha(0.6f)),
                    style = style, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
                )
                if (onOpen != null) Chevron()
            }
            Text(
                status, Modifier.padding(top = Tokens.Tiny, end = Tokens.Small),
                style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Two equal tiles side by side, 12 dp apart; a lone tile keeps half the width. */
@Composable
fun TilePair(first: @Composable (Modifier) -> Unit, second: (@Composable (Modifier) -> Unit)?) =
    Row(Modifier.fillMaxWidth().padding(bottom = Tokens.Gap), horizontalArrangement = Arrangement.spacedBy(Tokens.Gap)) {
        first(Modifier.weight(1f))
        if (second != null) second(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
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
) {
    val reduce = LocalReduceMotion.current
    val paused = LocalPreviewsPaused.current
    var tapped by remember(animation) { mutableStateOf(false) }
    val animated = (animation?.frames?.size ?: 0) > 1
    val run = animated && playing && !paused && (!reduce || tapped || userPlay)
    var frame by remember(animation) { mutableStateOf(animation?.frames?.firstOrNull()?.frame) }
    LaunchedEffect(animation, run, userPlay || tapped) {
        val frames = animation?.frames ?: return@LaunchedEffect
        if (!run) return@LaunchedEffect
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

/** A still preview of [frame]. */
@Composable
fun GlyphPreview(frame: Frame?, name: String, modifier: Modifier = Modifier, size: PreviewSize = PreviewSize.SMALL) =
    DotMatrix(frame?.pixels, frame?.profile ?: DeviceProfile.Phone3, modifier.size(size.dp).semantics { contentDescription = name })

/** A library glyph by id, playing if it is animated. */
@Composable
fun LibraryGlyph(id: String, modifier: Modifier = Modifier, size: PreviewSize = PreviewSize.SMALL) {
    val g = remember(id) { GlyphLibrary.get(id) }
    val anim = remember(id) { g?.animation(DeviceProfile.Phone3) }
    GlyphPreview(anim, g?.name ?: id, modifier, size)
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
 * The one sheet: full width, tall, 28 dp top corners, a drag handle and a header with [title] and Done (or
 * [confirm]). The content scrolls inside; swipe down, the scrim and back all dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    confirm: String = "Done",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    scrolls: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Sheet,
        shape = Tokens.SheetShape,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(start = Tokens.Gutter + Tokens.Tiny, end = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = Tokens.Tiny)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary) }
                    }
                    TextButton(onClick = onConfirm ?: onDismiss, enabled = confirmEnabled, modifier = Modifier.heightIn(min = Tokens.Target)) {
                        Text(confirm, style = MaterialTheme.typography.labelLarge, color = if (confirmEnabled) Color.White else Tokens.Secondary)
                    }
                }
            }
            Column(
                (if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .fillMaxWidth().padding(horizontal = Tokens.Gutter).padding(top = Tokens.Small, bottom = Tokens.Gutter),
                content = content,
            )
        }
    }
}

/**
 * A sheet option: at least 76 dp, a 48 dp preview, a 17 sp title, a description that wraps, a chevron (or a
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
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Tokens.OptionRow)
            .combinedClickableCompat(onClick)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        preview?.let { Box(Modifier.size(Tokens.Target), contentAlignment = Alignment.Center) { it() } }
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
            null -> Chevron()
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

/** A secondary button: dark grey, fully rounded, 52 dp. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Raised, contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

/** An outlined white button for actions on a card. */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = Tokens.Target),
        border = BorderStroke(1.dp, Color.White),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

/** A 36 dp chip (48 dp touch target); selected is white on black text. */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color.White else Tokens.Raised,
        contentColor = if (selected) Color.Black else Color.White,
        modifier = modifier.heightIn(min = Tokens.Target).padding(vertical = 6.dp),
    ) {
        Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

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
                Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---------------------------------------------------------------- empty state

/** A dot illustration, one line, at most one button. */
@Composable
fun EmptyState(line: String, button: String? = null, glyph: String = "dot", modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = Tokens.Section), horizontalAlignment = Alignment.CenterHorizontally) {
        LibraryGlyph(glyph, size = PreviewSize.MEDIUM)
        Text(line, style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Gutter))
        if (button != null) PrimaryButton(button, Modifier.padding(top = Tokens.Gutter), onClick = onClick)
    }
}

// ---------------------------------------------------------------- snackbars with Undo

/**
 * The app's snackbar: one message, an optional Undo, at least 8 s (longer when Android's "Time to take action"
 * asks for it), read politely by TalkBack. Everything Undo can reverse calls [show] with an undo instead of
 * asking first.
 */
class Snacks(private val host: SnackbarHostState, private val scope: CoroutineScope, private val timeoutMs: Long = UNDO_MS) {
    fun show(message: String, undo: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val job = launch {
                val r = host.showSnackbar(message, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Indefinite)
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

/** Provides [LocalSnacks] to [content] and shows the snackbars above the bottom bar. */
@Composable
fun UndoSnackbarHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val snacks = remember { Snacks(host, scope, Snacks.timeoutMs(ctx)) }
    CompositionLocalProvider(LocalSnacks provides snacks) {
        Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(host, modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Tokens.BottomBar + Tokens.Small)) { data ->
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
