package com.dotforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotText
import com.dotforge.device.Frame
import com.dotforge.device.PixelFont
import kotlinx.coroutines.delay

/** App skins: the accent colour on pure black. All free. */
enum class Skin(val label: String, val accent: Color) {
    RED("Nothing red", Color(0xFFD71921)),
    WHITE("Mono", Color(0xFFF2F2F2)),
    GREEN("Signal green", Color(0xFF2BD96B)),
    AMBER("Amber", Color(0xFFFFB000)),
    BLUE("Ice blue", Color(0xFF5AB8FF)),
}

/** The chosen skin; changing it recolours the app at once. */
object Skins {
    private const val KEY = "skin"
    private var prefs: android.content.SharedPreferences? = null
    var current by mutableStateOf(Skin.RED)
        private set

    fun init(p: android.content.SharedPreferences) {
        prefs = p
        current = Skin.entries.firstOrNull { it.name == p.getString(KEY, null) } ?: Skin.RED
    }

    fun set(s: Skin) {
        prefs?.edit()?.putString(KEY, s.name)?.apply()
        current = s
    }
}

/** The accent colour (named for the default skin). */
val NothingRed: Color get() = Skins.current.accent
val Muted = Color(0xFF9A9A9A)
val Surface1 = Color(0xFF121212)
val Surface2 = Color(0xFF1E1E1E)
private val DotOff = Color(0xFF262626)

private fun scheme() = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    tertiary = NothingRed,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    outline = Color(0xFF555555),
    outlineVariant = Color(0xFF333333),
    error = NothingRed,
)

private val Mono = FontFamily.Monospace
private val AppTypography = Typography().let {
    it.copy(
        titleMedium = it.titleMedium.copy(letterSpacing = 0.5.sp),
        labelLarge = it.labelLarge.copy(fontFamily = Mono),
    )
}

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp)

@Composable
fun DotForgeTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme(), typography = AppTypography, content = content)

/** Dot-matrix title drawn with the matrix's own 5x5 font. */
@Composable
fun DotTitle(text: String, modifier: Modifier = Modifier, dot: Dp = 4.dp, color: Color = Color.White) {
    val t = text.uppercase()
    Canvas(modifier.width(dot * (DotText.width(t) + 1)).height(dot * (PixelFont.GLYPH_HEIGHT + 1))) {
        val d = dot.toPx()
        var x = 0
        for (ch in t) {
            val g = PixelFont.getGlyph(ch)
            for (r in g.indices) for (c in g[r].indices) {
                if (g[r][c] != 0) drawCircle(color, d * 0.38f, Offset((x + c + 0.5f) * d, (r + 0.5f) * d))
            }
            x += g[0].size + PixelFont.CHAR_SPACING
        }
    }
}

/**
 * The round matrix: one dot per real LED. [pixels] is a full-grid frame (0-255) or null for all off.
 * If [onCell] is set, taps and drags report grid cells (the Create editor). [version] forces a redraw after a
 * caller mutates [pixels] in place.
 */
@Composable
fun MatrixView(
    pixels: IntArray?,
    profile: DeviceProfile,
    modifier: Modifier = Modifier,
    version: Int = 0,
    onCell: ((Int, Int) -> Unit)? = null,
) {
    val cb by rememberUpdatedState(onCell)
    var input = Modifier.aspectRatio(1f)
    if (onCell != null) {
        input = input
            .pointerInput(profile) {
                detectTapGestures { o ->
                    val cell = size.width / profile.size.toFloat()
                    cb?.invoke((o.x / cell).toInt(), (o.y / cell).toInt())
                }
            }
            .pointerInput(profile) {
                detectDragGestures { change, _ ->
                    val cell = size.width / profile.size.toFloat()
                    cb?.invoke((change.position.x / cell).toInt(), (change.position.y / cell).toInt())
                }
            }
    }
    Canvas(modifier.then(input)) {
        if (version < 0) return@Canvas // reading version makes in-place edits redraw
        val cell = size.width / profile.size
        drawCircle(Color(0xFF050505), radius = size.width / 2f)
        for (i in profile.ledCells) {
            val v = pixels?.getOrNull(i)?.coerceIn(0, 255) ?: 0
            drawCircle(
                color = if (v == 0) DotOff else Color.White.copy(alpha = 0.25f + 0.75f * v / 255f),
                radius = cell * 0.38f,
                center = Offset((i % profile.size + 0.5f) * cell, (i / profile.size + 0.5f) * cell),
            )
        }
    }
}

/** Plays [animation] in a loop for an on-screen preview; returns the current frame. */
@Composable
fun rememberLoopingFrame(animation: Animation?): Frame? {
    var frame by remember(animation) { mutableStateOf(animation?.frames?.firstOrNull()?.frame) }
    LaunchedEffect(animation) {
        val frames = animation?.frames ?: return@LaunchedEffect
        if (frames.size < 2) return@LaunchedEffect
        while (true) {
            for (f in frames) {
                frame = f.frame
                delay(f.durationMs.coerceAtLeast(Animation.MIN_FRAME_MS).toLong())
            }
        }
    }
    return frame
}
