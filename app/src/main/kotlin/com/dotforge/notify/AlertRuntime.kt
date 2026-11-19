package com.dotforge.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.dotforge.AppGraph
import com.dotforge.calls.CallDisplay
import com.dotforge.calls.CallSession
import com.dotforge.device.Animation
import com.dotforge.device.DotArt
import com.dotforge.device.DotArtMode
import com.dotforge.device.Effects
import com.dotforge.device.GlyphLibrary
import com.dotforge.engine.AlertGlyph
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.engine.Until
import com.dotforge.rules.CallShow
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import java.util.concurrent.ConcurrentHashMap

/**
 * The glue between Android and the alert engine that is not one notification: app icons in dots, the ringing
 * call's display, alerts held back while the phone was in use, and notifications we removed ourselves.
 */
object AlertRuntime {
    private val icons = ConcurrentHashMap<String, Animation>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ---------------------------------------------------------------- app icons in dots (row 55)

    /** [pkg]'s icon in dots: its monochrome layer when it has one, else its picture; null when unknown. */
    fun appIcon(pkg: String): Animation? {
        icons[pkg]?.let { return it }
        if (pkg.isBlank() || pkg in missing || !::app.isInitialized) return null
        val d = runCatching { app.packageManager.getApplicationIcon(pkg) }.getOrNull()
        val anim = d?.let { runCatching { Animation.single(iconFrame(it), 1_000) }.getOrNull() }
        if (anim == null) missing += pkg else icons[pkg] = anim
        return anim
    }

    private fun iconFrame(d: Drawable): com.dotforge.device.Frame {
        val p = AppGraph.profile
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val mono = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (d as? AdaptiveIconDrawable)?.monochrome else null
        val layer = mono ?: (d as? AdaptiveIconDrawable)?.foreground ?: d
        val draw = layer.constantState?.newDrawable()?.mutate() ?: layer
        if (mono != null) draw.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        // adaptive layers have 18 dp of bleed on each side of 108: crop it away
        val bleed = if (layer !== d || mono != null) -(size * 18 / 72) else 0
        draw.setBounds(bleed, bleed, size - bleed, size - bleed)
        draw.draw(c)
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()
        return DotArt.frame(p, size, size, px, if (mono != null) DotArtMode.POSTERISE else DotArtMode.DITHER)
    }

    // ---------------------------------------------------------------- the ringing call

    /** The ringing call, fed by call screening and ringing notifications. */
    val call = CallSession(::showCall)

    private var callAt = 0L

    /** The ringing caller's Contacts groups, looked up once per call. */
    private var callGroups: Set<String>? = null

    private fun showCall(d: CallDisplay?) {
        if (d == null) {
            AppGraph.arbiter.remove(CALL_ID)
            callAt = 0L
            return
        }
        val rules = AppGraph.rules.rules.value
        val phone = rules.firstOrNull { it.id == SimpleRules.PHONE_CALLS }
        if (phone == null || !phone.enabled) return
        val p = AppGraph.profile
        val info = NotificationInfo(CALL_ID, "phone", "Phone", d.name?.name.orEmpty(), "", 0, sender = d.name?.name.orEmpty(), number = d.number)
        // a person's glyph shows instead of the Phone calls glyph, else their Contacts group's
        if (callAt == 0L) callGroups = null
        val groups = if (rules.none { SimpleRules.isGroupRule(it) && it.enabled } || d.number == null || !::app.isInitialized) emptySet()
        else callGroups ?: runCatching { com.dotforge.calls.CallerLookup(app).caller(d.number)?.groups }.getOrNull().orEmpty().also { callGroups = it }
        val person = SimpleRules.callGlyphRule(rules, d.name?.name, d.number, groups)
        val glyphRule = person ?: phone
        val glyph = RuleEngine.animation(glyphRule, info, p, AppGraph::designAnimation, ::appIcon)
        val anim = CallSession.animation(p, d, phone.callShow, glyph, Effects.spam(p))
        val label = "Call: " + (d.name?.name ?: d.number ?: "Unknown")
        AppGraph.arbiter.post(DisplayRequest(CALL_ID, Priority.CALL, anim, loop = true, timeoutMs = CALL_TIMEOUT_MS, label = label))
        // one History line per call, updated as a better name arrives
        if (callAt == 0L) callAt = System.currentTimeMillis()
        AppGraph.history.add(
            com.dotforge.rules.HistoryEntry(
                key = "ring:$callAt", at = callAt, outcome = com.dotforge.rules.Outcome.LIT,
                detail = if (d.spam) "Spam glyph" else phone.callShow.label.let { if (it == "Both") "glyph and name" else it.lowercase() } + " · while it rang",
                appLabel = "Phone", title = d.name?.name ?: d.number.orEmpty(), number = d.number.orEmpty(), call = true,
                ruleId = glyphRule.id, rule = glyphRule.name,
            ),
        )
    }

    /** What Phone calls shows, for its sheet's preview. */
    fun previewCall(name: String, show: CallShow, glyph: Animation): Animation =
        CallSession.animation(AppGraph.profile, CallDisplay(com.dotforge.calls.NameCandidate(name, com.dotforge.calls.NameSource.CONTACT), null, false), show, glyph, Effects.spam(AppGraph.profile))

    // ---------------------------------------------------------------- while I'm using the phone

    private val deferred = ArrayList<AlertQueue.Alert<AlertGlyph>>()

    /** An alert made while the phone was in use: it shows briefly once the screen goes off. */
    fun defer(alert: AlertQueue.Alert<AlertGlyph>) = synchronized(deferred) {
        deferred.removeAll { it.key == alert.key }
        deferred += alert
        while (deferred.size > MAX_DEFERRED) deferred.removeAt(0)
    }

    fun screenOff() {
        val list = synchronized(deferred) { deferred.toList().also { deferred.clear() } }
        list.forEach { a -> AppGraph.alerts.offer(a.copy(until = Until.TIMEOUT, showMs = BRIEF_MS, againMs = null)) }
    }

    fun dropDeferred(key: String) = synchronized(deferred) { deferred.removeAll { it.key == key } }

    // ---------------------------------------------------------------- our own removals

    private val ownCancels = ConcurrentHashMap.newKeySet<String>()

    /** We are about to remove [key] (to silence it): its removal is not "checked". */
    fun cancelling(key: String) {
        ownCancels += key
    }

    /** True (once) when [key]'s removal was ours. */
    fun wasOurs(key: String): Boolean = ownCancels.remove(key)

    /** A quiet copy we posted is tagged with the original's key: its removal checks the original. */
    fun originalOf(ourTag: String?): String? = ourTag

    const val CALL_ID = "call"
    private const val CALL_TIMEOUT_MS = 90_000L
    private const val BRIEF_MS = 4_000L
    private const val MAX_DEFERRED = 6

    /** The default library glyph when an app has no icon. */
    val fallback: String get() = GlyphLibrary.get("chat")?.id ?: "chat"
}
