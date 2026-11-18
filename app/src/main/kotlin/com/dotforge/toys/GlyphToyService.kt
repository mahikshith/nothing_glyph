package com.dotforge.toys

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.diagnostics.EventLog
import com.dotforge.driver.Channel
import com.dotforge.driver.GlyphDriver
import com.dotforge.engine.MatrixDevice
import com.dotforge.engine.MatrixDevices
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Base class for Glyph Matrix toys. The system binds to the service and sends Glyph Button events through a
 * Messenger (shape from Nothing's GlyphMatrix Developer Kit README). Every event is also written to the
 * [EventLog] shown on the Diagnostics screen. Frames go to the toy channel through [GlyphDriver].
 */
abstract class GlyphToyService : Service() {

    protected val profile: DeviceProfile = GlyphDriver.profile
    /** Lives from bind to unbind. */
    protected lateinit var toyScope: CoroutineScope
        private set

    private lateinit var device: MatrixDevice
    private val presses = PressDecoder()
    private var animJob: Job? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                log("unknown_msg", "what=${msg.what}")
                super.handleMessage(msg)
                return
            }
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            log(event ?: "no_event")
            val now = SystemClock.uptimeMillis()
            val press = when (event) {
                GlyphToy.EVENT_ACTION_DOWN -> { presses.down(now); null }
                GlyphToy.EVENT_ACTION_UP -> presses.up(now)
                GlyphToy.EVENT_CHANGE -> presses.change(now)
                GlyphToy.EVENT_AOD -> { onAodTick(); null }
                else -> null
            }
            press?.let(::onPress)
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder {
        log("bind")
        device = MatrixDevices.create(this, Channel.TOY)
        toyScope = MainScope()
        onToyStart()
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        log("unbind")
        onToyStop()
        toyScope.cancel()
        animJob = null
        device.clear()
        return false
    }

    private fun log(kind: String, detail: String = "") =
        EventLog.record(this, kind, listOf(javaClass.simpleName, detail).filter { it.isNotEmpty() }.joinToString(" "))

    protected fun show(frame: Frame) = device.show(frame)

    /** Plays [animation] once (or looping), replacing any running animation. */
    protected fun play(animation: Animation, loop: Boolean = false) {
        animJob?.cancel()
        animJob = toyScope.launch {
            do {
                for (f in animation.frames) {
                    show(f.frame)
                    delay(f.durationMs.coerceAtLeast(Animation.MIN_FRAME_MS).toLong())
                }
            } while (loop && isActive)
        }
    }

    protected val isPlaying: Boolean get() = animJob?.isActive == true

    protected fun stopAnimation() {
        animJob?.cancel()
        animJob = null
    }

    /** Called on bind: draw the first frame and start any timers on [toyScope]. */
    abstract fun onToyStart()

    /** Called on unbind before [toyScope] is cancelled: release receivers and sensors. */
    open fun onToyStop() = Unit

    open fun onPress(press: Press) = Unit

    /** Sent about once a minute while the toy is the always-on toy. */
    open fun onAodTick() = Unit
}
