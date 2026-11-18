package com.dotforge.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dotforge.AppGraph
import com.dotforge.device.Effects
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.rules.MatchResult
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SpamFilter

/**
 * Turns notifications into matrix animations:
 *  - ongoing notifications with a determinate progress value (downloads, deliveries, timers, Android 16
 *    ProgressStyle / Live Updates) become a progress ring, when progress rings are on;
 *  - incoming calls and everything else first pass the spam filter, then the rule list (first match wins).
 */
class GlyphNotificationListener : NotificationListenerService() {

    /** Notification keys that should stop showing when the phone is unlocked. */
    private val clearOnUnlock = mutableSetOf<String>()
    private var receiverRegistered = false
    private val contacts by lazy { ContactLookup(this) }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val ids = clearOnUnlock.map(::notificationId).toSet()
            clearOnUnlock.clear()
            AppGraph.arbiter.removeIf { it.id in ids }
        }
    }

    override fun onListenerConnected() {
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onListenerDisconnected() {
        if (receiverRegistered) unregisterReceiver(unlockReceiver)
        receiverRegistered = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        if (sbn.isOngoing && !isIncomingCall(n)) {
            // Ongoing notifications (music, calls in progress, foreground services) are not alerts; only progress is shown.
            AppGraph.arbiter.remove(notificationId(sbn.key)) // e.g. an incoming call that was answered
            val fraction = progressOf(n)?.takeIf { AppGraph.progressRings.value }
            if (fraction == null) {
                AppGraph.arbiter.remove(progressId(sbn.key)) // finished, indeterminate or switched off
                return
            }
            AppGraph.arbiter.post(
                DisplayRequest(
                    id = progressId(sbn.key),
                    priority = Priority.PROGRESS,
                    animation = Effects.progress(AppGraph.profile, fraction),
                    loop = true,
                    label = "Progress: ${appLabel(sbn.packageName)}",
                ),
            )
            return
        }

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
            ?.mapNotNull { it.uri }.orEmpty()
        val info = NotificationInfo(sbn.key, sbn.packageName, appLabel(sbn.packageName), title, text, sbn.postTime, people)
        AppGraph.remember(info)

        if (SpamFilter.isSpam(info, AppGraph.spam.settings.value) { contacts.lookup(info) }) {
            AppGraph.spam.record(info)
            return
        }
        val rule = (RuleEngine.match(info, AppGraph.rules.rules.value) as? MatchResult.Show)?.rule ?: return
        if (inDnd() && !rule.breakDnd) return
        val anim = RuleEngine.animationFor(rule, info, AppGraph.profile, AppGraph.playerAnimation())
        if (rule.clearOnUnlock) clearOnUnlock += sbn.key
        AppGraph.arbiter.post(
            DisplayRequest(
                id = notificationId(sbn.key),
                priority = Priority.NOTIFICATION,
                animation = anim,
                loop = true,
                timeoutMs = if (rule.holdUntilDismissed) null else maxOf(SHOW_MS, anim.totalMs.toLong()),
                label = "${rule.name}: ${title.ifBlank { info.appLabel }}",
            ),
        )
    }

    /** A ringing call: ongoing, but an alert. Calls in progress have no full-screen intent. */
    private fun isIncomingCall(n: Notification) = n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        clearOnUnlock -= sbn.key
        AppGraph.arbiter.remove(notificationId(sbn.key))
        AppGraph.arbiter.remove(progressId(sbn.key))
    }

    /** Determinate progress 0..1, from the classic extras or (Android 16) a ProgressStyle notification. */
    private fun progressOf(n: Notification): Float? {
        val extras = n.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
            return extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val style = runCatching { Notification.Builder.recoverBuilder(this, n).style }.getOrNull()
            as? Notification.ProgressStyle ?: return null
        if (style.isProgressIndeterminate || style.progressMax <= 0) return null
        return style.progress.toFloat() / style.progressMax
    }

    private fun inDnd(): Boolean =
        getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        /** How long a notification that is not held keeps showing (a long marquee plays through once). */
        const val SHOW_MS = 6_000L

        fun notificationId(key: String) = "n:$key"
        fun progressId(key: String) = "p:$key"
    }
}
