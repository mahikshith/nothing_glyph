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
 * Thin adapter over [GlyphDriver] for one [channel]. Holds one driver reference while it has something on
 * screen, so the Glyph service connection only stays open while a frame is shown. Not thread-safe: each
 * instance is used from one thread (the arbiter's engine thread or a toy's main thread).
 */
class GlyphMatrixDevice(context: Context, private val channel: Channel) : MatrixDevice {
    private val appContext = context.applicationContext
    private var held = false

    override val isHardware = true

    override fun show(frame: Frame) {
        if (!held) {
            GlyphDriver.acquire(appContext)
            held = true
        }
        GlyphDriver.submit(frame.pixels, channel)
    }

    override fun clear() {
        if (!held) return
        when (channel) {
            Channel.APP -> GlyphDriver.closeApp()
            Channel.TOY -> GlyphDriver.endToy()
        }
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
