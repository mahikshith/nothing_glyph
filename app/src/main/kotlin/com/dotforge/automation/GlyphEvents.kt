package com.dotforge.automation

import android.content.Context
import android.content.Intent
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.system.SystemGlyphs
import com.dotforge.tile.TorchTileService
import com.dotforge.visualiser.LiveVisualiser

/**
 * "Tell other apps when something lights": a broadcast to apps holding [Automation.PERMISSION] each time
 * something new takes the Glyph. It carries the kind of thing only ([EXTRA_EVENT]: "alert", "call", "timer"…),
 * never who sent a message, what it said or which app it came from.
 */
object GlyphEvents {
    const val ACTION_LIT = "com.dotforge.event.GLYPH_LIT"
    const val EXTRA_EVENT = "event"

    /** Every name [name] can give, for the "For app makers" sheet. */
    val NAMES = listOf("alert", "call", "timer", "progress", "torch", "battery", "volume", "music", "automation")

    /** The event name for what now lights the Glyph; null for DotForge's own previews (they never leave the app). */
    fun name(r: DisplayRequest): String? = when {
        r.priority == Priority.STUDIO_PREVIEW -> null
        r.priority == Priority.CALL -> "call"
        r.id == TorchTileService.ID -> "torch"
        r.priority == Priority.TIMER || r.priority == Priority.TIMER_DONE -> "timer"
        r.id == LiveVisualiser.ID -> "music"
        r.id == SystemGlyphs.BATTERY_ID -> "battery"
        r.id == SystemGlyphs.VOLUME_ID -> "volume"
        r.id == AutomationRunner.GLYPH_ID || r.id == AutomationRunner.PLAY_ID -> "automation"
        r.priority == Priority.PROGRESS -> "progress"
        else -> "alert"
    }

    fun send(ctx: Context, event: String) {
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_LIT).putExtra(EXTRA_EVENT, event), Automation.PERMISSION)
        }
    }
}
