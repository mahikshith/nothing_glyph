package com.dotforge.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.dotforge.AppGraph
import com.dotforge.rules.BlockPattern
import com.dotforge.rules.GlyphChoice
import com.dotforge.rules.HistoryEntry
import com.dotforge.rules.Outcome
import com.dotforge.rules.PatternType
import com.dotforge.rules.SimpleRules
import com.dotforge.rules.WordKind
import com.dotforge.rules.Where
import com.dotforge.ui.AppEntry
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.alerts.AlertAppsPage
import com.dotforge.ui.alerts.AlertBanners
import com.dotforge.ui.alerts.AlertSettingsPage
import com.dotforge.ui.alerts.AlertSheet
import com.dotforge.ui.alerts.AlertsScreen
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.alerts.QuietPage
import com.dotforge.ui.alerts.RuleSheet
import com.dotforge.ui.alerts.TryItSheet
import com.dotforge.ui.alerts.WhoWinsPage
import com.dotforge.ui.design.UndoSnackbarHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every screen and state of Alerts: the four segments, the one sheet and its pickers, Spam, History, the pages. */
abstract class AlertsScreens : ScreenshotTest() {
    private val f = "alerts"
    private val ok = AlertBanners(device = DeviceState.PHONE3, access = true, doubleLights = false, ringtoneLights = false)

    private fun alerts(nav: Nav = Nav(Tab.ALERTS), banners: AlertBanners = ok, start: AlertSheet? = null, request: String? = null) {
        nav.alertsRequest = request
        val setup = Setup(activity)
        show { UndoSnackbarHost(bottomBar = true) { AlertsScreen(nav, setup, rememberLazyListState(), banners, start) } }
    }

    private val now = System.currentTimeMillis()
    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp").copy(id = "wa")
    private val mom = SimpleRules.newPerson("Mom", listOf("+44 7700 900123"), GlyphChoice.icon("heart")).copy(id = "person-mom")

    private fun manyApps() {
        AppGraph.rules.upsert(SimpleRules.newApp("com.slack", "Slack").copy(id = "slack", enabled = false))
        AppGraph.rules.upsert(SimpleRules.withShowFor(SimpleRules.newApp("com.google.android.gm", "Gmail", GlyphChoice.icon("mail")), 10).copy(id = "gmail"))
        AppGraph.rules.upsert(SimpleRules.newApp("org.telegram.messenger", "Telegram").copy(id = "tg"))
        AppGraph.rules.upsert(wa)
    }

    private fun people() {
        AppGraph.rules.upsert(SimpleRules.newPerson("Priya", glyph = GlyphChoice(com.dotforge.rules.GlyphStyle.NAME)).copy(id = "person-priya"))
        AppGraph.rules.upsert(SimpleRules.newPerson("Dad", listOf("+44 7700 900456"), GlyphChoice.icon("star")).copy(id = "person-dad", callRingtone = "content://x", callRingtoneName = "Pong"))
        AppGraph.rules.upsert(mom)
    }

    private fun blockRules() {
        AppGraph.rules.upsert(SimpleRules.blockRule(BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS, Where.SMS)), id = "block-140").copy(name = "Starts with 140"))
        AppGraph.rules.upsert(SimpleRules.blockRule(BlockPattern(PatternType.WORDS, "loan, lottery", setOf(Where.SMS, Where.WHATSAPP)), id = "block-loan"))
        AppGraph.rules.upsert(SimpleRules.blockRule(BlockPattern(PatternType.ENDS, "0000", setOf(Where.CALLS)), id = "block-0000").copy(enabled = false))
    }

    private fun history() {
        val h = AppGraph.history
        val min = 60_000L
        h.add(HistoryEntry("e1", now - 5 * min, Outcome.LIT, "App icon · until checked", "WhatsApp", "com.whatsapp", "Mom", "Are you home?", sender = "Mom", ruleId = "wa"))
        h.add(HistoryEntry("e2", now - 30 * min, Outcome.BLOCKED, "starts with 140", "Phone", "", "+91 140 555 0101", number = "+911405550101", call = true, ruleId = "block-140", rule = "Starts with 140"))
        h.add(HistoryEntry("e3", now - 50 * min, Outcome.SKIPPED, "Quiet", "Gmail", "com.google.android.gm", "Bank", "Your statement is ready", sender = "Bank", ruleId = "gmail"))
        h.add(HistoryEntry("e4", now - 70 * min, Outcome.SKIPPED, "phone face down", "Telegram", "org.telegram.messenger", "Sam", "lol", sender = "Sam", ruleId = "tg"))
        h.add(HistoryEntry("e5", now - 26 * 60 * min, Outcome.BLOCKED, "mentions “loan, lottery”", "Messages", "com.google.android.apps.messaging", "AX-LOANS", "Pre-approved loan", ruleId = "block-loan"))
        h.add(HistoryEntry("e6", now - 27 * 60 * min, Outcome.LIT, "Heart glyph · until checked", "WhatsApp", "com.whatsapp", "Mom", "Call me", sender = "Mom", ruleId = "person-mom"))
    }

    // ---------------------------------------------------------------- Apps

    @Test fun apps() {
        alerts()
        capture(f, "01-apps")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Headphones"))
        capture(f, "02-apps-this-phone")
    }

    @Test fun appsMany() {
        manyApps()
        AppGraph.rules.rules.value.firstOrNull { it.id == SimpleRules.REMINDERS }?.let { AppGraph.rules.upsert(it.copy(enabled = true)) }
        alerts(banners = ok.copy(doubleLights = true))
        capture(f, "03-apps-many-double-lights")
    }

    @Test fun appsEmpty() {
        AppGraph.rules.rules.value.filter(SimpleRules::isAppRule).forEach { AppGraph.rules.delete(it.id) }
        alerts()
        capture(f, "04-apps-empty")
    }

    @Test fun alertsOff() {
        alerts(banners = ok.copy(access = false, screeningLost = true))
        capture(f, "05-banner-alerts-off-screening")
    }

    @Test fun notPhone3() {
        alerts(banners = ok.copy(device = DeviceState.OTHER))
        capture(f, "06-banner-not-phone3")
    }

    @Test fun glyphOff() {
        alerts(banners = ok.copy(device = DeviceState.GLYPH_OFF))
        capture(f, "07-banner-glyph-off")
    }

    // ---------------------------------------------------------------- People

    @Test fun peopleEmpty() {
        alerts(request = "people")
        capture(f, "10-people-empty")
    }

    @Test fun peopleMany() {
        people()
        alerts(request = "people")
        capture(f, "11-people")
    }

    @Test fun addPersonDenied() {
        alerts(request = "people", start = AlertSheet.AddPerson)
        capture(f, "12-add-person-contacts-denied")
    }

    // ---------------------------------------------------------------- the one sheet

    private fun sheet(id: String, banners: AlertBanners = ok, advanced: Boolean = false, picker: WordKind? = null) {
        val nav = Nav(Tab.ALERTS)
        val setup = Setup(activity)
        show { UndoSnackbarHost(bottomBar = true) { RuleSheet(id, nav, setup, banners, onDismiss = {}, startAdvanced = advanced, startPicker = picker) } }
    }

    @Test fun sheetApp() {
        manyApps()
        blockRules()
        sheet("wa")
        capture(f, "20-sheet-app")
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Remove WhatsApp"))
        capture(f, "21-sheet-app-bottom")
    }

    @Test fun sheetAdvanced() {
        AppGraph.rules.upsert(SimpleRules.withWords(SimpleRules.withRemind(wa, 5), listOf("urgent")))
        sheet("wa", advanced = true)
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Who wins"))
        capture(f, "22-sheet-app-advanced")
    }

    @Test fun sheetDoubleLights() {
        AppGraph.rules.upsert(wa)
        sheet("wa", banners = ok.copy(doubleLights = true))
        capture(f, "23-sheet-app-double-lights")
    }

    @Test fun sheetPhoneCalls() {
        sheet(SimpleRules.PHONE_CALLS, banners = ok.copy(ringtoneLights = true))
        capture(f, "24-sheet-phone-calls-sound-only")
        rule.onNodeWithText("Name").performClick()
        capture(f, "25-sheet-phone-calls-name")
    }

    @Test fun sheetPerson() {
        people()
        sheet("person-mom")
        capture(f, "26-sheet-person")
    }

    @Test fun sheetReminders() {
        sheet(SimpleRules.REMINDERS)
        capture(f, "27-sheet-reminders")
    }

    @Test fun sheetMoment() {
        sheet("moment-alarm")
        capture(f, "28-sheet-moment-alarm")
    }

    @Test fun pickerGlyph() {
        AppGraph.rules.upsert(wa)
        sheet("wa", picker = WordKind.GLYPH)
        capture(f, "30-picker-glyph")
    }

    @Test fun pickerShowFor() {
        AppGraph.rules.upsert(wa)
        sheet("wa", picker = WordKind.SHOW_FOR)
        capture(f, "31-picker-show-for")
    }

    @Test fun pickerSound() {
        AppGraph.rules.upsert(wa)
        sheet("wa", picker = WordKind.SOUND)
        capture(f, "32-picker-sound")
    }

    @Test fun pickerWho() {
        AppGraph.rules.upsert(SimpleRules.withPeople(wa, listOf("Mom", "Sam")))
        sheet("wa", picker = WordKind.WHO)
        capture(f, "33-picker-from")
    }

    @Test fun tryItCountdown() {
        AppGraph.rules.upsert(wa)
        rule.mainClock.autoAdvance = false
        show { TryItSheet(wa, "WhatsApp", DeviceState.PHONE3, onDismiss = {}) }
        rule.mainClock.advanceTimeBy(500)
        capture(f, "34-try-it-countdown")
    }

    @Test fun tryItNotPhone3() {
        show { TryItSheet(mom, "Mom", DeviceState.OTHER, onDismiss = {}, startAt = 0) }
        capture(f, "35-try-it-on-screen-only")
    }

    // ---------------------------------------------------------------- Spam

    @Test fun spam() {
        alerts(request = "spam")
        capture(f, "40-spam")
    }

    @Test fun spamRules() {
        blockRules()
        history()
        alerts(request = "spam")
        capture(f, "41-spam-rules-counts")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("What blocking does"))
        capture(f, "42-spam-let-through-community")
    }

    @Test fun spamForAnApp() {
        blockRules()
        alerts(request = "spam:com.whatsapp")
        capture(f, "43-spam-for-whatsapp")
    }

    @Test fun addRule() {
        alerts(request = "spam", start = AlertSheet.AddBlock())
        capture(f, "44-add-rule-empty")
    }

    @Test fun addRuleValid() {
        blockRules()
        history()
        alerts(request = "spam", start = AlertSheet.AddBlock(editId = "block-140"))
        capture(f, "45-add-rule-valid")
    }

    @Test fun spamAlert() {
        alerts(request = "spam", start = AlertSheet.SpamAlert)
        capture(f, "46-spam-alert-sheet")
    }

    @Test fun howBlocking() {
        alerts(request = "spam", start = AlertSheet.HowBlocking)
        capture(f, "47-what-blocking-does")
    }

    // ---------------------------------------------------------------- History

    @Test fun historyEmpty() {
        alerts(request = "history")
        capture(f, "50-history-empty")
    }

    @Test fun historyAll() {
        blockRules()
        manyApps()
        people()
        history()
        alerts(request = "history")
        capture(f, "51-history-all")
        rule.onAllNodesWithText("Blocked").onFirst().performClick()
        capture(f, "52-history-blocked")
    }

    @Test fun historyEntryBlocked() {
        blockRules()
        history()
        alerts(request = "history", start = AlertSheet.Entry("e2"))
        capture(f, "53-history-entry-blocked-call")
    }

    @Test fun historyEntryLit() {
        manyApps()
        history()
        alerts(request = "history", start = AlertSheet.Entry("e1"))
        capture(f, "54-history-entry-lit")
    }

    @Test fun historyMenu() {
        history()
        alerts(request = "history")
        rule.onNodeWithText("History").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("More options")).onFirst().performClick()
        capture(f, "55-history-menu-clear")
    }

    // ---------------------------------------------------------------- pages and sheets

    private val apps = listOf(
        AppEntry("com.google.android.calendar", "Calendar"), AppEntry("com.google.android.gm", "Gmail"),
        AppEntry("com.instagram.android", "Instagram"), AppEntry("com.google.android.apps.messaging", "Messages"),
        AppEntry("com.slack", "Slack"), AppEntry("org.telegram.messenger", "Telegram"), AppEntry("com.whatsapp", "WhatsApp"),
        AppEntry("com.zomato", "Zomato"),
    )

    @Test fun addAppLoading() {
        rule.mainClock.autoAdvance = false
        show { AlertAppsPage("app", Nav(Tab.ALERTS), preset = null, loading = true) }
        capture(f, "60-add-app-loading")
    }

    @Test fun addApp() {
        manyApps()
        history()
        show { UndoSnackbarHost { AlertAppsPage("app", Nav(Tab.ALERTS), preset = apps) } }
        capture(f, "61-add-app")
    }

    @Test fun addAppNoMatch() {
        show { AlertAppsPage("app", Nav(Tab.ALERTS), preset = emptyList()) }
        capture(f, "62-add-app-none")
    }

    @Test fun alertSettings() {
        show { AlertSettingsPage(Nav(Tab.ALERTS)) }
        capture(f, "63-alert-settings")
    }

    @Test fun quiet() {
        AppGraph.filters.updateFilters { it.copy(quiet = it.quiet.copy(enabled = true, start = 23 * 60, end = 7 * 60)) }
        show { QuietPage(Nav(Tab.ALERTS)) }
        capture(f, "64-quiet")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Repeat callers"))
        capture(f, "65-quiet-bottom")
    }

    @Test fun whoWins() {
        manyApps()
        people()
        show { WhoWinsPage(Nav(Tab.ALERTS)) }
        capture(f, "66-who-wins")
    }

    @Test fun howItWorks() {
        alerts(start = AlertSheet.HowItWorks)
        capture(f, "67-how-it-works")
    }

    @Test fun doubleLights() {
        alerts(start = AlertSheet.DoubleLights)
        capture(f, "68-stop-double-lights")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class AlertsPhone3 : AlertsScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class AlertsSmall13 : AlertsScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class AlertsPhone3Font2 : AlertsScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
