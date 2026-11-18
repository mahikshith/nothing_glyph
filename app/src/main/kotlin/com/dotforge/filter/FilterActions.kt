package com.dotforge.filter

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.StatusBarNotification
import com.dotforge.AppGraph
import com.dotforge.R
import com.dotforge.engine.AlertGlyph
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.Until
import com.dotforge.ringtones.NotificationSound
import com.dotforge.rules.Conditions
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import java.time.LocalDateTime

/** What rules do on the phone: the spam alert, silencing and hiding notifications, vibrating, the phone's state. */
object FilterActions {
    private const val SILENCED_CHANNEL = "silenced"

    /** The phone's state for conditions and filters. */
    fun conditions(context: Context) = Conditions(
        now = LocalDateTime.now(),
        dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
        locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        faceDown = AppGraph.faceDown.faceDown,
        charging = context.getSystemService(BatteryManager::class.java)?.isCharging,
    )

    /** The spam alert: the spam glyph (unless switched off) and the spam sound (when one is chosen). */
    fun spamAlert(context: Context, who: String, key: String = "spam:$who:${System.currentTimeMillis()}") {
        val s = AppGraph.filters.spam.value
        if (s.glyphOn && s.glyph != GlyphStyle.NONE) {
            val info = NotificationInfo(key, "", "Spam", who, "", 0, sender = who)
            val anim = RuleEngine.animation(s.glyph, s.designId, info, AppGraph.profile, AppGraph::designAnimation)
            AppGraph.alerts.offer(AlertQueue.Alert(key, "spam:$who", isCall = false, until = Until.TIMEOUT, item = AlertGlyph(anim, "Spam: $who")))
        }
        s.soundUri?.let { NotificationSound.play(context, it) }
    }

    fun vibrate(context: Context) {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 90, 120), -1)) }
    }

    /**
     * Silences a notification: Android can't mute another app's notification, so it is removed (which stops its
     * sound and vibration) and a quiet copy that opens the same thing goes in DotForge's "Silenced" channel.
     * Without the notifications permission the copy can't be posted, so it ends up hidden.
     */
    fun silence(context: Context, sbn: StatusBarNotification, cancel: (String) -> Unit, spam: Boolean) {
        cancel(sbn.key)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(
            NotificationChannel(SILENCED_CHANNEL, "Silenced and spam", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet copies of notifications your rules silenced or marked as spam"
            },
        )
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val copy = Notification.Builder(context, SILENCED_CHANNEL)
            .setSmallIcon(R.drawable.ic_toy)
            .setContentTitle(if (spam) "Spam · $title" else title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(label)
            .setContentIntent(sbn.notification.contentIntent)
            .setAutoCancel(true)
            .setGroup(SILENCED_CHANNEL)
            .setWhen(sbn.postTime)
            .setShowWhen(true)
            .build()
        runCatching { nm.notify(sbn.key, 0, copy) }
    }
}
