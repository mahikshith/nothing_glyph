package com.dotforge.editor

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.screens.ScreenshotApp
import com.dotforge.studio.Design
import com.dotforge.ui.Nav
import com.dotforge.ui.Tab
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.editor.EditorScreen
import com.dotforge.ui.editor.EditorSession
import com.dotforge.ui.editor.EditorStart
import com.dotforge.ui.editor.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's top complaint, proved through the screen itself: a finger stroke turns Undo on, Undo turns Redo
 * on, Clear offers Undo, and drawing right after Clear works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class EditorUndoUiTest {
    @get:Rule val rule = createComposeRule()

    private val p get() = AppGraph.profile

    private fun lit(s: EditorSession) = s.doc.current.count { it > 0 }

    private fun stroke() = rule.onNode(hasContentDescription("Dot editor", substring = true)).performTouchInput {
        swipe(Offset(width * 0.3f, height * 0.5f), Offset(width * 0.7f, height * 0.5f), durationMillis = 300)
    }

    @Test fun undoRedoClearAndDrawingAfterClearWorkThroughTheScreen() {
        val d = Design(name = "Blank", animated = false, frames = Animation.single(Frame.blank(p), 1000).toMuseumJson()).also { AppGraph.designs.save(it) }
        lateinit var s: EditorSession
        rule.setContent {
            DotForgeTheme { EditorScreen(d.id, false, null, Nav(Tab.STUDIO), EditorStart(phone = PhoneState.OFF, prepare = { s = it })) }
        }
        rule.onNodeWithContentDescription("Undo, unavailable").assertIsNotEnabled()

        stroke()
        rule.waitForIdle()
        val drawn = lit(s)
        assertTrue("the stroke lit dots", drawn > 5)
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
        rule.onNodeWithContentDescription("Redo, unavailable").assertIsNotEnabled()
        // the frame strip's thumbnail follows the stroke

        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        assertEquals(0, lit(s))
        rule.onNodeWithContentDescription("Redo").assertIsEnabled()
        rule.onNodeWithContentDescription("Redo").performClick()
        rule.waitForIdle()
        assertEquals(drawn, lit(s))

        rule.onNodeWithContentDescription("Clear").performClick()
        rule.waitForIdle()
        assertEquals(0, lit(s))
        rule.onNodeWithText("Cleared frame 1").assertExists()
        rule.onNodeWithContentDescription("Frame 1, 1 s, empty, current").assertExists()
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()

        // drawing right after Clear
        stroke()
        rule.waitForIdle()
        assertTrue("drawing after Clear lights dots", lit(s) > 5)
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        assertEquals(0, lit(s))
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        assertEquals(drawn, lit(s))
    }

    @Test fun clearAllKeepsEveryFrameAndItsTimeAndOffersUndo() {
        val frames = (0 until 6).map { i -> com.dotforge.device.AnimFrame(Frame.of(p, IntArray(p.cellCount) { c -> if (c % 7 == i) 255 else 0 }), 150 + i * 100) }
        val d = Design(name = "Six", animated = true, frames = Animation(p, frames).toMuseumJson()).also { AppGraph.designs.save(it) }
        lateinit var s: EditorSession
        rule.setContent {
            DotForgeTheme { EditorScreen(d.id, true, null, Nav(Tab.STUDIO), EditorStart(phone = PhoneState.OFF, prepare = { s = it; it.select(2) })) }
        }
        val durations = s.doc.durations.toList()
        rule.onNodeWithContentDescription("Clear", substring = true).performClick()
        rule.onNodeWithText("Clear this frame").assertExists()
        rule.onNodeWithText("Delete this frame").assertExists()
        rule.onNodeWithText("Clear all 6 frames (keeps the frames)").performClick()
        rule.waitForIdle()
        assertEquals(6, s.doc.size)
        assertEquals(durations, s.doc.durations.toList())
        assertEquals(2, s.doc.cur)
        assertTrue(s.doc.frames.all { f -> f.none { it > 0 } })
        rule.onNodeWithText("Cleared the dots in all 6 frames").assertExists()
        rule.onNodeWithContentDescription("Frame 1, 150 ms, empty").assertExists()
        rule.onNodeWithContentDescription("Frame 6, 650 ms, empty").assertExists()
        // Undo brings the dots back
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(6, s.doc.size)
        assertTrue(s.doc.frames.all { f -> f.any { it > 0 } })

        // Delete this frame is the separate, red item, and has Undo too
        rule.onNodeWithContentDescription("Clear", substring = true).performClick()
        rule.onNodeWithText("Delete this frame").performClick()
        rule.waitForIdle()
        assertEquals(5, s.doc.size)
        rule.onNodeWithText("Deleted frame 3").assertExists()
    }

    /** A name that doesn't fit beside the Glyph pill moves to its own line under the bar, whole: never "De…". */
    @Test fun theNameIsNeverCutBesideTheGlyphPill() {
        val d = Design(name = "Design 12", animated = false, frames = Animation.single(Frame.blank(p), 1000).toMuseumJson()).also { AppGraph.designs.save(it) }
        rule.setContent {
            DotForgeTheme { EditorScreen(d.id, false, null, Nav(Tab.STUDIO), EditorStart(phone = PhoneState.ON)) }
        }
        rule.waitForIdle()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("Design 12").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("the name shows whole", results.isNotEmpty() && !results[0].isLineEllipsized(0) && !results[0].hasVisualOverflow)
    }
}
