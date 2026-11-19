package com.dotforge.ui

import android.content.ComponentName
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.dotforge.AppGraph
import com.dotforge.notify.GlyphNotificationListener
import com.dotforge.rules.Action
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.screens.ScreenshotApp
import com.dotforge.ui.alerts.AddGroupSheet
import com.dotforge.ui.alerts.AlertBanners
import com.dotforge.ui.alerts.AlertSheet
import com.dotforge.ui.alerts.AlertsScreen
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.FeaturePage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The user features that moved out of Old screens, each reached and used in the new design. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class OldScreensHomesUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private lateinit var before: List<com.dotforge.rules.Rule>
    private lateinit var calls: com.dotforge.calls.CallSettings
    private var media = false
    private var mediaAway = false

    @Before fun keep() {
        before = AppGraph.rules.rules.value
        calls = AppGraph.calls.settings.value
        media = AppGraph.media.on.value
        mediaAway = AppGraph.media.onlyLockedOrFaceDown.value
    }

    @After fun clean() {
        AppGraph.rules.rules.value.filter { r -> before.none { it.id == r.id } }.forEach { AppGraph.rules.delete(it.id) }
        before.forEach(AppGraph.rules::upsert)
        AppGraph.calls.updateSettings { calls }
        AppGraph.media.setOn(media)
        AppGraph.media.setOnlyLockedOrFaceDown(mediaAway)
    }

    private val ok = AlertBanners(DeviceState.PHONE3, true, false, false)

    private fun alerts(start: AlertSheet? = null, request: String? = null) {
        val nav = Nav(Tab.ALERTS).also { it.alertsRequest = request }
        rule.setContent { DotForgeTheme { UndoSnackbarHost { AlertsScreen(nav, Setup(rule.activity), rememberLazyListState(), ok, start) } } }
        rule.waitForIdle()
    }

    private fun sheetScrollTo(text: String) =
        rule.onNode(hasScrollAction() and hasAnyDescendant(hasText(text))).performScrollToNode(hasText(text))

    @Test fun theWhenRowSetsOnlyWhenLocked() {
        val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp").copy(id = "wa-when")
        AppGraph.rules.upsert(wa)
        alerts(AlertSheet.RuleOf(wa.id, advanced = true))
        sheetScrollTo("When")
        rule.onNodeWithText("Any time").assertExists()
        rule.onNodeWithText("When").tap()
        rule.waitForIdle()
        rule.onNode(hasText("Only when the phone is locked") and isToggleable()).tap()
        rule.onNodeWithText("Only when face down", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Save").tap()
        rule.waitForIdle()
        assertTrue(SimpleRules.locked(AppGraph.rules.rules.value.first { it.id == wa.id }))
        rule.onNodeWithText("Any time · Only when locked").assertExists()
    }

    @Test fun thePhoneCallsSheetTurnsOnTheMissedCallGlyph() {
        AppGraph.calls.updateSettings { it.copy(missedGlyph = false) }
        alerts(AlertSheet.RuleOf(SimpleRules.PHONE_CALLS))
        sheetScrollTo("Missed calls")
        rule.onNodeWithText("Missed calls").tap()
        rule.waitForIdle()
        rule.onNode(hasText("Light for missed calls") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.calls.settings.value.missedGlyph)
        rule.onNodeWithText("Until you check · again every 5 min").assertExists()
    }

    @Test fun aGroupIsAddedFromPeople() {
        rule.setContent { DotForgeTheme { AddGroupSheet(Setup(rule.activity), onDismiss = {}, groups = listOf("Family", "Work")) {} } }
        rule.waitForIdle()
        rule.onNodeWithText("Family").tap()
        rule.waitForIdle()
        val g = AppGraph.rules.rules.value.first { SimpleRules.isGroupRule(it) && it.name == "Family" }
        assertEquals("Family", g.match.conditions.single().cleanValues.single())
    }

    @Test fun addARuleDeclinesCalls() {
        alerts(AlertSheet.AddBlock(), request = "spam")
        rule.onNode(hasSetTextAction() and hasContentDescription("Number")).performTextInput("140555")
        sheetScrollTo("Decline")
        rule.onNodeWithText("Declined. It stays in your call log.").assertDoesNotExist()
        rule.onNodeWithText("Decline").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Declined. It stays in your call log.").assertExists()
        rule.onNodeWithText("Hide the notification").assertExists()
        rule.onNodeWithText("Add").tap()
        rule.waitForIdle()
        val r = AppGraph.rules.rules.value.first { it.name == "Starts with 140555" }
        assertEquals(Action.REJECT, r.callAction)
        assertEquals(Action.REJECT, com.dotforge.calls.CallActions.of(r))
    }

    @Test fun theSpamTabRestoresTheStarterRulesWithUndo() {
        AppGraph.rules.delete("preset-otp")
        alerts(request = "spam")
        rule.onNodeWithContentDescription("More options").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Restore the starter rules").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.rules.rules.value.any { it.id == "preset-otp" })
        rule.onNodeWithText("Undo").tap()
        rule.waitForIdle()
        assertFalse(AppGraph.rules.rules.value.any { it.id == "preset-otp" })
    }

    @Test fun theSpamTabSwitchesSpamWordsAndUnknownSenders() {
        alerts(request = "spam")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Unknown senders"))
        listOf("Spam words" to RuleEngine.SPAM_WORDS_RULE, "Unknown senders" to RuleEngine.UNKNOWN_SENDERS).forEach { (name, id) ->
            val was = AppGraph.rules.rules.value.firstOrNull { it.id == id }?.enabled == true
            rule.onNode(hasText(name) and isToggleable()).tap()
            rule.waitForIdle()
            assertEquals(name, !was, AppGraph.rules.rules.value.first { it.id == id }.enabled)
        }
    }

    @Test fun liveProgressHasTheMediaRingAndItsOption() {
        Settings.Secure.putString(
            rule.activity.contentResolver, "enabled_notification_listeners", ComponentName(rule.activity, GlyphNotificationListener::class.java).flattenToString(),
        )
        AppGraph.media.setOn(false)
        AppGraph.media.setOnlyLockedOrFaceDown(false)
        rule.setContent { DotForgeTheme { FeaturePage("progress", Nav(Tab.FEATURES), Setup(rule.activity)) } }
        rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Media progress"))
        rule.onNode(hasText("Media progress") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.media.on.value)
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Only when locked or face down"))
        rule.onNode(hasText("Only when locked or face down") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.media.onlyLockedOrFaceDown.value)
    }
}
