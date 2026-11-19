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
    val missingCore: Int get() = missingNames.size

    /** Their names, in the Permissions page's order. */
    val missingNames: List<String>
        get() = listOf(access to "Notification access", screening to "Call screening", contacts to "Contacts", keepRunning to "Keep running")
            .filterNot { it.first }.map { it.second }

    companion object {
        /**
         * "Call screening and Keep running need permission", "Contacts needs permission", or "3 things need
         * permission": names when there are two or fewer. Null when nothing is missing.
         */
        fun needLine(names: List<String>): String? = when (names.size) {
            0 -> null
            1 -> "${names[0]} needs permission"
            2 -> "${names[0]} and ${names[1]} need permission"
            else -> "${names.size} things need permission"
        }
    }
}

/**
 * First run (production design > User journeys): Welcome, the three core permission cards, the optional caller
 * name card, Pick apps, Done. Every step can be skipped; cards already allowed when they come up are passed over.
 */
object FirstRunFlow {
    enum class Step { WELCOME, NOTIFICATIONS, CALLS, KEEP_RUNNING, CALLER_NAME, PICK_APPS, DONE }

    /** The permission cards, in order; the first three are the core ones. */
    val CARDS = listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING, Step.CALLER_NAME)

    /** The core cards: the only ones a restored or existing install sees. */
    val CORE = listOf(Step.NOTIFICATIONS, Step.CALLS, Step.KEEP_RUNNING)

    /**
     * The steps for this phone. The caller name card needs Nothing's Glyph settings, so only a Phone (3) gets it.
     * [resumed] (after a restore, or an install that was already set up before First run existed): only the core
     * cards and Done, never Welcome or Pick apps, so nothing already set up is changed.
     */
    fun order(phone3: Boolean, resumed: Boolean): List<Step> =
        if (resumed) CORE + Step.DONE
        else Step.entries.filter { s -> s != Step.CALLER_NAME || phone3 }

    /**
     * An install that was set up before First run existed: it has rules or designs of its own, settings someone
     * changed, or it was updated rather than freshly installed.
     */
    fun isExisting(ownRules: Boolean, designs: Boolean, settings: Boolean, firstInstallMs: Long, lastUpdateMs: Long): Boolean =
        ownRules || designs || settings || firstInstallMs != lastUpdateMs

    /** True when [step] has nothing left to allow. */
    fun done(step: Step, p: Perms): Boolean = when (step) {
        Step.NOTIFICATIONS -> p.access
        Step.CALLS -> p.calls
        Step.KEEP_RUNNING -> p.keepRunning
        Step.CALLER_NAME -> p.callerName == true
        else -> false
    }

    /** Where First run starts: Welcome, or for a resumed install the first card still to do (Done: nothing left). */
    fun start(order: List<Step>, resumed: Boolean, p: Perms): Step =
        if (resumed) from(0, order, p) else Step.WELCOME

    /** The step after [from] ("Next", "Not now" or Continue), passing over cards that are already allowed. */
    fun next(from: Step, order: List<Step>, p: Perms): Step = from(order.indexOf(from) + 1, order, p)

    private fun from(index: Int, order: List<Step>, p: Perms): Step {
        var i = index.coerceAtLeast(0)
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

/**
 * The Calls card's state: a warning ([warning], shown as a banner), a quieter line under it ([line]), and what the
 * main button does.
 */
data class CallsCard(val warning: String?, val line: String?, val button: String, val action: Action) {
    enum class Action { ASK_ROLE, OPEN_DEFAULT_APPS, ASK_CONTACTS, OPEN_APP_INFO, NEXT }

    companion object {
        /** Android stops showing the role dialog after this many refusals, so the button opens Default apps. */
        const val ROLE_TRIES = 2
        const val SCREENING_OFF = "Call screening is off, so spam calls won't be blocked."
        const val DEFAULT_APPS_STEPS = "Tap Caller ID & spam app, then pick DotForge."
        const val CONTACTS_OFF = "Contacts is off, so callers show as numbers."
        const val CONTACTS_APP_INFO = "Android won't ask again. In App info, tap Permissions, then Contacts."

        /**
         * [roleRefusals]: how often the role dialog was answered No (or [roleMissing]: the phone has none);
         * [contactsBlocked]: Contacts was refused twice, so only App info can turn it on.
         */
        fun of(screening: Boolean, contacts: Boolean, roleRefusals: Int, contactsBlocked: Boolean = false, roleMissing: Boolean = false): CallsCard = when {
            screening && contacts -> CallsCard(null, null, "Next", Action.NEXT)
            // a phone without the dialog: straight to Default apps, with no warning before anything was refused
            !screening && (roleRefusals >= ROLE_TRIES || roleMissing) ->
                CallsCard(SCREENING_OFF.takeIf { roleRefusals > 0 }, DEFAULT_APPS_STEPS, "Open Default apps", Action.OPEN_DEFAULT_APPS)
            !screening && roleRefusals > 0 -> CallsCard(SCREENING_OFF, null, "Try again", Action.ASK_ROLE)
            !screening -> CallsCard(null, "Android asks twice: once to screen calls, then for your contacts.", "Allow", Action.ASK_ROLE)
            contactsBlocked -> CallsCard(CONTACTS_OFF, CONTACTS_APP_INFO, "Open App info", Action.OPEN_APP_INFO)
            else -> CallsCard(null, CONTACTS_OFF, "Allow contacts", Action.ASK_CONTACTS)
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
        // the phone's own SMS app wins over Google Messages
        val messages = listOfNotNull(defaultSms, MESSAGES).firstOrNull { it in pkgs }
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
        val existing = rules.firstOrNull { it.id == SimpleRules.PHONE_CALLS }
        // a Phone calls row someone already changed (its glyph, timing, sound…) is theirs: never touched
        val untouched = existing == null || existing.copy(enabled = true) == SimpleRules.phoneCalls()
        if (phoneOffered && untouched) {
            val calls = existing ?: SimpleRules.phoneCalls()
            if (calls.enabled != phonePicked || existing == null) out += calls.copy(enabled = phonePicked)
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

    private const val MODE = "mode"

    /**
     * Restored, or an install that existed before First run (decided once, at its first start, and kept so a
     * fresh install's own picks never turn it into an "existing" one halfway through).
     */
    fun resumed(ctx: Context): Boolean {
        if (restored(ctx)) return true
        val p = prefs(ctx)
        p.getString(MODE, null)?.let { return it == "resumed" }
        val existing = existing(ctx)
        p.edit().putString(MODE, if (existing) "resumed" else "fresh").commit()
        return existing
    }

    /** See [FirstRunFlow.isExisting]: rules or designs of its own, settings someone changed, or an update. */
    fun existing(ctx: Context): Boolean {
        val presets = RuleEngine.presets().map { it.id }.toSet()
        val ownRules = com.dotforge.AppGraph.rules.rules.value.any { it.id !in presets }
        val designs = runCatching { com.dotforge.AppGraph.designs.designs.value.isNotEmpty() }.getOrDefault(false)
        // files only a person's own choices write (asked-once flags, Home's hidden checklist, a backup)
        val settings = listOf("setup", "home").any { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty() } ||
            ctx.getSharedPreferences("backup", Context.MODE_PRIVATE).getLong("last_ms", 0L) > 0L
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        return FirstRunFlow.isExisting(ownRules, designs, settings, info?.firstInstallTime ?: 0L, info?.lastUpdateTime ?: 0L)
    }

    /**
     * Whether First run shows at all. A resumed install with every core permission already allowed has nothing
     * to do: it is marked done without showing anything.
     */
    fun shouldShow(ctx: Context, p: Perms, phone3: Boolean): Boolean {
        if (done(ctx)) return false
        val resumed = resumed(ctx)
        if (resumed && FirstRunFlow.start(FirstRunFlow.order(phone3, resumed = true), resumed = true, p) == FirstRunFlow.Step.DONE) {
            markDone(ctx)
            return false
        }
        return true
    }

    fun reset(ctx: Context) = prefs(ctx).edit().clear().commit()
}
