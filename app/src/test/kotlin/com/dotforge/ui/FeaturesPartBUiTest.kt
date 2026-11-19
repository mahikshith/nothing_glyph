package com.dotforge.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.dotforge.AppGraph
import com.dotforge.automation.Automation
import com.dotforge.automation.AutomationRunner
import com.dotforge.automation.KeyReceiver
import com.dotforge.composition.VisualStyle
import com.dotforge.device.ShowFor
import com.dotforge.screens.ScreenshotApp
import com.dotforge.system.ChargingStyle
import com.dotforge.system.FeatureTiming
import com.dotforge.system.Sensitivity
import com.dotforge.tile.TorchTileService
import com.dotforge.toys.ToyComponents
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.FeaturePage
import com.dotforge.ui.features.MIC_WHY
import com.dotforge.ui.features.ON_THE_GLYPH_TAG
import com.dotforge.ui.features.VOLUME_GLYPH_TAG
import com.dotforge.ui.features.batteryStatus
import com.dotforge.ui.features.maskKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Features Part B: Toys, Music visualiser, Torch, Battery and volume, Integrations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FeaturesPartBUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

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

    private fun page(key: String) {
        rule.setContent {
            val setup = androidx.compose.runtime.remember { Setup(rule.activity) }
            DotForgeTheme { UndoSnackbarHost { FeaturePage(key, Nav(Tab.FEATURES), setup) } }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    private fun sheetTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    private fun open(tag: String = ON_THE_GLYPH_TAG) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        rule.onNode(hasTestTag(tag)).tap()
        rule.waitForIdle()
    }

    /** The arbiter works on its own thread: wait for it, then for the page to catch up. */
    private fun until(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(20)
        rule.waitForIdle()
    }

    private fun done() {
        rule.onNodeWithText("Done").tap()
        rule.waitForIdle()
    }

    // ---------------------------------------------------------------- torch

    @Test fun torchSwitchAndItsFourControls() {
        page("torch")
        rule.onNodeWithText("100% · off after 5 min · fade 1x").assertExists()
        rule.onNode(hasText("Torch") and isToggleable()).assertIsOff().tap()
        until { AppGraph.arbiter.has(TorchTileService.ID) }
        assertTrue(AppGraph.arbiter.has(TorchTileService.ID))
        rule.onNodeWithText("On · off in", substring = true).assertExists()
        open()
        rule.onNodeWithText("Turns off after").assertExists()
        rule.onNodeWithText("Never").tap()
        rule.waitForIdle()
        assertEquals(0, AppGraph.features.torchMinutes.value)
        sheetTo("Fade in")
        rule.onNodeWithText("Fast 2x").tap()
        rule.waitForIdle()
        assertEquals(2f, AppGraph.features.timing(FeatureTiming.TORCH).value.speed)
        sheetTo("It stays on")
        rule.onNodeWithText("Repeat").assertExists()
        sheetTo("Exact brightness")
        rule.onNodeWithText("50%").tap()
        rule.waitForIdle()
        assertEquals(50, AppGraph.features.timing(FeatureTiming.TORCH).value.brightness)
        done()
        until { AppGraph.arbiter.active.value.any { it.id == TorchTileService.ID } }
        rule.onNodeWithText("50% · stays on · fade 2x").assertExists()
        // the torch that is on took the change and stays on
        rule.onNodeWithText("On · stays on").assertExists()
        rule.onNode(hasText("Torch") and isToggleable()).assertIsOn().tap()
        until { !AppGraph.arbiter.has(TorchTileService.ID) }
        assertFalse(AppGraph.arbiter.has(TorchTileService.ID))
    }

    // ---------------------------------------------------------------- battery and volume

    @Test fun chargingDesignAndTiming() {
        page("battery")
        rule.onNode(hasText("When you plug in") and isToggleable()).assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.chargingMeter.value)
        rule.onNodeWithText("Level % · 5 s · 100% · fast pulse").assertExists()
        open()
        rule.onNodeWithText("While plugged in").tap()
        rule.waitForIdle()
        assertEquals(ShowFor.UNTIL_CHECKED, AppGraph.features.timing(FeatureTiming.CHARGING).value.showFor)
        sheetTo("Every 5 min")
        rule.onNodeWithText("Repeat while charging").assertExists()
        rule.onNodeWithText("Every 5 min").tap()
        rule.waitForIdle()
        AppGraph.features.timing(FeatureTiming.CHARGING).value.let {
            assertEquals(ShowFor.SECONDS, it.showFor)
            assertEquals(5, it.remindMinutes)
        }
        sheetTo("Speed")
        rule.onNodeWithText("Fast 2x").tap()
        rule.waitForIdle()
        sheetTo("Exact brightness")
        rule.onNodeWithText("Brightness").assertExists()
        sheetTo("Pulse on a fast charger")
        rule.onNodeWithText("Fill").tap()
        rule.waitForIdle()
        assertEquals(ChargingStyle.FILL, AppGraph.features.chargingStyle.value)
        rule.onNode(hasText("Pulse on a fast charger") and isToggleable()).assertIsOn().tap()
        rule.waitForIdle()
        assertFalse(AppGraph.features.fastPulse.value)
        done()
        rule.onNodeWithText("Fill · 10 s · again every 5 min · 100%").assertExists()
    }

    @Test fun volumeDesignAndTheShakes() {
        page("battery")
        scrollTo("When the volume changes")
        rule.onNode(hasText("When the volume changes") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.volume.value)
        rule.onNodeWithText("2 s · slide 1x · 100%").assertExists()
        open(VOLUME_GLYPH_TAG)
        rule.onNodeWithText("3 s").tap()
        rule.waitForIdle()
        assertEquals(3, AppGraph.features.timing(FeatureTiming.VOLUME).value.seconds)
        sheetTo("Slide speed")
        rule.onNodeWithText("Slow 0.5x").tap()
        rule.waitForIdle()
        sheetTo("It shows once for each change.")
        sheetTo("Exact brightness")
        rule.onNodeWithText("25%").tap()
        rule.waitForIdle()
        done()
        rule.onNodeWithText("3 s · slide 0.5x · 25%").assertExists()
        scrollTo("Shake twice for the torch")
        rule.onNode(hasText("Shake twice for the torch") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.shakeTorch.value)
        scrollTo("Needs a harder shake")
        rule.onNodeWithText("Needs a harder shake").assertExists()
        assertEquals("Charging and volume", batteryStatus(true, true))
        assertEquals("Off", batteryStatus(false, false))
    }

    // ---------------------------------------------------------------- music

    @Test fun musicExplainsTheMicrophoneFirst() {
        page("music")
        rule.onNodeWithText(MIC_WHY).assertExists()
        rule.onNodeWithText("Allow").assertExists()
        rule.onNodeWithText("Needs microphone access").assertExists()
        rule.onNodeWithText("While music plays · fall-back 1x · 100% · Normal").assertExists()
    }

    @Test fun musicOnTheGlyphAndStyles() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        page("music")
        rule.onNodeWithText(MIC_WHY).assertDoesNotExist()
        rule.onNodeWithText("Off").assertExists()
        open()
        rule.onNodeWithText("Until I turn it off").tap()
        rule.waitForIdle()
        assertFalse(AppGraph.features.mediaOnly.value)
        sheetTo("Fall-back speed")
        rule.onNodeWithText("Slow 0.5x").tap()
        rule.waitForIdle()
        assertEquals(0.5f, AppGraph.features.timing(FeatureTiming.MUSIC).value.speed)
        sheetTo("It follows the music.")
        rule.onNodeWithText("Repeat").assertExists()
        sheetTo("Sensitivity")
        rule.onNodeWithText("High").tap()
        rule.waitForIdle()
        assertEquals(Sensitivity.HIGH, AppGraph.features.sensitivity.value)
        sheetTo("Exact brightness")
        rule.onNodeWithText("75%").tap()
        rule.waitForIdle()
        done()
        rule.onNodeWithText("Until turned off · fall-back 0.5x · 75% · High").assertExists()
        scrollTo("Radial spectrum")
        rule.onNodeWithText("Ring pulse").tap()
        rule.waitForIdle()
        assertEquals(VisualStyle.RING_PULSE, AppGraph.toys.visualStyle)
        AppGraph.toys.visualStyle = VisualStyle.BARS
    }

    // ---------------------------------------------------------------- toys

    @Test fun toysListWithSwitchesAndTheirPlace() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        page("toys")
        rule.onNodeWithText("Nothing's toy settings").assertExists()
        rule.onNodeWithText("100% · 1x · shows while picked").assertExists()
        // on by default; this phone can't read Nothing's list, so the step left is said
        scrollTo("DF Clock")
        rule.onAllNodesWithText("On · add it in Glyph Toys").onFirst().assertExists()
        scrollTo("DF Snake")
        rule.onNodeWithContentDescription("DF Snake").assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(ToyComponents.isOn(ctx, "SnakeToy"))
        for (t in listOf("DF Weather", "DF Day dots", "DF Tilt maze", "DF Timer")) {
            scrollTo(t)
            rule.onNodeWithText(t).assertExists()
        }
        ToyComponents.set(ctx, "SnakeToy", false)
    }

    @Test fun toysOnTheGlyph() {
        page("toys")
        open()
        rule.onAllNodesWithText("Set by each toy", substring = true).onFirst().assertExists()
        sheetTo("Animation speed")
        rule.onNodeWithText("Fast 2x").tap()
        rule.waitForIdle()
        assertEquals(2f, AppGraph.features.timing(FeatureTiming.TOYS).value.speed)
        sheetTo("animations loop")
        sheetTo("Exact brightness")
        rule.onNodeWithText("50%").tap()
        rule.waitForIdle()
        done()
        rule.onNodeWithText("50% · 2x · shows while picked").assertExists()
    }

    // ---------------------------------------------------------------- integrations

    @Test fun integrationsKeyAndSwitches() {
        page("integrations")
        rule.onNode(hasText("Let other apps show designs") and isToggleable()).assertIsOn()
        rule.onNodeWithText("10 s · 1x · 100% unless the app says").assertExists()
        val key = AppGraph.features.key.value
        scrollTo("Make a new key")
        rule.onNodeWithText("For Tasker's Send intent: ${maskKey(key)}").assertExists()
        rule.onNodeWithContentDescription("Copy key").tap()
        rule.waitForIdle()
        val clip = rule.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        assertEquals(key, clip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithText("Copied", substring = true).assertExists()
        rule.onNodeWithText("Make a new key").tap()
        rule.waitForIdle()
        rule.onAllNodesWithText("Make a new key").onLast().tap()
        rule.waitForIdle()
        assertNotEquals(key, AppGraph.features.key.value)
        scrollTo("Tell other apps when something lights")
        rule.onNode(hasText("Tell other apps when something lights") and isToggleable()).assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(AppGraph.features.tellOthers.value)
        // off: the plugin and the key are greyed
        scrollTo("Let other apps show designs")
        rule.onNode(hasText("Let other apps show designs") and isToggleable()).tap()
        rule.waitForIdle()
        assertFalse(AppGraph.features.showDesigns.value)
        rule.onNode(hasText("Tasker plugin") and isToggleable()).assertIsOff().assertIsNotEnabled()
    }

    @Test fun integrationsOnTheGlyphAndForAppMakers() {
        page("integrations")
        open()
        rule.onNodeWithText("30 s").tap()
        rule.waitForIdle()
        assertEquals(30, AppGraph.features.timing(FeatureTiming.APPS).value.seconds)
        sheetTo("Speed")
        sheetTo("Exact brightness")
        done()
        rule.onNodeWithText("30 s · 1x · 100% unless the app says").assertExists()
        scrollTo("For app makers")
        rule.onNodeWithText("For app makers").tap()
        rule.waitForIdle()
        rule.onNodeWithText("KeyReceiver", substring = true).assertExists()
    }

    @Test fun theKeyReceiverObeysOnlyTheKey() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        fun send(key: String) = KeyReceiver().onReceive(
            ctx, android.content.Intent(Automation.ACTION_GLYPH).putExtra(Automation.EXTRA_GLYPH, "Heart").putExtra(Automation.EXTRA_KEY, key),
        )
        send("wrong")
        assertTrue(AutomationRunner.last.value.orEmpty().contains("key doesn't match"))
        AppGraph.features.setShowDesigns(false)
        send(AppGraph.features.key.value)
        assertTrue(AutomationRunner.last.value.orEmpty().contains("is off"))
    }
}
