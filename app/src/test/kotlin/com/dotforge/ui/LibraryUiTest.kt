package com.dotforge.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import com.dotforge.AppGraph
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.device.library.ItemForm
import com.dotforge.rules.GlyphChoice
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.SimpleRules
import com.dotforge.screens.ScreenshotApp
import com.dotforge.ui.alerts.GlyphPicker
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.editor.Drafts
import com.dotforge.ui.editor.PickDesignSheet
import com.dotforge.ui.editor.backgroundFilter
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.library.LibraryActions
import com.dotforge.ui.library.LibraryBrowser
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.ui.library.LibraryScreen
import com.dotforge.ui.studio.TextBackgroundSheet
import com.dotforge.ui.studio.TextStarts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Library: chips, search, Favourites, Recently used, the item sheet and the picker in every host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class LibraryUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val prefs get() = rule.activity.getSharedPreferences("library-test", Context.MODE_PRIVATE)

    @Before fun fresh() {
        prefs.edit().clear().commit()
        LibraryPrefs.init(prefs)
    }

    private fun search(text: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(text)
        rule.waitForIdle()
    }

    /** Clicks through semantics: touches don't reach a sheet's window under Robolectric. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun tile(name: String) = rule.onAllNodesWithContentDescription(name, substring = true).onFirst()

    @Test fun opensOnFeaturedAndCategoriesSitInTheirOwnSheet() {
        rule.setContent { DotForgeTheme { LibraryBrowser { } } }
        rule.onNodeWithText("Featured").assertIsSelected()
        for (chip in listOf("All", "Favourites", "Recently used")) {
            rule.onNodeWithTag("library-chips").performScrollToNode(hasText(chip))
            rule.onNodeWithText(chip).assertExists()
        }
        rule.onNodeWithContentDescription("Categories").tap()
        rule.waitForIdle()
        for (title in listOf("Love", "Heartbreak", "Moods", "Anime style", "Celebrations", "Utility", "Food & delivery", "Travel & rides", "Work & study", "Sports", "Text", "Glyph + text")) {
            rule.onNode(hasContentDescription("$title, ", substring = true)).performScrollTo().assertExists()
        }
        // a category shows only that category, and its chip leads the row
        rule.onNode(hasContentDescription("Glyph + text, ", substring = true)).performScrollTo().tap()
        rule.waitForIdle()
        tile("I♥U, glyph and text").assertExists()
        rule.onNodeWithText("Glyph + text ▾").assertIsSelected()
    }

    @Test fun searchFindsAndShowsAnEmptyState() {
        rule.setContent { DotForgeTheme { LibraryBrowser { } } }
        search("food bag")
        tile("Food bag, animated").assertExists()
        search("qqqzzz")
        rule.onNodeWithText("Nothing matches “qqqzzz”.").assertExists()
        rule.onNodeWithText("Clear search").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Featured").assertIsSelected()
    }

    @Test fun searchFromFeaturedMovesToAllAndClearingGoesBack() {
        rule.setContent { DotForgeTheme { LibraryBrowser { } } }
        search("heart")
        rule.onNodeWithText("All").assertIsSelected()
        rule.onNodeWithText("Featured").assertIsNotSelected()
        search("")
        rule.onNodeWithText("Featured").assertIsSelected()
    }

    @Test fun searchInACategoryStaysThereAndOffersEverything() {
        val state = com.dotforge.ui.library.LibraryBrowserState(com.dotforge.device.library.Categories.Love.id, "")
        rule.setContent { DotForgeTheme { LibraryBrowser(state = state) { } } }
        search("car")
        rule.onNodeWithText("Love ▾").assertIsSelected()
        rule.onNodeWithText("Nothing in Love matches “car”.").assertExists()
        rule.onNodeWithText("Search everything").tap()
        rule.waitForIdle()
        rule.onNodeWithText("All").assertIsSelected()
        tile("Car").assertExists()
    }

    @Test fun theSelectedChipScrollsIntoView() {
        val state = com.dotforge.ui.library.LibraryBrowserState(com.dotforge.ui.library.LibraryBrowserState.RECENT, "")
        rule.setContent { DotForgeTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) { LibraryBrowser(state = state) { } } } }
        rule.waitForIdle()
        rule.onNodeWithText("Recently used").assertIsDisplayed()
        state.chip = com.dotforge.ui.library.LibraryBrowserState.FEATURED
        rule.waitForIdle()
        rule.onNodeWithText("Featured").assertIsDisplayed()
    }

    @Test fun favouritesComeFromALongPressAndPersist() {
        rule.setContent { DotForgeTheme { LibraryBrowser { } } }
        rule.onNodeWithText("Favourites").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Long-press any design to keep it here.").assertExists()
        rule.onNodeWithText("All").tap()
        search("food bag")
        tile("Food bag, animated").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        assertTrue(LibraryPrefs.isFavourite("g-food-bag"))
        tile("Food bag, animated").assertExists()
        // stored: a fresh start reads it back
        LibraryPrefs.init(rule.activity.getSharedPreferences("library-test", Context.MODE_PRIVATE))
        assertEquals(listOf("g-food-bag"), LibraryPrefs.favourites.toList())
        search("")
        rule.onNodeWithText("Favourites").tap()
        rule.waitForIdle()
        tile("Food bag, animated").assertExists()
        // long-press again removes it
        tile("Food bag, animated").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        assertFalse(LibraryPrefs.isFavourite("g-food-bag"))
        rule.onNodeWithText("Long-press any design to keep it here.").assertExists()
    }

    @Test fun recentlyUsedFollowsPicksAndKeepsTheNewestFirst() {
        var picked: String? = null
        rule.setContent { DotForgeTheme { PickDesignSheet("Insert", onDismiss = {}) { name, _ -> picked = name } } }
        rule.onNodeWithText("Recently used").tap()

        rule.waitForIdle()
        rule.onNodeWithText("What you try, use or edit shows here.").assertExists()
        rule.onNodeWithText("All").tap()
        search("parcel delivered")
        tile("Parcel delivered").tap()
        rule.waitForIdle()
        assertEquals("Parcel delivered", picked)
        LibraryPrefs.used("g-bell")
        assertEquals(listOf("g-bell", "g-parcel-done"), LibraryPrefs.recent.toList())
        repeat(LibraryPrefs.MAX_RECENT + 5) { LibraryPrefs.used("x$it") }
        assertEquals(LibraryPrefs.MAX_RECENT, LibraryPrefs.recent.size)
    }

    @Test fun alertsGlyphPickerUsesTheLibrary() {
        val rule0 = AppGraph.rules.rules.value.first { it.id == SimpleRules.PHONE_CALLS }
        var choice: GlyphChoice? = null
        rule.setContent { DotForgeTheme { GlyphPicker(rule0, "Calls", isApp = false, onDismiss = {}) { choice = it } } }
        search("food bag")
        tile("Food bag, animated").tap()
        rule.waitForIdle()
        assertEquals(GlyphChoice(GlyphStyle.ICON, "g-food-bag"), choice)
        // and an alert can play it
        assertTrue(com.dotforge.device.GlyphLibrary.alert(AppGraph.profile, "g-food-bag").frames.isNotEmpty())
    }

    @Test fun backgroundPickersLeaveOutReadyText() {
        var picked: String? = null
        rule.setContent { DotForgeTheme { TextBackgroundSheet(onDismiss = {}) { name, _ -> picked = name } } }
        search("kya scene")
        rule.onNodeWithText("Nothing matches “kya scene”.").assertExists()
        search("diya")
        tile("Diya, still").tap()
        rule.waitForIdle()
        assertEquals("Diya", picked)
        assertFalse(AnimationLibrary.all.filter { it.form == ItemForm.TEXT }.any(backgroundFilter))
    }

    @Test fun homePicksFromTheLibraryAndOpensTheItemSheet() {
        val nav = Nav(Tab.HOME)
        rule.setContent { DotForgeTheme { HomeScreen(nav, Setup(rule.activity), androidx.compose.foundation.lazy.rememberLazyListState()) } }
        rule.onNodeWithText("Choose a design").tap()
        rule.waitForIdle()
        // (not one of Home's own tiles, so the tap lands in the picker)
        search("parcel delivered")
        tile("Parcel delivered").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Try on Glyph").assertExists()
        rule.onNodeWithText("Use for…").assertExists()
        rule.onNodeWithText("Edit a copy").assertExists()
        assertEquals("g-parcel-done", LibraryPrefs.recent.first())
    }

    @Test fun itemSheetTriesUsesAndEditsCopies() {
        val nav = Nav(Tab.STUDIO)
        rule.setContent { DotForgeTheme { LibraryScreen(nav) } }
        search("sixer")
        tile("SIXER, glyph and text").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Try on Glyph").tap()
        rule.waitForIdle()
        assertTrue(AppGraph.arbiter.active.value.any { it.id == LibraryActions.TRY_ID })
        // the button turns into Stop in place
        rule.onNodeWithText("Stop on Glyph").tap()
        // the arbiter drops requests on its own scope, so wait for it rather than for Compose
        rule.waitUntil(5_000) { AppGraph.arbiter.active.value.none { it.id == LibraryActions.TRY_ID } }
        assertFalse(AppGraph.arbiter.active.value.any { it.id == LibraryActions.TRY_ID })
        rule.onNodeWithText("Add to Favourites").tap()
        rule.waitForIdle()
        assertTrue(LibraryPrefs.favourites.any { it.startsWith("gt-") })
        rule.onNodeWithText("Edit a copy").tap()
        rule.waitForIdle()
        val page = nav.page as Page.Editor
        val draft = Drafts.take(page.draft)!!
        assertNotNull("the glyph + text keeps its layers", draft.layers?.background)
        assertEquals("SIXER", draft.layers?.text?.spec?.text)
    }

    @Test fun useForSavesACopyOnlyOnceItIsUsed() {
        val nav = Nav(Tab.STUDIO)
        rule.setContent { DotForgeTheme { LibraryScreen(nav) } }
        search("parcel delivered")
        tile("Parcel delivered").tap()
        rule.waitForIdle()
        val before = AppGraph.designs.designs.value.size
        rule.onNodeWithText("Use for…").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Tick where it should show. Done saves a copy to your designs.").assertExists()
        rule.onNodeWithText("Done").tap()
        rule.waitForIdle()
        assertEquals("nothing ticked, nothing saved", before, AppGraph.designs.designs.value.size)
        tile("Parcel delivered").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Use for…").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Gallery").performScrollTo().tap()
        rule.onNodeWithText("Done").tap()
        rule.waitForIdle()
        assertEquals(before + 1, AppGraph.designs.designs.value.size)
    }

    @Test fun textItemsOpenInTheTextScreenAndUseForSavesACopy() {
        val nav = Nav(Tab.STUDIO)
        val item = AnimationLibrary.search("come in").first { it.form == ItemForm.TEXT }
        LibraryActions.editCopy(item, nav)
        val page = nav.page as Page.TextDesign
        val start = TextStarts.take(page.start)!!
        assertEquals("COME IN", start.spec.text)
        val before = AppGraph.designs.designs.value.size
        val d = LibraryActions.saveCopy(item)
        assertEquals(before + 1, AppGraph.designs.designs.value.size)
        assertEquals(com.dotforge.studio.DesignKind.TEXT, d.kind)
        assertEquals("COME IN", d.text?.text)
        val layered = LibraryActions.saveCopy(AnimationLibrary.item("gt-happy-diwali")!!)
        assertNotNull(layered.layers?.text)
    }
}
