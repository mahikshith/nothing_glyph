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
import com.dotforge.rules.HistoryEntry
import com.dotforge.rules.HistoryText
import com.dotforge.rules.Outcome
import com.dotforge.rules.QuietReason
import com.dotforge.rules.SimpleRules
import com.dotforge.rules.SoundMode
import android.telecom.TelecomManager
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

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, replay = false)

    /**
     * [replay]: a notification already in the shade when the listener (re)connects. Its progress ring returns,
     * and while the phone is locked so does its glyph if its rule holds it (until read or unlock); no sound,
     * vibration, blocking or tester line.
     */
    private fun handle(sbn: StatusBarNotification, replay: Boolean) {
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
            if (n.category == Notification.CATEGORY_CALL) AlertRuntime.call.ended()
            return
        }

        if (replay && ringing) return
        val rules = AppGraph.rules.rules.value
        // a ringing call: its name feeds the call display (the best source wins, live), unless Phone calls is off
        if (ringing && rules.any { it.id == SimpleRules.PHONE_CALLS && it.enabled }) {
            val dialer = runCatching { getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull() == sbn.packageName
            AlertRuntime.call.notification(info.title, info.text, dialer)
            return
        }
        val filters = AppGraph.filters.filters.value
        val verdict = Delivery.decide(
            info, rules, AppGraph.rules.lists.value, filters,
            conditions = { FilterActions.conditions(this) },
            sender = { contacts.lookup(info) },
            region = region,
            spamList = AppGraph.spamList::contains,
        )
        var cooldown = false
        // the missed call glyph (Calls page option) repeats until the phone is unlocked, unless a filter holds it back
        if (replay) {
            val rule = (verdict as? Verdict.Show)?.match?.rule ?: return
            // held glyphs end on unlock, so they come back only while the phone is locked
            val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            if (!locked || rule.glyph == GlyphStyle.NONE || rule.until == Until.TIMEOUT) return
            AppGraph.alerts.offer(alertFor(rule, info, filters, dim = verdict.dim))
            return
        }
        val missed = missedCall(n) && (verdict is Verdict.Show || verdict == Verdict.NoRule)
        if (missed) {
            val glyph = AlertGlyph(Effects.missedCall(AppGraph.profile), "Missed call: ${info.title.ifBlank { info.appLabel }}")
            AppGraph.alerts.offer(AlertQueue.Alert(info.key, info.conversation, false, Until.UNLOCK, glyph, rank = AlertQueue.RANK_CALL))
            AppGraph.arbiter.refreshAfter(DotForgeApp.COVERED_MS)
        } else when (verdict) {
            is Verdict.Blocked -> block(sbn, info, verdict)
            is Verdict.Show -> {
                val rule = verdict.match.rule
                var queued = true
                if (rule.glyph != GlyphStyle.NONE) {
                    val offer = AppGraph.alerts.offer(alertFor(rule, info, filters, verdict.dim))
                    cooldown = offer == AlertQueue.Offer.COOLDOWN
                    queued = offer == AlertQueue.Offer.QUEUED
                    // Nothing's own notification glyph plays now and covers ours without telling us; show ours again after it.
                    AppGraph.arbiter.refreshAfter(DotForgeApp.COVERED_MS)
                }
                sound(sbn, rule, queued)
                if (!cooldown) history(info, Outcome.LIT, HistoryText.litDetail(glyphName(rule), rule), rule)
            }
            is Verdict.Quiet -> {
                val rule = verdict.match.rule
                // made while the phone was in use: it shows briefly once the screen goes off
                if (verdict.reason == QuietReason.IN_USE && rule.glyph != GlyphStyle.NONE) AlertRuntime.defer(alertFor(rule, info, filters, dim = false))
                history(info, Outcome.SKIPPED, verdict.reason, rule)
            }
            Verdict.NoRule -> Unit
        }
        AppGraph.log(LogEntry(info, verdict, cooldown))
    }

    private fun alertFor(rule: com.dotforge.rules.Rule, info: NotificationInfo, filters: com.dotforge.rules.FilterSettings, dim: Boolean) =
        RuleEngine.alert(
            rule, info, AppGraph.profile, AppGraph::designAnimation, AppGraph::designTiming, isCall = false,
            rank = SimpleRules.rank(rule, filters.order), dim = dim, appIcon = AlertRuntime::appIcon,
        )

    /** The rule's sound: the app's own (nothing to do), none, or our tune instead of the app's (row 59 decision). */
    private fun sound(sbn: StatusBarNotification, rule: com.dotforge.rules.Rule, queued: Boolean) {
        when (rule.sound) {
            SoundMode.APP -> Unit
            SoundMode.NONE -> FilterActions.silence(this, sbn, ::cancelNotification, spam = false)
            SoundMode.CUSTOM -> {
                FilterActions.silence(this, sbn, ::cancelNotification, spam = false)
                if (queued) rule.soundUri?.let { NotificationSound.play(this, it) }
            }
        }
    }

    private fun glyphName(rule: com.dotforge.rules.Rule): String = when (rule.glyph) {
        GlyphStyle.ICON -> com.dotforge.device.GlyphLibrary.get(rule.designId)?.name?.let { "$it glyph" } ?: "Glyph"
        GlyphStyle.APP_ICON -> "App icon"
        GlyphStyle.NAME -> "Their name"
        GlyphStyle.DESIGN -> AppGraph.designs.get(rule.designId)?.name ?: "My design"
        else -> rule.glyph.label
    }

    private fun history(info: NotificationInfo, outcome: Outcome, detail: String, rule: com.dotforge.rules.Rule?) =
        AppGraph.history.add(
            HistoryEntry(
                key = info.key + ":" + info.postedAt, at = info.postedAt, outcome = outcome, detail = detail,
                appLabel = info.appLabel, packageName = info.packageName, title = info.title, text = info.text,
                sender = info.sender, number = info.number.orEmpty(), ruleId = rule?.id, rule = rule?.name, action = rule?.action,
            ),
        )

    /**
     * A progress bar (classic or Android 16 ProgressStyle) on the outer ring, when progress rings are on and a
     * "Show progress" rule matches; taken off when it finishes, turns indeterminate or no rule wants it.
     */
    private fun showProgress(sbn: StatusBarNotification, info: NotificationInfo) {
        val fraction = progressOf(sbn.notification)?.takeIf { AppGraph.progressRings.value }
        val waiting = fraction == null && AppGraph.progressRings.value && Progress.waiting(progressValues(sbn.notification))
        val match = (fraction ?: if (waiting) 0f else null)?.let {
            val s = Delivery.subject(
                info, AppGraph.filters.filters.value, { FilterActions.conditions(this) }, { contacts.lookup(info) },
                region, AppGraph.spamList::contains,
            )
            RuleEngine.progress(s, AppGraph.rules.rules.value, AppGraph.rules.lists.value)
        }
        if (match == null) {
            AppGraph.arbiter.remove(progressId(sbn.key))
            return
        }
        AppGraph.arbiter.post(
            DisplayRequest(
                id = progressId(sbn.key),
                priority = Priority.PROGRESS,
                // no value yet: the ring breathes until there is one
                animation = fraction?.let { Effects.progress(AppGraph.profile, it) } ?: Progress.breathing(AppGraph.profile),
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
        history(info, Outcome.BLOCKED, HistoryText.blockedDetail(v.match.rule), v.match.rule)
        if (action == Action.SPAM) FilterActions.spamAlert(this, who)
    }

    private var mediaRing: MediaRing? = null

    override fun onListenerConnected() {
        // replay what is already in the shade (after a reboot, an update or Android rebinding the listener)
        runCatching { activeNotifications }.getOrNull().orEmpty().sortedBy { it.postTime }.forEach { sbn ->
            runCatching { handle(sbn, replay = true) }
        }
        mediaRing?.stop()
        mediaRing = MediaRing(this, ComponentName(this, GlyphNotificationListener::class.java)).also { it.start() }
    }

    override fun onListenerDisconnected() {
        mediaRing?.stop()
        mediaRing = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        AppGraph.arbiter.remove(progressId(sbn.key))
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null) AlertRuntime.call.ended()
        // our own removal (to silence it) is not "checked"; our quiet copy carries the original's key as its tag
        if (AlertRuntime.wasOurs(sbn.key)) return
        val key = if (sbn.packageName == packageName && n.channelId == FilterActions.SILENCED_CHANNEL) sbn.tag ?: return else sbn.key
        AppGraph.alerts.removed(key)
        AlertRuntime.dropDeferred(key)
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


    /** A notification's progress extras as plain values for [Progress]. */
    private fun progressValues(n: Notification): Map<String, Any?> {
        val e = n.extras
        return buildMap {
            for (k in listOf(Progress.PROGRESS, Progress.MAX)) if (e.containsKey(k)) put(k, e.getInt(k))
            put(Progress.INDETERMINATE, e.getBoolean(Progress.INDETERMINATE, false))
            put(Progress.TEMPLATE, e.getString(Progress.TEMPLATE))
            e.getParcelableArrayList(Progress.SEGMENTS, Bundle::class.java)?.let { list ->
                put(Progress.SEGMENTS, list.map { mapOf(Progress.SEGMENT_LENGTH to it.getInt(Progress.SEGMENT_LENGTH)) })
            }
        }
    }

    /** Determinate progress 0..1, from the classic extras or (Android 16) a ProgressStyle notification. */
    private fun progressOf(n: Notification): Float? {
        val values = progressValues(n)
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
