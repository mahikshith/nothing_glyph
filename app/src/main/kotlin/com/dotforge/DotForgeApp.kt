package com.dotforge

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.driver.Channel
import com.dotforge.driver.GlyphDriver
import com.dotforge.engine.DisplayArbiter
import com.dotforge.engine.MatrixDevice
import com.dotforge.engine.MatrixDevices
import com.dotforge.engine.Priority
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RulesRepository
import com.dotforge.rules.SpamRepository
import com.dotforge.studio.DesignsRepository
import com.dotforge.toys.ToySettings
import com.dotforge.system.NothingSettingsGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Process-wide objects, created once in [DotForgeApp.onCreate]. */
object AppGraph {
    val profile: DeviceProfile = GlyphDriver.profile

    lateinit var device: MatrixDevice
        private set
    lateinit var arbiter: DisplayArbiter
        private set
    lateinit var rules: RulesRepository
        private set
    lateinit var spam: SpamRepository
        private set
    lateinit var designs: DesignsRepository
        private set
    lateinit var toys: ToySettings
        private set
    private lateinit var prefs: SharedPreferences

    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_PROGRESS = "progress_rings"
    private const val RECENT_MAX = 30

    private val _recent = MutableStateFlow<List<NotificationInfo>>(emptyList())
    /** Last notifications seen, newest first, for the rule editor's "would match" list. */
    val recent: StateFlow<List<NotificationInfo>> = _recent.asStateFlow()

    fun remember(info: NotificationInfo) =
        _recent.update { list -> (listOf(info) + list.filterNot { it.key == info.key }).take(RECENT_MAX) }

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

    /** What DF Player and the "My design" effect play: the chosen design, else the newest. */
    fun playerAnimation(): Animation? {
        val list = designs.designs.value
        val design = designs.get(toys.playerDesignId) ?: list.firstOrNull() ?: return null
        return designs.animation(design)
    }

    internal fun init(app: Application) {
        prefs = app.getSharedPreferences("dotforge", Context.MODE_PRIVATE)
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        device = MatrixDevices.create(app, Channel.APP)
        arbiter = DisplayArbiter(scope = engineScope, device = device, gate = NothingSettingsGate(app.contentResolver))
        GlyphDriver.setBrightness(prefs.getInt(KEY_BRIGHTNESS, 255))
        _progressRings.value = prefs.getBoolean(KEY_PROGRESS, true)
        rules = RulesRepository(File(app.filesDir, "rules.json"))
        spam = SpamRepository(File(app.filesDir, "spam.json"))
        designs = DesignsRepository(profile, File(app.filesDir, "designs.json"), legacyFile = File(app.filesDir, "studio_current.json"))
        toys = ToySettings(app.getSharedPreferences("toys", Context.MODE_PRIVATE))
    }
}

class DotForgeApp : Application() {
    /** Nothing tells us when system lights covered our frame, so re-send it whenever the user is likely to look. */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = AppGraph.arbiter.refresh()
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
