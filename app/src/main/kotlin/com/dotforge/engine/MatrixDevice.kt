package com.dotforge.engine

import android.content.Context
import android.os.Build
import com.dotforge.device.Frame
import com.dotforge.driver.Channel
import com.dotforge.driver.GlyphDriver

/** Something that can show a [Frame]: the real Glyph Matrix, or nothing (preview mode). */
interface MatrixDevice {
    val isHardware: Boolean
    fun show(frame: Frame)
    /** Stop showing anything; the next [show] starts again. */
    fun clear()
}

/** Used on other phones and emulators: everything still works, frames only appear in the on-screen preview. */
object PreviewDevice : MatrixDevice {
    override val isHardware = false
    override fun show(frame: Frame) = Unit
    override fun clear() = Unit
}

/**
 * Thin adapter over [GlyphDriver] for one [channel]. The app channel (the arbiter's) holds the driver for the
 * process's lifetime and always closes the app matrix on [clear], even if this process never drew: a frame
 * from before a restart (a torch left on) would otherwise stay on the panel. A toy holds the driver only while
 * it shows something. Not thread-safe: each instance is used from one thread.
 */
class GlyphMatrixDevice(context: Context, private val channel: Channel) : MatrixDevice {
    private val appContext = context.applicationContext
    private var held = false

    init {
        if (channel == Channel.APP) {
            GlyphDriver.acquire(appContext)
            held = true
        }
    }

    override val isHardware = true

    override fun show(frame: Frame) {
        if (!held) {
            GlyphDriver.acquire(appContext)
            held = true
        }
        GlyphDriver.submit(frame.pixels, channel)
    }

    override fun clear() {
        if (channel == Channel.APP) return GlyphDriver.closeAppAlways()
        if (!held) return
        GlyphDriver.endToy()
        GlyphDriver.release()
        held = false
    }
}

object MatrixDevices {
    /** True on Nothing phones. The driver itself reports whether this one has a matrix (register fails otherwise). */
    val isNothingPhone: Boolean get() = Build.MANUFACTURER.equals("Nothing", ignoreCase = true)

    fun create(context: Context, channel: Channel): MatrixDevice =
        if (isNothingPhone) GlyphMatrixDevice(context, channel) else PreviewDevice
}
