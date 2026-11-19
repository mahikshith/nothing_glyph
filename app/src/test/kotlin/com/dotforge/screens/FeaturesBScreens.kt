package com.dotforge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.tile.TorchTileService
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.Toy
import com.dotforge.ui.ToySettingsPage
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.FeaturePage
import com.dotforge.ui.features.ON_THE_GLYPH_TAG
import com.dotforge.ui.features.VOLUME_GLYPH_TAG
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Features, part B: Toys (the list, On the Glyph, the new toys' pages), Music visualiser (asking for the
 * microphone, styles, On the Glyph), Torch (off, on, On the Glyph), Battery and volume (both designs, Also, the
 * charging and volume sheets) and Integrations (the key, apps, On the Glyph, For app makers, a new key).
 */
abstract class FeaturesBScreens : ScreenshotTest() {
    private val f = "features"

    @Before fun fresh() {
        AppGraph.features.reset()
        AppGraph.system.setChargingMeter(false)
        AppGraph.system.setVolume(false)
        AppGraph.system.setShakeTorch(false)
        AppGraph.arbiter.clearAll()
    }

    @After fun clean() {
        AppGraph.arbiter.clearAll()
        AppGraph.features.reset()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private fun page(key: String) = show {
        UndoSnackbarHost {
            Box(Modifier.fillMaxSize().background(Tokens.Background)) { FeaturePage(key, Nav(Tab.FEATURES), Setup(activity)) }
        }
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
        settle()
    }

    private fun sheetTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(text, substring = true))
        settle()
    }

    private fun open(tag: String = ON_THE_GLYPH_TAG) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        rule.onNode(hasTestTag(tag)).tap()
        settle()
    }

    // ---------------------------------------------------------------- toys

    @Test fun toys() {
        page("toys")
        settle()
        capture(f, "30-toys")
        scrollTo("DF Snake")
        capture(f, "30b-toys-list")
        scrollTo("DF Tilt maze")
        capture(f, "30c-toys-new-toys")
        scrollTo("Get more toys")
        capture(f, "30d-toys-end")
    }

    @Test fun toysOnTheGlyph() {
        page("toys")
        settle()
        open()
        capture(f, "31-toys-on-the-glyph-sheet")
        sheetTo("Exact brightness")
        capture(f, "31b-toys-on-the-glyph-sheet-lower")
    }

    private fun toyPage(toy: Toy) = show {
        Box(Modifier.fillMaxSize().background(Tokens.Background)) { ToySettingsPage(toy, Nav(Tab.FEATURES)) }
    }

    @Test fun weatherToy() {
        toyPage(Toy.WEATHER)
        settle()
        capture(f, "32-toy-weather")
    }

    @Test fun dayDotsToy() {
        toyPage(Toy.DAY_DOTS)
        settle()
        capture(f, "32b-toy-day-dots")
    }

    @Test fun mazeToy() {
        toyPage(Toy.MAZE)
        settle()
        capture(f, "32c-toy-tilt-maze")
    }

    @Test fun timerToy() {
        toyPage(Toy.TIMER)
        settle()
        capture(f, "32d-toy-timer")
    }

    // ---------------------------------------------------------------- music

    @Test fun musicNeedsTheMicrophone() {
        page("music")
        settle()
        capture(f, "40-music-asks-for-the-microphone")
    }

    @Test fun music() {
        org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        page("music")
        settle()
        capture(f, "40b-music")
        scrollTo("Radial spectrum")
        capture(f, "40c-music-styles")
    }

    @Test fun musicOnTheGlyph() {
        org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        page("music")
        settle()
        open()
        capture(f, "41-music-on-the-glyph-sheet")
        sheetTo("Sensitivity")
        capture(f, "41b-music-on-the-glyph-sheet-lower")
    }

    // ---------------------------------------------------------------- torch

    @Test fun torch() {
        page("torch")
        settle()
        capture(f, "50-torch-off")
        rule.onNode(hasText("Torch") and isToggleable()).tap()
        val end = System.currentTimeMillis() + 3_000
        while (!AppGraph.arbiter.has(TorchTileService.ID) && System.currentTimeMillis() < end) Thread.sleep(20)
        settle()
        capture(f, "50b-torch-on")
        TorchTileService.set(false)
    }

    @Test fun torchOnTheGlyph() {
        page("torch")
        settle()
        open()
        capture(f, "51-torch-on-the-glyph-sheet")
        sheetTo("Exact brightness")
        capture(f, "51b-torch-on-the-glyph-sheet-lower")
    }

    // ---------------------------------------------------------------- battery and volume

    @Test fun battery() {
        AppGraph.system.setChargingMeter(true)
        AppGraph.system.setVolume(true)
        page("battery")
        settle()
        capture(f, "60-battery-and-volume")
        AppGraph.system.setShakeTorch(true)
        scrollTo("Needs a harder shake")
        capture(f, "60b-battery-also")
    }

    @Test fun batteryOff() {
        page("battery")
        settle()
        capture(f, "60c-battery-off")
    }

    @Test fun chargingSheet() {
        AppGraph.system.setChargingMeter(true)
        page("battery")
        settle()
        open()
        capture(f, "61-charging-on-the-glyph-sheet")
        sheetTo("Pulse on a fast charger")
        capture(f, "61b-charging-on-the-glyph-sheet-design")
    }

    @Test fun volumeSheet() {
        AppGraph.system.setVolume(true)
        page("battery")
        settle()
        open(VOLUME_GLYPH_TAG)
        capture(f, "62-volume-on-the-glyph-sheet")
        sheetTo("Exact brightness")
        capture(f, "62b-volume-on-the-glyph-sheet-lower")
    }

    // ---------------------------------------------------------------- integrations

    @Test fun integrations() {
        AppGraph.automation.noteAsked("net.dinglisch.android.taskerm")
        AppGraph.automation.allow("com.arlosoft.macrodroid", true)
        page("integrations")
        settle()
        capture(f, "70-integrations")
        scrollTo("For app makers")
        capture(f, "70b-integrations-apps")
        AppGraph.automation.allow("com.arlosoft.macrodroid", false)
    }

    @Test fun integrationsOnTheGlyph() {
        page("integrations")
        settle()
        open()
        capture(f, "71-integrations-on-the-glyph-sheet")
        sheetTo("Exact brightness")
        capture(f, "71b-integrations-on-the-glyph-sheet-lower")
    }

    @Test fun integrationsForAppMakers() {
        page("integrations")
        settle()
        scrollTo("For app makers")
        rule.onNodeWithText("For app makers").tap()
        settle()
        capture(f, "72-integrations-for-app-makers")
    }

    @Test fun integrationsNewKey() {
        page("integrations")
        settle()
        scrollTo("Make a new key")
        rule.onNodeWithText("Make a new key").tap()
        settle()
        capture(f, "73-integrations-new-key")
    }

    @Test fun integrationsOff() {
        AppGraph.features.setShowDesigns(false)
        page("integrations")
        settle()
        capture(f, "74-integrations-off")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FeaturesBPhone3 : FeaturesBScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class FeaturesBSmall13 : FeaturesBScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FeaturesBPhone3Font2 : FeaturesBScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
