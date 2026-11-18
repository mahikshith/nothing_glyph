package com.dotforge.rules

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private fun note(pkg: String = "com.example", title: String = "Alex", text: String = "hello") =
        NotificationInfo("k", pkg, "Example", title, text, 0)

    private fun matched(info: NotificationInfo, vararg rules: Rule): Rule? = when (val m = RuleEngine.match(info, rules.toList())) {
        is MatchResult.Show -> m.rule
        is MatchResult.Suppressed -> m.rule
        MatchResult.NoMatch -> null
    }

    @Test fun emptyRuleMatchesEverything() {
        val r = Rule(name = "all")
        assertSame(r, matched(note(), r))
    }

    @Test fun packageFilterIsCaseInsensitive() {
        val r = Rule(packages = listOf("COM.WhatsApp"))
        assertSame(r, matched(note(pkg = "com.whatsapp"), r))
        assertEquals(null, matched(note(pkg = "org.other"), r))
    }

    @Test fun senderMatchesTitleSubstring() {
        val r = Rule(senders = listOf("mum"))
        assertSame(r, matched(note(title = "Mum (2 messages)"), r))
        assertEquals(null, matched(note(title = "Bob"), r))
    }

    @Test fun anyModeNeedsOneKeyword() {
        val r = Rule(keywords = listOf("urgent", "asap"), keywordMode = KeywordMode.ANY)
        assertSame(r, matched(note(text = "please reply ASAP"), r))
        assertEquals(null, matched(note(text = "whenever"), r))
    }

    @Test fun allModeNeedsEveryKeywordAcrossTitleAndText() {
        val r = Rule(keywords = listOf("server", "down"), keywordMode = KeywordMode.ALL)
        assertSame(r, matched(note(title = "Server alert", text = "prod is down"), r))
        assertEquals(null, matched(note(title = "Server alert", text = "all fine"), r))
    }

    @Test fun noneModeRejectsAnyKeyword() {
        val r = Rule(keywords = listOf("promo", "sale"), keywordMode = KeywordMode.NONE)
        assertSame(r, matched(note(text = "your parcel arrived"), r))
        assertEquals(null, matched(note(text = "Big SALE today"), r))
    }

    @Test fun regexModeMatchesAndInvalidPatternNeverMatches() {
        val r = Rule(keywords = listOf("""\b\d{6}\b"""), keywordMode = KeywordMode.REGEX)
        assertSame(r, matched(note(text = "Your code is 123456"), r))
        assertEquals(null, matched(note(text = "Your code is 12345"), r))
        val broken = Rule(keywords = listOf("(unclosed"), keywordMode = KeywordMode.REGEX)
        assertEquals(null, matched(note(text = "(unclosed"), broken))
    }

    @Test fun blankKeywordsAreIgnored() {
        val r = Rule(keywords = listOf(" ", ""), keywordMode = KeywordMode.ALL)
        assertSame(r, matched(note(), r))
    }

    @Test fun firstMatchWinsInListOrderAndDisabledRulesAreSkipped() {
        val off = Rule(name = "off", enabled = false)
        val first = Rule(name = "first", keywords = listOf("hello"))
        val second = Rule(name = "second")
        assertSame(first, matched(note(), off, first, second))
        assertSame(second, matched(note(text = "bye"), off, first, second))
        assertSame(second, matched(note(), off, second, first)) // reordering changes the winner
    }

    @Test fun spamRuleSuppressesAndStopsLowerRules() {
        val spam = Rule(name = "spam", keywords = listOf("lottery"), suppress = true)
        val all = Rule(name = "all")
        val m = RuleEngine.match(note(text = "You won the LOTTERY"), listOf(spam, all))
        assertTrue(m is MatchResult.Suppressed && m.rule === spam)
        assertTrue(RuleEngine.match(note(text = "hi"), listOf(spam, all)) == MatchResult.Show(all))
    }

    @Test fun noRulesMeansNoMatch() = assertEquals(MatchResult.NoMatch, RuleEngine.match(note(), emptyList()))

    @Test fun presetsCatchCallsOtpCodesAndMessages() {
        val rules = RuleEngine.presets()
        fun name(n: NotificationInfo) = (RuleEngine.match(n, rules) as? MatchResult.Show)?.rule?.name
        assertEquals(listOf("Calls", "OTP", "Messages"), rules.map { it.name })
        assertEquals("Calls", name(note(pkg = "com.google.android.dialer", text = "Incoming call")))
        assertEquals("OTP", name(note(pkg = "com.google.android.apps.messaging", text = "Your OTP is 482913. Do not share it.")))
        assertEquals("OTP", name(note(text = "482913 is your verification code")))
        assertEquals("Messages", name(note(pkg = "com.whatsapp", text = "See you at 1830")))
        assertEquals(null, name(note(text = "Order 12345678 shipped")))
    }

    @Test fun customEffectFallsBackToPulseWithoutADesign() {
        val p = DeviceProfile.Phone3
        val anim = RuleEngine.animationFor(Rule(effect = Effect.CUSTOM), note(), p, custom = null)
        assertEquals(Effects.pulse(p).frames.size, anim.frames.size)
    }

    @Test fun letterEffectUsesFirstLetterOfSender() {
        val p = DeviceProfile.Phone3
        val anim = RuleEngine.animationFor(Rule(effect = Effect.LETTER), note(title = "  alex"), p, custom = null)
        assertEquals(Effects.letter(p, 'A').pixels.toList(), anim.frames.first().frame.pixels.toList())
    }
}
