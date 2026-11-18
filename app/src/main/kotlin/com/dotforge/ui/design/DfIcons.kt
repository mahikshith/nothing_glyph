package com.dotforge.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/**
 * Line icons the core icon set lacks (Undo, Pause, the editor's tools and actions), drawn on a 24 dp grid with
 * 2 dp round strokes so they match the Material outlined icons beside them.
 */
object DfIcons {
    private fun icon(name: String, fill: Boolean = false, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                PathData(build),
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = if (fill) null else SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        close()
    }

    private fun PathBuilder.rect(x0: Float, y0: Float, x1: Float, y1: Float) {
        moveTo(x0, y0); lineTo(x1, y0); lineTo(x1, y1); lineTo(x0, y1); close()
    }

    val Undo = icon("Undo") { moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f); moveTo(4f, 9f); lineTo(14.5f, 9f); arcTo(5.5f, 5.5f, 0f, false, true, 14.5f, 20f); lineTo(11f, 20f) }
    val Redo = icon("Redo") { moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f); moveTo(20f, 9f); lineTo(9.5f, 9f); arcTo(5.5f, 5.5f, 0f, false, false, 9.5f, 20f); lineTo(13f, 20f) }
    val Play = icon("Play", fill = true) { moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close() }
    val Pause = icon("Pause", fill = true) { rect(6f, 5f, 10f, 19f); rect(14f, 5f, 18f, 19f) }
    val StepBack = icon("StepBack") { moveTo(6f, 5f); lineTo(6f, 19f); moveTo(18f, 5f); lineTo(9f, 12f); lineTo(18f, 19f); close() }
    val StepForward = icon("StepForward") { moveTo(18f, 5f); lineTo(18f, 19f); moveTo(6f, 5f); lineTo(15f, 12f); lineTo(6f, 19f); close() }
    val Brush = icon("Draw") { moveTo(4f, 20f); lineTo(4.8f, 16.4f); lineTo(16f, 5.2f); lineTo(18.8f, 8f); lineTo(7.6f, 19.2f); close(); moveTo(14f, 7.2f); lineTo(16.8f, 10f) }
    val Eraser = icon("Erase") { moveTo(8f, 20f); lineTo(4f, 16f); lineTo(14f, 6f); lineTo(20f, 12f); lineTo(12f, 20f); close(); moveTo(9f, 11f); lineTo(15f, 17f); moveTo(12f, 20f); lineTo(20f, 20f) }
    val Fill = icon("Fill") { moveTo(11f, 4f); lineTo(19f, 12f); lineTo(12f, 19f); lineTo(4f, 11f); close(); moveTo(4f, 11f); lineTo(18f, 11f); moveTo(20f, 15f); lineTo(21f, 18f); arcTo(1.2f, 1.2f, 0f, false, true, 19f, 18f); close() }
    val Shapes = icon("Shapes") { circle(8.5f, 8.5f, 4.5f); rect(11f, 11f, 20f, 20f) }
    val Select = icon("Select") {
        for ((a, b) in listOf(4f to 4f, 4f to 20f, 20f to 4f, 20f to 20f)) circle(a, b, 0.6f)
        moveTo(8f, 4f); lineTo(10f, 4f); moveTo(14f, 4f); lineTo(16f, 4f); moveTo(8f, 20f); lineTo(10f, 20f); moveTo(14f, 20f); lineTo(16f, 20f)
        moveTo(4f, 8f); lineTo(4f, 10f); moveTo(4f, 14f); lineTo(4f, 16f); moveTo(20f, 8f); lineTo(20f, 10f); moveTo(20f, 14f); lineTo(20f, 16f)
    }
    val Timer = icon("Timing") { circle(12f, 13f, 7.5f); moveTo(12f, 13f); lineTo(12f, 9f); moveTo(10f, 3f); lineTo(14f, 3f) }
    val Sparkle = icon("Effects") { moveTo(12f, 3f); lineTo(13.8f, 10.2f); lineTo(21f, 12f); lineTo(13.8f, 13.8f); lineTo(12f, 21f); lineTo(10.2f, 13.8f); lineTo(3f, 12f); lineTo(10.2f, 10.2f); close() }
    val OnPhone = icon("Show on phone") { rect(7f, 3f, 17f, 21f); circle(12f, 10f, 2.5f); moveTo(10.5f, 17.5f); lineTo(13.5f, 17.5f) }
    val Eye = icon("Shown") { moveTo(2.5f, 12f); arcTo(10.5f, 8f, 0f, false, true, 21.5f, 12f); arcTo(10.5f, 8f, 0f, false, true, 2.5f, 12f); circle(12f, 12f, 3f) }
    val EyeOff = icon("Hidden") { moveTo(2.5f, 12f); arcTo(10.5f, 8f, 0f, false, true, 21.5f, 12f); arcTo(10.5f, 8f, 0f, false, true, 2.5f, 12f); moveTo(4f, 4f); lineTo(20f, 20f) }
    val Copy = icon("Copy") { rect(8f, 8f, 20f, 20f); moveTo(16f, 8f); lineTo(16f, 4f); lineTo(4f, 4f); lineTo(4f, 16f); lineTo(8f, 16f) }
    val Paste = icon("Paste") { rect(5f, 5f, 19f, 21f); rect(9f, 3f, 15f, 7f) }
    val FlipH = icon("Flip left-right") { moveTo(12f, 3f); lineTo(12f, 21f); moveTo(9f, 6f); lineTo(3f, 18f); lineTo(9f, 18f); close(); moveTo(15f, 6f); lineTo(21f, 18f); lineTo(15f, 18f); close() }
    val FlipV = icon("Flip top-bottom") { moveTo(3f, 12f); lineTo(21f, 12f); moveTo(6f, 9f); lineTo(18f, 3f); lineTo(18f, 9f); close(); moveTo(6f, 15f); lineTo(18f, 21f); lineTo(18f, 15f); close() }
    val Mirror = icon("Mirror") { moveTo(12f, 2f); lineTo(12f, 4f); moveTo(12f, 7f); lineTo(12f, 9f); moveTo(12f, 12f); lineTo(12f, 14f); moveTo(12f, 17f); lineTo(12f, 19f); moveTo(9f, 7f); lineTo(4f, 12f); lineTo(9f, 17f); moveTo(15f, 7f); lineTo(20f, 12f); lineTo(15f, 17f) }
    val Pick = icon("Pick a shade") { moveTo(4f, 20f); lineTo(5f, 16f); lineTo(13f, 8f); lineTo(16f, 11f); lineTo(8f, 19f); close(); moveTo(12f, 6f); lineTo(18f, 12f); moveTo(15f, 9f); lineTo(18f, 6f); arcTo(2f, 2f, 0f, false, false, 15f, 3f); lineTo(12f, 6f) }
    val Text = icon("Text") { moveTo(5f, 6f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 6f); moveTo(12f, 4f); lineTo(12f, 20f); moveTo(9f, 20f); lineTo(15f, 20f) }
    val Photo = icon("Photo") { rect(3f, 5f, 21f, 19f); circle(8.5f, 9.5f, 1.5f); moveTo(3f, 17f); lineTo(9f, 12f); lineTo(13f, 15f); lineTo(16f, 12.5f); lineTo(21f, 16.5f) }
    val Layers = icon("Layers") { moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close(); moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f); moveTo(3f, 16f); lineTo(12f, 21f); lineTo(21f, 16f) }
    val Grid = icon("Library") { rect(4f, 4f, 10f, 10f); rect(14f, 4f, 20f, 10f); rect(4f, 14f, 10f, 20f); rect(14f, 14f, 20f, 20f) }
    val Music = icon("Music") { moveTo(9f, 18f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 16f); circle(6.5f, 18f, 2.5f); circle(17.5f, 16f, 2.5f) }
    val Film = icon("Animation") { rect(3f, 5f, 21f, 19f); moveTo(7f, 5f); lineTo(7f, 19f); moveTo(17f, 5f); lineTo(17f, 19f); moveTo(3f, 12f); lineTo(7f, 12f); moveTo(17f, 12f); lineTo(21f, 12f) }
    val Still = icon("Still design") { circle(12f, 12f, 8.5f); circle(12f, 12f, 1f); circle(8f, 12f, 1f); circle(16f, 12f, 1f); circle(12f, 8f, 1f); circle(12f, 16f, 1f) }
    val Camera = icon("Camera") { moveTo(3f, 8f); lineTo(7f, 8f); lineTo(9f, 5f); lineTo(15f, 5f); lineTo(17f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close(); circle(12f, 13f, 3.5f) }
    /** The Brush button (Paint, Brighten, Dim): a paintbrush, handle up. */
    val Paintbrush = icon("Paintbrush") { moveTo(18f, 3f); lineTo(21f, 6f); lineTo(12.5f, 14.5f); lineTo(9.5f, 11.5f); close(); moveTo(9.5f, 11.5f); curveTo(6.5f, 11.5f, 5f, 13.5f, 5f, 16f); curveTo(5f, 18f, 4f, 19.5f, 3f, 20.5f); curveTo(7f, 21f, 12.5f, 19.5f, 12.5f, 14.5f) }
    val Plus = icon("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Crop = icon("Crop") { moveTo(6f, 2f); lineTo(6f, 18f); lineTo(22f, 18f); moveTo(2f, 6f); lineTo(18f, 6f); lineTo(18f, 22f) }
    val Rotate = icon("Turn") { moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, true, 12f, 20f); moveTo(4f, 12f); lineTo(1.5f, 9f); moveTo(4f, 12f); lineTo(7f, 9.5f) }
    val Adjust = icon("Adjust") { moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 17f); lineTo(20f, 17f); circle(9f, 7f, 2.3f); circle(15f, 17f, 2.3f) }
    val Frame = icon("Frame") { rect(5f, 5f, 19f, 19f); moveTo(9f, 3f); lineTo(9f, 5f); moveTo(15f, 3f); lineTo(15f, 5f); moveTo(9f, 19f); lineTo(9f, 21f); moveTo(15f, 19f); lineTo(15f, 21f) }
    val Pencil = icon("Rename") { moveTo(4f, 20f); lineTo(4.8f, 16.4f); lineTo(15f, 6.2f); lineTo(17.8f, 9f); lineTo(7.6f, 19.2f); close() }
    val Clear = icon("Clear") { moveTo(5f, 7f); lineTo(19f, 7f); moveTo(10f, 7f); lineTo(10f, 4f); lineTo(14f, 4f); lineTo(14f, 7f); moveTo(6.5f, 7f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 7f) }
    val Face = icon("Face") { circle(12f, 12f, 8.5f); moveTo(9f, 10f); lineTo(9f, 10.5f); moveTo(15f, 10f); lineTo(15f, 10.5f); moveTo(8.5f, 14.5f); arcTo(4f, 4f, 0f, false, false, 15.5f, 14.5f) }
}
