package com.dotforge.screens

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import com.dotforge.backup.Backup
import com.dotforge.ui.AppRoot
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.design.ComponentGallery
import com.dotforge.ui.design.TimingKind
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.settings.BackupPage
import com.dotforge.ui.settings.PermissionItem
import com.dotforge.ui.settings.PermissionState
import com.dotforge.ui.settings.ProfilePage
import com.dotforge.ui.settings.RestoreChoice
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Every screen and state of the foundation: the tab scaffold, Profile and its pages, the component gallery. */
abstract class FoundationScreens : ScreenshotTest() {
    private val f = "foundation"

    private lateinit var setup: Setup

    /** The real app root. */
    private fun app(nav: Nav) {
        setup = Setup(activity)
        show { AppRoot(nav, setup) }
    }
    private fun page(p: Page) = Nav(Tab.HOME, mapOf(Tab.HOME to listOf(p)))

    @Test fun tabs() {
        val nav = Nav(Tab.HOME)
        app(nav)
        capture(f, "01-tab-home")
        for ((i, t) in listOf(Tab.ALERTS, Tab.STUDIO, Tab.FEATURES).withIndex()) {
            rule.runOnUiThread { nav.tab = t }
            capture(f, "0${i + 2}-tab-${t.name.lowercase()}")
        }
    }

    /** Never at launch (01 has no sheet): asked in context, when a call rule turns on. */
    @Test fun callScreeningAskInContext() {
        app(Nav(Tab.ALERTS))
        rule.runOnUiThread { setup.needCallScreening() }
        capture(f, "05-call-screening-ask-in-context")
    }

    @Test fun profile() {
        app(page(Page.Profile))
        capture(f, "10-profile-top")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Diagnostics"))
        capture(f, "11-profile-bottom")
    }

    @Test fun profileDeveloperRows() {
        com.dotforge.ui.settings.DevMode.set(true)
        app(page(Page.Profile))
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Hide developer rows"))
        capture(f, "14-profile-developer-rows")
        com.dotforge.ui.settings.DevMode.set(false)
    }

    private val mixed = listOf(
        PermissionItem("Notification access", "Lights up the Glyph for your apps", PermissionState.ALLOWED) {},
        PermissionItem("Call screening", "Blocks spam calls and shows caller glyphs", PermissionState.NEEDED) {},
        PermissionItem("Contacts", "Names for people and callers", PermissionState.ALLOWED) {},
        PermissionItem("Keep DotForge running", "Keeps alerts and timers working in the background", PermissionState.NEEDED) {},
        PermissionItem("Microphone", "Only for the Music visualiser", PermissionState.OPTIONAL) {},
        PermissionItem("Modify system settings", "Only to set Glyph ringtones", PermissionState.OPTIONAL) {},
        PermissionItem("Show DotForge notifications", "The timer on the lock screen, and quiet copies of silenced ones", PermissionState.ALLOWED) {},
    )

    @Test fun profileMixedPermissionsAndHelp() {
        show { UndoSnackbarHost { ProfilePage(Nav(), mixed) } }
        capture(f, "12-profile-permissions-mixed")
    }

    @Test fun profileHelpSheet() {
        show { ProfilePage(Nav(), mixed, helpOpen = true) }
        capture(f, "13-profile-help-sheet")
    }

    @Test fun backup() {
        app(page(Page.Backup))
        capture(f, "20-backup")
    }

    @Test fun backupBusy() {
        show { BackupPage(Nav(), backingUp = true) }
        capture(f, "20b-backup-backing-up")
    }

    @Test fun backupError() {
        show { BackupPage(Nav(), error = "DotForge can't read this file. Pick a .dotforge backup.") }
        capture(f, "21-backup-error")
    }

    private fun sample(): Backup.Contents {
        val dir = Files.createTempDirectory("b").toFile()
        dir.resolve("designs.json").writeText("""[{"id":"a","name":"Heart","animated":false,"frames":"x"},{"id":"b","name":"Star","animated":true,"frames":"y"}]""")
        dir.resolve("rules.json").writeText("""{"version":5,"rules":[{"id":"r1","name":"WhatsApp","action":"SHOW"},{"id":"person-1","name":"Mom","action":"SHOW"},{"id":"block-1","name":"140","action":"SPAM"}]}""")
        val out = ByteArrayOutputStream()
        Backup.write(dir, JsonObject(emptyMap()), out)
        return Backup.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test fun backupRestore() {
        show { BackupPage(Nav(), preview = RestoreChoice(sample())) }
        capture(f, "22-backup-restore-add")
        rule.onNodeWithText("Replace everything").performClick()
        capture(f, "23-backup-restore-replace")
        rule.onAllNodes(hasText("Restore") and hasClickAction()).onLast().performClick()
        capture(f, "24-backup-restore-replace-confirm")
    }

    @Test fun licences() {
        app(page(Page.Licences))
        capture(f, "30-licences")
        rule.onNodeWithText("Doto").performClick()
        capture(f, "31-licence-sheet")
    }

    @Test fun oldScreens() {
        app(page(Page.OldScreens))
        capture(f, "32-old-screens")
    }

    @Test fun featureAndStubPages() {
        val nav = page(Page.Feature("torch"))
        app(nav)
        capture(f, "33-feature-page-template")
    }

    @Test fun gallery() {
        app(page(Page.Components))
        capture(f, "40-components-rows")
        val list = rule.onNode(hasScrollAction())
        list.performScrollToIndex(2)
        capture(f, "41-components-tiles-previews")
        list.performScrollToIndex(4)
        capture(f, "42-components-slider-buttons-chips")
        list.performScrollToIndex(7)
        capture(f, "47-components-states-loading-denied-not-phone3")
        list.performScrollToIndex(8)
        capture(f, "43-components-sheets-empty")
        rule.onNodeWithText("Show a snackbar with Undo").performClick()
        capture(f, "44-snackbar-undo")
        rule.onNodeWithText("Open a sheet").performClick()
        capture(f, "45-sheet-options")
    }

    @Test fun confirmDialog() {
        app(page(Page.Components))
        rule.onNode(hasScrollAction()).performScrollToIndex(8)
        rule.onNodeWithText("Open a confirm dialog").performClick()
        capture(f, "48-confirm-dialog")
    }

    @Test fun previewsWithRemoveAnimations() {
        show(reduceMotion = true) { ComponentGallery(onBack = {}) }
        rule.onNode(hasScrollAction()).performScrollToIndex(3)
        capture(f, "46-previews-remove-animations")
    }

    private fun timing(kind: TimingKind, name: String) {
        show { ComponentGallery(onBack = {}, timingKind = kind) }
        capture(f, "$name-top")
        rule.onAllNodes(hasScrollAction()).onFirst()
        val sheet = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size - 1
        rule.onAllNodes(hasScrollAction())[sheet].performScrollToNode(hasText("More"))
        rule.onNodeWithText("More").performClick()
        rule.onAllNodes(hasScrollAction())[sheet].performScrollToNode(hasText("Fade in and out"))
        capture(f, "$name-more")
    }

    @Test fun timingStill() = timing(TimingKind.STILL, "50-timing-still")
    @Test fun timingAnimation() = timing(TimingKind.ANIMATION, "52-timing-animation")
    @Test fun timingText() = timing(TimingKind.TEXT, "54-timing-text")
    @Test fun timingUse() = timing(TimingKind.USE, "56-timing-use-override")

    @Test fun timingUntilChecked() {
        show { ComponentGallery(onBack = {}, timingKind = TimingKind.ANIMATION) }
        rule.onAllNodesWithText("Until I check").onFirst().performClick()
        capture(f, "58-timing-until-i-check")
    }

    @Test fun timingSecondsAndSpeed() {
        show { ComponentGallery(onBack = {}, timingKind = TimingKind.ANIMATION) }
        rule.onAllNodesWithText("10 s").onFirst().performClick()
        rule.onAllNodesWithText("Fast 2x").onFirst().performClick()
        capture(f, "59-timing-seconds-fast")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FoundationPhone3 : FoundationScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class FoundationSmall13 : FoundationScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class FoundationPhone3Font2 : FoundationScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
