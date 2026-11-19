package com.dotforge.automation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dotforge.AppGraph
import com.dotforge.composition.GlyphAuthor
import com.dotforge.composition.ImportKind
import com.dotforge.composition.RingtoneParser
import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.device.Timings
import com.dotforge.system.FeatureTiming
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.ringtones.RingtoneImporter
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import com.dotforge.visualiser.LiveVisualiser
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which apps may automate DotForge, the apps that asked, the plugin switch and its key. */
class AutomationSettings(private val prefs: SharedPreferences) {
    private val _allowed = MutableStateFlow(prefs.getStringSet(KEY_ALLOWED, emptySet())!!.toSet())
    val allowed: StateFlow<Set<String>> = _allowed.asStateFlow()
    private val _asked = MutableStateFlow(prefs.getStringSet(KEY_ASKED, emptySet())!!.toSet())
    /** Apps that sent a command before they were allowed. */
    val asked: StateFlow<Set<String>> = _asked.asStateFlow()
    private val _plugin = MutableStateFlow(prefs.getBoolean(KEY_PLUGIN, true))
    val plugin: StateFlow<Boolean> = _plugin.asStateFlow()

    fun allow(pkg: String, on: Boolean) {
        val next = if (on) _allowed.value + pkg else _allowed.value - pkg
        prefs.edit().putStringSet(KEY_ALLOWED, next).apply()
        _allowed.value = next
    }

    fun noteAsked(pkg: String) {
        if (pkg in _asked.value) return
        val next = _asked.value + pkg
        prefs.edit().putStringSet(KEY_ASKED, next).apply()
        _asked.value = next
    }

    fun setPlugin(on: Boolean) {
        prefs.edit().putBoolean(KEY_PLUGIN, on).apply()
        _plugin.value = on
    }

    /** The plugin's signing key: made once, kept on the phone. */
    val pluginKey: ByteArray
        get() = synchronized(this) {
            prefs.getString(KEY_SECRET, null)?.let { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
                ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.also { k ->
                    prefs.edit().putString(KEY_SECRET, k.joinToString("") { "%02x".format(it) }).commit()
                }
        }

    private companion object {
        const val KEY_ALLOWED = "allowed"
        const val KEY_ASKED = "asked"
        const val KEY_PLUGIN = "plugin"
        const val KEY_SECRET = "plugin_key"
    }
}

/** Carries out commands from the automation API and the Tasker plugin. */
object AutomationRunner {
    private const val TAG = "DotForgeAutomation"
    const val GLYPH_ID = "automation"
    const val PLAY_ID = "automation-play"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private val _last = MutableStateFlow<String?>(null)
    /** The last command and how it went, for the Integrations page. */
    val last: StateFlow<String?> = _last.asStateFlow()

    fun note(line: String) {
        Log.i(TAG, line)
        _last.value = line
    }

    fun run(ctx: Context, c: Command, from: String, done: () -> Unit = {}) {
        scope.launch {
            val result = runCatching { execute(ctx.applicationContext, c) }.getOrElse { it.message ?: "failed" }
            note("${Automation.describe(c)} (from $from): $result")
            done()
        }
    }

    private fun execute(ctx: Context, c: Command): String = when (c) {
        is Command.ShowGlyph -> {
            val plan = Timings.plan(glyph(c), Automation.timingFor(c, AppGraph.features.timing(FeatureTiming.APPS).value))
            AppGraph.arbiter.post(
                DisplayRequest(GLYPH_ID, Priority.NOTIFICATION, plan.animation, loop = plan.loop, timeoutMs = plan.showMs, label = "Automation: ${Automation.describe(c)}"),
            )
            "shown"
        }
        is Command.Visualiser -> if (c.on) {
            if (LiveVisualiser.start(ctx)) "started" else "Android didn't let the visualiser start from the background"
        } else {
            LiveVisualiser.stop(ctx)
            "stopped"
        }
        is Command.SetRule -> {
            val r = AppGraph.rules.rules.value.firstOrNull { it.id == c.rule } ?: AppGraph.rules.rules.value.firstOrNull { it.name.equals(c.rule, ignoreCase = true) }
            if (r == null) "no rule called ${c.rule}" else {
                AppGraph.rules.upsert(r.copy(enabled = c.on))
                if (c.on) "on" else "off"
            }
        }
        is Command.PlayComposition -> play(ctx, c.composition)
    }

    private fun glyph(c: Command.ShowGlyph): Animation {
        val profile = AppGraph.profile
        c.text?.let { return Effects.marquee(profile, it) }
        val name = c.glyph
        com.dotforge.device.GlyphLibrary.all.firstOrNull { it.name.equals(name, true) || it.id.equals(name, true) }?.let { return it.animation(profile) }
        val style = GlyphStyle.entries.firstOrNull { it != GlyphStyle.DESIGN && it != GlyphStyle.NONE && it != GlyphStyle.ICON && (it.name.equals(name, true) || it.label.equals(name, true)) }
        if (style != null) {
            val info = NotificationInfo("automation", "", "DotForge", "DotForge", "", 0, sender = "DotForge")
            return RuleEngine.animation(style, null, info, profile, AppGraph::designAnimation)
        }
        val design = AppGraph.designs.designs.value.firstOrNull { it.name.equals(name, ignoreCase = true) || it.id == name }
        return design?.let { AppGraph.designs.animation(it) } ?: Effects.pulse(profile)
    }

    /** Plays a saved composition: its sound (media volume) and its Phone (3) lights at 30 fps. */
    private fun play(ctx: Context, name: String): String {
        if (AppGraph.ringtones.items.value.isEmpty()) AppGraph.ringtones.refresh()
        val r = AppGraph.ringtones.items.value.firstOrNull { it.uri.toString() == name }
            ?: AppGraph.ringtones.items.value.firstOrNull { it.title.equals(name, ignoreCase = true) }
            ?: return "no composition called $name"
        val bytes = ctx.contentResolver.openInputStream(r.uri)?.use { it.readBytes() } ?: return "couldn't open ${r.title}"
        val rows = (ImportKind.of(runCatching { RingtoneParser.parse(bytes) }.getOrNull()) as? ImportKind.Phone3)?.rows.orEmpty()
        val profile = AppGraph.profile
        val step = 2 // 60 fps rows, every second one
        val frames = rows.indices.step(step).map { i -> AnimFrame(Frame.of(profile, RingtoneImporter.toLevels(rows[i])), step * 1000 / GlyphAuthor.FPS) }
        main.post {
            player?.release()
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setDataSource(ctx, r.uri)
                    setOnCompletionListener { mp ->
                        mp.release()
                        if (player === mp) player = null
                        AppGraph.arbiter.remove(PLAY_ID)
                    }
                    prepare()
                    start()
                }
            }.getOrNull()
            if (frames.isNotEmpty()) {
                AppGraph.arbiter.post(DisplayRequest(PLAY_ID, Priority.NOTIFICATION, Animation(profile, frames), label = "Automation: ${r.title}"))
            }
        }
        return if (frames.isEmpty()) "playing (no lights for this phone)" else "playing"
    }
}

/** Plain values of a Bundle, as the parsers read them. */
internal fun Bundle.toMap(): Map<String, Any?> = keySet().associateWith {
    @Suppress("DEPRECATION")
    get(it)
}

/** The automation API (see [Automation]). Holds [Automation.PERMISSION]; only apps the user allowed are obeyed. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // a keyed broadcast is KeyReceiver's (sent to the package, both receivers get it)
        if (intent.hasExtra(Automation.EXTRA_KEY)) return
        val shared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromPackage else null
        val caller = runCatching { intent.getParcelableExtra(Automation.EXTRA_CALLER, PendingIntent::class.java)?.creatorPackage }.getOrNull()
        receive(this, context, intent, shared ?: caller, key = null)
    }
}

/**
 * The same API for apps that can't hold the permission, such as Tasker's Send intent: exported without one, and
 * obeys only a broadcast carrying the Integrations page's key ([Automation.EXTRA_KEY]).
 */
class KeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Automation.EXTRA_KEY) ?: return
        val shared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromPackage else null
        receive(this, context, intent, shared, key)
    }
}

private fun receive(r: BroadcastReceiver, context: Context, intent: Intent, sender: String?, key: String?) {
    val settings = AppGraph.automation
    if (sender != context.packageName && !AppGraph.features.showDesigns.value) {
        AutomationRunner.note("Ignored ${intent.action}: \"Let other apps send designs\" is off.")
        return
    }
    when (val gate = Automation.check(sender, settings.allowed.value, context.packageName, key, AppGraph.features.key.value)) {
        Gate.UnknownSender -> {
            AutomationRunner.note("Ignored ${intent.action}: the sender didn't identify itself (add a PendingIntent as \"${Automation.EXTRA_CALLER}\", or the key as \"${Automation.EXTRA_KEY}\").")
            return
        }
        Gate.WrongKey -> {
            AutomationRunner.note("Ignored ${intent.action}: the key doesn't match. Copy it again from the Integrations page.")
            return
        }
        is Gate.NotAllowed -> {
            settings.noteAsked(gate.packageName)
            AutomationRunner.note("Ignored ${intent.action} from ${gate.packageName}: allow it on the Integrations page.")
            return
        }
        Gate.Allowed -> Unit
    }
    val from = sender ?: "an app with the key"
    when (val p = Automation.parse(intent.action, intent.extras?.toMap().orEmpty())) {
        is Parsed.Rejected -> AutomationRunner.note("Ignored ${intent.action} from $from: ${p.reason}")
        is Parsed.Ok -> {
            val pending = r.goAsync()
            AutomationRunner.run(context, p.command, from) { pending.finish() }
        }
    }
}

/** Tasker / Locale plugin: runs the signed settings made by [PluginEditActivity]. */
class PluginFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginBundle.ACTION_FIRE) return
        val settings = AppGraph.automation
        if (!settings.plugin.value) {
            AutomationRunner.note("Ignored a Tasker action: the plugin is switched off.")
            return
        }
        val values = intent.getBundleExtra(PluginBundle.EXTRA_BUNDLE)?.toMap() ?: return
        when (val p = PluginBundle.read(values, settings.pluginKey)) {
            is Parsed.Rejected -> AutomationRunner.note("Ignored a Tasker action: ${p.reason}")
            is Parsed.Ok -> {
                val pending = goAsync()
                AutomationRunner.run(context, p.command, "Tasker plugin") { pending.finish() }
            }
        }
    }
}
