package com.dotforge.rules

import com.dotforge.device.ShowFor
import com.dotforge.engine.AlertQueue
import com.dotforge.engine.Until
import com.dotforge.filter.Subject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Alerts step: Show for, Who wins, Quiet, block rule checks, History. */
class AlertsModelTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp")
    private val mom = SimpleRules.newPerson("Mom", listOf("+447700900123"))

    // ---- show for (row 4)

    @Test fun oneShowForControl() {
        assertNull(SimpleRules.showSeconds(wa))
        assertEquals("until you check", SimpleRules.showText(wa))
        val five = SimpleRules.withShowFor(wa, 5)
        assertEquals(Until.TIMEOUT, five.until)
        assertEquals(ShowFor.SECONDS, five.timing?.showFor)
        assertEquals("for 5 s", SimpleRules.showText(five))
        val back = SimpleRules.withRemind(SimpleRules.withShowFor(five, null), 5)
        assertEquals(Until.READ, back.until)
        assertEquals(5, SimpleRules.remindMinutes(back))
        // the alert it posts waits until checked and reminds every 5 min
        val info = NotificationInfo("k", "com.whatsapp", "WhatsApp", "Ann", "hi", 0)
        val a = RuleEngine.alert(back, info, com.dotforge.device.DeviceProfile.Phone3, { null })
        assertEquals(Until.READ, a.until)
        assertEquals(300_000L, a.againMs)
        // switching to seconds drops the reminder and shows for that long
        val ten = RuleEngine.alert(SimpleRules.withShowFor(back, 10), info, com.dotforge.device.DeviceProfile.Phone3, { null })
        assertEquals(Until.TIMEOUT, ten.until)
        assertEquals(10_000L, ten.showMs)
        assertNull(ten.againMs)
    }

    @Test fun dontRepeatWithinAMinuteAndDim() {
        val info = NotificationInfo("k", "com.whatsapp", "WhatsApp", "Ann", "hi", 0)
        val p = com.dotforge.device.DeviceProfile.Phone3
        assertEquals(RuleEngine.NO_REPEAT_MS, RuleEngine.alert(wa.copy(noRepeat = true), info, p, { null }).cooldownMs)
        assertNull(RuleEngine.alert(wa, info, p, { null }).cooldownMs)
        val bright = RuleEngine.alert(wa.copy(glyph = GlyphStyle.ICON, designId = "heart"), info, p, { null }).item.animation
        val dim = RuleEngine.alert(wa.copy(glyph = GlyphStyle.ICON, designId = "heart"), info, p, { null }, dim = true).item.animation
        assertTrue(dim.frames.first().frame.pixels.max() <= bright.frames.first().frame.pixels.max() * RuleEngine.DIM_PERCENT / 100 + 1)
    }

    // ---- who wins (row 49)

    @Test fun peopleBeatAppsAndTheOrderDecidesWithinALevel() {
        val tg = SimpleRules.newApp("org.telegram.messenger", "Telegram")
        val order = listOf(tg.id, wa.id)
        assertTrue(SimpleRules.rank(mom, order) < SimpleRules.rank(tg, order))
        assertTrue(SimpleRules.rank(tg, order) < SimpleRules.rank(wa, order))
        assertEquals(AlertQueue.RANK_CALL, SimpleRules.rank(SimpleRules.phoneCalls(), order))
        val rules = listOf(wa, tg, mom)
        assertEquals(listOf(mom.id, tg.id, wa.id), SimpleRules.whoWins(rules, order).map { it.id })
        val moved = SimpleRules.move(rules, order, wa.id, -1)
        assertEquals(listOf(mom.id, wa.id, tg.id), moved)
        // never across levels: an app can't move above a person
        assertEquals(moved, SimpleRules.move(rules, moved, wa.id, -1))
    }

    @Test fun groupChatsOnlyWhenAsked() {
        fun s(group: Boolean) = Subject(isCall = false, app = "com.whatsapp", sender = "Mom", groupChat = group, at = LocalDateTime.of(2026, 11, 18, 12, 0))
        assertTrue(mom.appliesTo(s(false)))
        assertFalse(mom.appliesTo(s(true)))
        assertTrue(mom.copy(groupChats = true).appliesTo(s(true)))
        // a phone moment never matches a notification
        assertFalse(SimpleRules.moments().first().copy(enabled = true).appliesTo(s(false)))
    }

    // ---- quiet (row 7, row 54)

    private val match = RuleMatch(wa, "WhatsApp")
    private fun c(locked: Boolean = true, screenOn: Boolean = false, dnd: Boolean = false, faceDown: Boolean? = null, h: Int = 12) =
        Conditions(LocalDateTime.of(2026, 11, 18, h, 0), dnd, locked, faceDown, screenOn = screenOn, nowMs = 1_000)

    @Test fun quietSkipsDimsOrLetsThrough() {
        val night = FilterSettings(quiet = QuietHours(enabled = true))
        assertEquals(Verdict.Quiet(match, "Quiet"), Delivery.gate(match, night, c(h = 23), { false }, false))
        assertEquals(Verdict.Show(match, dim = true), Delivery.gate(match, night.copy(quietMode = QuietMode.DIM), c(h = 23), { false }, false))
        assertEquals(Verdict.Show(match), Delivery.gate(match, night, c(h = 23), { true }, false)) // a favourite
        assertEquals(Verdict.Show(match), Delivery.gate(match, night, c(h = 23), { false }, true)) // calling again
        assertEquals(Verdict.Quiet(match, "Quiet"), Delivery.gate(match, night.copy(letFavourites = false), c(h = 23), { true }, false))
        // switched on by hand until later
        assertEquals("Quiet", (Delivery.gate(match, FilterSettings(quietUntil = 5_000), c(), { false }, false) as Verdict.Quiet).reason)
        assertTrue(Delivery.gate(match, FilterSettings(quietUntil = 500), c(), { false }, false) is Verdict.Show)
        assertEquals(QuietReason.BEDTIME, Delivery.quietReason(FilterSettings(), c().copy(bedtime = true)))
    }

    @Test fun screenAndPhoneInUse() {
        val f = FilterSettings()
        assertEquals(QuietReason.IN_USE, (Delivery.gate(match, f, c(locked = false, screenOn = true), { false }, false) as Verdict.Quiet).reason)
        // the lock screen is on, not in use
        assertTrue(Delivery.gate(match, f, c(locked = true, screenOn = true), { false }, false) is Verdict.Show)
        assertEquals(QuietReason.SCREEN_ON, (Delivery.gate(match, f.copy(lightWhen = LightWhen.SCREEN_OFF), c(locked = true, screenOn = true), { false }, false) as Verdict.Quiet).reason)
        assertEquals(4L * 3_600_000L, f.capMs)
        assertNull(f.copy(waitCapHours = 0).capMs)
    }

    // ---- block rules (row 63)

    @Test fun addARuleIsCheckedAndExplained() {
        assertEquals("Type at least 3 digits", SimpleRules.problem(BlockPattern(PatternType.STARTS, "14", setOf(Where.CALLS))))
        assertEquals("Pick where it applies", SimpleRules.problem(BlockPattern(PatternType.STARTS, "140", emptySet())))
        assertEquals("Use digits only", SimpleRules.problem(BlockPattern(PatternType.STARTS, "14a0", setOf(Where.CALLS))))
        assertNull(SimpleRules.problem(BlockPattern(PatternType.STARTS, "+91 140", setOf(Where.CALLS))))
        assertEquals("Words apply to messages, not calls", SimpleRules.problem(BlockPattern(PatternType.WORDS, "loan", setOf(Where.CALLS))))
        assertEquals("Matches +91 140… and 0140…", SimpleRules.normalisation(BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS)), "91"))
        assertEquals("Matches +91 140… and 0140…", SimpleRules.normalisation(BlockPattern(PatternType.STARTS, "+91140", setOf(Where.CALLS)), "91"))
        assertNull(SimpleRules.normalisation(BlockPattern(PatternType.WORDS, "loan", setOf(Where.SMS)), "91"))
        val words = SimpleRules.blockRule(BlockPattern(PatternType.WORDS, "loan, lottery", setOf(Where.SMS)))
        assertEquals(BlockPattern(PatternType.WORDS, "loan, lottery", setOf(Where.SMS)), SimpleRules.pattern(words))
        val s = Subject(isCall = false, app = "com.google.android.apps.messaging", text = "Your LOAN is approved", at = LocalDateTime.of(2026, 11, 18, 12, 0))
        assertTrue(RuleEngine.decide(s, listOf(words)).blocked)
    }

    // ---- history (row 52)

    @Test fun historyKeepsThirtyDaysMigratesTheSpamLogAndUndoesClear() {
        val now = LocalDateTime.of(2026, 11, 18, 9, 14).toInstant(ZoneOffset.UTC).toEpochMilli()
        val day = 86_400_000L
        val old = SpamCatch("old", "Phone", "+44 140", "", now - 40 * day, call = true, number = "+44140", rule = "Starts with 140", why = "Starts with 140")
        val recent = old.copy(key = "new", at = now - day)
        val file = File(tmp.root, "history.json")
        val repo = HistoryRepository(file, legacy = listOf(old, recent)) { now }
        assertEquals(listOf("new"), repo.entries.value.map { it.key })
        assertEquals("Blocked · starts with 140", repo.entries.value.single().line)
        repo.add(HistoryEntry("lit", now, Outcome.LIT, "Chat glyph · 30 s", appLabel = "WhatsApp", sender = "Ann"))
        assertEquals(listOf("lit", "new"), HistoryRepository(file) { now }.entries.value.map { it.key })
        val cleared = repo.clear()
        assertTrue(repo.entries.value.isEmpty())
        repo.restore(cleared)
        assertEquals(2, repo.entries.value.size)
        val rule = SimpleRules.blockRule(BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS))).copy(name = "Starts with 140")
        val count = HistoryRepository.counts(repo.entries.value, rule)
        assertEquals(1, count.blocked)
        assertEquals("Blocked 1 · last yesterday 09:14", HistoryText.count(count, LocalDate.of(2026, 11, 18), ZoneOffset.UTC))
        assertEquals("Nothing blocked yet", HistoryText.count(RuleCount(0, null)))
        assertEquals(1, HistoryRepository.blockedThisWeek(repo.entries.value, now))
        assertEquals(HistoryFilter.BLOCKED, HistoryFilter.of("blocked"))
        assertEquals("Yesterday", HistoryText.dayTitle(now - day, LocalDate.of(2026, 11, 18), ZoneOffset.UTC))
    }
}

class QuietTextTest {
    private fun c(h: Int, m: Int = 0) = Conditions(LocalDateTime.of(2026, 11, 18, h, m), false, true, null, nowMs = 0)

    @Test fun statusLines() {
        assertEquals("Off", QuietText.status(FilterSettings(), c(12)))
        val sched = FilterSettings(quiet = QuietHours(enabled = true, start = 23 * 60, end = 7 * 60))
        assertEquals("Scheduled 23:00", QuietText.status(sched, c(12)))
        assertEquals("On until 07:00", QuietText.status(sched, c(23, 30)))
        assertEquals("On until 13:00", QuietText.status(FilterSettings(quietUntil = 3_600_000L), c(12)))
        assertEquals(3_600_000L, QuietText.untilMs(FilterSettings(), c(12)))
        assertEquals(8 * 3_600_000L, QuietText.untilMs(sched, c(23)))
    }

    @Test fun sentenceWordsAreTheControls() {
        val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp")
        assertEquals(
            "When WhatsApp sends something from everyone, the app icon shows until you check. Sound: app's own sound.",
            AlertSentence.text(wa, "WhatsApp"),
        )
        val words = AlertSentence.parts(wa, "WhatsApp").filterIsInstance<SentencePart.Word>()
        assertEquals(listOf(WordKind.APP, WordKind.WHO, WordKind.GLYPH, WordKind.SHOW_FOR, WordKind.SOUND), words.map { it.kind })
        assertEquals("app icon, change glyph", words[2].spoken)
        val heart = SimpleRules.withShowFor(SimpleRules.withPeople(SimpleRules.withGlyph(wa, GlyphChoice.icon("heart")), listOf("Ann")), 10)
        assertEquals("When WhatsApp sends something from Ann, the Heart glyph shows for 10 s. Sound: app's own sound.", AlertSentence.text(heart, "WhatsApp"))
        assertEquals("Ann · Heart glyph · For 10 s", AlertSentence.summary(heart))
        assertEquals("When a call rings, the Phone glyph shows, then the caller's name scrolls, while it rings.", AlertSentence.text(SimpleRules.phoneCalls(), "Phone"))
        val unlock = SimpleRules.moments().first()
        assertEquals("When you unlock the phone, the Lock glyph shows for 5 s.", AlertSentence.text(unlock, ""))
        assertEquals("Off", AlertSentence.summary(unlock))
    }

    @Test fun advancedWordsChatsAndTimes() {
        var r = SimpleRules.newApp("com.whatsapp", "WhatsApp")
        r = SimpleRules.withWords(r, listOf("urgent", " otp "))
        r = SimpleRules.withChats(r, listOf("Family"))
        r = SimpleRules.withTimes(r, listOf(5, 1, 2, 3, 4), "09:00-18:00")
        assertEquals(listOf("urgent", "otp"), SimpleRules.words(r))
        assertEquals(listOf("Family"), SimpleRules.chats(r))
        assertEquals("Mon–Fri · 09:00–18:00", SimpleRules.timesText(r))
        val at = LocalDateTime.of(2026, 11, 18, 10, 0) // a Wednesday
        fun s(title: String, text: String, time: LocalDateTime = at) = Subject(isCall = false, app = "com.whatsapp", title = title, text = text, at = time)
        assertTrue(RuleEngine.explain(r, s("Family", "URGENT call me")) != null)
        assertNull(RuleEngine.explain(r, s("Work", "urgent")))
        assertNull(RuleEngine.explain(r, s("Family", "urgent", at.withHour(20))))
        assertEquals("Any time", SimpleRules.timesText(SimpleRules.withTimes(r, (1..7).toList(), null)))
        assertEquals(emptyList<String>(), SimpleRules.words(SimpleRules.withWords(r, emptyList())))
    }
}
