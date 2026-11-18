package com.dotforge.notify

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dotforge.AppGraph
import com.dotforge.DotForgeApp
import com.dotforge.device.Effects
import com.dotforge.engine.AlertGlyph
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.rules.Conditions
import com.dotforge.rules.Delivery
import com.dotforge.rules.LogEntry
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.Verdict
import java.time.LocalDateTime

/**
 * Turns notifications into matrix glyphs:
 *  - ongoing notifications with a determinate progress value (downloads, deliveries, timers, Android 16
 *    ProgressStyle / Live Updates) become a progress ring, when progress rings are on;
 *  - ringing calls and everything else go through [Delivery] (spam filter, best rule, quiet hours and the
 *    other filters) and, when shown, into the alert queue. Every one is logged for the rule tester.
 */
class GlyphNotificationListener : NotificationListenerService() {

    private val contacts by lazy { ContactLookup(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val ringing = n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null
        // Messaging apps post calls as group summaries (found by Glyphifier, see NOTICE); other summaries only
        // repeat their children.
        if (!ringing && n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        if (sbn.isOngoing && !ringing) {
            // Ongoing notifications (music, calls in progress, foreground services) are not alerts; only progress is shown.
            AppGraph.alerts.removed(sbn.key) // e.g. an incoming call that was answered
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

        val info = infoOf(sbn, ringing)
        val verdict = Delivery.decide(
            info, AppGraph.rules.rules.value, AppGraph.filters.spam.value, AppGraph.filters.filters.value,
            conditions = ::conditions,
            sender = { contacts.lookup(info) },
        )
        var cooldown = false
        when (verdict) {
            is Verdict.Blocked -> AppGraph.filters.record(info)
            is Verdict.Show -> {
                val rule = verdict.match.rule
                val glyph = AlertGlyph(
                    RuleEngine.animation(rule, info, AppGraph.profile, AppGraph::designAnimation),
                    "${rule.name}: ${info.title.ifBlank { info.appLabel }}",
                )
                val offer = AppGraph.alerts.offer(AlertQueue.Alert(info.key, info.conversation, info.isCall, rule.until, glyph))
                cooldown = offer == AlertQueue.Offer.COOLDOWN
                // Nothing's own notification glyph plays now and covers ours without telling us; show ours again after it.
                AppGraph.arbiter.refreshAfter(DotForgeApp.COVERED_MS)
            }
            Verdict.NoRule, is Verdict.Quiet -> Unit
        }
        AppGraph.log(LogEntry(info, verdict, cooldown))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        AppGraph.alerts.removed(sbn.key)
        AppGraph.arbiter.remove(progressId(sbn.key))
    }

    private fun infoOf(sbn: StatusBarNotification, ringing: Boolean): NotificationInfo {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
            ?.mapNotNull { it.uri }.orEmpty()
        val fromPerson = n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_CALL ||
            people.isNotEmpty() || extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
        return NotificationInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime,
            people = people,
            fromPerson = fromPerson,
            isCall = ringing,
            conversation = n.shortcutId?.let { "${sbn.packageName}/$it" } ?: "${sbn.packageName}/${title.trim()}",
        )
    }

    private fun conditions() = Conditions(
        now = LocalDateTime.now(),
        dnd = getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        faceDown = AppGraph.faceDown.faceDown,
    )

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

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        fun progressId(key: String) = "p:$key"
    }
}
