package com.dotforge.calls

import com.dotforge.device.AlertEffects
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.rules.CallShow

/** What a ringing call shows right now: the best name so far, the number, and whether a source said spam. */
data class CallDisplay(val name: NameCandidate?, val number: String?, val spam: Boolean)

/**
 * One ringing call, fed from every source as it arrives: the call screening service (the number and the saved
 * contact), the default phone app's ringing notification, and other caller ID or VoIP apps' ringing calls.
 * Each change that alters what shows calls [publish]; [ended] clears it (null). Thread-safe.
 */
class CallSession(private val publish: (CallDisplay?) -> Unit) {
    private val name = CallerName()
    private var number: String? = null
    private var active = false
    private var shown: CallDisplay? = null

    val ringing: Boolean get() = synchronized(this) { active }

    /** From call screening: [formatted] is the number as the phone shows it; [contact] the saved name. */
    fun ringing(formatted: String?, contact: String?) = synchronized(this) {
        if (!active) start()
        number = formatted?.takeIf { it.isNotBlank() } ?: number
        name.offer(contact, NameSource.CONTACT)
        name.offer(formatted, NameSource.NUMBER)
        emit()
    }

    /** A ringing call notification ([dialer]: from the default phone app). */
    fun notification(title: String?, text: String?, dialer: Boolean) = synchronized(this) {
        if (!active) start()
        name.markSpam(title, text)
        name.offer(CallerNames.fromNotification(title, text), if (dialer) NameSource.DIALER else NameSource.OTHER_APP)
        emit()
    }

    fun ended() = synchronized(this) {
        if (!active) return@synchronized
        active = false
        name.clear()
        number = null
        shown = null
        publish(null)
    }

    private fun start() {
        active = true
        name.clear()
        number = null
    }

    private fun emit() {
        val d = CallDisplay(name.best, number, name.spam)
        if (d == shown) return
        shown = d
        publish(d)
    }

    companion object {
        /**
         * What the Glyph plays for [d]: the spam glyph for a spam label; otherwise, by [show], the caller's
         * [glyph], their name scrolling, or the glyph then the name. Without a name, Name falls back to the glyph.
         */
        fun animation(profile: DeviceProfile, d: CallDisplay, show: CallShow, glyph: Animation, spam: Animation, speed: Float = 1f, brightness: Int = 100): Animation {
            if (d.spam) return spam
            val text = d.name?.name
            return when {
                text == null || show == CallShow.GLYPH -> glyph
                show == CallShow.NAME -> AlertEffects.name(profile, text, speed = speed, brightness = brightness)
                else -> AlertEffects.name(profile, text, prefix = glyph, speed = speed, brightness = brightness)
            }
        }
    }
}
