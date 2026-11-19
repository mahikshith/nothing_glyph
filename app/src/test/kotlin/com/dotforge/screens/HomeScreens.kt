package com.dotforge.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.engine.AlertPlayer
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.rules.HistoryEntry
import com.dotforge.rules.Outcome
import com.dotforge.tile.TorchTileService
import com.dotforge.toys.HubFace
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.ToysFeature
import com.dotforge.ui.home.HomeNow
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.home.HomeStatus
import com.dotforge.ui.library.LibraryActions
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.widget.WidgetArt
import com.dotforge.widget.WidgetChoice
import com.dotforge.widget.WidgetConfigScreen
import com.dotforge.widget.WidgetContent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every state of Home: nothing showing, an alert, two waiting, the torch, Nothing busy, the three phone states,
 * the checklist, all done, Quiet and Alerts off, the microphone, the spam and always-on lines, the always-on
 * sheet, and the widget (its settings, its picture and Features > Toys' "Add the widget").
 */
abstract class HomeScreens : ScreenshotTest() {
    private val f = "home"

    @Before fun fresh() {
        LibraryPrefs.reset()
        AppGraph.filters.updateFilters { it.copy(alertsPaused = false, quietUntil = 0L) }
        AppGraph.toys.hubFace = HubFace.CLOCK
        AppGraph.history.clear()
        TorchTileService.onSince = 0
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private val heart get() = AnimationLibrary.item("g-heart")!!.load(AppGraph.profile)
    private val chat get() = heart
    /** What an alert shows at a glance: the heart's poster. */
    private val still get() = AnimationLibrary.thumbnail(AnimationLibrary.item("g-heart")!!, AppGraph.profile)

    private fun home(status: HomeStatus = HomeStatus(), now: HomeNow = HomeNow()) = show {
        UndoSnackbarHost(bottomBar = true) {
            androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.HOME) {} }) { pad ->
                Box(Modifier.padding(pad)) { HomeScreen(Nav(Tab.HOME), Setup(activity), rememberLazyListState(), status, now) }
            }
        }
    }

    /** Scrolls Home down to [text] (the lower half: switches, lines, designs). */
    private fun down(text: String = "Choose a design") {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    private fun alert(label: String) = DisplayRequest(AlertPlayer.ID, Priority.NOTIFICATION, chat, loop = true, label = label)

    @Test fun nothingShowing() {
        home()
        settle()
        capture(f, "01-home-nothing-showing")
        down()
        settle()
        capture(f, "02-home-nothing-showing-lower")
    }

    @Test fun anAlertShowing() {
        val a = chat
        home(now = HomeNow(still, alert("WhatsApp from Mom"), listOf("WhatsApp from Mom")))
        settle()
        capture(f, "03-home-alert-showing")
    }

    @Test fun twoWaiting() {
        val a = chat
        home(now = HomeNow(still, alert("Mom"), listOf("Mom", "WhatsApp")))
        settle()
        capture(f, "04-home-two-waiting-stop-all")
        home(now = HomeNow(still, alert("Mom"), listOf("Mom", "WhatsApp", "Gmail", "Slack")))
        settle()
        capture(f, "05-home-four-waiting")
    }

    @Test fun torchOn() {
        TorchTileService.onSince = System.currentTimeMillis() - 48_000
        val full = com.dotforge.device.Frame(AppGraph.profile, IntArray(AppGraph.profile.cellCount).also { px -> AppGraph.profile.ledCells.forEach { px[it] = 255 } })
        val torch = DisplayRequest(TorchTileService.ID, Priority.TORCH, com.dotforge.device.Animation.single(full), loop = true, label = "Torch")
        home(now = HomeNow(full, torch))
        settle()
        capture(f, "06-home-torch-on")
        down("Quiet")
        settle()
        capture(f, "07-home-torch-on-tiles")
    }

    @Test fun glyphBusy() {
        home(now = HomeNow(null, alert("Mom"), listOf("Mom"), busy = "charging"))
        settle()
        capture(f, "08-home-glyph-busy")
    }

    @Test fun notAPhone3() {
        home(HomeStatus(device = DeviceState.OTHER))
        settle()
        capture(f, "09-home-not-phone3")
        down("Live progress")
        settle()
        capture(f, "10-home-not-phone3-tiles")
    }

    @Test fun glyphOff() {
        home(HomeStatus(device = DeviceState.GLYPH_OFF))
        settle()
        capture(f, "11-home-glyph-off")
    }

    @Test fun serviceMissing() {
        home(HomeStatus(device = DeviceState.SERVICE_MISSING))
        settle()
        capture(f, "12-home-service-missing")
    }

    @Test fun checklist() {
        home(HomeStatus(access = false, screening = false, appsPicked = false, keepRunning = false, microphone = false))
        settle()
        capture(f, "13-home-checklist-none-done")
        down("Keep DotForge running")
        settle()
        capture(f, "14-home-checklist-keep-running")
        down()
        settle()
        capture(f, "15-home-missing-permissions-lower")
        home(HomeStatus(appsPicked = false))
        settle()
        capture(f, "16-home-checklist-three-done")
    }

    @Test fun quietAndAlertsOff() {
        AppGraph.filters.updateFilters { it.copy(alertsPaused = true, quietUntil = System.currentTimeMillis() + 3_600_000) }
        home(HomeStatus(microphone = false))
        down("Live progress")
        settle()
        capture(f, "17-home-quiet-on-alerts-off-no-mic")
    }

    @Test fun spamAndAlwaysOn() {
        val now = System.currentTimeMillis()
        listOf(true, true, true).forEachIndexed { i, call -> AppGraph.history.add(HistoryEntry("s$i", now - i * 1_000, Outcome.BLOCKED, call = call, number = "+44140000000$i")) }
        AppGraph.toys.hubFace = HubFace.MOON
        home(HomeStatus(alwaysOn = true))
        down()
        settle()
        capture(f, "18-home-spam-blocked-always-on")
        AppGraph.history.add(HistoryEntry("m", now, Outcome.BLOCKED, appLabel = "Messages"))
        home(HomeStatus(alwaysOn = false))
        down()
        settle()
        capture(f, "19-home-always-on-set-up")
        AppGraph.history.clear()
        home(HomeStatus(screening = false))
        down()
        settle()
        capture(f, "20-home-spam-needs-screening")
    }

    @Test fun alwaysOnSheet() {
        AppGraph.designs.save(LibraryActions.design(AnimationLibrary.item("g-cake")!!))
        home(HomeStatus(alwaysOn = true))
        down("Always-on")
        rule.onNodeWithText("Always-on: Clock").tap()
        rule.waitForIdle()
        settle()
        capture(f, "21-home-always-on-sheet")
    }

    @Test fun widget() {
        LibraryPrefs.toggleFavourite("g-heart")
        LibraryPrefs.toggleFavourite("g-cake")
        show {
            UndoSnackbarHost { WidgetConfigScreen(WidgetChoice(), onCancel = {}) {} }
        }
        settle()
        capture(f, "22-widget-settings-live")
        show {
            UndoSnackbarHost { WidgetConfigScreen(WidgetChoice(WidgetChoice.library("g-cake")), onCancel = {}) {} }
        }
        settle()
        capture(f, "23-widget-settings-favourite")
        // the widget's own picture, as the launcher shows it
        System.getProperty("screens.out")?.let { out ->
            val dir = File(out, f).apply { mkdirs() }
            val shown = WidgetContent.of(WidgetChoice(WidgetChoice.library("g-cake")))
            File(dir, "24-widget-picture_$sizeName.png").outputStream().use {
                WidgetArt.bitmap(shown.still, AppGraph.profile, 300).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        show {
            UndoSnackbarHost {
                Box(Modifier.fillMaxSize().background(Tokens.Background)) { ToysFeature(Nav(Tab.FEATURES), Setup(activity)) }
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add the widget"))
        settle()
        capture(f, "25-toys-add-the-widget")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class HomePhone3 : HomeScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class HomeSmall13 : HomeScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class HomePhone3Font2 : HomeScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
