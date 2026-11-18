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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.foundation.layout.padding
import com.dotforge.device.Picture
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

    init {
        // Text and Photo show their Glyph toggle live, as on a Phone (3)
        com.dotforge.ui.editor.GlyphStateForTests.forced = PhoneState.ON
    }
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

    /** Studio as the app shows it: the tab's list over the bottom bar. */
    private fun studio(nav: Nav = Nav(Tab.STUDIO), start: StudioSheet? = null, playing: String? = null, over: (@androidx.compose.runtime.Composable () -> Unit)? = null) = show {
        UndoSnackbarHost(bottomBar = true) {
            androidx.compose.material3.Scaffold(containerColor = Tokens.Background, bottomBar = { com.dotforge.ui.BottomBar(Tab.STUDIO) {} }) { pad ->
                androidx.compose.foundation.layout.Box(Modifier.padding(pad)) { StudioScreen(nav, rememberLazyListState(), start, playing) }
            }
            over?.invoke()
        }
    }

    /** A real test photo (CC0 / public domain, see NOTICE) from core-device's test resources. */
    private fun testPhoto(name: String): Picture {
        val bmp = javaClass.getResourceAsStream("/photos/$name.jpg")!!.use { android.graphics.BitmapFactory.decodeStream(it) }
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Picture(bmp.width, bmp.height, argb)
    }

    /** The saved-ringtones list without MediaStore: a fake library with three ringtones. */
    private class FakeRingtones(ctx: android.content.Context, private val list: List<com.dotforge.ringtones.SavedRingtone>) : com.dotforge.ringtones.RingtoneLibrary(ctx) {
        override fun refresh() { _items.value = list }
    }

    private fun seedRingtones(): List<com.dotforge.ringtones.SavedRingtone> {
        val list = listOf("Sunflower" to 30_000L, "Morning run" to 18_500L, "Lo-fi bells" to 42_000L).mapIndexed { i, (t, ms) ->
            com.dotforge.ringtones.SavedRingtone(android.net.Uri.parse("content://media/external/audio/media/${100 + i}"), t, ms)
        }
        AppGraph.ringtones = FakeRingtones(activity, list).also { it.refresh() }
        return list
    }

    private fun stroke() = rule.onNode(androidx.compose.ui.test.hasContentDescription("Dot editor", substring = true)).performTouchInput {
        swipe(androidx.compose.ui.geometry.Offset(width * 0.3f, height * 0.45f), androidx.compose.ui.geometry.Offset(width * 0.7f, height * 0.55f), durationMillis = 300)
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
        // WhatsApp already shows this design (ticked); Gmail doesn't
        AppGraph.rules.upsert(SimpleRules.newApp("com.whatsapp", "WhatsApp", GlyphChoice(GlyphStyle.DESIGN, ds[0].id)).copy(id = "wa"))
        AppGraph.rules.upsert(SimpleRules.newApp("com.google.android.gm", "Gmail").copy(id = "gm"))
        studio(start = StudioSheet.UseFor(ds[0].id))
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
        val nav = Nav(Tab.STUDIO)
        studio(over = { com.dotforge.ui.GetMoreSheet(nav, onDismiss = {}, load = { kotlinx.coroutines.awaitCancellation() }) })
        capture(f, "15-get-more-loading")
        studio(over = { com.dotforge.ui.GetMoreSheet(nav, onDismiss = {}, load = { throw java.io.IOException("offline") }) })
        capture(f, "15b-get-more-error-retry")
        val c = com.dotforge.ringtones.RingtoneCatalog.COLLECTIONS[0]
        val files = listOf("Bounce", "Night drive", "Chime").mapIndexed { i, n -> com.dotforge.ringtones.CatalogFile("$n.ogg", "demo", 40_000L + i * 9_000, "") }
        studio(over = { com.dotforge.ui.GetMoreSheet(nav, onDismiss = {}, load = { listOf(com.dotforge.ringtones.CatalogResult.Files(c, "MIT", files)) }) })
        capture(f, "15c-get-more-list")
    }

    @Test fun studioRingtoneList() {
        val list = seedRingtones()
        seed(2)
        studio(playing = list[0].uri.toString())
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Get more ringtones"))
        capture(f, "16-studio-ringtones-playing")
        studio(start = StudioSheet.SetAs(list[1].uri.toString(), list[1].title))
        capture(f, "17-ringtone-set-as")
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
        // then back up to the section's heading, so nothing reads cut under the pinned preview
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Make it move"))
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
        listOf(PhoneState.OFF to "40", PhoneState.TORCH to "41", PhoneState.BUSY to "41b", PhoneState.FACE_DOWN to "42", PhoneState.NOT_PHONE3 to "43").forEach { (ph, n) ->
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
        val beach = com.dotforge.ui.studio.MediaSource.Pictures(listOf(testPhoto("landscape")), "Landscape")
        editor(d, EditorStart(phone = PhoneState.ON, photo = beach, prepare = { it.select(2); session = it }))
        settle()
        capture(f, "82-editor-photo-add-as-frame")
        val trip = com.dotforge.ui.studio.MediaSource.Pictures(listOf("face", "pet", "landscape").map(::testPhoto), "Trip")
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
        val file = com.dotforge.ui.studio.PhotoLoader.keep(testPhoto("pet"), "shot-trace")
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

    // ---------------------------------------------------------------- Undo proof, zoom, brush

    @Test fun editorUndoProof() {
        val blank = Design(name = "Blank", animated = false, frames = Animation.single(Frame.blank(p), 1000).toMuseumJson()).also { AppGraph.designs.save(it) }
        editor(blank)
        stroke()
        capture(f, "89-editor-undo-after-stroke")
        rule.onNodeWithContentDescription("Undo").performClick()
        capture(f, "90-editor-redo-after-undo")
        val d = animation()
        editor(d, EditorStart(phone = PhoneState.ON, prepare = { it.select(2) }))
        rule.onNodeWithContentDescription("Clear", substring = true).performClick()
        rule.onNodeWithText("Clear this frame").performClick()
        capture(f, "91-editor-cleared-frame-3")
        stroke()
        capture(f, "92-editor-drawing-after-clear")
        editor(d, EditorStart(phone = PhoneState.ON, prepare = { it.select(2) }))
        rule.onNodeWithContentDescription("Clear", substring = true).performClick()
        rule.onNodeWithText("Clear all", substring = true).performClick()
        capture(f, "93-editor-cleared-all-frames")
    }

    @Test fun editorZoomAndBrush() {
        val d = still()
        editor(d, EditorStart(phone = PhoneState.ON, prepare = { it.zoom = 2.5f; it.pan = androidx.compose.ui.geometry.Offset(-60f, -40f) }))
        capture(f, "94-editor-zoomed")
        editor(d, EditorStart(EditorPanel.BRUSH, phone = PhoneState.ON))
        capture(f, "95-editor-brush-menu")
        editor(d, EditorStart(phone = PhoneState.ON, brush = Brush(mode = com.dotforge.device.BrushMode.DIM)))
        stroke()
        capture(f, "96-editor-dim-in-use")
        editor(d, EditorStart(phone = PhoneState.ON, brush = Brush(mode = com.dotforge.device.BrushMode.BRIGHTEN, value = 255)))
        capture(f, "97-editor-brighten-chosen")
        val a = animation()
        editor(a, EditorStart(EditorPanel.EFFECTS, phone = PhoneState.ON, prepare = { it.select(2) }))
        capture(f, "98-editor-effects-apply-to")
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
        textColumn().performScrollToNode(hasText("Behind the text"))
        settle()
        capture(f, "54-text-background")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO), TextPanel.MENU) }
        capture(f, "55-text-menu")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO), TextPanel.TIMING) }
        capture(f, "56-text-timing")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO)) }
        rule.onNode(hasSetTextAction()).performTextInput("!")
        rule.mainClock.advanceTimeBy(1_000)
        settle()
        capture(f, "58-text-undo-after-typing")
        show { TextScreen(d.id, null, Nav(Tab.STUDIO), TextPanel.BACKGROUND) }
        capture(f, "57-text-background-picker")
    }

    // ---------------------------------------------------------------- Photo

    /**
     * Real photos: a face, a pet, a logo with text and a landscape, opened the way the app opens a picked photo
     * (faces found, defaults by content). The JVM has no face detector, so the face in face.jpg is given as
     * Android's FaceDetector reports it (between the eyes, the eye distance), measured on the photo.
     */
    private fun photo(name: String): PhotoSession {
        val pic = testPhoto(name)
        com.dotforge.ui.studio.PhotoLoader.faces = { if (name == "face") listOf(com.dotforge.device.FaceSpot(167f, 127f, 40f)) else emptyList() }
        return try {
            com.dotforge.ui.studio.PhotoLoader.session(name.replaceFirstChar { it.uppercase() }, listOf(pic), listOf(1000))
        } finally {
            com.dotforge.ui.studio.PhotoLoader.faces = com.dotforge.ui.studio.FaceFinder::find
        }
    }

    private fun photos() = PhotoSession(p, "Trip", listOf("face", "pet", "landscape").map(::testPhoto), List(3) { 500 })

    @Test fun photoTabs() {
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photo("face"))) }
        settle()
        capture(f, "60-photo-style-face")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.CROP, PhotoLoad.Ready(photo("face"))) }
        settle()
        capture(f, "60c-photo-crop-face")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.CROP, PhotoLoad.Ready(photo("face").also { it.face(false) })) }
        settle()
        capture(f, "60d-photo-crop-face-off")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photo("logo"))) }
        settle()
        capture(f, "60b-photo-style-logo")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.CROP, PhotoLoad.Ready(photo("pet"))) }
        settle()
        capture(f, "61-photo-crop-pet")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.ADJUST, PhotoLoad.Ready(photo("landscape"))) }
        settle()
        capture(f, "62-photo-adjust-landscape")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photo("face")), startAnimate = true) }
        settle()
        capture(f, "63-photo-animate")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.STYLE, PhotoLoad.Ready(photos())) }
        settle()
        capture(f, "64-photo-frames")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), PhotoTab.FRAMES, PhotoLoad.Ready(photos())) }
        settle()
        capture(f, "64b-photo-frames-list")
    }

    @Test fun photoLoading() {
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), startLoad = PhotoLoad.Loading("Converting… 12 of 48 frames", 0.25f)) }
        capture(f, "65-photo-converting-gif")
        show { PhotoScreen(null, null, Nav(Tab.STUDIO), startLoad = PhotoLoad.Failed("This GIF can't be read.")) }
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
        RingtoneReady(ui, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, startMenu = menu, startFlip = flip, onEditLights = {})
    }

    @Test fun ringtoneStates() {
        ringtonePage { RingtoneLoading(0.4f) {} }
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
        ready(song())
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Edit lights"))
        capture(f, "76-ringtone-sync-and-edit-lights")
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
