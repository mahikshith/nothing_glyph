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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.tile.TorchTileService
import com.dotforge.toys.FakeToyProvider
import com.dotforge.ui.alerts.DeviceState
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
 * Features, part B: Toys (the list in each state it can say, On the Glyph, the toys' pages with their
 * permissions and weather states), Music visualiser (asking for the microphone, microphone off, styles, On the
 * Glyph), Torch (off, on, brightness and the shake, On the Glyph), Battery and volume (Charging lights and Volume
 * lights, Also) and Integrations (the three parts, apps by name, On the Glyph with Repeat, For app makers, a new
 * key with Undo, off), and a page on a phone that isn't a Phone (3).
 */
abstract class FeaturesBScreens : ScreenshotTest() {
    private val f = "features"

    @Before fun fresh() {
        AppGraph.features.reset()
        AppGraph.system.setChargingMeter(false)
        AppGraph.system.setVolume(false)
        AppGraph.system.setShakeTorch(false)
        AppGraph.arbiter.clearAll()
        FakeToyProvider.active = emptyMap()
    }

    @After fun clean() {
        AppGraph.arbiter.clearAll()
        AppGraph.features.reset()
        FakeToyProvider.active = emptyMap()
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

    /** The sheet opens at half height: drag it up first, so scrolling really moves its lower parts into view. */
    private fun sheetLower(text: String = "Phone's Glyph brightness") {
        rule.onAllNodes(hasScrollAction()).onLast().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 300) }
        settle()
        sheetTo(text)
    }

    private fun open(tag: String = ON_THE_GLYPH_TAG) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        rule.onNode(hasTestTag(tag)).tap()
        settle()
    }

    private val app get() = activity.application

    private fun noUsageAccess() {
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_IGNORED)
    }

    // ---------------------------------------------------------------- toys

    @Test fun toys() {
        page("toys")
        settle()
        capture(f, "30-toys")
        scrollTo("DF Clock")
        capture(f, "30b-toys-list")
        // the toys are one card: drag the page on to show the rest of it
        rule.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.15f, durationMillis = 400) }
        settle()
        capture(f, "30c-toys-list-more")
        scrollTo("Get more toys")
        capture(f, "30d-toys-end")
    }

    @Test fun toysReadFromGlyphToys() {
        FakeToyProvider.install(mapOf("WeatherToy" to true, "ClockToy" to true, "BatteryToy" to true, "DayDotsToy" to false))
        page("toys")
        settle()
        scrollTo("DF Clock")
        capture(f, "30e-toys-in-glyph-toys-and-not-added")
    }

    @Test fun toysOnTheGlyph() {
        page("toys")
        settle()
        open()
        capture(f, "31-toys-on-the-glyph-sheet")
        sheetLower()
        capture(f, "31b-toys-on-the-glyph-sheet-lower")
    }

    private fun toyPage(toy: Toy) = show {
        Box(Modifier.fillMaxSize().background(Tokens.Background)) { ToySettingsPage(toy, Nav(Tab.FEATURES)) }
    }

    private val sample = """{"current":{"time":"2026-11-19T10:00","temperature_2m":21.4,"weather_code":2},"daily":{"precipitation_probability_max":[20]}}"""

    @Test fun weatherAsksForLocation() {
        AppGraph.toys.weatherPlace = null
        AppGraph.toys.weatherAskedLocation = false
        toyPage(Toy.WEATHER)
        settle()
        scrollTo("Units") // the state sits below the preview: bring it into view at every size
        capture(f, "32-toy-weather-allow-location")
    }

    @Test fun weatherLocationOff() {
        AppGraph.toys.weatherPlace = null
        AppGraph.toys.weatherAskedLocation = true
        toyPage(Toy.WEATHER)
        settle()
        scrollTo("Units") // the state sits below the preview: bring it into view at every size
        capture(f, "32a-toy-weather-location-off")
    }

    @Test fun weatherUpdated() {
        AppGraph.toys.weatherPlace = "Bengaluru"
        AppGraph.toys.weatherPlaceAt = 12.97 to 77.59
        AppGraph.toys.weatherJson = sample
        AppGraph.toys.weatherAt = System.currentTimeMillis() - 12 * 60_000
        AppGraph.toys.weatherOfflineAt = 0
        toyPage(Toy.WEATHER)
        settle()
        scrollTo("Units") // the state sits below the preview: bring it into view at every size
        capture(f, "32e-toy-weather-updated")
    }

    @Test fun weatherOffline() {
        AppGraph.toys.weatherPlace = "Bengaluru"
        AppGraph.toys.weatherPlaceAt = 12.97 to 77.59
        AppGraph.toys.weatherJson = sample
        AppGraph.toys.weatherAt = java.time.LocalDate.now().atTime(9, 40).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli().let {
            if (it > System.currentTimeMillis()) it - 86_400_000L else it
        }.coerceAtMost(System.currentTimeMillis() - 2 * 3_600_000L)
        AppGraph.toys.weatherOfflineAt = System.currentTimeMillis() - 5 * 60_000
        toyPage(Toy.WEATHER)
        settle()
        scrollTo("Units") // the state sits below the preview: bring it into view at every size
        capture(f, "32f-toy-weather-offline")
    }

    @Test fun dayDotsToy() {
        noUsageAccess()
        AppGraph.toys.dayAskedUsage = false
        toyPage(Toy.DAY_DOTS)
        settle()
        scrollTo("Daily goal") // the state sits below the preview: bring it into view at every size
        capture(f, "32b-toy-day-dots-usage-access")
    }

    @Test fun dayDotsAccessOff() {
        noUsageAccess()
        AppGraph.toys.dayAskedUsage = true
        toyPage(Toy.DAY_DOTS)
        settle()
        scrollTo("Daily goal") // the state sits below the preview: bring it into view at every size
        capture(f, "32g-toy-day-dots-usage-access-off")
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

    @Test fun clockToyMenu() {
        toyPage(Toy.CLOCK)
        settle()
        capture(f, "33-toy-clock")
        rule.onNodeWithContentDescription("More").tap()
        settle()
        capture(f, "33b-toy-clock-hide-from-glyph-toys")
    }

    // ---------------------------------------------------------------- music

    @Test fun musicNeedsTheMicrophone() {
        page("music")
        settle()
        capture(f, "40-music-asks-for-the-microphone")
    }

    @Test fun musicMicrophoneOff() {
        AppGraph.features.setMicAsked(true)
        page("music")
        settle()
        capture(f, "40d-music-microphone-off")
    }

    @Test fun music() {
        org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        page("music")
        settle()
        capture(f, "40b-music")
        scrollTo("One of my designs")
        capture(f, "40c-music-styles")
    }

    @Test fun musicOnTheGlyph() {
        org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        page("music")
        settle()
        open()
        capture(f, "41-music-on-the-glyph-sheet")
        sheetLower("High lights the quiet parts")
        capture(f, "41b-music-on-the-glyph-sheet-lower")
    }

    @Test fun notAPhone3() {
        featureDevice = DeviceState.OTHER
        page("music")
        settle()
        capture(f, "75-not-a-phone-3")
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
        AppGraph.system.setShakeTorch(true)
        scrollTo("How hard")
        capture(f, "50c-torch-brightness-and-shake")
        TorchTileService.set(false)
    }

    @Test fun torchOnTheGlyph() {
        page("torch")
        settle()
        open()
        capture(f, "51-torch-on-the-glyph-sheet")
        sheetLower()
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
        scrollTo("How hard")
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
        capture(f, "61-charging-lights-sheet")
        sheetLower()
        capture(f, "61b-charging-lights-sheet-lower")
    }

    @Test fun volumeSheet() {
        AppGraph.system.setVolume(true)
        page("battery")
        settle()
        open(VOLUME_GLYPH_TAG)
        capture(f, "62-volume-lights-sheet")
        sheetLower()
        capture(f, "62b-volume-lights-sheet-lower")
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
        sheetLower()
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
        capture(f, "73-integrations-new-key-undo")
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
