package com.dotforge.rules

import com.dotforge.device.ShowFor
import com.dotforge.device.TimingOverride
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.Until
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Match
import com.dotforge.filter.Op
import java.util.UUID

/** "Block senders that …": how the text is compared. */
enum class PatternType(val label: String, val chip: String, val op: Op, val numbers: Boolean) {
    STARTS("Start with", "Starts with", Op.STARTS, numbers = true),
    ENDS("End with", "Ends with", Op.ENDS, numbers = true),
    CONTAINS("Contain", "Contains", Op.CONTAINS, numbers = true),
    IS("Are exactly", "Exactly", Op.IS, numbers = true),
    /** Words in the message (apps only): "contains any of …". */
    WORDS("Mention", "Words", Op.CONTAINS, numbers = false),
}

/** Where a block rule applies, as chips, with what blocking means there (row 61). */
enum class Where(val label: String, val means: String) {
    CALLS("Calls", "Silenced: it doesn't ring. Still in your call log. Add a rule can decline it instead."),
    SMS("SMS", "Notification hidden. The text stays in Messages."),
    WHATSAPP("WhatsApp", "Notification dismissed. The message stays in the app."),
    ANY_APP("Other apps", "Notification dismissed. The message stays in the app."),
}

/**
 * A simple block rule: senders that [type] [text], in [where] (and in [apps], app packages from an app's
 * sheet that no chip covers).
 */
data class BlockPattern(val type: PatternType, val text: String, val where: Set<Where>, val apps: List<String> = emptyList()) {
    val label: String get() = if (type == PatternType.WORDS) "Mentions “$text”" else "${type.chip} $text"
}

/** The glyph a simple sheet picks: a library glyph ([GlyphStyle.ICON] + id), a design, or another style. */
data class GlyphChoice(val style: GlyphStyle, val id: String? = null) {
    companion object {
        fun icon(id: String) = GlyphChoice(GlyphStyle.ICON, id)
        fun of(r: Rule) = GlyphChoice(r.glyph, r.designId.takeIf { r.glyph == GlyphStyle.ICON || r.glyph == GlyphStyle.DESIGN })
    }
}

/**
 * What the simple Alerts sheets show and write. Every sheet writes a normal [Rule] the filter engine runs, so
 * the full editor (Advanced) can open the same rule.
 * - An app: a [Action.SHOW] rule for the app's package, optionally only from senders picked by name.
 * - A person: a [Action.SHOW] rule for any app whose sender is the person (by name or number).
 * - A block rule: a [Action.SPAM] rule whose sender or number starts with, ends with, contains or is the text.
 */
object SimpleRules {
    val SMS_APPS = listOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.messaging", "com.android.mms",
    )
    val WHATSAPP_APPS = listOf("com.whatsapp", "com.whatsapp.w4b")
    const val PERSON_PREFIX = "person-"
    const val BLOCK_PREFIX = "block-"

    // ---- apps ----

    /** A new app row: the app's icon in dots until you check, from everyone, with the app's own sound. */
    fun newApp(pkg: String, label: String, glyph: GlyphChoice = GlyphChoice(GlyphStyle.APP_ICON)) = Rule(
        id = UUID.randomUUID().toString(),
        name = label,
        source = Source.APPS,
        packages = listOf(pkg),
        action = Action.SHOW,
        glyph = glyph.style,
        designId = glyph.id,
        until = Until.READ,
    )

    /** A rule shown as a row under Apps: anything that shows a glyph and isn't a person's, a moment or Phone calls. */
    fun isAppRule(r: Rule) = r.action == Action.SHOW && !isPersonRule(r) && r.moment == null && r.id != PHONE_CALLS && r.id != REMINDERS

    // ---- phone calls, reminders and phone moments ----

    /** The "Phone calls" row at the top of Apps (it was the "Calls" preset): the caller's name and a glyph. */
    const val PHONE_CALLS = "preset-calls"
    const val REMINDERS = "preset-reminders"
    const val MOMENT_PREFIX = "moment-"

    /** Apps that remind you (Reminders & to-dos); any app can be added on the sheet. */
    val REMINDER_APPS = listOf(
        "com.google.android.apps.tasks", "com.google.android.keep", "com.google.android.calendar", "com.microsoft.todos",
        "com.ticktick.task", "com.todoist", "com.samsung.android.app.reminder", "com.nothing.ntessentialspace",
    )
    val REMINDER_CATEGORIES = listOf("reminder", "event")

    fun phoneCalls() = Rule(
        id = PHONE_CALLS, name = "Phone calls", packages = RuleEngine.CALL_APPS, glyph = GlyphStyle.ICON, designId = "phone",
        until = Until.READ, callShow = CallShow.BOTH,
    )

    fun reminders() = Rule(
        id = REMINDERS, name = "Reminders & to-dos", enabled = false, packages = emptyList(),
        match = Match(
            all = false,
            conditions = listOf(Condition(Field.CATEGORY, Op.IS, REMINDER_CATEGORIES), Condition(Field.APP, Op.IS, REMINDER_APPS)),
        ),
        glyph = GlyphStyle.BELL, until = Until.READ,
    )

    /** The five phone moments, all off: each plays its design once, or while it lasts. */
    fun moments() = Moment.entries.map { m ->
        Rule(
            id = MOMENT_PREFIX + m.name.lowercase(), name = m.label, enabled = false, moment = m,
            glyph = GlyphStyle.ICON, designId = MOMENT_GLYPHS.getValue(m), until = if (m.lasts) Until.UNLOCK else Until.TIMEOUT,
        )
    }

    private val MOMENT_GLYPHS = mapOf(
        Moment.UNLOCK to "lock", Moment.SHAKE to "wave", Moment.CAMERA to "camera", Moment.ALARM to "alarm",
        Moment.HEADPHONES to "music",
    )

    /** Extra apps the reminders rule covers beyond the built-in list. */
    fun reminderApps(r: Rule): List<String> =
        r.match.conditions.firstOrNull { it.field == Field.APP }?.cleanValues.orEmpty()

    fun withReminderApps(r: Rule, apps: List<String>): Rule = r.copy(
        match = r.match.copy(
            all = false,
            conditions = r.match.conditions.filterNot { it.field == Field.APP } + Condition(Field.APP, Op.IS, apps.distinct()),
        ),
    )

    // ---- Advanced (row 53): words, chats, times ----

    private fun cond(r: Rule, field: Field) = r.match.conditions.firstOrNull { it.field == field && !it.not && it.list == null }

    private fun withCond(r: Rule, field: Field, c: Condition?): Rule {
        val rest = r.match.conditions.filterNot { it.field == field && !it.not && it.list == null }
        return r.copy(match = r.match.copy(all = true, conditions = rest + listOfNotNull(c)))
    }

    /** "contains any of …" words; [wordsPattern] when they are one pattern ("Use pattern"). */
    fun words(r: Rule): List<String> = cond(r, Field.MESSAGE)?.cleanValues.orEmpty()
    fun wordsPattern(r: Rule): Boolean = cond(r, Field.MESSAGE)?.op == Op.REGEX

    fun withWords(r: Rule, words: List<String>, pattern: Boolean = false): Rule {
        val clean = words.map { it.trim() }.filter { it.isNotEmpty() }
        return withCond(r, Field.MESSAGE, clean.takeIf { it.isNotEmpty() }?.let { Condition(Field.MESSAGE, if (pattern) Op.REGEX else Op.CONTAINS, it) })
    }

    /** Only these chats: the chat (or group) names as the app shows them in the title. */
    fun chats(r: Rule): List<String> = cond(r, Field.TITLE)?.cleanValues.orEmpty()

    fun withChats(r: Rule, chats: List<String>): Rule {
        val clean = chats.map { it.trim() }.filter { it.isNotEmpty() }
        return withCond(r, Field.TITLE, clean.takeIf { it.isNotEmpty() }?.let { Condition(Field.TITLE, Op.CONTAINS, it) })
    }

    /** Days (1 = Monday; empty = every day) and a "09:00-18:00" range (null = all day). */
    fun days(r: Rule): List<Int> = cond(r, Field.DAY)?.cleanValues?.mapNotNull { it.toIntOrNull() }.orEmpty()
    fun hours(r: Rule): String? = cond(r, Field.TIME)?.cleanValues?.firstOrNull()

    fun withTimes(r: Rule, days: List<Int>, hours: String?): Rule {
        val d = days.distinct().sorted().takeIf { it.isNotEmpty() && it.size < 7 }?.let { Condition(Field.DAY, Op.IS, it.map(Int::toString)) }
        return withCond(withCond(r, Field.DAY, d), Field.TIME, hours?.let { Condition(Field.TIME, Op.IS, listOf(it)) })
    }

    /** Only while the phone is locked (not while it's in use): a "phone locked" condition. */
    fun locked(r: Rule): Boolean = cond(r, Field.LOCKED)?.cleanValues?.firstOrNull()?.lowercase() == "yes"

    fun withLocked(r: Rule, on: Boolean): Rule = withCond(r, Field.LOCKED, Condition.flag(Field.LOCKED).takeIf { on })

    /** The When row: "Any time", "Mon–Fri · 09:00–18:00", "Any time · Only when locked". */
    fun whenText(r: Rule): String = listOfNotNull(timesText(r), "Only when locked".takeIf { locked(r) }).joinToString(" · ")

    /** "Any time", "Mon–Fri", "09:00–18:00", "Mon–Fri · 09:00–18:00". */
    fun timesText(r: Rule): String {
        val d = days(r)
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayText = when {
            d.isEmpty() -> null
            d == listOf(1, 2, 3, 4, 5) -> "Mon–Fri"
            d == listOf(6, 7) -> "Weekends"
            else -> d.joinToString(", ") { names[it - 1] }
        }
        return listOfNotNull(dayText, hours(r)?.replace("-", "–")).joinToString(" · ").ifEmpty { "Any time" }
    }

    // ---- show for (row 4): one control ----

    /** Seconds the glyph shows, or null for "until you check". */
    fun showSeconds(r: Rule): Int? = when {
        r.until != Until.TIMEOUT -> null
        r.timing?.showFor == ShowFor.SECONDS -> r.timing.seconds ?: 5
        else -> 5
    }

    /** "5 s", "10 s", "30 s", or until you check (null), keeping the rest of the timing. */
    fun withShowFor(r: Rule, seconds: Int?): Rule {
        val t = r.timing ?: TimingOverride()
        return if (seconds == null) r.copy(until = if (r.until == Until.UNLOCK) Until.UNLOCK else Until.READ, timing = t.copy(showFor = ShowFor.UNTIL_CHECKED, seconds = null))
        else r.copy(until = Until.TIMEOUT, timing = t.copy(showFor = ShowFor.SECONDS, seconds = seconds, remindMinutes = null))
    }

    /** "Remind me" for an alert that waits: every 1, 5 or 15 min; 0 = off. */
    fun remindMinutes(r: Rule): Int = r.timing?.remindMinutes ?: 0

    fun withRemind(r: Rule, minutes: Int): Rule = r.copy(timing = (r.timing ?: TimingOverride()).copy(remindMinutes = minutes.takeIf { it > 0 }))

    /** The sentence's last word: "until you check", "for 5 s", "while it lasts". */
    fun showText(r: Rule): String = when {
        r.moment?.lasts == true && r.until != Until.TIMEOUT -> "while it lasts"
        r.until == Until.UNLOCK -> "until you unlock"
        r.until == Until.READ -> "until you check"
        else -> "for ${showSeconds(r)} s"
    }

    // ---- who wins (row 49) ----

    /** The queue rank of [r]'s alerts: calls, then people, then apps; within a level, the Who wins order. */
    fun rank(r: Rule, order: List<String>, isCall: Boolean = false): Int {
        if (isCall || r.id == PHONE_CALLS) return AlertQueue.RANK_CALL
        val i = order.indexOf(r.id).let { if (it < 0) order.size + 1 else it }
        return (if (isPersonRule(r)) AlertQueue.RANK_PERSON else AlertQueue.RANK_APP) + i.coerceAtMost(999)
    }

    /** [ids] (people first, then apps) in the saved Who wins order, new ones at the end of their level. */
    fun whoWins(rules: List<Rule>, order: List<String>): List<Rule> {
        val showing = rules.filter { isPersonRule(it) || isAppRule(it) }
        return showing.sortedWith(compareBy({ if (isPersonRule(it)) 0 else 1 }, { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
    }

    /** [order] with [id] moved one place up (-1) or down (+1) within its level. */
    fun move(rules: List<Rule>, order: List<String>, id: String, by: Int): List<String> {
        val list = whoWins(rules, order).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices || isPersonRule(list[i]) != isPersonRule(list[j])) return list.map { it.id }
        list.add(j, list.removeAt(i))
        return list.map { it.id }
    }

    /** True when the app sheet's fields describe [r] fully (otherwise Advanced has more, and the sheet says so). */
    fun isSimpleApp(r: Rule): Boolean =
        isAppRule(r) && r.source == Source.APPS && r.packages.isNotEmpty() && r.match.groups.isEmpty() &&
            r.match.conditions.all { it == peopleCondition(r) }

    private fun peopleCondition(r: Rule): Condition? =
        r.match.conditions.firstOrNull { it.field == Field.SENDER && it.op == Op.IS && !it.not && it.list == null }

    /** Senders an app rule is limited to; empty = everyone. */
    fun people(r: Rule): List<String> = peopleCondition(r)?.cleanValues.orEmpty()

    /** Everyone ([names] empty) or only the senders named; other conditions stay. */
    fun withPeople(r: Rule, names: List<String>): Rule {
        val rest = r.match.conditions.filterNot { it == peopleCondition(r) }
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val conditions = if (clean.isEmpty()) rest else listOf(Condition(Field.SENDER, Op.IS, clean)) + rest
        return r.copy(match = r.match.copy(all = true, conditions = conditions))
    }

    /** "Stay on until I check": the glyph stays until the notification is read, else a few seconds. */
    fun staysOn(r: Rule) = r.until != Until.TIMEOUT

    fun withStays(r: Rule, on: Boolean) = r.copy(until = if (on) Until.READ else Until.TIMEOUT)

    fun withGlyph(r: Rule, g: GlyphChoice) = r.copy(glyph = g.style, designId = g.id)

    fun withSound(r: Rule, uri: String?, name: String?) = r.copy(soundUri = uri, soundName = name?.takeIf { uri != null })

    /** "Everyone · Chat · Stays on · 1 blocked", or "Off". */
    fun summary(r: Rule, glyphName: String, blocked: Int): String {
        if (!r.enabled) return "Off"
        val who = if (people(r).isEmpty()) "Everyone" else if (people(r).size == 1) people(r).first() else "${people(r).size} people"
        return listOfNotNull(who, glyphName, "Stays on".takeIf { staysOn(r) }, "$blocked blocked".takeIf { blocked > 0 }).joinToString(" · ")
    }

    // ---- people ----

    /** A person lights up from any app, by name (as apps show it) or by any of their [numbers]. */
    fun newPerson(name: String, numbers: List<String> = emptyList(), glyph: GlyphChoice = GlyphChoice.icon("heart")) = Rule(
        id = PERSON_PREFIX + UUID.randomUUID(),
        name = name,
        source = Source.APPS,
        match = personMatch(name, numbers),
        action = Action.SHOW,
        glyph = glyph.style,
        designId = glyph.id,
        until = Until.READ,
    )

    private fun personMatch(name: String, numbers: List<String>) = Match(
        all = false,
        conditions = listOf(Condition(Field.SENDER, Op.IS, listOf(name.trim()))) +
            listOfNotNull(numbers.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { Condition(Field.NUMBER, Op.IS, it) }),
    )

    fun isPersonRule(r: Rule) = r.action == Action.SHOW && r.id.startsWith(PERSON_PREFIX)

    /**
     * A Contacts group ("Family", "Work"): one glyph for everyone in it, from any app. It lists with People;
     * a person named exactly still wins over their group ([RuleEngine.tier]).
     */
    const val GROUP_PREFIX = PERSON_PREFIX + "group-"

    fun newGroup(title: String, glyph: GlyphChoice = GlyphChoice.icon("people")) = Rule(
        id = GROUP_PREFIX + UUID.randomUUID(),
        name = title.trim(),
        source = Source.APPS,
        match = Match(conditions = listOf(Condition(Field.CONTACT_GROUP, Op.IS, listOf(title.trim())))),
        action = Action.SHOW,
        glyph = glyph.style,
        designId = glyph.id,
        until = Until.READ,
    )

    fun isGroupRule(r: Rule) = r.action == Action.SHOW && r.id.startsWith(GROUP_PREFIX)

    fun personNumbers(r: Rule): List<String> = r.match.conditions.firstOrNull { it.field == Field.NUMBER }?.cleanValues.orEmpty()

    fun renamePerson(r: Rule, name: String, numbers: List<String> = personNumbers(r)) = r.copy(name = name, match = personMatch(name, numbers))

    // ---- block rules ----

    /** Why [p] can't be added yet ("Type at least 3 digits"), or null when it is valid. */
    fun problem(p: BlockPattern): String? {
        val t = p.text.trim()
        return when {
            p.where.isEmpty() -> "Pick where it applies"
            p.type == PatternType.WORDS && t.length < 3 -> "Type at least 3 letters"
            p.type == PatternType.WORDS && Where.CALLS in p.where && p.where.size == 1 -> "Words apply to messages, not calls"
            // "Exactly" also takes a name, like AX-LOANS or a group's name
            p.type == PatternType.IS && t.any(Char::isLetter) -> if (t.length < 2) "Type at least 2 letters" else null
            p.type != PatternType.WORDS && t.count(Char::isDigit) < 3 -> "Type at least 3 digits"
            p.type != PatternType.WORDS && t.any { !it.isDigit() && it !in "+ -()" } -> "Use digits only"
            else -> null
        }
    }

    /**
     * What a number pattern matches once numbers are normalised: "Matches +91 140… and 0140…" for a prefix
     * in a region with calling code [code].
     */
    fun normalisation(p: BlockPattern, code: String?): String? {
        if (p.type == PatternType.WORDS) return null
        val digits = p.text.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) < 3) return null
        val local = digits.removePrefix("+").let { d -> if (code != null && digits.startsWith("+$code")) d.removePrefix(code) else d.removePrefix("0") }
        return when (p.type) {
            PatternType.STARTS -> if (code != null) "Matches +$code $local… and 0$local…" else "Matches numbers starting $digits…"
            PatternType.ENDS -> "Matches any number ending …$local"
            PatternType.CONTAINS -> "Matches any number with $local in it"
            else -> if (code != null) "Matches +$code $local and 0$local" else "Matches $digits only"
        }
    }

    /**
     * A rule that marks senders matching [p] as spam, and only when [more] (More conditions) is true too. Its
     * name says both: "Mentions “loan”, sender isn't in my contacts".
     */
    fun blockRule(p: BlockPattern, id: String = BLOCK_PREFIX + UUID.randomUUID(), more: com.dotforge.filter.Group = com.dotforge.filter.Group()): Rule {
        val calls = Where.CALLS in p.where
        val anyApp = Where.ANY_APP in p.where
        val packages = if (anyApp) emptyList() else buildList {
            if (Where.SMS in p.where) addAll(SMS_APPS)
            if (Where.WHATSAPP in p.where) addAll(WHATSAPP_APPS)
            addAll(p.apps.filterNot { it in this })
        }
        val apps = anyApp || packages.isNotEmpty()
        val source = when {
            calls && apps -> Source.EVERYTHING
            apps -> Source.APPS
            else -> Source.CALLS
        }
        val text = p.text.trim()
        val match = if (p.type == PatternType.WORDS) {
            Match(all = false, conditions = listOf(Condition(Field.MESSAGE, Op.CONTAINS, text.split(',').map { it.trim() }.filter { it.isNotEmpty() })))
        } else Match(
            all = false,
            conditions = listOf(Condition(Field.SENDER, p.type.op, listOf(text)), Condition(Field.NUMBER, p.type.op, listOf(text))) +
                // "Exactly" in apps also matches a chat or group by its name
                listOfNotNull(Condition(Field.TITLE, Op.IS, listOf(text)).takeIf { p.type == PatternType.IS && source != Source.CALLS }),
        )
        val rule = Rule(
            id = id,
            name = p.label,
            source = if (p.type == PatternType.WORDS && source == Source.EVERYTHING) Source.APPS else source,
            packages = if (source == Source.CALLS) emptyList() else packages,
            match = match,
            action = Action.SPAM,
        )
        val extra = MoreConditions.with(rule, more)
        val g = MoreConditions.of(extra)
        return if (g.conditions.isEmpty()) extra
        else extra.copy(name = p.label + ", " + MoreConditions.summary(g).replaceFirstChar { it.lowercase() })
    }

    /** The simple block pattern [r] was written as, or null when it is something else. */
    fun pattern(r: Rule): BlockPattern? {
        // a block rule with More conditions keeps its own sender or number match apart from them
        val m = MoreConditions.base(r)
        if (r.action != Action.SPAM || m.groups.isNotEmpty() || m.all) return null
        val cs = m.conditions
        if (r.id.startsWith(BLOCK_PREFIX) && cs.size == 1 && cs[0].field == Field.MESSAGE && cs[0].op == Op.CONTAINS && !cs[0].not && cs[0].list == null) {
            val where = mutableSetOf<Where>()
            if (r.packages.isEmpty()) where += Where.ANY_APP
            if (r.packages.containsAll(SMS_APPS)) where += Where.SMS
            if (r.packages.containsAll(WHATSAPP_APPS)) where += Where.WHATSAPP
            return BlockPattern(PatternType.WORDS, cs[0].cleanValues.joinToString(", "), where)
        }
        if (cs.size !in 2..3 || cs.any { it.not || it.list != null }) return null
        val type = PatternType.entries.firstOrNull { it.op == cs[0].op } ?: return null
        val text = cs[0].cleanValues.singleOrNull() ?: return null
        val fields = cs.map { it.field }.toSet()
        val expected = if (cs.size == 3) setOf(Field.SENDER, Field.NUMBER, Field.TITLE) else setOf(Field.SENDER, Field.NUMBER)
        if (fields != expected || (cs.size == 3 && type != PatternType.IS) || cs.any { it.op != cs[0].op || it.cleanValues != listOf(text) }) return null
        val where = mutableSetOf<Where>()
        if (r.source != Source.APPS) where += Where.CALLS
        val others = mutableListOf<String>()
        if (r.source != Source.CALLS) {
            if (r.packages.isEmpty()) where += Where.ANY_APP
            if (r.packages.containsAll(SMS_APPS)) where += Where.SMS
            if (r.packages.containsAll(WHATSAPP_APPS)) where += Where.WHATSAPP
            others += r.packages.filter { pkg ->
                !(Where.SMS in where && pkg in SMS_APPS) && !(Where.WHATSAPP in where && pkg in WHATSAPP_APPS)
            }
        }
        return BlockPattern(type, text, where, others)
    }

    /** The chips a block made on [pkg]'s own sheet gets: WhatsApp's or SMS's chip, else the app itself. */
    fun blockPlace(pkg: String): Pair<Set<Where>, List<String>> = when (pkg) {
        in WHATSAPP_APPS -> setOf(Where.WHATSAPP) to emptyList()
        in SMS_APPS -> setOf(Where.SMS) to emptyList()
        else -> emptySet<Where>() to listOf(pkg)
    }

    /** Blocks [who] (a sender, a number or a group's name) in [pkg]: an "Exactly" rule with the app's chip. */
    fun blockSender(pkg: String, who: String): Rule {
        val (where, apps) = blockPlace(pkg)
        return blockRule(BlockPattern(PatternType.IS, who.trim(), where, apps))
    }

    /** True when [p] applies in [pkg]. */
    fun covers(p: BlockPattern, pkg: String): Boolean =
        Where.ANY_APP in p.where || pkg in p.apps || (pkg in WHATSAPP_APPS && Where.WHATSAPP in p.where) || (pkg in SMS_APPS && Where.SMS in p.where)

    /** The "Exactly [who]" rule that blocks [who] in [pkg], if there is one. */
    fun senderBlock(rules: List<Rule>, pkg: String, who: String): Rule? = rules.firstOrNull { r ->
        val p = pattern(r)
        p != null && p.type == PatternType.IS && p.text.equals(who.trim(), ignoreCase = true) && covers(p, pkg)
    }

    /** Block rules that apply to the app [pkg] on its own sheet ("Block senders in WhatsApp"). */
    fun appBlocks(rules: List<Rule>, pkg: String): List<Rule> =
        rules.filter { r -> pattern(r) != null && r.source != Source.CALLS && (r.packages.isEmpty() || pkg in r.packages) && r.packages.isNotEmpty() }

    /** "Calls, SMS", or the app names for packages no chip covers. */
    fun whereText(p: BlockPattern, label: (String) -> String): String =
        (Where.entries.filter { it in p.where }.map { it.label } + p.apps.map(label)).joinToString(", ").ifEmpty { "Nowhere" }
}
