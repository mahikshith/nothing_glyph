package com.dotforge.notify

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dotforge.AppGraph
import com.dotforge.DotForgeApp
import com.dotforge.calls.CallerLookup
import com.dotforge.device.Effects
import com.dotforge.engine.AlertGlyph
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.engine.Until
import com.dotforge.filter.FilterActions
import com.dotforge.filter.Senders
import com.dotforge.ringtones.NotificationSound
import com.dotforge.rules.Action
import com.dotforge.rules.Delivery
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.LogEntry
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SpamCatch
import com.dotforge.rules.Verdict

/**
 * Turns notifications into matrix glyphs:
 *  - a determinate progress bar (downloads, deliveries, timers, Android 16 ProgressStyle / Live Updates)
 *    becomes a progress ring when progress rings are on and a "Show progress" rule matches; ongoing
 *    notifications go no further;
 *  - while connected, [MediaRing] shows the playing media's position on the ring when switched on;
 *  - ringing calls and everything else go through [Delivery] (spam rules first, then the best rule, quiet
 *    hours and the other filters): shown ones go into the alert queue; silenced, hidden and spam ones are
 *    handled and logged in the Spam log. Every one is logged for the rule tester.
 * The message itself always stays in its app; only the notification, the sound and the glyph change.
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

        val info = infoOf(sbn, ringing)
        if (!ringing) showProgress(sbn, info)
        // Ongoing notifications (music, calls in progress, foreground services) are not alerts; only progress is shown.
        if (sbn.isOngoing && !ringing) {
            AppGraph.alerts.removed(sbn.key) // e.g. an incoming call that was answered
            return
        }

        val verdict = Delivery.decide(
            info, AppGraph.rules.rules.value, AppGraph.rules.lists.value, AppGraph.filters.filters.value,
            conditions = { FilterActions.conditions(this) },
            sender = { contacts.lookup(info) },
            region = region,
            spamList = AppGraph.spamList::contains,
        )
        var cooldown = false
        // the missed call glyph (Calls page option) repeats until the phone is unlocked, unless a filter holds it back
        val missed = missedCall(n) && (verdict is Verdict.Show || verdict == Verdict.NoRule)
        if (missed) {
            val glyph = AlertGlyph(Effects.missedCall(AppGraph.profile), "Missed call: ${info.title.ifBlank { info.appLabel }}")
            AppGraph.alerts.offer(AlertQueue.Alert(info.key, info.conversation, false, Until.UNLOCK, glyph))
            AppGraph.arbiter.refreshAfter(DotForgeApp.COVERED_MS)
        } else when (verdict) {
            is Verdict.Blocked -> block(sbn, info, verdict)
            is Verdict.Show -> {
                val rule = verdict.match.rule
                var queued = true
                if (rule.glyph != GlyphStyle.NONE) {
                    val glyph = AlertGlyph(
                        RuleEngine.animation(rule, info, AppGraph.profile, AppGraph::designAnimation),
                        "${rule.name}: ${info.sender.ifBlank { info.title }.ifBlank { info.appLabel }}",
                    )
                    val offer = AppGraph.alerts.offer(AlertQueue.Alert(info.key, info.conversation, info.isCall, rule.until, glyph))
                    cooldown = offer == AlertQueue.Offer.COOLDOWN
                    queued = offer == AlertQueue.Offer.QUEUED
                    // Nothing's own notification glyph plays now and covers ours without telling us; show ours again after it.
                    AppGraph.arbiter.refreshAfter(DotForgeApp.COVERED_MS)
                }
                if (queued) {
                    rule.soundUri?.let { NotificationSound.play(this, it) }
                    if (rule.vibrate) FilterActions.vibrate(this)
                }
            }
            Verdict.NoRule, is Verdict.Quiet -> Unit
        }
        AppGraph.log(LogEntry(info, verdict, cooldown))
    }

    /**
     * A progress bar (classic or Android 16 ProgressStyle) on the outer ring, when progress rings are on and a
     * "Show progress" rule matches; taken off when it finishes, turns indeterminate or no rule wants it.
     */
    private fun showProgress(sbn: StatusBarNotification, info: NotificationInfo) {
        val fraction = progressOf(sbn.notification)?.takeIf { AppGraph.progressRings.value }
        val match = fraction?.let {
            val s = Delivery.subject(
                info, AppGraph.filters.filters.value, { FilterActions.conditions(this) }, { contacts.lookup(info) },
                region, AppGraph.spamList::contains,
            )
            RuleEngine.progress(s, AppGraph.rules.rules.value, AppGraph.rules.lists.value)
        }
        if (fraction == null || match == null) {
            AppGraph.arbiter.remove(progressId(sbn.key))
            return
        }
        AppGraph.arbiter.post(
            DisplayRequest(
                id = progressId(sbn.key),
                priority = Priority.PROGRESS,
                animation = Effects.progress(AppGraph.profile, fraction),
                loop = true,
                label = "Progress: ${info.appLabel}",
            ),
        )
    }

    /** A spam-filter rule: silence (quiet copy), hide, or mark as spam (quiet copy and the spam alert); then log it. */
    private fun block(sbn: StatusBarNotification, info: NotificationInfo, v: Verdict.Blocked) {
        val action = v.match.rule.action
        when (action) {
            Action.HIDE -> cancelNotification(sbn.key)
            Action.SILENCE -> FilterActions.silence(this, sbn, ::cancelNotification, spam = false)
            else -> FilterActions.silence(this, sbn, ::cancelNotification, spam = true)
        }
        val who = info.sender.ifBlank { info.title }.ifBlank { info.appLabel }
        AppGraph.filters.record(
            SpamCatch(
                key = sbn.key, appLabel = info.appLabel, title = info.title, text = info.text, at = info.postedAt,
                packageName = info.packageName, sender = info.sender.ifBlank { info.title }, number = info.number.orEmpty(),
                action = action, rule = v.match.rule.name, why = v.match.why,
            ),
        )
        if (action == Action.SPAM) FilterActions.spamAlert(this, who)
    }

    private var mediaRing: MediaRing? = null

    override fun onListenerConnected() {
        mediaRing?.stop()
        mediaRing = MediaRing(this, ComponentName(this, GlyphNotificationListener::class.java)).also { it.start() }
    }

    override fun onListenerDisconnected() {
        mediaRing?.stop()
        mediaRing = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        AppGraph.alerts.removed(sbn.key)
        AppGraph.arbiter.remove(progressId(sbn.key))
    }

    private val region: String? get() = callerLookup.region()
    private val callerLookup by lazy { CallerLookup(this) }

    private fun infoOf(sbn: StatusBarNotification, ringing: Boolean): NotificationInfo {
        val n = sbn.notification
        val extras = n.extras
        val f = Senders.fields(extrasMap(extras))
        val sender = Senders.sender(f, region)
        val people = f.people
        val messaging = extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name || f.messages.isNotEmpty()
        val fromPerson = n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_CALL ||
            n.category == Notification.CATEGORY_EMAIL || people.isNotEmpty() || messaging
        val text = f.text.ifBlank { f.bigText }
        return NotificationInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = f.title,
            text = text,
            postedAt = sbn.postTime,
            people = people,
            fromPerson = fromPerson,
            isCall = ringing,
            conversation = n.shortcutId?.let { "${sbn.packageName}/$it" } ?: "${sbn.packageName}/${f.title.trim()}",
            channel = n.channelId.orEmpty(),
            category = n.category.orEmpty(),
            bigText = f.bigText,
            subText = f.subText,
            summary = f.summary,
            sender = if (fromPerson) sender.name else "",
            email = sender.email,
            number = sender.number,
            groupChat = sender.group,
            ongoing = sbn.isOngoing,
        )
    }

    /** The extras the filter engine reads, as plain values (see [Senders]). */
    private fun extrasMap(extras: android.os.Bundle): Map<String, Any?> {
        val messages = runCatching {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java),
            ).map { m -> mapOf("sender" to m.senderPerson?.name?.toString(), "text" to m.text?.toString(), "uri" to m.senderPerson?.uri) }
        }.getOrDefault(emptyList())
        val people = (extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)?.mapNotNull { it.uri }.orEmpty() +
            @Suppress("DEPRECATION") extras.getStringArray(Notification.EXTRA_PEOPLE).orEmpty()).distinct()
        return mapOf(
            Senders.TITLE to extras.getCharSequence(Notification.EXTRA_TITLE),
            Senders.TEXT to extras.getCharSequence(Notification.EXTRA_TEXT),
            Senders.BIG_TEXT to extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            Senders.SUB_TEXT to extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            Senders.SUMMARY to extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
            Senders.CONVERSATION_TITLE to extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            Senders.IS_GROUP to extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            Senders.MESSAGES to messages,
            Senders.PEOPLE to people,
            Senders.TEXT_LINES to extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() },
        )
    }

    private fun missedCall(n: Notification) =
        n.category == Notification.CATEGORY_MISSED_CALL && AppGraph.calls.settings.value.missedGlyph


    /** Determinate progress 0..1, from the classic extras or (Android 16) a ProgressStyle notification. */
    private fun progressOf(n: Notification): Float? {
        val e = n.extras
        val values = buildMap<String, Any?> {
            for (k in listOf(Progress.PROGRESS, Progress.MAX)) if (e.containsKey(k)) put(k, e.getInt(k))
            put(Progress.INDETERMINATE, e.getBoolean(Progress.INDETERMINATE, false))
            put(Progress.TEMPLATE, e.getString(Progress.TEMPLATE))
            e.getParcelableArrayList(Progress.SEGMENTS, Bundle::class.java)?.let { list ->
                put(Progress.SEGMENTS, list.map { mapOf(Progress.SEGMENT_LENGTH to it.getInt(Progress.SEGMENT_LENGTH)) })
            }
        }
        Progress.fraction(values)?.let { return it }
        // a ProgressStyle that keeps its values elsewhere: ask Android
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA || values[Progress.TEMPLATE] != Progress.PROGRESS_STYLE) return null
        val style = runCatching { Notification.Builder.recoverBuilder(this, n).style }.getOrNull()
            as? Notification.ProgressStyle ?: return null
        if (style.isProgressIndeterminate || style.progressMax <= 0) return null
        return (style.progress.toFloat() / style.progressMax).coerceIn(0f, 1f)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        fun progressId(key: String) = "p:$key"
    }
}
