package com.dotforge.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.dotforge.toys.FakeToyProvider
import com.dotforge.toys.ToyComponents
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.features.FeaturePage
import com.dotforge.ui.features.MIC_OFF
import com.dotforge.ui.features.MIC_WHY
import com.dotforge.ui.features.NOTHING_LIGHTS
import com.dotforge.ui.features.NOT_PHONE3
import com.dotforge.ui.features.toysStatus
import com.dotforge.ui.features.ON_THE_GLYPH_TAG
import com.dotforge.ui.features.VOLUME_GLYPH_TAG
import com.dotforge.ui.features.batteryStatus
import com.dotforge.ui.features.maskKey
import androidx.compose.ui.unit.dp
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
        FakeToyProvider.active = emptyMap()
        TorchTileService.extraMs = 0L
        AppGraph.automation.allowed.value.forEach { AppGraph.automation.allow(it, false) }
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

    @Test fun torchSwitchBrightnessAndItsFourControls() {
        page("torch")
        // not a Phone (3) here: the page says so, plainly
        rule.onNodeWithText(NOT_PHONE3).assertExists()
        rule.onNodeWithText("100% · off after 5 min").assertExists()
        rule.onNodeWithContentDescription("Torch off").assertExists()
        rule.onNode(hasText("Torch") and isToggleable()).assertIsOff().tap()
        until { AppGraph.arbiter.has(TorchTileService.ID) }
        assertTrue(AppGraph.arbiter.has(TorchTileService.ID))
        rule.onNodeWithContentDescription("Torch on").assertExists()
        rule.onNodeWithText("On · off in", substring = true).assertExists()
        // +5 min while it's on
        rule.onNodeWithContentDescription("Keep the torch on 5 minutes longer").assertHeightIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        assertEquals(TorchTileService.EXTEND_MS, TorchTileService.extraMs)
        // Brightness right on the page
        scrollTo("Of your phone's Glyph brightness")
        rule.onNodeWithText("50%").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        assertEquals(50, AppGraph.features.timing(FeatureTiming.TORCH).value.brightness)
        open()
        rule.onNodeWithText("Turns off after").assertExists()
        rule.onNodeWithText("Never").assertHeightIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        assertEquals(0, AppGraph.features.torchMinutes.value)
        sheetTo("Fade in")
        rule.onNodeWithText("Quick").tap()
        rule.waitForIdle()
        assertEquals(2f, AppGraph.features.timing(FeatureTiming.TORCH).value.speed)
        sheetTo("It stays on")
        rule.onNodeWithText("Repeat").assertExists()
        sheetTo("Phone's Glyph brightness")
        rule.onNodeWithText("Exact brightness").assertExists()
        done()
        until { AppGraph.arbiter.active.value.any { it.id == TorchTileService.ID } }
        rule.onNodeWithText("50% · stays on").assertExists()
        // the torch that is on took the change and stays on
        rule.onNodeWithText("On · stays on").assertExists()
        rule.onNode(hasText("Torch") and isToggleable()).assertIsOn().tap()
        until { !AppGraph.arbiter.has(TorchTileService.ID) }
        assertFalse(AppGraph.arbiter.has(TorchTileService.ID))
    }

    @Test fun shakeForTheTorchIsOneSettingOnBothPages() {
        page("torch")
        scrollTo("Shake twice for the torch")
        rule.onNode(hasText("Shake twice for the torch") and isToggleable()).assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.shakeTorch.value)
        scrollTo("How hard")
        rule.onNodeWithText("Firm").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.shakeTorchFirm.value)
        AppGraph.system.setShakeTorchFirm(false)
    }

    // ---------------------------------------------------------------- battery and volume

    @Test fun chargingLightsDesignFirstAndTiming() {
        page("battery")
        rule.onNodeWithText("Battery").assertExists()
        rule.onNode(hasText("When you plug in") and isToggleable()).assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.chargingMeter.value)
        rule.onNodeWithText("Battery level · 5 s · 100% · fast pulse").assertExists()
        open()
        rule.onNodeWithText("Charging lights").assertExists()
        // the design comes first; My design needs a design to play
        rule.onNodeWithText("Fill").tap()
        rule.waitForIdle()
        assertEquals(ChargingStyle.FILL, AppGraph.features.chargingStyle.value)
        rule.onNodeWithText("My design").assertExists()
        sheetTo("While plugged in")
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
        sheetTo("Exact brightness")
        rule.onNodeWithText("Brightness").assertExists()
        sheetTo("Pulse on a fast charger")
        rule.onNode(hasText("Pulse on a fast charger") and isToggleable()).assertIsOn().tap()
        rule.waitForIdle()
        assertFalse(AppGraph.features.fastPulse.value)
        done()
        rule.onNodeWithText("Fill · 10 s · again every 5 min · 100%").assertExists()
        scrollTo(NOTHING_LIGHTS)
        rule.onNodeWithText(NOTHING_LIGHTS).assertExists()
    }

    @Test fun volumeLightsAndTheShakes() {
        page("battery")
        scrollTo("When the volume changes")
        rule.onNode(hasText("When the volume changes") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.volume.value)
        rule.onNodeWithText("Ring · 2 s · 100%").assertExists()
        open(VOLUME_GLYPH_TAG)
        rule.onNodeWithText("Volume lights").assertExists()
        rule.onNodeWithText("My design").assertExists()
        sheetTo("3 s")
        rule.onNodeWithText("3 s").tap()
        rule.waitForIdle()
        assertEquals(3, AppGraph.features.timing(FeatureTiming.VOLUME).value.seconds)
        sheetTo("Slide")
        rule.onNodeWithText("Slow").tap()
        rule.waitForIdle()
        assertEquals(0.5f, AppGraph.features.timing(FeatureTiming.VOLUME).value.speed)
        sheetTo("It shows once for each change.")
        sheetTo("Exact brightness")
        rule.onNodeWithText("25%").tap()
        rule.waitForIdle()
        done()
        rule.onNodeWithText("Ring · 3 s · 25%").assertExists()
        scrollTo("Shake twice for the torch")
        rule.onNode(hasText("Shake twice for the torch") and isToggleable()).tap()
        rule.waitForIdle()
        assertTrue(AppGraph.system.shakeTorch.value)
        scrollTo("How hard")
        rule.onNodeWithText("Gentle").assertExists()
        assertEquals("Charging and volume", batteryStatus(true, true))
        assertEquals("Off", batteryStatus(false, false))
    }

    // ---------------------------------------------------------------- music

    @Test fun musicExplainsTheMicrophoneHonestly() {
        page("music")
        rule.onNodeWithText(MIC_WHY).assertExists()
        assertTrue(MIC_WHY.contains("uses the microphone instead"))
        rule.onNodeWithText("Allow").assertExists()
        rule.onNodeWithText("Needs microphone access").assertExists()
        rule.onNodeWithText("While music plays · Normal · 100% · Normal sensitivity").assertExists()
    }

    @Test fun musicSaysWhenTheMicrophoneIsOff() {
        AppGraph.features.setMicAsked(true)
        page("music")
        rule.onNodeWithText(MIC_OFF).assertExists()
        rule.onNodeWithText("Open settings").assertExists()
        rule.onNodeWithText(MIC_WHY).assertDoesNotExist()
    }

    @Test fun musicOnTheGlyphStylesAndShakeToSkip() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        page("music")
        rule.onNodeWithText(MIC_WHY).assertDoesNotExist()
        rule.onNodeWithText("Off").assertExists()
        rule.onNode(hasText("Shake to skip") and isToggleable()).assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(AppGraph.features.shakeSkip.value)
        open()
        rule.onNodeWithText("Until I turn it off").tap()
        rule.waitForIdle()
        assertFalse(AppGraph.features.mediaOnly.value)
        sheetTo("Smoothing")
        rule.onNodeWithText("Smooth").tap()
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
        rule.onNodeWithText("Until turned off · Smooth · 75% · High sensitivity").assertExists()
        scrollTo("One of my designs")
        rule.onNodeWithText("Ring pulse").tap()
        rule.waitForIdle()
        assertEquals(VisualStyle.RING_PULSE, AppGraph.toys.visualStyle)
        rule.onNodeWithText("Make a design in Create first").assertExists()
        AppGraph.toys.visualStyle = VisualStyle.BARS
    }

    // ---------------------------------------------------------------- toys

    @Test fun toysListSaysWhereEachToyStandsWithoutSwitches() {
        page("toys")
        rule.onNodeWithText("Glyph Toys").assertExists()
        rule.onNodeWithText("100% · Normal speed").assertExists()
        // this phone can't read Nothing's list: the one instruction, never "On"
        scrollTo("DF Weather")
        rule.onAllNodesWithText("Add it in Glyph Toys").onFirst().assertExists()
        rule.onAllNodesWithText("On", substring = false).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onAllNodesWithText("New").fetchSemanticsNodes().let { assertEquals(3, it.size) }
        for (t in listOf("DF Weather", "DF Day dots", "DF Tilt maze", "DF Clock", "DF Snake", "DF Timer")) {
            scrollTo(t)
            rule.onNodeWithText(t).assertExists()
            rule.onNode(hasText(t) and isToggleable()).assertDoesNotExist()
        }
    }

    @Test fun toysListReadsNothingsListAndAdds() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        FakeToyProvider.install(mapOf("ClockToy" to true, "BatteryToy" to false))
        page("toys")
        scrollTo("DF Clock")
        rule.onNodeWithText("In Glyph Toys").assertExists()
        scrollTo("DF Snake")
        rule.onAllNodesWithText("Not added").onFirst().assertExists()
        rule.onNodeWithContentDescription("Add DF Snake to Glyph Toys").assertHeightIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        // Add puts it in Nothing's list (its component on) and opens the list
        assertTrue(ToyComponents.isOn(ctx, "SnakeToy"))
        ToyComponents.set(ctx, "SnakeToy", false)
        assertEquals("1 in Glyph Toys", toysStatus(FakeToyProvider.active))
    }

    @Test fun aToyPageShowsTheSameStatusAndAddsOrHides() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        rule.setContent { DotForgeTheme { ToySettingsPage(Toy.CLOCK, Nav(Tab.FEATURES)) } }
        rule.waitForIdle()
        rule.onNodeWithText("Add it in Glyph Toys").assertExists()
        rule.onNodeWithText("Add to Glyph Toys").assertExists()
        // the standard parts: section labels and chips, the chosen one checked
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Time format"))
        rule.onNodeWithText("12-hour").assertExists()
        assertTrue(ToyComponents.isOn(ctx, "ClockToy"))
        rule.onNodeWithContentDescription("More").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Hide from Glyph Toys list").tap()
        rule.waitForIdle()
        assertFalse(ToyComponents.isOn(ctx, "ClockToy"))
        rule.onNodeWithText("Hidden from the Glyph Toys list", substring = true).assertExists()
        ToyComponents.set(ctx, "ClockToy", true)
    }

    @Test fun weatherAsksForLocationAndNeverMakesUpATemperature() {
        AppGraph.toys.weatherJson = null
        AppGraph.toys.weatherPlace = null
        AppGraph.toys.weatherAskedLocation = false
        assertEquals(com.dotforge.device.WeatherFace.waiting(AppGraph.profile).pixels.toList(), Toy.WEATHER.preview(AppGraph.profile).pixels.toList())
        rule.setContent { DotForgeTheme { ToySettingsPage(Toy.WEATHER, Nav(Tab.FEATURES)) } }
        rule.waitForIdle()
        rule.onNodeWithText("Allow rough location so the weather is for where you are.").assertExists()
        rule.onNodeWithText("Allow").assertExists()
    }

    @Test fun weatherSaysWhenLocationIsOff() {
        AppGraph.toys.weatherPlace = null
        AppGraph.toys.weatherAskedLocation = true
        rule.setContent { DotForgeTheme { ToySettingsPage(Toy.WEATHER, Nav(Tab.FEATURES)) } }
        rule.waitForIdle()
        rule.onNodeWithText("Location is off. Type a place instead.").assertExists()
        rule.onNodeWithText("Type a place").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Town or city").assertExists()
        AppGraph.toys.weatherAskedLocation = false
    }

    @Test fun weatherLines() {
        val clock = { _: Long -> "9:40" }
        val now = 100_000_000L
        assertEquals("Updated 12 min ago", weatherLine(now, now - 12 * 60_000, 0, clock))
        assertEquals("Updated just now", weatherLine(now, now - 20_000, 0, clock))
        assertEquals("No connection. Showing the last weather from 9:40.", weatherLine(now, now - 3 * 3_600_000, now - 60_000, clock))
        assertEquals("No connection. The weather shows once the phone is online.", weatherLine(now, 0, now - 60_000, clock))
        assertEquals(null, weatherLine(now, 0, 0, clock))
    }

    @Test fun dayDotsAsksForUsageAccess() {
        noUsageAccess()
        AppGraph.toys.dayAskedUsage = false
        rule.setContent { DotForgeTheme { ToySettingsPage(Toy.DAY_DOTS, Nav(Tab.FEATURES)) } }
        rule.waitForIdle()
        rule.onNodeWithText("Day dots reads screen time from Usage access. It stays on this phone.").assertExists()
        rule.onNodeWithText("Allow").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Usage access is off, so Day dots can't count screen time.").assertExists()
        AppGraph.toys.dayAskedUsage = false
    }

    private fun noUsageAccess() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_IGNORED)
    }

    @Test fun toyIconsAreBright() {
        for (t in Toy.entries) {
            val icon = t.icon(AppGraph.profile)
            val lit = icon.pixels.filter { it > 0 }
            assertTrue("${t.name} has dots", lit.size >= 5)
            assertTrue("${t.name} reads at 40 dp", lit.all { it >= Toy.ICON_FLOOR })
        }
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
        rule.onNodeWithText("50% · Fast speed").assertExists()
    }

    // ---------------------------------------------------------------- integrations

    @Test fun integrationsThreePlainPartsAndAKeyWithUndo() {
        page("integrations")
        rule.onNode(hasText("Tasker plugin") and isToggleable()).assertIsOn()
        rule.onNode(hasText("Let other apps send designs") and isToggleable()).assertIsOn()
        rule.onNodeWithText("10 s · 100% · unless the app says").assertExists()
        val key = AppGraph.features.key.value
        scrollTo("Make a new key")
        rule.onNodeWithText("For Tasker's Send intent: ${maskKey(key)}").assertExists()
        rule.onNodeWithContentDescription("Copy key").assertHeightIsAtLeast(48.dp).tap()
        rule.waitForIdle()
        val clip = rule.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        assertEquals(key, clip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithText("Copied", substring = true).assertExists()
        // made at once, with Undo; no "Are you sure?"
        rule.onNodeWithText("Make a new key").tap()
        rule.waitForIdle()
        assertNotEquals(key, AppGraph.features.key.value)
        rule.onNodeWithText("Undo").tap()
        rule.waitForIdle()
        assertEquals(key, AppGraph.features.key.value)
        scrollTo("Tell other apps when something lights")
        rule.onNodeWithText("Only apps you allow can hear this", substring = true).assertExists()
        rule.onNode(hasText("Tell other apps when something lights") and isToggleable()).assertIsOff().tap()
        rule.waitForIdle()
        assertTrue(AppGraph.features.tellOthers.value)
        // no switch covers another: the plugin stays on and usable with sending off
        scrollTo("Let other apps send designs")
        rule.onNode(hasText("Let other apps send designs") and isToggleable()).tap()
        rule.waitForIdle()
        assertFalse(AppGraph.features.showDesigns.value)
        rule.onNode(hasText("Tasker plugin") and isToggleable()).assertIsOn().assertIsEnabled()
        rule.onNodeWithContentDescription("Copy key").assertDoesNotExist()
    }

    @Test fun appsShowByNameNeverByPackage() {
        AppGraph.automation.noteAsked("com.arlosoft.macrodroid")
        AppGraph.automation.noteAsked("com.example.gone")
        page("integrations")
        scrollTo("Apps you allow")
        scrollTo("MacroDroid")
        rule.onNodeWithText("MacroDroid").assertExists()
        rule.onNodeWithText("com.arlosoft.macrodroid", substring = true).assertDoesNotExist()
        rule.onNodeWithText("com.example.gone", substring = true).assertDoesNotExist()
    }

    @Test fun integrationsOnTheGlyphHasRepeatAndForAppMakersScrolls() {
        page("integrations")
        open()
        rule.onNodeWithText("30 s").tap()
        rule.waitForIdle()
        assertEquals(30, AppGraph.features.timing(FeatureTiming.APPS).value.seconds)
        sheetTo("Repeat")
        rule.onNodeWithText("3 times").tap()
        rule.waitForIdle()
        AppGraph.features.timing(FeatureTiming.APPS).value.let {
            assertEquals(ShowFor.WHOLE, it.showFor)
            assertEquals(com.dotforge.device.Repeat.TIMES, it.repeat)
            assertEquals(3, it.times)
        }
        sheetTo("Speed")
        sheetTo("Exact brightness")
        done()
        rule.onNodeWithText("3 times · 100% · unless the app says").assertExists()
        scrollTo("For app makers")
        rule.onNodeWithText("For app makers").tap()
        rule.waitForIdle()
        rule.onAllNodesWithText("KeyReceiver", substring = true).onFirst().assertExists()
        rule.onAllNodesWithContentDescription("Copy the code").onFirst().assertExists()
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

    @Test fun onlyAllowedAppsHearWhenSomethingLights() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadow = org.robolectric.Shadows.shadowOf(app)
        shadow.clearBroadcastIntents()
        com.dotforge.automation.GlyphEvents.send(app, "alert", emptySet())
        assertTrue(shadow.broadcastIntents.none { it.action == com.dotforge.automation.GlyphEvents.ACTION_LIT })
        com.dotforge.automation.GlyphEvents.send(app, "alert", setOf("com.example.allowed"))
        val sent = shadow.broadcastIntents.filter { it.action == com.dotforge.automation.GlyphEvents.ACTION_LIT }
        assertEquals(listOf("com.example.allowed"), sent.map { it.`package` })
    }
}
