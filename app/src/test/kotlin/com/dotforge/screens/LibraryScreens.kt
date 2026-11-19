package com.dotforge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.dotforge.AppGraph
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.device.library.Categories
import com.dotforge.device.library.ItemForm
import com.dotforge.device.library.LibraryItem
import com.dotforge.rules.SimpleRules
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.alerts.GlyphPicker
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.editor.EditorPanel
import com.dotforge.ui.editor.EditorScreen
import com.dotforge.ui.editor.EditorStart
import com.dotforge.ui.editor.PhoneState
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.library.LibraryActions
import com.dotforge.ui.library.LibraryBrowserState
import com.dotforge.ui.library.LibraryItemSheet
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.ui.library.LibraryScreen
import com.dotforge.ui.studio.TextBackgroundSheet
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every state of the Library: chips, search, Favourites, Recently used, the item sheet and the picker in each host. */
abstract class LibraryScreens : ScreenshotTest() {
    private val f = "library"

    init {
        com.dotforge.ui.editor.GlyphStateForTests.forced = PhoneState.ON
    }

    @Before fun freshPrefs() = LibraryPrefs.reset()

    /** Clicks through semantics: touches don't reach a sheet's window under Robolectric. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun tile(name: String) = rule.onAllNodesWithContentDescription(name, substring = true).onFirst()

    /** Lets live previews start before a capture. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private fun library(chip: String = LibraryBrowserState.FEATURED, query: String = "") = show {
        UndoSnackbarHost { LibraryScreen(Nav(Tab.STUDIO), LibraryBrowserState(chip, query)) }
    }

    private fun item(id: String) = AnimationLibrary.item(id)!!

    private fun sheet(item: LibraryItem) = show {
        UndoSnackbarHost {
            Box(Modifier.fillMaxSize().background(Tokens.Background))
            LibraryItemSheet(item, Nav(Tab.STUDIO), onDismiss = {}) {}
        }
    }

    // ---------------------------------------------------------------- the page

    @Test fun featuredAndAll() {
        library()
        settle()
        capture(f, "01-library-featured")
        library(LibraryBrowserState.ALL)
        settle()
        capture(f, "02-library-all")
    }

    @Test fun categories() {
        library(Categories.Love.id)
        settle()
        capture(f, "04-library-love")
        library(Categories.Anime.id)
        settle()
        capture(f, "05-library-anime")
        library(Categories.Text.id)
        settle()
        capture(f, "06-library-text-groups")
        library(Categories.GlyphText.id)
        settle()
        capture(f, "07-library-glyph-text")
        library(Categories.Seasonal.id)
        settle()
        capture(f, "08-library-celebrations")
    }

    @Test fun categoriesSheet() {
        library()
        rule.onNodeWithContentDescription("Categories").tap()
        rule.waitForIdle()
        settle()
        capture(f, "03-categories-sheet")
        // opened from a category: that card is marked
        library(Categories.Food.id)
        rule.onNodeWithContentDescription("Category: Food & delivery. Choose another").tap()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Food & delivery, ", substring = true)).performScrollTo()
        settle()
        capture(f, "03b-categories-sheet-current")
    }

    @Test fun search() {
        library(query = "heart")
        settle()
        capture(f, "10-search-heart")
        library(query = "qqqzzz")
        capture(f, "11-search-empty")
        library(Categories.Love.id, query = "car")
        capture(f, "12-search-empty-in-category")
    }

    @Test fun favourites() {
        library(LibraryBrowserState.FAVOURITES)
        capture(f, "20-favourites-empty")
        for (id in listOf("gt-i-h-u", "g-heart-arrow", "g-food-bag", "g-car")) if (AnimationLibrary.item(id) != null) LibraryPrefs.toggleFavourite(id)
        library(LibraryBrowserState.FAVOURITES)
        settle()
        capture(f, "21-favourites")
    }

    @Test fun longPressAddsAFavourite() {
        library(query = "food bag")
        tile("Food bag, animated").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        capture(f, "22-long-press-favourite")
    }

    @Test fun recent() {
        library(LibraryBrowserState.RECENT)
        capture(f, "25-recent-empty")
        for (id in listOf("g-bell", "gt-sixer", "g-heart-arrow", "g-parcel")) if (AnimationLibrary.item(id) != null) LibraryPrefs.used(id)
        library(LibraryBrowserState.RECENT)
        settle()
        capture(f, "26-recent")
    }

    // ---------------------------------------------------------------- the item sheet

    @Test fun itemSheets() {
        sheet(item("g-food-bag"))
        capture(f, "30-item-still")
        sheet(item("g-heart-arrow"))
        capture(f, "31-item-animated")
        sheet(AnimationLibrary.search("come in").first { it.form == ItemForm.TEXT })
        capture(f, "32-item-text")
        sheet(AnimationLibrary.search("sixer").first { it.form == ItemForm.LAYERED })
        capture(f, "33-item-glyph-text")
        sheet(AnimationLibrary.all.first { it.credit != null })
        capture(f, "34-item-credit")
    }

    @Test fun itemSheetActions() {
        val it = item("g-bell")
        LibraryPrefs.toggleFavourite(it.id)
        sheet(it)
        capture(f, "35-item-favourite")
        rule.onNodeWithText("Try on Glyph").tap()
        rule.waitForIdle()
        capture(f, "36-item-try-on-glyph-stop")
        AppGraph.arbiter.remove(LibraryActions.TRY_ID)
    }

    @Test fun itemSheetUseFor() {
        library(query = "cupid arrow")
        tile("Cupid arrow").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Use for…").tap()
        rule.waitForIdle()
        capture(f, "37-item-use-for")
    }

    // ---------------------------------------------------------------- every host

    @Test fun hostAlerts() {
        val r = AppGraph.rules.rules.value.first { it.id == SimpleRules.REMINDERS }
        show {
            Box(Modifier.fillMaxSize().background(Tokens.Background))
            GlyphPicker(r, "Reminders", isApp = false, onDismiss = {}) {}
        }
        settle()
        capture(f, "40-host-alerts-glyph")
    }

    @Test fun hostInsert() {
        show { EditorScreen(null, false, null, Nav(Tab.STUDIO), EditorStart(panel = EditorPanel.INSERT, phone = PhoneState.ON)) }
        settle()
        capture(f, "41-host-editor-insert")
    }

    @Test fun hostBackground() {
        val d = LibraryActions.saveCopy(item("gt-gn").let { it })
        show { EditorScreen(d.id, true, null, Nav(Tab.STUDIO), EditorStart(panel = EditorPanel.LAYER_BG, phone = PhoneState.ON)) }
        settle()
        capture(f, "42-host-layer-background")
    }

    @Test fun hostTextBackground() {
        show {
            UndoSnackbarHost {
                Box(Modifier.fillMaxSize().background(Tokens.Background))
                TextBackgroundSheet(onDismiss = {}) { _, _ -> }
            }
        }
        settle()
        capture(f, "43-host-text-background")
    }

    private fun home() = show {
        UndoSnackbarHost(bottomBar = true) {
            androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.HOME) {} }) { pad ->
                Box(Modifier.padding(pad)) { HomeScreen(Nav(Tab.HOME), Setup(activity), rememberLazyListState(), com.dotforge.ui.home.HomeStatus()) }
            }
        }
    }

    /** Home's designs and "Choose a design" sit under the Glyph card and the switches. */
    private fun toDesigns() {
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Choose a design"))
        rule.waitForIdle()
    }

    @Test fun hostHomeYours() {
        AppGraph.designs.save(LibraryActions.design(item("g-cake")))
        home()
        toDesigns()
        rule.onNodeWithText("Choose a design").tap()
        rule.waitForIdle()
        rule.onNodeWithText("Yours").tap()
        rule.waitForIdle()
        settle()
        capture(f, "48-host-home-picker-yours")
    }

    @Test fun hostHome() {
        home()
        toDesigns()
        settle()
        capture(f, "44-host-home")
        for (id in listOf("g-bell", "gt-sixer")) LibraryPrefs.used(id)
        home()
        toDesigns()
        settle()
        capture(f, "45-host-home-recent")
        rule.onNodeWithText("Choose a design").tap()
        rule.waitForIdle()
        settle()
        capture(f, "46-host-home-picker")
        // one that isn't among Home's own tiles, so the tap lands in the picker
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("on my way")
        rule.waitForIdle()
        tile("OMW, glyph and text").tap()
        rule.waitForIdle()
        capture(f, "47-host-home-item-sheet")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class LibraryPhone3 : LibraryScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class LibrarySmall13 : LibraryScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class LibraryPhone3Font2 : LibraryScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
