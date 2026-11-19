package com.dotforge.rules

import com.dotforge.calls.CallActions
import com.dotforge.calls.CallSettings
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import com.dotforge.device.ShowFor
import com.dotforge.device.TimingOverride
import com.dotforge.engine.Until
import com.dotforge.filter.Caller
import com.dotforge.filter.PhoneState
import com.dotforge.filter.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The features that moved out of Old screens: the When row (only when locked), the missed call glyph's
 * repeat, a Contacts group against a person, and what a block rule does to a call.
 */
class OldScreensHomesTest {
    private val wa = SimpleRules.newApp("com.whatsapp", "WhatsApp")

    private fun msg(sender: String, groups: Set<String> = emptySet(), locked: Boolean? = null, contact: Boolean = true) = Subject(
        isCall = false, app = "com.whatsapp", appLabel = "WhatsApp", sender = sender, title = sender, text = "hi", fromPerson = true,
        contact = { Caller(contact, name = sender, groups = groups) }, state = { PhoneState(locked = locked) },
    )

    // ---------------------------------------------------------------- the When row

    @Test fun onlyWhenLockedLightsOnlyWhileLocked() {
        val r = SimpleRules.withLocked(wa, true)
        assertTrue(SimpleRules.locked(r))
        assertNotNull(RuleEngine.explain(r, msg("Mom", locked = true)))
        assertNull(RuleEngine.explain(r, msg("Mom", locked = false)))
        // and it sits with the days and hours on one row
        val timed = SimpleRules.withTimes(r, listOf(1, 2, 3, 4, 5), "09:00-18:00")
        assertEquals("Mon–Fri · 09:00–18:00 · Only when locked", SimpleRules.whenText(timed))
        assertEquals("Any time", SimpleRules.whenText(wa))
        assertFalse(SimpleRules.locked(SimpleRules.withLocked(timed, false)))
        assertEquals("Mon–Fri · 09:00–18:00", SimpleRules.whenText(SimpleRules.withLocked(timed, false)))
    }

    @Test fun faceDownIsQuietForEveryRuleSoTheWhenRowHasNoSwitchForIt() {
        // the firmware hides app lights face down, so "Only when face down" could never light; face down
        // quiets every rule, locked or not, and turning the phone over lets it through
        val r = SimpleRules.withLocked(wa, true)
        val match = RuleMatch(r, "WhatsApp")
        fun gate(faceDown: Boolean?) = Delivery.gate(
            match, FilterSettings(), Conditions(java.time.LocalDateTime.of(2026, 11, 16, 12, 0), dnd = false, locked = true, faceDown = faceDown),
            starred = { false }, repeated = false,
        )
        assertEquals(Verdict.Quiet(match, QuietReason.FACE_DOWN), gate(true))
        assertEquals(Verdict.Show(match), gate(false))
        assertEquals(Verdict.Quiet(RuleMatch(wa, "WhatsApp"), QuietReason.FACE_DOWN), Delivery.gate(
            RuleMatch(wa, "WhatsApp"), FilterSettings(), Conditions(java.time.LocalDateTime.of(2026, 11, 16, 12, 0), dnd = false, locked = false, faceDown = true, screenOn = false),
            starred = { false }, repeated = false,
        ))
    }

    // ---------------------------------------------------------------- the missed call glyph

    private val info = NotificationInfo("k", "com.google.android.dialer", "Phone", "Mom", "Missed call", 0, sender = "Mom")
    private val profile = DeviceProfile.Phone3

    @Test fun aMissedCallWaitsUntilCheckedAndLightsAgainEvery5Min() {
        val rule = CallActions.missedRule(CallSettings(missedGlyph = true))
        assertEquals(Until.READ, rule.until)
        var builtIn = false
        val a = RuleEngine.alert(rule, info, profile, design = { id -> builtIn = id == CallActions.BUILT_IN; Effects.missedCall(profile) }, rank = com.dotforge.engine.AlertQueue.RANK_CALL)
        assertTrue(builtIn)
        assertEquals(Until.READ, a.until)
        assertEquals(5 * 60_000L, a.againMs)
        assertEquals(com.dotforge.engine.AlertQueue.RANK_CALL, a.rank)
    }

    @Test fun theMissedCallUsesTheSharedTimingAndThePickedGlyph() {
        val every1 = CallSettings(missedGlyph = true, missedStyle = GlyphStyle.ICON, missedDesign = "phone", missedTiming = TimingOverride(showFor = ShowFor.UNTIL_CHECKED, remindMinutes = 1))
        val rule = CallActions.missedRule(every1)
        assertEquals(GlyphStyle.ICON, rule.glyph)
        assertEquals("phone", rule.designId)
        assertEquals(60_000L, RuleEngine.alert(rule, info, profile, design = { null }).againMs)
        val once = every1.copy(missedTiming = TimingOverride(showFor = ShowFor.UNTIL_CHECKED, remindMinutes = 0))
        assertNull(RuleEngine.alert(CallActions.missedRule(once), info, profile, design = { null }).againMs)
    }

    // ---------------------------------------------------------------- a Contacts group against a person

    @Test fun aPersonBeatsTheirGroupAndAGroupBeatsTheApp() {
        val family = SimpleRules.newGroup("Family")
        val mom = SimpleRules.newPerson("Mom")
        assertTrue(SimpleRules.isGroupRule(family))
        assertTrue(SimpleRules.isPersonRule(family))
        assertFalse(SimpleRules.isAppRule(family))
        val rules = listOf(wa, family, mom)
        fun shown(s: Subject) = RuleEngine.decide(s, rules).show?.rule?.id
        assertEquals(mom.id, shown(msg("Mom", setOf("Family"))))
        assertEquals(family.id, shown(msg("Dad", setOf("Family"))))
        assertEquals(wa.id, shown(msg("Sam", setOf("Work"))))
        // the order doesn't matter: listed first, the group still loses to Mom
        assertEquals(mom.id, RuleEngine.decide(msg("Mom", setOf("Family")), listOf(family, wa, mom)).show?.rule?.id)
        // without Contacts nobody is in a group
        assertEquals(wa.id, RuleEngine.decide(Subject(isCall = false, app = "com.whatsapp", sender = "Dad", fromPerson = true), rules).show?.rule?.id)
    }

    // ---------------------------------------------------------------- what a block rule does to a call

    private val starts140 = BlockPattern(PatternType.STARTS, "140", setOf(Where.CALLS, Where.SMS))

    @Test fun aBlockRuleSilencesDeclinesOrDeclinesWithoutALogEntry() {
        val r = SimpleRules.blockRule(starts140, id = "block-140")
        assertEquals(Action.SPAM, CallActions.of(r))
        assertEquals(CallActions.Flags(silence = true), CallActions.flags(CallActions.of(r)))
        val decline = r.copy(callAction = Action.REJECT)
        assertEquals(Action.REJECT, CallActions.of(decline))
        assertEquals(CallActions.Flags(disallow = true, reject = true), CallActions.flags(Action.REJECT))
        val quiet = r.copy(callAction = Action.REJECT_QUIET)
        assertEquals(CallActions.Flags(disallow = true, reject = true, skipLog = true, skipNotification = true), CallActions.flags(CallActions.of(quiet)))
        // a message is still hidden as spam, and the rule still reads as a simple block rule
        val sms = Subject(isCall = false, app = "com.google.android.apps.messaging", sender = "+911405550101", number = "+911405550101", fromPerson = true, region = "IN")
        assertTrue(RuleEngine.decide(sms, listOf(decline)).blocked)
        assertEquals(starts140, SimpleRules.pattern(decline))
        val call = Subject(isCall = true, number = "+911405550101", fromPerson = true, region = "IN")
        assertEquals(decline.id, RuleEngine.decide(call, listOf(decline)).gate?.rule?.id)
        // another rule's own action is what a call gets
        assertEquals(Action.SILENCE, CallActions.of(RuleEngine.presets().first { it.id == RuleEngine.HIDDEN_CALLS }))
        assertEquals(Action.REJECT, CallActions.of(RuleEngine.presets().first { it.id == RuleEngine.FAKED_CALLS }))
        assertEquals(CallActions.Flags(), CallActions.flags(Action.ALLOW))
    }
}
