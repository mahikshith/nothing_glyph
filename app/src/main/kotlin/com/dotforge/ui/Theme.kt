package com.dotforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.dotforge.device.Frame
import com.dotforge.ui.design.Tokens
import kotlinx.coroutines.delay

/** The accent colour (named for the default skin). */
val NothingRed: Color get() = Tokens.Accent
val AccentText: Color get() = Tokens.AccentText
val Muted = Tokens.Secondary
val Surface1 = Tokens.Card
val Surface2 = Tokens.Raised
private val DotOff = Tokens.DotOff

val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** A title in the dot font; [dot] picks the size (kept for older pages). */
@Composable
fun DotTitle(text: String, modifier: Modifier = Modifier, dot: Dp = 4.dp, color: Color = Color.White) =
    Text(
        text,
        modifier.semantics { heading() },
        style = if (dot >= 4.dp) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

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
    onRelease: (() -> Unit)? = null,
) {
    val cb by rememberUpdatedState(onCell)
    val end by rememberUpdatedState(onRelease)
    var input = Modifier.aspectRatio(1f)
    if (onCell != null) {
        input = input
            .pointerInput(profile) {
                detectTapGestures { o ->
                    val cell = size.width / profile.size.toFloat()
                    cb?.invoke((o.x / cell).toInt(), (o.y / cell).toInt())
                    end?.invoke()
                }
            }
            .pointerInput(profile) {
                detectDragGestures(onDragEnd = { end?.invoke() }, onDragCancel = { end?.invoke() }) { change, _ ->
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
