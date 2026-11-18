package com.dotforge

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.dotforge.device.DeviceProfile
import com.dotforge.driver.Channel
import com.dotforge.driver.GlyphDriver
import com.dotforge.engine.DisplayArbiter
import com.dotforge.engine.MatrixDevice
import com.dotforge.engine.MatrixDevices
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RulesRepository
import com.dotforge.studio.StudioRepository
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
    lateinit var studio: StudioRepository
        private set

    private const val PREFS = "dotforge"
    private const val KEY_LIGHTS_ON = "lights_on"
    private const val RECENT_MAX = 30

    private val _recent = MutableStateFlow<List<NotificationInfo>>(emptyList())
    /** Last notifications seen, newest first, for the rule tester. */
    val recent: StateFlow<List<NotificationInfo>> = _recent.asStateFlow()

    fun remember(info: NotificationInfo) =
        _recent.update { list -> (listOf(info) + list.filterNot { it.key == info.key }).take(RECENT_MAX) }

    /** Master switch, persisted. */
    fun setLightsOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIGHTS_ON, on).apply()
        arbiter.setEnabled(on)
    }

    internal fun init(app: Application) {
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        device = MatrixDevices.create(app, Channel.APP)
        arbiter = DisplayArbiter(
            scope = engineScope,
            device = device,
            gate = NothingSettingsGate(app.contentResolver),
            enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHTS_ON, true),
        )
        rules = RulesRepository(File(app.filesDir, "rules.json"))
        studio = StudioRepository(profile, File(app.filesDir, "studio_current.json"))
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
