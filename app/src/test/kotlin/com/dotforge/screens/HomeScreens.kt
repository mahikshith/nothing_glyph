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
import androidx.compose.ui.test.performScrollToIndex
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

    private var list: androidx.compose.foundation.lazy.LazyListState? = null

    private fun home(status: HomeStatus = HomeStatus(), now: HomeNow = HomeNow()) = show {
        UndoSnackbarHost(bottomBar = true) {
            androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.HOME) {} }) { pad ->
                val state = rememberLazyListState().also { list = it }
                Box(Modifier.padding(pad)) { HomeScreen(Nav(Tab.HOME), Setup(activity), state, status, now) }
            }
        }
    }

    /** Scrolls Home down to [text] (the lower half: switches, lines, designs). */
    private fun down(text: String = "Recent designs") {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    /** Scrolls to the very end of Home. */
    private fun bottom() {
        val n = list!!.layoutInfo.totalItemsCount
        rule.onNode(hasScrollAction()).performScrollToIndex(n - 1)
        rule.waitForIdle()
    }

    /**
     * How many screens Home's list takes: every item's height (measured as it scrolls by) over the list's
     * viewport. Written to page-length.txt next to the shots.
     */
    private fun pageLength(name: String) {
        val sizes = HashMap<Any, Int>()
        val n = list!!.layoutInfo.totalItemsCount
        for (i in 0 until n) {
            rule.onNode(hasScrollAction()).performScrollToIndex(i)
            rule.waitForIdle()
            list!!.layoutInfo.visibleItemsInfo.forEach { sizes[it.key] = it.size }
        }
        val info = list!!.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val total = sizes.values.sum() + info.afterContentPadding + info.beforeContentPadding
        System.getProperty("screens.out")?.let { out ->
            val file = File(File(out, f).apply { mkdirs() }, "page-length.txt")
            file.appendText("%s_%s: %.2f screens (%d px of content, %d px viewport)\n".format(name, sizeName, total.toFloat() / viewport, total, viewport))
        }
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        rule.waitForIdle()
    }

    private fun alert(label: String) = DisplayRequest(AlertPlayer.ID, Priority.NOTIFICATION, chat, loop = true, label = label)

    @Test fun nothingShowing() {
        home()
        settle()
        capture(f, "01-home-nothing-showing")
        bottom()
        settle()
        capture(f, "02-home-nothing-showing-lower")
        pageLength("all-done")
    }

    @Test fun anAlertShowing() {
        home(now = HomeNow(still, alert("WhatsApp from Mom"), listOf("WhatsApp from Mom")))
        settle()
        capture(f, "03-home-alert-showing")
    }

    @Test fun waiting() {
        home(now = HomeNow(still, alert("Mom"), listOf("Mom", "WhatsApp", "Gmail")))
        settle()
        capture(f, "04-home-two-more-waiting-stop-all")
        home(now = HomeNow(still, alert("Mom"), listOf("Mom", "WhatsApp", "Gmail", "Slack", "Uber", "Swiggy")))
        settle()
        capture(f, "05-home-five-more-waiting")
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
        home(now = HomeNow(still, alert("Mom"), listOf("Mom"), busy = "charging"))
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
        down("Live progress")
        settle()
        capture(f, "11b-home-glyph-off-tiles")
    }

    @Test fun serviceMissing() {
        home(HomeStatus(device = DeviceState.SERVICE_MISSING))
        settle()
        capture(f, "12-home-service-missing")
        down("Live progress")
        settle()
        capture(f, "12b-home-service-missing-tiles")
    }

    @Test fun checklist() {
        home(HomeStatus(access = false, screening = false, appsPicked = false, doubleLights = false, keepRunning = false, microphone = false))
        settle()
        capture(f, "13-home-alerts-are-off-checklist")
        down("Keep DotForge running")
        settle()
        capture(f, "14-home-checklist-five-to-do")
        down("Live progress")
        settle()
        capture(f, "15-home-missing-permissions-tiles")
        bottom()
        settle()
        capture(f, "15b-home-missing-permissions-lower")
        pageLength("checklist")
        home(HomeStatus(appsPicked = false, doubleLights = false))
        settle()
        capture(f, "16-home-checklist-three-done")
    }

    @Test fun alertsPausedAndQuiet() {
        AppGraph.filters.updateFilters { it.copy(alertsPaused = true, quietUntil = System.currentTimeMillis() + 3_600_000) }
        home(HomeStatus(microphone = false))
        settle()
        capture(f, "17-home-alerts-paused")
        down("Live progress")
        settle()
        capture(f, "17b-home-paused-quiet-no-mic-tiles")
        AppGraph.filters.updateFilters { it.copy(alertsPaused = false) }
        home(HomeStatus())
        settle()
        capture(f, "17c-home-quiet")
    }

    @Test fun spamAndAlwaysOn() {
        val now = System.currentTimeMillis()
        listOf(0, 1, 2).forEach { i -> AppGraph.history.add(HistoryEntry("s$i", now - i * 86_400_000L, Outcome.BLOCKED, call = true, number = "+44140000000$i")) }
        AppGraph.toys.hubFace = HubFace.MOON
        home(HomeStatus(alwaysOn = true))
        bottom()
        settle()
        capture(f, "18-home-spam-blocked-always-on")
        AppGraph.history.add(HistoryEntry("m", now, Outcome.BLOCKED, appLabel = "Messages"))
        home(HomeStatus(alwaysOn = false))
        bottom()
        settle()
        capture(f, "19-home-always-on-set-in-nothing")
        AppGraph.history.clear()
        home(HomeStatus(screening = false))
        bottom()
        settle()
        capture(f, "20-home-spam-calls-not-blocked")
    }

    @Test fun alwaysOnSheet() {
        AppGraph.designs.save(LibraryActions.design(AnimationLibrary.item("g-cake")!!))
        LibraryPrefs.toggleFavourite("g-heart")
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
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add widget"))
        settle()
        capture(f, "22b-widget-settings-live-end")
        show {
            UndoSnackbarHost { WidgetConfigScreen(WidgetChoice(WidgetChoice.library("g-cake")), onCancel = {}) {} }
        }
        settle()
        capture(f, "23-widget-settings-favourite")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add widget"))
        settle()
        capture(f, "23b-widget-settings-favourite-end")
        // the widget's own picture at a 2x2 and a 4x4 widget's size (xxhdpi), as the launcher shows it
        System.getProperty("screens.out")?.let { out ->
            val dir = File(out, f).apply { mkdirs() }
            for ((name, choice) in listOf("cake" to WidgetChoice(WidgetChoice.library("g-cake")), "live-nothing-lit" to WidgetChoice())) {
                for ((size, px) in listOf("2x2" to 330, "4x4" to 960)) {
                    val shown = WidgetContent.of(choice)
                    File(dir, "24-widget-picture-$name-${size}_$sizeName.png").outputStream().use {
                        WidgetArt.bitmap(shown.still, AppGraph.profile, px).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
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
        show {
            UndoSnackbarHost {
                Box(Modifier.fillMaxSize().background(Tokens.Background)) { com.dotforge.ui.settings.ProfilePage(Nav(Tab.HOME), emptyList()) }
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Home screen widget"))
        settle()
        capture(f, "26-profile-home-screen-widget")
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
