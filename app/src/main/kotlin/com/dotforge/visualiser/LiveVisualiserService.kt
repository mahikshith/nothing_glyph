package com.dotforge.visualiser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.composition.VisualStyle
import com.dotforge.device.Animation
import com.dotforge.device.Frame
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The Live music visualiser's state, shared by the Glyph tab switch, the Quick Settings tile and the service. */
object LiveVisualiser {
    const val ID = "live"

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _usingMic = MutableStateFlow(false)
    /** True when Android gave no music data and the microphone is used instead. */
    val usingMic: StateFlow<Boolean> = _usingMic.asStateFlow()

    private val _music = MutableStateFlow(false)
    /** True while music plays (the matrix is driven only then). */
    val music: StateFlow<Boolean> = _music.asStateFlow()

    internal fun set(running: Boolean, usingMic: Boolean = false, music: Boolean = false) {
        _running.value = running
        _usingMic.value = usingMic
        _music.value = music
    }

    internal fun setMic(on: Boolean) { _usingMic.value = on }
    internal fun setMusic(on: Boolean) { _music.value = on }

    /** Starts the service; false when Android refuses (e.g. started from the background). */
    fun start(context: Context): Boolean = runCatching {
        context.startForegroundService(Intent(context, LiveVisualiserService::class.java))
        true
    }.getOrDefault(false)

    fun stop(context: Context) {
        context.stopService(Intent(context, LiveVisualiserService::class.java))
        set(false)
    }
}

/**
 * Drives the matrix (app channel, through the arbiter) from what is playing, at about 30 fps, but only while
 * music plays. A foreground service of type microphone, as Visualizer and the microphone fallback both need
 * RECORD_AUDIO while DotForge is in the background.
 */
class LiveVisualiserService : Service() {
    private var engine: VisualiserEngine? = null
    private val main = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            startForeground(NOTIFICATION_ID, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) {
            val e = VisualiserEngine(this, AppGraph.profile, { AppGraph.toys.visualStyle }) { frame -> onFrame(frame) }
            if (!e.start()) {
                e.close()
                stopSelf()
                return START_NOT_STICKY
            }
            engine = e
            LiveVisualiser.set(true, e.usingMic.value)
            main.launch {
                e.usingMic.collect { mic ->
                    LiveVisualiser.setMic(mic)
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(mic))
                }
            }
            LiveTileService.refresh(this)
        }
        return START_STICKY
    }

    private fun onFrame(pixels: IntArray) {
        val e = engine ?: return
        val playing = e.musicActive()
        LiveVisualiser.setMusic(playing)
        if (playing) {
            val frame = Frame.of(AppGraph.profile, pixels)
            AppGraph.arbiter.post(DisplayRequest(LiveVisualiser.ID, Priority.LIVE, Animation.single(frame, 1_000), loop = true, label = "Music"))
        } else if (AppGraph.arbiter.has(LiveVisualiser.ID)) {
            AppGraph.arbiter.remove(LiveVisualiser.ID)
        }
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        main.cancel()
        AppGraph.arbiter.remove(LiveVisualiser.ID)
        LiveVisualiser.set(false)
        LiveTileService.refresh(this)
        super.onDestroy()
    }

    private fun notification(mic: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Music visualiser", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveVisualiserService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_toy)
            .setContentTitle("Music on the Glyph Matrix")
            .setContentText(if (mic) "Using the microphone: Android gave no music data." else "Lights follow what's playing.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "live"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.dotforge.live.STOP"
    }
}

/** Quick Settings tile for the Live visualiser. Opens DotForge when the microphone permission is still needed. */
class LiveTileService : TileService() {
    override fun onStartListening() = update(LiveVisualiser.running.value)

    override fun onClick() {
        if (LiveVisualiser.running.value) {
            LiveVisualiser.stop(this)
            update(false)
            return
        }
        val engineOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (engineOk && LiveVisualiser.start(this)) {
            update(true)
        } else {
            // the switch on the Glyph tab asks for the permission (or starts it from the foreground)
            val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LIVE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun update(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, android.content.ComponentName(context, LiveTileService::class.java)) }
        }
    }
}

/** The style both the Live visualiser and the DF Music toy draw. */
fun VisualStyle.next(): VisualStyle = VisualStyle.entries[(ordinal + 1) % VisualStyle.entries.size]
