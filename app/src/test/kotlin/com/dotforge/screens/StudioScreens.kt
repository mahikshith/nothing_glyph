package com.dotforge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.dotforge.AppGraph
import com.dotforge.composition.ComposeStyle
import com.dotforge.composition.ConvertLayout
import com.dotforge.composition.ImportKind
import com.dotforge.composition.SongWindow
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.BackgroundEffect
import com.dotforge.device.BackgroundLayer
import com.dotforge.device.Brush
import com.dotforge.device.DotFont
import com.dotforge.device.Frame
import com.dotforge.device.LayerKind
import com.dotforge.device.Layers
import com.dotforge.device.Mirror
import com.dotforge.device.PhotoDots
import com.dotforge.device.Region
import com.dotforge.device.TextBand
import com.dotforge.device.TextLayer
import com.dotforge.device.TextRender
import com.dotforge.device.TextSpec
import com.dotforge.device.TextStyle
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.rules.GlyphChoice
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.SimpleRules
import com.dotforge.studio.Design
import com.dotforge.studio.DesignKind
import com.dotforge.ui.Nav
import com.dotforge.ui.Tab
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.TopBar
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.editor.EditorPanel
import com.dotforge.ui.editor.EditorScreen
import com.dotforge.ui.editor.EditorStart
import com.dotforge.ui.editor.PhoneState
import com.dotforge.ui.editor.Tool
import com.dotforge.ui.studio.DesignShare
import com.dotforge.ui.studio.NewSheet
import com.dotforge.ui.studio.PhotoLoad
import com.dotforge.ui.studio.PhotoScreen
import com.dotforge.ui.studio.PhotoSession
import com.dotforge.ui.studio.PhotoTab
import com.dotforge.ui.studio.RingtoneFailed
import com.dotforge.ui.studio.RingtoneLoading
import com.dotforge.ui.studio.RingtoneReady
import com.dotforge.ui.studio.RingtoneUi
import com.dotforge.ui.studio.ShareSheet
import com.dotforge.ui.studio.StudioScreen
import com.dotforge.ui.studio.StudioSheet
import com.dotforge.ui.studio.TextPanel
import com.dotforge.ui.studio.TextScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

/** Every screen and state of Studio: the grid, + New, the editor, Text, Photo to dots and the ringtone screen. */
abstract class StudioScreens : ScreenshotTest() {
    private val f = "studio"
    private val p get() = AppGraph.profile

    /** [n] designs from the library, newest first; returns them. */
    private fun seed(n: Int): List<Design> {
        val items = AnimationLibrary.all.filter { it.load(p).frames.size in 1..60 }.take(n)
        val now = System.currentTimeMillis()
        val out = items.mapIndexed { i, it ->
            val a = it.load(p)
            Design(name = it.name, animated = a.frames.size > 1, frames = a.toMuseumJson(), updatedAt = now - i * 3_600_000L)
        }
        out.reversed().forEach { AppGraph.designs.save(it) }
        return AppGraph.designs.designs.value
    }

    private fun still(): Design {
        val a = AnimationLibrary.all.first { it.load(p).frames.size == 1 }.load(p)
        return Design(name = "Smile", animated = false, frames = a.toMuseumJson()).also { AppGraph.designs.save(it) }
    }

    private fun animation(): Design {
        val a = AnimationLibrary.all.first { it.load(p).frames.size in 6..20 }.load(p)
        return Design(name = "Bounce", animated = true, frames = a.toMuseumJson()).also { AppGraph.designs.save(it) }
    }

    private fun layered(): Design {
        val bg = AnimationLibrary.all.first { it.load(p).frames.size in 6..30 }.load(p)
        val layers = Layers(background = BackgroundLayer(bg.toMuseumJson(), "Ripple", BackgroundEffect.PLAY), text = TextLayer(TextSpec("HI", style = TextStyle.SCROLL, band = TextBand.BOTTOM)))
        val drawing = Animation.single(Frame.blank(p), 1000)
        val d = Design(
            name = "Party", animated = true, frames = com.dotforge.device.Compositor.render(p, layers, drawing).toMuseumJson(),
            layers = layers, drawing = drawing.toMuseumJson(),
        )
        AppGraph.designs.save(d)
        return d
    }

    /** Lets work done off the main thread (style thumbnails, live previews) land before a capture. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(500)
        Thread.sleep(2_000)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    private fun studio(nav: Nav = Nav(Tab.STUDIO), start: StudioSheet? = null, over: (@androidx.compose.runtime.Composable () -> Unit)? = null) = show {
        UndoSnackbarHost(bottomBar = true) {
            StudioScreen(nav, rememberLazyListState(), start)
            over?.invoke()
        }
    }

    private fun editor(d: Design?, start: EditorStart = EditorStart(phone = PhoneState.ON), animated: Boolean = false) =
        show { EditorScreen(d?.id, animated, null, Nav(Tab.STUDIO), start) }

    // ---------------------------------------------------------------- Studio

    @Test fun studioEmpty() {
        studio()
        capture(f, "01-studio-empty")
    }

    @Test fun studioDesigns() {
        val ds = seed(6)
        AppGraph.rules.upsert(SimpleRules.newApp("com.whatsapp", "WhatsApp", GlyphChoice(GlyphStyle.DESIGN, ds[0].id)).copy(id = "wa"))
        studio()
        capture(f, "02-studio-designs")
    }

    @Test fun studioMany() {
        seed(15)
        studio()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Recent"))
        capture(f, "03-studio-many-search")
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Nothing matches", substring = true))
        capture(f, "04-studio-search-none")
    }

    @Test fun studioCardMenu() {
        val ds = seed(4)
        AppGraph.rules.upsert(SimpleRules.newApp("com.whatsapp", "WhatsApp", GlyphChoice(GlyphStyle.DESIGN, ds[0].id)).copy(id = "wa"))
        studio(start = StudioSheet.Menu(ds[0].id))
        capture(f, "05-studio-card-menu")
        rule.onNodeWithText("Delete").performClick()
        capture(f, "06-studio-deleted-undo")
    }

    @Test fun studioRename() {
        val ds = seed(3)
        // a focused field in a dialog never lets Robolectric go idle
        com.dotforge.ui.studio.RenameFocus.auto = false
        studio(start = StudioSheet.Rename(ds[0].id))
        capture(f, "07-studio-rename")
    }

    @Test fun studioUseFor() {
        val ds = seed(3)
        studio(start = StudioSheet.UseFor(ds[0].id, fresh = true))
        capture(f, "08-studio-use-for")
    }

    @Test fun studioShare() {
        val ds = seed(3)
        studio(start = StudioSheet.Share(ds[0].id))
        capture(f, "09-studio-share")
        studio(over = { ShareSheet(ds[0], onDismiss = {}, making = DesignShare.Kind.GIF) })
        capture(f, "10-studio-share-making-gif")
    }

    @Test fun studioContinue() {
        val ds = seed(3)
        AppGraph.designs.beginEditing(ds[1].id)
        AppGraph.designs.editorOpen = false
        studio()
        capture(f, "11-studio-continue")
    }

    @Test fun studioSaved() {
        val ds = seed(3)
        val nav = Nav(Tab.STUDIO).also { it.studioRequest = "saved:${ds[0].id}" }
        studio(nav)
        capture(f, "12-studio-saved-use-for")
    }

    @Test fun studioNew() {
        studio(start = StudioSheet.New)
        capture(f, "13-new")
        studio(over = { NewSheet(Nav(Tab.STUDIO), onDismiss = {}, startOnFiles = true) })
        capture(f, "14-new-photo-or-file")
    }

    @Test fun studioRingtonesGetMore() {
        studio(start = StudioSheet.GetMore)
        capture(f, "15-get-more-ringtones")
    }

    // ---------------------------------------------------------------- editor

    @Test fun editorNewStill() {
        editor(null)
        capture(f, "20-editor-new")
    }

    @Test fun editorStillTools() {
        val d = still()
        editor(d)
        capture(f, "21-editor-still")
        editor(d, EditorStart(tool = Tool.ERASE, phone = PhoneState.ON, brush = Brush(size = 3, mirror = Mirror.BOTH)))
        capture(f, "22-editor-erase-mirror")
        editor(d, EditorStart(tool = Tool.FILL, phone = PhoneState.ON, brush = Brush(value = 111)))
        capture(f, "23-editor-fill-shade")
        editor(d, EditorStart(tool = Tool.SHAPES, phone = PhoneState.ON))
        capture(f, "24-editor-shapes")
        editor(d, EditorStart(tool = Tool.SELECT, selection = Region(6, 6, 16, 14), phone = PhoneState.ON))
        capture(f, "25-editor-select")
        editor(d, EditorStart(phone = PhoneState.ON, prepare = { it.picking = true }))
        capture(f, "26-editor-pick")
    }

    @Test fun editorStillSheets() {
        val d = still()
        editor(d, EditorStart(EditorPanel.SHADE, phone = PhoneState.ON))
        capture(f, "27-editor-shade")
        editor(d, EditorStart(EditorPanel.EFFECTS, phone = PhoneState.ON))
        capture(f, "28-editor-effects")
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Pulse"))
        rule.onNodeWithText("Pulse").performClick()
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Apply", substring = true))
        settle()
        capture(f, "29-editor-effects-make-it-move")
        editor(d, EditorStart(EditorPanel.TIMING, phone = PhoneState.ON))
        capture(f, "30-editor-timing")
        editor(d, EditorStart(EditorPanel.MORE, phone = PhoneState.ON))
        capture(f, "31-editor-more")
        editor(d, EditorStart(EditorPanel.INSERT, phone = PhoneState.ON))
        capture(f, "33-editor-insert")
        editor(d, EditorStart(EditorPanel.SHARE, phone = PhoneState.ON))
        capture(f, "34-editor-share")
    }

    @Test fun editorRename() {
        val d = still()
        com.dotforge.ui.studio.RenameFocus.auto = false
        editor(d, EditorStart(EditorPanel.RENAME, phone = PhoneState.ON))
        capture(f, "32-editor-rename")
    }

    @Test fun editorAnimation() {
        val d = animation()
        editor(d, EditorStart(phone = PhoneState.ON, prepare = { it.select(2) }))
        capture(f, "35-editor-animation-onion")
        editor(d, EditorStart(EditorPanel.CLEAR, phone = PhoneState.ON, prepare = { it.select(2) }))
        capture(f, "36-editor-clear-menu")
        editor(d, EditorStart(EditorPanel.FRAME, phone = PhoneState.ON, prepare = { it.select(2) }))
        capture(f, "37-editor-frame-menu")
        editor(d, EditorStart(playing = true, phone = PhoneState.ON))
        capture(f, "38-editor-playing")
        editor(d, EditorStart(EditorPanel.GUIDE, phone = PhoneState.ON))
        capture(f, "39-editor-guide")
    }

    @Test fun editorPhoneStates() {
        val d = still()
        listOf(PhoneState.OFF to "40", PhoneState.BUSY to "41", PhoneState.FACE_DOWN to "42", PhoneState.NOT_PHONE3 to "43").forEach { (ph, n) ->
            editor(d, EditorStart(phone = ph, prepare = { s -> if (ph == PhoneState.OFF) s.showOnPhone = false }))
            capture(f, "$n-editor-phone-${ph.name.lowercase().replace('_', '-')}")
        }
    }

    @Test fun editorLayers() {
        val d = layered()
        editor(d)
        capture(f, "44-editor-layers-drawing")
        editor(d, EditorStart(layer = LayerKind.BACKGROUND, phone = PhoneState.ON))
        capture(f, "45-editor-layer-background")
        editor(d, EditorStart(layer = LayerKind.TEXT, phone = PhoneState.ON))
        capture(f, "46-editor-layer-text")
        editor(d, EditorStart(EditorPanel.LAYERS, phone = PhoneState.ON))
        capture(f, "47-editor-layers-sheet")
        editor(d, EditorStart(EditorPanel.LAYER_TEXT, phone = PhoneState.ON, layer = LayerKind.TEXT))
        capture(f, "48-editor-text-layer-sheet")
        editor(d, EditorStart(EditorPanel.LAYER_BG, phone = PhoneState.ON, layer = LayerKind.BACKGROUND))
        capture(f, "49-editor-background-picker")
    }

    // ---------------------------------------------------------------- add a photo as a frame, trace photo

    @Test fun editorAddPhotoAsFrame() {
        val d = animation()
        editor(d, EditorStart(EditorPanel.ADD_MENU, phone = PhoneState.ON, prepare = { it.select(2) }))
        capture(f, "80-editor-add-frame-menu")
        editor(d, EditorStart(EditorPanel.INSERT, phone = PhoneState.ON, prepare = { it.select(2) }))
        capture(f, "81-editor-insert-photo")
        var session: com.dotforge.ui.editor.EditorSession? = null
        val beach = com.dotforge.ui.studio.MediaSource.Pictures(listOf(PhotoDots.samplePicture(200, 150)), "Beach")
        editor(d, EditorStart(phone = PhoneState.ON, photo = beach, prepare = { it.select(2); session = it }))
        settle()
        capture(f, "82-editor-photo-add-as-frame")
        val trip = com.dotforge.ui.studio.MediaSource.Pictures(List(3) { PhotoDots.samplePicture(160 + it * 20, 120) }, "Trip")
        editor(d, EditorStart(phone = PhoneState.ON, photo = trip, prepare = { it.select(2) }))
        settle()
        capture(f, "83-editor-photos-add-frames")
        editor(d, EditorStart(phone = PhoneState.ON, photo = beach, prepare = { it.select(2); session = it }))
        settle()
        val before = session!!.doc.size
        rule.onNodeWithText("Add as frame").performClick()
        rule.waitForIdle()
        check(session!!.doc.size == before + 1 && session!!.doc.cur == 3) { "the photo goes in after frame 3" }
        capture(f, "84-editor-photo-added")
    }

    @Test fun editorTracePhoto() {
        val d = still()
        editor(d, EditorStart(EditorPanel.TRACE, phone = PhoneState.ON))
        capture(f, "85-editor-trace-choose")
        val file = com.dotforge.ui.studio.PhotoLoader.keep(PhotoDots.samplePicture(300, 300), "shot-trace")
        val traced = d.copy(trace = com.dotforge.studio.TracePhoto(file, opacity = 45)).also { AppGraph.designs.update(it) }
        editor(traced)
        settle()
        capture(f, "86-editor-trace-under-grid")
        editor(traced, EditorStart(EditorPanel.TRACE, phone = PhoneState.ON))
        settle()
        capture(f, "87-editor-trace-sheet")
        editor(traced, EditorStart(EditorPanel.MORE, phone = PhoneState.ON))
        capture(f, "88-editor-more-trace")
    }

    // ---------------------------------------------------------------- Text

    private fun textDesign(spec: TextSpec, layers: Layers? = null): Design {
        val d = Design(
            name = spec.text, animated = true, frames = TextRender.render(p, spec).toMuseumJson(), kind = DesignKind.TEXT, text = spec, layers = layers,
            drawing = layers?.let { Animation.single(Frame.blank(p), 1000).toMuseumJson() },
        )
        AppGraph.designs.save(d)
        return d
    }

    /** The Text screen's scrolling controls (not the style strip, which scrolls sideways). */
    private fun textColumn() = rule.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyChild(hasText("Style")))

    @Test fun textNew() {
        show { TextScreen(null, null, Nav(Tab.STUDIO)) }
        settle()
        capture(f, "50-text-new")
        show { TextScreen(null, "I ♥ U", Nav(Tab.STUDIO), TextPanel.SYMBOLS) }
        settle()
        capture(f, "51-text-symbols")
    }

    @Test fun textInPlace() {
        val d = textDesign(TextSpec("BUSY", font = DotFont.BOLD, style = TextStyle.TYPEWRITER))
        show { TextScreen(d.id, null, Nav(Tab.STUDIO)) }
        textColumn().performScrollToNode(hasText("Font"))
        settle()
        capture(f, "52-text-plays-in-place")
        textColumn().performScrollToNode(hasText("Background"))
        capture(f, "53-text-look-timing")
    }

    @Test fun textBackground() {
        val bg = AnimationLibrary.all.first { it.load(p).frames.size in 6..30 }.load(p)
        val spec = TextSpec("GN", band = TextBand.BOTTOM)
        val d = textDesign(spec, Layers(BackgroundLayer(bg.toMuseumJson(), "Moon", BackgroundEffect.PLAY), TextLayer(spec)))
        show { TextScreen(d.id, null, Nav(Tab.STUDIO)) }
        textColumn().performScrollToNode(hasText("Behind the words"))
        settle()
        capture(f, "54-text-background")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO), TextPanel.MENU) }
        capture(f, "55-text-menu")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO), TextPanel.TIMING) }
        capture(f, "56-text-timing")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO), TextPanel.BACKGROUND) }
        capture(f, "57-text-background-picker")
    }

    // ---------------------------------------------------------------- Photo

    private fun photo(n: Int = 1) = PhotoSession(p, if (n == 1) "Beach" else "Trip", List(n) { PhotoDots.samplePicture(160 + it * 20, 120) }, List(n) { if (n == 1) 1000 else 500 })

    @Test fun photoTabs() {
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photo())) }
        settle()
        capture(f, "60-photo-style")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.CROP, PhotoLoad.Ready(photo())) }
        capture(f, "61-photo-crop")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.ADJUST, PhotoLoad.Ready(photo())) }
        capture(f, "62-photo-adjust")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photo()), startAnimate = true) }
        settle()
        capture(f, "63-photo-animate")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photo(3))) }
        settle()
        capture(f, "64-photo-frames")
    }

    @Test fun photoLoading() {
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), startLoad = PhotoLoad.Loading("Converting… 12 of 48 frames", 0.25f)) }
        capture(f, "65-photo-converting-gif")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), startLoad = PhotoLoad.Failed("This GIF can't be read. Try another file.")) }
        capture(f, "66-photo-cant-read")
    }

    // ---------------------------------------------------------------- Ringtone

    private fun ringtonePage(content: @androidx.compose.runtime.Composable () -> Unit) = show {
        UndoSnackbarHost {
            Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopBar("Glyph ringtone", onBack = {}, close = true)
                content()
            }
        }
    }

    private fun song(kind: ImportKind = ImportKind.AudioOnly, playing: Boolean = false) = RingtoneUi(
        name = "Sunflower (Live)", kind = kind,
        envelope = if (kind == ImportKind.AudioOnly) FloatArray(400) { i -> (0.35f + 0.3f * sin(i / 9f) + 0.25f * sin(i / 2.3f)).coerceIn(0f, 1f) } else null,
        window = if (kind == ImportKind.AudioOnly) SongWindow.of(185_000, 42_000, 30_000) else null,
        updating = false, style = ComposeStyle.RIPPLE, layout = ConvertLayout.RINGS, brightness = 1f, speed = 1f, title = "Sunflower",
        pixels = AnimationLibrary.all.first().load(p).frames[0].frame.pixels, playing = playing, saving = false,
    )

    private fun ready(ui: RingtoneUi, menu: Boolean = false, flip: Boolean = false) = ringtonePage {
        RingtoneReady(ui, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, startMenu = menu, startFlip = flip)
    }

    @Test fun ringtoneStates() {
        ringtonePage { RingtoneLoading {} }
        capture(f, "70-ringtone-finding-the-beat")
        ringtonePage { RingtoneFailed({}, {}) }
        capture(f, "71-ringtone-cant-read")
        ready(song())
        capture(f, "72-ringtone-song")
        ready(song(playing = true), flip = true)
        capture(f, "73-ringtone-preview-flip")
        ready(song(), menu = true)
        capture(f, "74-ringtone-save-and-set")
        ready(song(ImportKind.Phone3(emptyList())))
        capture(f, "75-ringtone-glyph-file")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class StudioPhone3 : StudioScreens() {
    override val sizeName = "412x917"
    override val fontScale = 1f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w360dp-h640dp-xhdpi")
class StudioSmall13 : StudioScreens() {
    override val sizeName = "360x640-font1.3"
    override val fontScale = 1.3f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w412dp-h917dp-xxhdpi")
class StudioPhone3Font2 : StudioScreens() {
    override val sizeName = "412x917-font2"
    override val fontScale = 2f
}
