package com.dotforge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.RemoteViews
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.ui.library.LibraryPrefs

/** What a tap on the widget does (parity row 92). */
enum class WidgetTap(val label: String, val hint: String) {
    SHOW("Show on Glyph", "Plays it on the Glyph for 10 seconds"),
    NEXT("Next favourite", "Moves on to your next Library favourite and shows it"),
    OPEN("Open DotForge", "Opens the app on Home"),
}

/**
 * One widget's choices: [source] is [LIVE] (the Glyph as it is now), "lib:<id>" (a Library design) or
 * "design:<id>" (one of yours); [tap] what a tap does.
 */
data class WidgetChoice(val source: String = LIVE, val tap: WidgetTap = WidgetTap.SHOW) {
    val live: Boolean get() = source == LIVE

    companion object {
        const val LIVE = "live"
        fun library(id: String) = "lib:$id"
        fun design(id: String) = "design:$id"
    }
}

/** The widgets' choices, by widget id. */
class WidgetStore(private val prefs: SharedPreferences) {
    fun get(id: Int): WidgetChoice = WidgetChoice(
        prefs.getString("src_$id", null) ?: WidgetChoice.LIVE,
        WidgetTap.entries.firstOrNull { it.name == prefs.getString("tap_$id", null) } ?: WidgetTap.SHOW,
    )

    fun set(id: Int, c: WidgetChoice) = prefs.edit().putString("src_$id", c.source).putString("tap_$id", c.tap.name).apply()

    fun remove(id: Int) = prefs.edit().remove("src_$id").remove("tap_$id").apply()

    companion object {
        fun of(ctx: Context) = WidgetStore(ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE))
    }
}

/** What a widget draws and plays: its name, its still and its animation (null for the live Glyph). */
object WidgetContent {
    data class Shown(val name: String, val still: Frame?, val animation: Animation?)

    fun of(c: WidgetChoice, p: DeviceProfile = AppGraph.profile): Shown = when {
        // nothing lit: the logo rather than a blank disc
        c.live -> Shown("The Glyph now", AppGraph.arbiter.frame.value?.takeIf { f -> f.pixels.any { it > 0 } } ?: WidgetArt.logo(p), null)
        c.source.startsWith("lib:") -> AnimationLibrary.item(c.source.removePrefix("lib:"))
            ?.let { Shown(it.name, AnimationLibrary.thumbnail(it, p), it.load(p)) } ?: Shown("Gone", null, null)
        else -> AppGraph.designs.get(c.source.removePrefix("design:"))
            ?.let { d -> val a = AppGraph.designs.animation(d); Shown(d.name, a?.let(com.dotforge.device.Posters::of), a) } ?: Shown("Gone", null, null)
    }

    /** The next Library favourite after [c]'s, or the first. */
    fun nextFavourite(c: WidgetChoice): WidgetChoice? {
        val favs = LibraryPrefs.favourites.toList().filter { AnimationLibrary.item(it) != null }
        if (favs.isEmpty()) return null
        val now = c.source.removePrefix("lib:")
        val i = favs.indexOf(now)
        return c.copy(source = WidgetChoice.library(favs[(i + 1) % favs.size]))
    }
}

/**
 * The round dot picture the widget shows: lit dots white, the rest dark grey, on a black disc (transparent
 * corners). It is drawn at the widget's own size in pixels, so it stays sharp on a 4x4 widget.
 */
object WidgetArt {
    /** The widget's side in pixels: its largest size on the home screen, from [AppWidgetManager]'s options. */
    fun sidePx(ctx: Context, mgr: AppWidgetManager, id: Int): Int {
        val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
        val dp = listOfNotNull(
            o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)?.takeIf { it > 0 },
            o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 },
        ).minOrNull() ?: DEFAULT_DP
        return (dp * ctx.resources.displayMetrics.density).toInt().coerceIn(MIN_PX, MAX_PX)
    }

    const val DEFAULT_DP = 160
    const val MIN_PX = 120
    /** A 4x4 widget at xxxhdpi, within RemoteViews' bitmap limit. */
    const val MAX_PX = 1_200

    /** DotForge's logo on the Glyph: the app icon's 3x3 dots, the centre one dimmer (the icon's red dot). */
    fun logo(p: DeviceProfile): Frame {
        val px = IntArray(p.cellCount)
        val c = p.size / 2
        for (gy in -1..1) for (gx in -1..1) {
            val cx = c + gx * 6
            val cy = c + gy * 6
            for (dy in -1..1) for (dx in -1..1) {
                val i = (cy + dy) * p.size + cx + dx
                if (p.mask[i]) px[i] = if (gx == 0 && gy == 0) 120 else 255
            }
        }
        return Frame(p, px)
    }

    fun bitmap(frame: Frame?, p: DeviceProfile, side: Int = 300): Bitmap {
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = side / p.size.toFloat()
        val r = cell * 0.4f
        // a black disc under the dots, so white dots show on any wallpaper
        paint.color = android.graphics.Color.BLACK
        c.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        for (i in p.ledCells) {
            val v = frame?.pixels?.get(i) ?: 0
            paint.color = if (v == 0) 0xFF333333.toInt() else android.graphics.Color.argb((64 + 191 * v / 255), 255, 255, 255)
            c.drawCircle((i % p.size + 0.5f) * cell, (i / p.size + 0.5f) * cell, r, paint)
        }
        return bmp
    }
}

/**
 * The Glyph widget (parity row 92): a round picture of a design or of the live Glyph. A tap shows it on the
 * Glyph, moves to the next favourite or opens DotForge. It redraws on a tap, when its choice changes and, for
 * the live Glyph, at most once a minute (battery).
 */
class GlyphWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
    }

    /** Resized on the home screen: redrawn at its new size. */
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) = update(ctx, mgr, id)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val store = WidgetStore.of(ctx)
        ids.forEach(store::remove)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_TAP) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        tap(ctx, id)
    }

    companion object {
        const val ACTION_TAP = "com.dotforge.widget.TAP"
        const val TRY_ID = "widget"
        const val SHOW_MS = 10_000L
        /** The live Glyph widget redraws at most this often. */
        const val LIVE_MS = 60_000L

        /** A tap: show it on the Glyph, the next favourite, or open the app. */
        fun tap(ctx: Context, id: Int) {
            val store = WidgetStore.of(ctx)
            var c = store.get(id)
            when (c.tap) {
                WidgetTap.OPEN -> open(ctx)
                WidgetTap.NEXT -> {
                    c = WidgetContent.nextFavourite(c) ?: c
                    store.set(id, c)
                    show(ctx, c)
                }
                WidgetTap.SHOW -> if (c.live) open(ctx) else show(ctx, c)
            }
            update(ctx, AppWidgetManager.getInstance(ctx), id)
        }

        private fun show(ctx: Context, c: WidgetChoice) {
            val shown = WidgetContent.of(c)
            val a = shown.animation ?: return open(ctx)
            AppGraph.arbiter.post(DisplayRequest(TRY_ID, Priority.STUDIO_PREVIEW, a, loop = true, timeoutMs = SHOW_MS, label = "Widget: ${shown.name}"))
        }

        private fun open(ctx: Context) {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val c = WidgetStore.of(ctx).get(id)
            val shown = WidgetContent.of(c)
            val views = RemoteViews(ctx.packageName, R.layout.glyph_widget)
            views.setImageViewBitmap(R.id.glyph_widget_image, WidgetArt.bitmap(shown.still, AppGraph.profile, WidgetArt.sidePx(ctx, mgr, id)))
            views.setContentDescription(R.id.glyph_widget_image, "${shown.name}. ${c.tap.label}")
            val tap = Intent(ctx, GlyphWidgetProvider::class.java).setAction(ACTION_TAP).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            views.setOnClickPendingIntent(R.id.glyph_widget_image, PendingIntent.getBroadcast(ctx, id, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(id, views)
        }

        fun ids(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, GlyphWidgetProvider::class.java))

        /** Redraws the widgets that show the live Glyph. */
        fun updateLive(ctx: Context) {
            val store = WidgetStore.of(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in ids(ctx)) if (store.get(id).live) update(ctx, mgr, id)
        }

        /** Puts the widget on the home screen through the launcher's own dialog; false when the launcher can't. */
        fun requestPin(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            return mgr.requestPinAppWidget(ComponentName(ctx, GlyphWidgetProvider::class.java), null, null)
        }
    }
}
