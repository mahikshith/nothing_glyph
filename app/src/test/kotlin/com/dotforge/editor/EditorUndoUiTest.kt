package com.dotforge.editor

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
