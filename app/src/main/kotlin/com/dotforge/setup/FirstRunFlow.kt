package com.dotforge.setup

import android.content.Context
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.ui.AppEntry

/**
 * What First run knows about the phone's permissions, read again on every resume. [callerName] is whether Nothing's
 * ringtone lights are set to Sound only (null when the firmware doesn't say).
 */
data class Perms(
    val access: Boolean = false,
    val screening: Boolean = false,
    val contacts: Boolean = false,
    val keepRunning: Boolean = false,
    val callerName: Boolean? = null,
) {
    /** Calls is one card: the call screening role and Contacts. */
    val calls: Boolean get() = screening && contacts

    /** The core permissions still missing, one per permission (as Profile > Permissions lists them). */
    val missingCore: Int get() = listOf(access, screening, contacts, keepRunning).count { !it }
}

/**
 * First run (production design > User journeys): Welcome, the three core permission cards, the optional caller
 * name card, Pick apps, Done. Every step can be skipped; cards already allowed when they come up are passed over.
 */
object FirstRunFlow {
    enum class Step { WELCOME, NOTIFICATIONS, CALLS, KEEP_RUNNING, CALLER_NAME, PICK_APPS, DONE }

    /** The permission cards, in order; the first three are the core ones. */
    val CARDS = listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.CALLER_NAME)

    /**
     * The steps for this phone. The caller name card needs Nothing's Glyph settings, so only a Phone (3) gets it;
     * after a restore the apps came back with the backup, so Pick apps is left out.
     */
    fun order(phone3: Boolean, restored: Boolean): List<Step> = Step.entries.filter { s ->
        when (s) {
            Step.CALLER_NAME -> phone3
            Step.PICK_APPS -> !restored
            else -> true
        }
    }

    /** True when [step] has nothing left to allow. */
    fun done(step: Step, p: Perms): Boolean = when (step) {
        Step.NOTIFICATIONS -> p.access
        Step.CALLS -> p.calls
        Step.KEEP_RUNNING -> p.keepRunning
        Step.CALLER_NAME -> p.callerName == true
        else -> false
    }

    /** Where First run starts: Welcome, or the first card still to do after a restore (the restore was the welcome). */
    fun start(order: List<Step>, restored: Boolean, p: Perms): Step =
        if (restored) next(Step.WELCOME, order, p) else Step.WELCOME

    /** The step after [from] ("Next", "Not now" or Continue), passing over cards that are already allowed. */
    fun next(from: Step, order: List<Step>, p: Perms): Step {
        var i = order.indexOf(from).coerceAtLeast(0) + 1
        while (i < order.size && order[i] in CARDS && done(order[i], p)) i++
        return order.getOrElse(i) { Step.DONE }
    }

    /** The step before [from] for Back (cards included, so a skipped card can be reached again); null on Welcome. */
    fun back(from: Step, order: List<Step>): Step? = order.indexOf(from).takeIf { it > 0 }?.let { order[it - 1] }

    /** "Step 2 of 5": where [step] sits among the steps between Welcome and Done, or null for those two. */
    fun position(step: Step, order: List<Step>): Pair<Int, Int>? {
        val middle = order.filter { it != Step.WELCOME && it != Step.DONE }
        val i = middle.indexOf(step)
        return if (i < 0) null else (i + 1) to middle.size
    }
}

/** The Calls card's state: its one line under the reason, and what its main button does. */
data class CallsCard(val line: String?, val button: String, val action: Action) {
    enum class Action { ASK_ROLE, OPEN_DEFAULT_APPS, ASK_CONTACTS, NEXT }

    companion object {
        /** Android stops showing the role dialog after this many refusals, so the button opens Default apps. */
        const val ROLE_TRIES = 2

        fun of(screening: Boolean, contacts: Boolean, roleRefusals: Int): CallsCard = when {
            screening && contacts -> CallsCard(null, "Next", Action.NEXT)
            !screening && roleRefusals >= ROLE_TRIES ->
                CallsCard("Android didn't let DotForge screen calls, so spam calls can't be blocked. Pick DotForge in Default apps instead.", "Open settings", Action.OPEN_DEFAULT_APPS)
            !screening && roleRefusals > 0 ->
                CallsCard("Android didn't let DotForge screen calls, so spam calls can't be blocked.", "Try again", Action.ASK_ROLE)
            !screening -> CallsCard("Android asks twice: once to screen calls, then for your contacts.", "Allow", Action.ASK_ROLE)
            else -> CallsCard("Contacts is off, so callers show as numbers.", "Allow contacts", Action.ASK_CONTACTS)
        }
    }
}

/**
 * First run's Pick apps: the installed messaging and call apps, with WhatsApp, Messages and Phone ticked when
 * they are installed. Picking writes normal Alerts rows (each app with its own icon), and Phone is the Phone calls
 * row: no new kind of rule.
 */
object PickApps {
    const val WHATSAPP = "com.whatsapp"
    const val MESSAGES = "com.google.android.apps.messaging"

    /** Phone apps: ticking one keeps the Phone calls row on. */
    val DIALERS = RuleEngine.CALL_APPS + listOf("com.samsung.android.dialer", "com.nothing.dialer")

    /** Messaging and calling apps worth offering, most used first. */
    val KNOWN = listOf(
        WHATSAPP, MESSAGES, "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
        "com.whatsapp.w4b", "com.samsung.android.messaging", "com.android.messaging", "com.android.mms",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android",
        "com.snapchat.android", "com.discord", "com.slack", "com.microsoft.teams", "com.truecaller",
        "com.google.android.apps.tachyon", "com.google.android.gm",
    )

    /** The installed apps to offer, in [KNOWN]'s order, plus the phone's own SMS and phone apps when not listed. */
    fun offered(installed: List<AppEntry>, defaultSms: String? = null, defaultDialer: String? = null): List<AppEntry> {
        val byPkg = installed.associateBy { it.packageName }
        val wanted = (KNOWN + listOfNotNull(defaultSms, defaultDialer)).distinct()
        return wanted.mapNotNull { byPkg[it] }
    }

    fun isPhone(pkg: String, defaultDialer: String? = null) = pkg in DIALERS || pkg == defaultDialer

    /** Ticked at the start: WhatsApp, Messages (or the phone's SMS app) and Phone, only those that are installed. */
    fun preTicked(offered: List<AppEntry>, defaultSms: String? = null, defaultDialer: String? = null): Set<String> {
        val pkgs = offered.map { it.packageName }
        val messages = listOfNotNull(MESSAGES, defaultSms).firstOrNull { it in pkgs }
        val phone = pkgs.firstOrNull { isPhone(it, defaultDialer) }
        return setOfNotNull(WHATSAPP.takeIf { it in pkgs }, messages, phone)
    }

    /**
     * The rows to save for [picked]: a new app row for each picked app that has none yet, and the Phone calls row
     * on (picked) or off (offered but not picked). Apps already in Alerts are left as they are.
     */
    fun apply(rules: List<Rule>, offered: List<AppEntry>, picked: Set<String>, defaultDialer: String? = null): List<Rule> {
        val out = ArrayList<Rule>()
        val phoneOffered = offered.any { isPhone(it.packageName, defaultDialer) }
        val phonePicked = picked.any { isPhone(it, defaultDialer) }
        if (phoneOffered) {
            val calls = rules.firstOrNull { it.id == SimpleRules.PHONE_CALLS } ?: SimpleRules.phoneCalls()
            if (calls.enabled != phonePicked || rules.none { it.id == SimpleRules.PHONE_CALLS }) out += calls.copy(enabled = phonePicked)
        }
        val labels = offered.associate { it.packageName to it.label }
        picked.filterNot { isPhone(it, defaultDialer) }.forEach { pkg ->
            val has = rules.any { SimpleRules.isAppRule(it) && it.packages == listOf(pkg) }
            if (!has) out += SimpleRules.newApp(pkg, labels[pkg] ?: pkg)
        }
        return out
    }
}

/** First run's own memory: finished (never shown again), and "a backup was just restored" (start at the cards). */
object FirstRunPrefs {
    private const val PREFS = "first_run"
    private const val DONE = "done"
    private const val RESTORED = "restored"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun done(ctx: Context): Boolean = prefs(ctx).getBoolean(DONE, false)

    fun markDone(ctx: Context) = prefs(ctx).edit().putBoolean(DONE, true).remove(RESTORED).commit()

    fun restored(ctx: Context): Boolean = prefs(ctx).getBoolean(RESTORED, false)

    /** A backup was restored before First run finished: it comes back at the permission cards after the restart. */
    fun markRestored(ctx: Context) {
        if (!done(ctx)) prefs(ctx).edit().putBoolean(RESTORED, true).commit()
    }

    fun reset(ctx: Context) = prefs(ctx).edit().clear().commit()
}
