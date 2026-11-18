package com.dotforge

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.dotforge.automation.AutomationSettings
import com.dotforge.calls.CallsRepository
import com.dotforge.calls.SpamListStore
import com.dotforge.calls.SpamListWorker
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.driver.Channel
import com.dotforge.driver.GlyphDriver
import com.dotforge.engine.AlertPlayer
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.DisplayArbiter
import com.dotforge.engine.MatrixDevice
import com.dotforge.engine.MatrixDevices
import com.dotforge.engine.Priority
import com.dotforge.filter.Field
import com.dotforge.notify.FaceDownSensor
import com.dotforge.notify.MediaRingSettings
import com.dotforge.system.SystemGlyphSettings
import com.dotforge.system.SystemGlyphs
import com.dotforge.ringtones.RingtoneLibrary
import com.dotforge.rules.FiltersRepository
import com.dotforge.rules.LogEntry
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.RulesRepository
import com.dotforge.rules.SpamCatch
import com.dotforge.studio.DesignsRepository
import com.dotforge.system.NothingSettingsGate
import com.dotforge.toys.ToySettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Process-wide objects, created once in [DotForgeApp.onCreate]. */
object AppGraph {
    val profile: DeviceProfile = GlyphDriver.profile

    lateinit var device: MatrixDevice
        private set
    lateinit var arbiter: DisplayArbiter
        private set
    lateinit var rules: RulesRepository
        private set
    lateinit var filters: FiltersRepository
        private set
    lateinit var alerts: AlertPlayer
        private set
    lateinit var faceDown: FaceDownSensor
        private set
    lateinit var designs: DesignsRepository
        private set
    lateinit var toys: ToySettings
        private set
    lateinit var ringtones: RingtoneLibrary
        private set
    lateinit var calls: CallsRepository
        private set
    lateinit var spamList: SpamListStore
        private set
    lateinit var automation: AutomationSettings
        private set
    lateinit var media: MediaRingSettings
        private set
    lateinit var system: SystemGlyphSettings
        private set
    private lateinit var prefs: SharedPreferences

    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_PROGRESS = "progress_rings"
    private const val LOG_MAX = 20

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    /** The last notifications and what happened to each, newest first: the rule tester. */
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    fun log(entry: LogEntry) =
        _log.update { list -> (listOf(entry) + list.filterNot { it.info.key == entry.info.key }).take(LOG_MAX) }

    private val _progressRings = MutableStateFlow(true)
    /** Progress rings for downloads, deliveries and timers. */
    val progressRings: StateFlow<Boolean> = _progressRings.asStateFlow()

    fun setProgressRings(on: Boolean) {
        prefs.edit().putBoolean(KEY_PROGRESS, on).apply()
        _progressRings.value = on
        if (!on) arbiter.removeIf { it.priority == Priority.PROGRESS }
    }

    /** Brightness of DotForge's own lights (not toys), 0-255. */
    val brightness: StateFlow<Int> get() = GlyphDriver.brightness

    fun setBrightness(level: Int) {
        prefs.edit().putInt(KEY_BRIGHTNESS, level).apply()
        GlyphDriver.setBrightness(level)
        arbiter.refresh()
    }

    /** "Allow this sender" from the Spam log: on the allowed list, off the blocked list, out of the log. */
    fun allowSender(c: SpamCatch) {
        val key = c.senderKey.ifBlank { return }
        rules.addToList(RuleEngine.ALLOWED, key, removeFrom = RuleEngine.BLOCKED)
        rules.ensurePreset("spam-allowed")
        filters.forget(key)
    }

    /** "Always block" from the Spam log or a notification: on the blocked list, off the allowed list. */
    fun blockSender(key: String) {
        if (key.isBlank()) return
        rules.addToList(RuleEngine.BLOCKED, key, removeFrom = RuleEngine.ALLOWED)
        rules.ensurePreset("spam-blocked")
    }

    /** "Block numbers starting with …": the prefix goes on the "Spam prefixes" list. */
    fun blockPrefix(prefix: String) {
        if (prefix.isBlank()) return
        rules.addToList(RuleEngine.SPAM_PREFIXES, prefix)
        rules.ensurePreset("spam-prefixes")
    }

    /** What DF Player plays: the chosen design, else the newest. */
    fun playerAnimation(): Animation? = designAnimation(toys.playerDesignId)

    /** A saved design's animation; the newest design when [id] is null or gone. */
    /** A saved design's own timing (null id = the newest design). */
    fun designTiming(id: String?): com.dotforge.device.Timing? = (designs.get(id) ?: designs.designs.value.firstOrNull())?.timing

    fun designAnimation(id: String?): Animation? {
        val design = designs.get(id) ?: designs.designs.value.firstOrNull() ?: return null
        return designs.animation(design)
    }

    internal fun init(app: Application) {
        prefs = app.getSharedPreferences("dotforge", Context.MODE_PRIVATE)
        com.dotforge.ui.design.HapticsSetting.init(app.getSharedPreferences("ui", Context.MODE_PRIVATE))
        com.dotforge.ui.settings.DevMode.init(app.getSharedPreferences("ui", Context.MODE_PRIVATE))
        com.dotforge.backup.BackupStore.init(app)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        device = MatrixDevices.create(app, Channel.APP)
        device.clear() // nothing is posted yet: take off any frame an earlier process left (a stuck torch)
        filters = FiltersRepository(File(app.filesDir, "filters.json"), legacySpam = File(app.filesDir, "spam.json"))
        val gate = NothingSettingsGate(app.contentResolver) { filters.filters.value.quiet.followBedtime }
        arbiter = DisplayArbiter(scope = engineScope, device = device, gate = gate)
        alerts = AlertPlayer(engineScope, arbiter, AlertQueue(System::currentTimeMillis))
        GlyphDriver.setBrightness(prefs.getInt(KEY_BRIGHTNESS, 255))
        _progressRings.value = prefs.getBoolean(KEY_PROGRESS, true)
        rules = RulesRepository(File(app.filesDir, "rules.json"), legacySpam = filters.spam.value)
        faceDown = FaceDownSensor(app)
        media = MediaRingSettings(app.getSharedPreferences("media", Context.MODE_PRIVATE))
        // the sensor runs only while a filter or the media ring asks whether the phone is face down
        MainScope().launch {
            combine(filters.filters.map { it.onlyFaceDown }, media.on, media.onlyLockedOrFaceDown) { f, on, away -> f || (on && away) }
                .distinctUntilChanged().collect(faceDown::setEnabled)
        }
        designs = DesignsRepository(profile, File(app.filesDir, "designs.json"), legacyFile = File(app.filesDir, "studio_current.json"))
        toys = ToySettings(app.getSharedPreferences("toys", Context.MODE_PRIVATE))
        ringtones = RingtoneLibrary(app)
        calls = CallsRepository(File(app.filesDir, "calls.json"))
        spamList = SpamListStore(File(app.filesDir, "spam-list"))
        automation = AutomationSettings(app.getSharedPreferences("automation", Context.MODE_PRIVATE))
        system = SystemGlyphSettings(app.getSharedPreferences("system_glyphs", Context.MODE_PRIVATE))
        val systemGlyphs = SystemGlyphs(app, system).also { it.start() }
        MainScope().launch { combine(system.wiggle, system.shakeTorch) { a, b -> a to b }.collect { systemGlyphs.refresh() } }
        // the spam list is fetched (and refreshed weekly) only while a rule uses it
        MainScope().launch {
            rules.rules.map { list -> list.any { r -> r.enabled && r.match.allConditions.any { it.field == Field.SPAM_LIST } } }.distinctUntilChanged().collect { on ->
                SpamListWorker.schedule(app, on)
                if (on && !File(app.filesDir, "spam-list/spam_numbers.txt").exists()) SpamListWorker.now(app)
            }
        }
    }
}

class DotForgeApp : Application() {
    /**
     * Nothing tells us when system lights covered our frame, so re-send it whenever the user is likely to look:
     * at once, and again once the unlock / screen-on animation is over. Unlocking also ends held alerts.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) AppGraph.alerts.unlocked()
            AppGraph.arbiter.refresh()
            AppGraph.arbiter.refreshAfter(COVERED_MS)
        }
    }

    companion object {
        /** About how long a system light (notification glyph, unlock animation) covers the app frame. */
        const val COVERED_MS = 1_500L
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }
}
