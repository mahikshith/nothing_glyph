package com.dotforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotText
import com.dotforge.device.PixelFont

val NothingRed = Color(0xFFD71921)
val Muted = Color(0xFF9A9A9A)
private val DotOff = Color(0xFF262626)

private val Scheme = darkColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    tertiary = NothingRed,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0E0E0E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF555555),
    error = NothingRed,
)

private val Mono = FontFamily.Monospace
private val AppTypography = Typography().let {
    it.copy(
        titleMedium = it.titleMedium.copy(fontFamily = Mono, letterSpacing = 1.sp),
        bodySmall = it.bodySmall.copy(fontFamily = Mono),
        labelLarge = it.labelLarge.copy(fontFamily = Mono),
    )
}

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp)

@Composable
fun DotForgeTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)

/** Dot-matrix title drawn with the matrix's own 5x5 font. */
@Composable
fun DotTitle(text: String, dot: Dp = 4.dp, color: Color = Color.White) {
    val t = text.uppercase()
    Canvas(Modifier.width(dot * (DotText.width(t) + 1)).height(dot * (PixelFont.GLYPH_HEIGHT + 1))) {
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

/** Section title in the Nothing style. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp,
    color = Color(0xFFB0B0B0),
)

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Card(
    modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
) { Column(Modifier.padding(16.dp)) { content() } }

/**
 * The round matrix: one dot per real LED. [pixels] is a full-grid frame (0-255) or null for all off.
 * If [onCell] is set, taps and drags report grid cells (the Studio). [version] forces a redraw after a
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
