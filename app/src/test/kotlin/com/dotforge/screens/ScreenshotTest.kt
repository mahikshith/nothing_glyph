package com.dotforge.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.LocalReduceMotion
import org.junit.Rule
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders screens with Robolectric's native graphics and saves PNGs to `<screens.out>/<feature>/`. Each
 * subclass fixes the window size (with @Config qualifiers) and the font scale. Run with
 * `-Pscreens=/mnt/project-files/nothing-glyph/screens`; normal test runs skip these.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    /** The activity, launched with this test's font scale so sheets and dialogs (own windows) get it too. */
    lateinit var activity: ComponentActivity
        private set

    @Before fun launch() {
        RuntimeEnvironment.setFontScale(fontScale)
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity = it }
    }

    @After fun close() {
        scenario?.close()
    }

    /** Part of each file name, e.g. "412x917" or "360x640-font1.3". */
    abstract val sizeName: String
    abstract val fontScale: Float

    private val out: File? = System.getProperty("screens.out")?.let(::File)
    private var shown = false

    /** The phone the Features pages think they run on: a Phone (3) unless a test says otherwise. */
    var featureDevice: com.dotforge.ui.alerts.DeviceState = com.dotforge.ui.alerts.DeviceState.PHONE3

    /** Shows [content] under the app theme at this test's font scale. */
    fun show(reduceMotion: Boolean = false, content: @Composable () -> Unit) {
        // a second show in one test: clear the first, so its sheets and menus (their own windows) close
        if (shown) {
            scenario!!.onActivity { it.setContent { } }
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
        }
        shown = true
        scenario!!.onActivity {
            it.setContent {
                DotForgeTheme {
                    CompositionLocalProvider(LocalReduceMotion provides reduceMotion, com.dotforge.ui.features.LocalFeatureDevice provides featureDevice) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * Saves every window (the page, then sheets and dialogs over it) composited into one PNG. Windows are drawn
     * with View.draw: Compose's captureToImage waits for a redraw that Robolectric never reports.
     */
    fun capture(feature: String, name: String) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val result = rule.runOnUiThread {
            val views = windows().filter { it.isShown && it.width > 0 && it.height > 0 }
            val base = views.first()
            val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.BLACK)
            val origin = IntArray(2).also(base::getLocationOnScreen)
            views.forEach { v ->
                val at = IntArray(2).also(v::getLocationOnScreen)
                // Robolectric leaves a centred dialog window at 0,0: centre it the way the window manager would
                val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
                val vertical = (lp?.gravity ?: 0) and android.view.Gravity.VERTICAL_GRAVITY_MASK
                if (lp != null && v !== base && vertical == android.view.Gravity.TOP) {
                    // a popup (a menu) placed by its offsets from the top left
                    at[0] = origin[0] + lp.x
                    at[1] = origin[1] + lp.y
                } else if (lp != null && v !== base && vertical != android.view.Gravity.BOTTOM && v.height < base.height) {
                    at[0] = origin[0] + (base.width - v.width) / 2
                    at[1] = origin[1] + (base.height - v.height) / 2
                }
                // the window manager dims everything under a window that asks for it (dialogs): draw that scrim
                if (lp != null && v !== base && lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((lp.dimAmount.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0))
                }
                canvas.save()
                canvas.translate((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
                v.draw(canvas)
                canvas.restore()
            }
            bmp
        }
        val dir = File(out ?: return, feature).apply { mkdirs() }
        File(dir, "${name}_$sizeName.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every window's root view, bottom to top (WindowManagerGlobal is hidden API, so reflection). */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        return (wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>).toList()
    }
}
