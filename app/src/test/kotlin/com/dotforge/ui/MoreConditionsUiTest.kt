package com.dotforge.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.dotforge.AppGraph
import com.dotforge.filter.Caller
import com.dotforge.filter.Group
import com.dotforge.filter.Subject
import com.dotforge.rules.MoreConditions
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.screens.ScreenshotApp
import com.dotforge.ui.alerts.AlertBanners
import com.dotforge.ui.alerts.AlertSheet
import com.dotforge.ui.alerts.AlertsScreen
import com.dotforge.ui.alerts.ConditionsSheet
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.DotForgeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** More conditions on a phone: built as sentences in the sheet, saved to the rule, run by the engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class MoreConditionsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private lateinit var before: List<com.dotforge.rules.Rule>
    private lateinit var lists: List<com.dotforge.filter.NamedList>

    @Before fun keep() {
        before = AppGraph.rules.rules.value
        lists = AppGraph.rules.lists.value
    }

    @After fun clean() {
        AppGraph.rules.rules.value.filter { r -> before.none { it.id == r.id } }.forEach { AppGraph.rules.delete(it.id) }
        before.forEach(AppGraph.rules::upsert)
        AppGraph.rules.lists.value.filter { l -> lists.none { it.name == l.name } }.forEach { AppGraph.rules.deleteList(it.name) }
    }

    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp").copy(id = "wa-more")

    private fun msg(sender: String, title: String, text: String = "", contact: Boolean? = null) = Subject(
        isCall = false, app = "com.whatsapp", appLabel = "WhatsApp", sender = sender, title = title, text = text, fromPerson = true,
        contact = { contact?.let { Caller(it) } },
    )

    private fun builder(onSave: (Group) -> Unit) {
        rule.setContent {
            DotForgeTheme {
                ConditionsSheet(Group(), calls = false, apps = true, blocking = false, preview = { MoreConditions.with(wa, it) }, onDismiss = {}, onSave = onSave)
            }
        }
        rule.waitForIdle()
    }

    private fun add(field: String, test: String, value: String? = null) {
        rule.onNodeWithText("Add a condition").tap()
        rule.waitForIdle()
        rule.onNodeWithText(field).tap()
        rule.onNodeWithText(test).tap()
        rule.waitForIdle()
        if (value != null) rule.onNode(hasSetTextAction() and hasContentDescription("Value")).performTextInput(value)
        rule.waitForIdle()
        rule.onNodeWithText("Add condition").tap()
        rule.waitForIdle()
    }

    @Test fun conditionsBuiltInTheSheetMatchAll() {
        var saved: Group? = null
        builder { saved = it }
        add("Title", "has", "OTP")
        add("Who sent it", "isn't", "Bank")
        // the rows read as one sentence
        rule.onNodeWithText("Title has “OTP”").assertExists()
        rule.onNodeWithText("and not from “Bank”").assertExists()
        rule.onNodeWithText("Save").tap()
        val r = MoreConditions.with(wa, saved!!)
        assertEquals("Title has “OTP” and not from “Bank”", MoreConditions.summary(saved!!))
        assertNotNull(RuleEngine.explain(r, msg("HDFC", "Your OTP is 1")))
        assertNull(RuleEngine.explain(r, msg("Bank", "Your OTP is 1")))
    }

    @Test fun matchAnyAndNotFromTheSheet() {
        var saved: Group? = null
        builder { saved = it }
        add("Title", "has", "OTP")
        add("Contacts", "isn't a contact")
        rule.onNodeWithText("Any of these").tap()
        rule.waitForIdle()
        rule.onNodeWithText("or sender isn't a contact").assertExists()
        rule.onNodeWithText("Save").tap()
        val r = MoreConditions.with(wa, saved!!)
        assertNotNull(RuleEngine.explain(r, msg("Mom", "Your OTP", contact = true)))
        assertNotNull(RuleEngine.explain(r, msg("AX-LOANS", "Offer", contact = false)))
        assertNull(RuleEngine.explain(r, msg("Mom", "Dinner", contact = true)))
    }

    @Test fun aListIsMadeInPlaceAndUsed() {
        var saved: Group? = null
        builder { saved = it }
        rule.onNodeWithText("Add a condition").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Message").tap()
        rule.onNodeWithText("isn't in list").tap()
        rule.waitForIdle()
        rule.onNodeWithText("+ New list").tap()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasContentDescription("Name")).performTextInput("Greetings")
        rule.onNode(hasSetTextAction() and hasContentDescription("Entries")).performTextInput("hi, hello")
        rule.onNodeWithText("Save list").tap()
        rule.waitForIdle()
        assertEquals(listOf("hi", "hello"), AppGraph.rules.list("Greetings")?.entries)
        rule.onNodeWithText("Reads: Message isn't in list “Greetings”").assertExists()
        rule.onNodeWithText("Add condition").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Save").tap()
        val r = MoreConditions.with(wa, saved!!)
        val l = AppGraph.rules.lists.value
        assertNull(RuleEngine.explain(r, msg("x", "x", text = "hello"), l))
        assertNotNull(RuleEngine.explain(r, msg("x", "x", text = "call me"), l))
    }

    @Test fun theAppSheetsRowOpensTheBuilderAndSaves() {
        AppGraph.rules.upsert(MoreConditions.with(wa, Group(true, listOf(MoreConditions.condition(MoreConditions.Pick.TITLE, MoreConditions.Test.CONTAINS, "OTP")))))
        rule.setContent {
            DotForgeTheme {
                AlertsScreen(Nav(Tab.ALERTS), Setup(rule.activity), rememberLazyListState(), AlertBanners(DeviceState.PHONE3, true, false, false), AlertSheet.RuleOf(wa.id, advanced = true))
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("More conditions"))).performScrollToNode(hasText("More conditions"))
        rule.onNodeWithText("Title has “OTP”").assertExists()
        // Advanced stays within 8 rows: brightness and speed share one
        rule.onNodeWithText("Brightness and speed").assertExists()
        rule.onNodeWithText("More conditions").tap()
        rule.waitForIdle()
        add("Who sent it", "isn't", "Bank")
        rule.onNodeWithText("Save").tap()
        rule.waitForIdle()
        val saved = AppGraph.rules.rules.value.first { it.id == wa.id }
        assertEquals("Title has “OTP” and not from “Bank”", MoreConditions.summary(MoreConditions.of(saved)))
    }

    @Test fun aSpamRuleGetsMoreConditionsAndCountsThem() {
        rule.setContent {
            DotForgeTheme {
                AlertsScreen(Nav(Tab.ALERTS), Setup(rule.activity), rememberLazyListState(), AlertBanners(DeviceState.PHONE3, true, false, false), AlertSheet.AddBlock())
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Words").tap()
        rule.onNode(hasSetTextAction() and hasContentDescription("Words")).performTextInput("loan")
        rule.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("More conditions"))).performScrollToNode(hasText("More conditions"))
        rule.onNodeWithText("More conditions").tap()
        rule.waitForIdle()
        add("Contacts", "isn't a contact")
        rule.onNodeWithText("Block only when these are true too.").assertExists()
        rule.onNodeWithText("Save").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Sender isn't a contact").assertExists()
        rule.onNodeWithText("Add").tap()
        rule.waitForIdle()
        val added = AppGraph.rules.rules.value.first { it.name == "Mentions “loan”, sender isn't a contact" }
        assertTrue(SimpleRules.pattern(added) != null)
        assertEquals(1, MoreConditions.of(added).conditions.size)
    }

    @Test fun theSpamTabSwitchesStrangersHiddenAndFakedCalls() {
        val nav = Nav(Tab.ALERTS).also { it.alertsRequest = "spam" }
        rule.setContent {
            DotForgeTheme { AlertsScreen(nav, Setup(rule.activity), rememberLazyListState(), AlertBanners(DeviceState.PHONE3, true, false, false)) }
        }
        rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Faked numbers"))
        listOf("Hidden numbers" to RuleEngine.HIDDEN_CALLS, "Faked numbers" to RuleEngine.FAKED_CALLS).forEach { (name, id) ->
            rule.onNode(hasText(name) and androidx.compose.ui.test.isToggleable()).tap()
            rule.waitForIdle()
            assertTrue(name, AppGraph.rules.rules.value.first { it.id == id }.enabled)
        }
        // people not in your contacts: one row, a choice for calls and one for messages
        rule.onNodeWithText("Calls").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.rules.rules.value.first { it.id == RuleEngine.UNKNOWN_CALLS }.enabled)
        rule.onNodeWithText("Messages").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.rules.rules.value.first { it.id == RuleEngine.UNKNOWN_SENDERS }.enabled)
    }
}
