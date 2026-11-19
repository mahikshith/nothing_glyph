package com.dotforge.ui

import android.app.role.RoleManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.dotforge.AppGraph
import com.dotforge.filter.Group
import com.dotforge.filter.NamedList
import com.dotforge.rules.BlockPattern
import com.dotforge.rules.MoreConditions
import com.dotforge.rules.MoreConditions.Pick
import com.dotforge.rules.MoreConditions.Test as T
import com.dotforge.rules.PatternType
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.rules.Where
import com.dotforge.screens.ScreenshotApp
import com.dotforge.ui.alerts.AlertBanners
import com.dotforge.ui.alerts.AlertSheet
import com.dotforge.ui.alerts.AlertsScreen
import com.dotforge.ui.alerts.ConditionsSheet
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.UndoSnackbarHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Alerts additions review, on screen: lists in use, permissions, calls only, Strangers and the restores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class AlertsReviewUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private lateinit var before: List<com.dotforge.rules.Rule>
    private lateinit var lists: List<NamedList>

    @Before fun keep() {
        before = AppGraph.rules.rules.value
        lists = AppGraph.rules.lists.value
    }

    @After fun clean() {
        AppGraph.history.remove("review-1")
        AppGraph.rules.rules.value.filter { r -> before.none { it.id == r.id } }.forEach { AppGraph.rules.delete(it.id) }
        before.forEach(AppGraph.rules::upsert)
        AppGraph.rules.lists.value.filter { l -> lists.none { it.name == l.name } }.forEach { AppGraph.rules.deleteList(it.name) }
        lists.forEach { AppGraph.rules.putList(it) }
    }

    private val ok = AlertBanners(DeviceState.PHONE3, true, false, false)
    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp").copy(id = "wa-review")
    private val notBank = MoreConditions.condition(Pick.SENDER, T.NOT_IN_LIST, list = "Banks")

    private fun alerts(start: AlertSheet? = null, request: String? = null) {
        val nav = Nav(Tab.ALERTS).also { it.alertsRequest = request }
        rule.setContent { DotForgeTheme { UndoSnackbarHost { AlertsScreen(nav, Setup(rule.activity), rememberLazyListState(), ok, start) } } }
        rule.waitForIdle()
    }

    private fun sheetScrollTo(text: String) =
        rule.onNode(hasScrollAction() and hasAnyDescendant(hasText(text))).performScrollToNode(hasText(text))

    private fun builder(g: Group, blocking: Boolean = false, edit: Int? = null, list: String? = null) {
        rule.setContent {
            DotForgeTheme {
                ConditionsSheet(
                    g, calls = false, apps = true, blocking = blocking, preview = { MoreConditions.with(wa, it) }, onDismiss = {},
                    startEdit = edit, startList = list,
                ) {}
            }
        }
        rule.waitForIdle()
    }

    @Test fun aListInUseSaysSoDeletesAtOnceAndUndoPutsItBack() {
        val banks = NamedList("Banks", listOf("HDFC", "ICICI"))
        AppGraph.rules.putList(banks)
        AppGraph.rules.upsert(MoreConditions.with(wa, Group(true, listOf(notBank))))
        AppGraph.rules.upsert(SimpleRules.blockRule(BlockPattern(PatternType.WORDS, "loan", setOf(Where.SMS)), id = "block-review", more = Group(true, listOf(notBank))))
        builder(Group(true, listOf(notBank)), edit = 0, list = "Banks")
        rule.onNodeWithText("Used by 2 rules. Changes apply to all of them at once.").assertExists()
        rule.onNodeWithText("Delete “Banks”").tap()
        rule.waitForIdle()
        assertNull(AppGraph.rules.list("Banks"))
        rule.onNodeWithText("“Banks” deleted").assertExists()
        rule.onNodeWithText("Undo").tap()
        rule.waitForIdle()
        assertEquals(banks, AppGraph.rules.list("Banks"))
    }

    @Test fun aConditionOnADeletedListSaysListRemoved() {
        AppGraph.rules.deleteList("Banks")
        builder(Group(true, listOf(notBank)))
        rule.onNodeWithText("List removed · Fix").assertExists()
        // its editor says what happened and won't save it until another list is picked
        rule.onNodeWithText("List removed · Fix").tap()
        rule.waitForIdle()
        rule.onNodeWithText("“Banks” was deleted. Pick another list or make a new one.").assertExists()
        rule.onNodeWithText("Save condition").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }

    @Test fun aLightRuleIsntOfferedTheSpamFiltersLists() {
        builder(Group(true, listOf(MoreConditions.condition(Pick.SENDER, T.IN_LIST, list = "Banks"))), edit = 0)
        rule.onNodeWithText(RuleEngine.ALLOWED).assertDoesNotExist()
        rule.onNodeWithText(RuleEngine.SPAM_PREFIXES).assertDoesNotExist()
    }

    @Test fun aBlockRuleIsOfferedTheSpamFiltersLists() {
        builder(Group(true, listOf(MoreConditions.condition(Pick.SENDER, T.IN_LIST, list = "Banks"))), blocking = true, edit = 0)
        rule.onNodeWithText(RuleEngine.ALLOWED).assertExists()
    }

    @Test fun theBuilderUsesPlainWords() {
        AppGraph.history.add(
            com.dotforge.rules.HistoryEntry(
                "review-1", System.currentTimeMillis() - 60_000, com.dotforge.rules.Outcome.LIT, appLabel = "WhatsApp", packageName = "com.whatsapp",
                title = "Ravi", text = "hi", sender = "Ravi",
            ),
        )
        builder(Group(false, listOf(MoreConditions.condition(Pick.TITLE, T.CONTAINS, "OTP"), MoreConditions.condition(Pick.CONTACTS, T.NOT_IN_CONTACTS))))
        rule.onNodeWithText("All of these").assertExists()
        rule.onNodeWithText("Any of these").assertExists()
        rule.onNodeWithText("Light only when any of these is true.").assertExists()
        rule.onNodeWithText("Title has “OTP”").assertExists()
        rule.onNodeWithText("or sender isn't a contact").assertExists()
        // a Contacts part without Contacts: it can't be counted, so it isn't shown as 0
        rule.onNodeWithText("Can't count this part yet").assertExists()
        rule.onNodeWithText("Title has “OTP”").tap()
        rule.waitForIdle()
        rule.onNodeWithText("What to check").assertExists()
        rule.onNodeWithText("How").assertExists()
        rule.onNodeWithText("Who sent it").assertExists()
        rule.onNodeWithText("Notification type").assertExists()
    }

    @Test fun turningARuleToCallsOnlyTakesOutTitleConditionsWithUndo() {
        val title = MoreConditions.condition(Pick.TITLE, T.CONTAINS, "OTP")
        alerts(AlertSheet.AddBlock(more = Group(true, listOf(title)), where = setOf(Where.CALLS, Where.SMS)), request = "spam")
        rule.onNode(hasSetTextAction() and hasContentDescription("Number")).performTextInput("140555")
        sheetScrollTo("SMS")
        rule.onNodeWithText("SMS").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Took out a condition a call can't meet: title has “OTP”.").assertExists()
        sheetScrollTo("More conditions")
        rule.onNodeWithText("None").assertExists()
        rule.onNodeWithText("Undo").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Title has “OTP”").assertExists()
    }

    @Test fun withoutCallScreeningTheCallChoicesWaitForIt() {
        alerts(AlertSheet.AddBlock(), request = "spam")
        sheetScrollTo("Decline")
        rule.onNodeWithText("Needs call screening to silence or decline calls").assertExists()
        rule.onNodeWithText("Decline").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        rule.onNodeWithText("Silence").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }

    @Test fun withCallScreeningTheCallChoicesWork() {
        shadowOf(rule.activity.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        alerts(AlertSheet.AddBlock(), request = "spam")
        sheetScrollTo("Decline")
        rule.onNodeWithText("Needs call screening to silence or decline calls").assertDoesNotExist()
        rule.onNodeWithText("Decline").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
    }

    @Test fun strangersAreOneRowWithCallsAndMessagesAndSayWhatTheyNeed() {
        listOf(RuleEngine.UNKNOWN_CALLS, RuleEngine.UNKNOWN_SENDERS).forEach { id ->
            AppGraph.rules.rules.value.firstOrNull { it.id == id }?.let { AppGraph.rules.upsert(it.copy(enabled = false)) }
        }
        alerts(request = "spam")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("People not in your contacts"))
        rule.onNodeWithText("Strangers and hidden callers").assertExists()
        rule.onNodeWithText("Calls that don't ring").assertDoesNotExist()
        rule.onNodeWithText("Unknown senders").assertDoesNotExist()
        rule.onNodeWithText("Calls").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.rules.rules.value.first { it.id == RuleEngine.UNKNOWN_CALLS }.enabled)
        assertFalse(AppGraph.rules.rules.value.first { it.id == RuleEngine.UNKNOWN_SENDERS }.enabled)
        rule.onNodeWithText("Needs Contacts to know who is a stranger").assertExists()
        rule.onNodeWithText("Needs call screening").assertExists()
    }

    @Test fun theAppsMenuRestoresStarterAppRules() {
        AppGraph.rules.delete("preset-otp")
        alerts()
        rule.onNodeWithContentDescription("More options").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Restore starter spam rules").assertDoesNotExist()
        rule.onNodeWithText("Restore starter app rules").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.rules.rules.value.any { it.id == "preset-otp" })
        rule.onNodeWithText("Restored OTP codes").assertExists()
    }

    @Test fun groupsHaveTheirOwnSection() {
        AppGraph.rules.upsert(SimpleRules.newGroup("Family").copy(id = SimpleRules.GROUP_PREFIX + "review"))
        alerts(request = "people")
        rule.onNodeWithText("Groups").assertExists()
        rule.onNodeWithText("Contacts group", substring = true).assertExists()
        rule.onNodeWithText("Add a person").assertExists()
        rule.onNodeWithText("Add a group").assertExists()
    }
}
