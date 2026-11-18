package com.dotforge.rules

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RuleEngineTest {
    private fun note(pkg: String = "com.example", title: String = "Alex", text: String = "hello", person: Boolean = false) =
        NotificationInfo("k", pkg, "Example", title, text, 0, fromPerson = person)

    private fun matched(info: NotificationInfo, vararg rules: Rule, sender: Sender? = null): Rule? =
        RuleEngine.match(info, rules.toList()) { sender }?.rule

    @Test fun emptyRuleMatchesEverything() {
        val r = Rule(name = "all")
        assertSame(r, matched(note(), r))
        assertEquals("any notification", RuleEngine.explain(r, note()))
    }

    @Test fun packageFilterIsCaseInsensitive() {
        val r = Rule(packages = listOf("COM.WhatsApp"))
        assertSame(r, matched(note(pkg = "com.whatsapp"), r))
        assertNull(matched(note(pkg = "org.other"), r))
    }

    @Test fun anyAllAndNoneKeywordModes() {
        val any = Rule(keywords = listOf("urgent", "asap"), keywordMode = KeywordMode.ANY)
        assertSame(any, matched(note(text = "please reply ASAP"), any))
        assertNull(matched(note(text = "whenever"), any))

        val all = Rule(keywords = listOf("server", "down"), keywordMode = KeywordMode.ALL)
        assertSame(all, matched(note(title = "Server alert", text = "prod is down"), all))
        assertNull(matched(note(title = "Server alert", text = "all fine"), all))

        val none = Rule(keywords = listOf("promo", "sale"), keywordMode = KeywordMode.NONE)
        assertSame(none, matched(note(text = "your parcel arrived"), none))
        assertNull(matched(note(text = "Big SALE today"), none))
    }

    @Test fun regexAppliesWithEveryModeAndInvalidPatternNeverMatches() {
        val r = Rule(keywords = listOf("""\b\d{6}\b"""), regex = true)
        assertSame(r, matched(note(text = "Your code is 123456"), r))
        assertNull(matched(note(text = "Your code is 12345"), r))
        val none = r.copy(keywordMode = KeywordMode.NONE)
        assertSame(none, matched(note(text = "no digits"), none))
        val broken = Rule(keywords = listOf("(unclosed"), regex = true)
        assertNull(matched(note(text = "(unclosed"), broken))
    }

    @Test fun blankKeywordsAreIgnored() {
        val r = Rule(keywords = listOf(" ", ""), keywordMode = KeywordMode.ALL)
        assertSame(r, matched(note(), r))
    }

    @Test fun peopleMatchTitleOrContactNameStarredAndUnknown() {
        val mum = Rule(people = listOf("Mum"))
        assertSame(mum, matched(note(title = "Mum (2 messages)"), mum))
        assertSame(mum, matched(note(title = "+44 7700 900123"), mum, sender = Sender(ContactStatus.CONTACT, "mum")))
        assertNull(matched(note(title = "Bob"), mum, sender = Sender(ContactStatus.CONTACT, "Bob")))

        val starred = Rule(starred = true)
        assertSame(starred, matched(note(), starred, sender = Sender(ContactStatus.STARRED, "Alex")))
        assertNull(matched(note(), starred, sender = Sender(ContactStatus.CONTACT, "Alex")))

        val unknown = Rule(unknown = true)
        assertSame(unknown, matched(note(person = true), unknown, sender = Sender(ContactStatus.UNKNOWN)))
        assertNull(matched(note(person = false), unknown, sender = Sender(ContactStatus.UNKNOWN))) // a news app is not a sender
        assertNull(matched(note(person = true), unknown, sender = null)) // no Contacts permission: unknown can't be told
    }

    @Test fun contactsAreReadOnceAndOnlyWhenNeeded() {
        var reads = 0
        val lookup = { reads++; Sender(ContactStatus.STARRED) }
        RuleEngine.match(note(), listOf(Rule(packages = listOf("com.example")), Rule()), lookup)
        assertEquals(0, reads)
        RuleEngine.match(note(title = "Mum"), listOf(Rule(people = listOf("Mum"))), lookup)
        assertEquals(0, reads) // the title already names her
        RuleEngine.match(note(), listOf(Rule(people = listOf("Sam")), Rule(starred = true), Rule(unknown = true)), lookup)
        assertEquals(1, reads)
    }

    @Test fun specificityBeatsListOrder() {
        val any = Rule(name = "any")
        val app = Rule(name = "app", packages = listOf("com.example"))
        val words = Rule(name = "words", keywords = listOf("hello"))
        val appWords = Rule(name = "app+words", packages = listOf("com.example"), keywords = listOf("hello"))
        val person = Rule(name = "person", people = listOf("Alex"))
        val n = note()
        assertEquals("person", matched(n, any, app, words, appWords, person)?.name)
        assertEquals("app+words", matched(n, any, app, words, appWords)?.name)
        assertEquals("words", matched(n, any, app, words)?.name)
        assertEquals("app", matched(n, any, app)?.name)
        assertEquals("any", matched(n, any)?.name)
        assertEquals(Tier.CONTACT, RuleEngine.tier(Rule(unknown = true)))
    }

    @Test fun manualPriorityBreaksTiesThenListOrder() {
        val first = Rule(name = "first", packages = listOf("com.example"))
        val second = Rule(name = "second", packages = listOf("com.example"))
        assertEquals("first", matched(note(), first, second)?.name)
        assertEquals("second", matched(note(), first, second.copy(priority = 1))?.name)
        assertEquals("second", matched(note(), first.copy(priority = -1), second)?.name)
        // priority never beats a more specific rule
        assertEquals("words", matched(note(), first.copy(priority = 1), Rule(name = "words", keywords = listOf("hello"), priority = -1))?.name)
    }

    @Test fun disabledRulesAreSkipped() {
        val off = Rule(name = "off", enabled = false, people = listOf("Alex"))
        val on = Rule(name = "on")
        assertSame(on, matched(note(), off, on))
    }

    @Test fun explanationSaysWhy() {
        val r = Rule(packages = listOf("com.example"), keywords = listOf("hello"))
        assertEquals("Example, mentions \"hello\"", RuleEngine.match(note(), listOf(r))?.why)
        assertEquals("from Alex", RuleEngine.match(note(), listOf(Rule(people = listOf("Alex"))))?.why)
    }

    @Test fun presetsCatchCallsMessagesOtpCodesAndDelivery() {
        val rules = RuleEngine.presets()
        fun name(n: NotificationInfo) = RuleEngine.match(n, rules)?.rule?.name
        assertEquals(listOf("Calls", "Messages", "OTP codes", "Delivery"), rules.map { it.name })
        assertEquals("Calls", name(note(pkg = "com.google.android.dialer", text = "Incoming call")))
        assertEquals("OTP codes", name(note(pkg = "com.google.android.apps.messaging", text = "Your OTP is 482913. Do not share it.")))
        assertEquals("OTP codes", name(note(text = "482913 is your verification code")))
        assertEquals("Messages", name(note(pkg = "com.whatsapp", text = "See you at 1830")))
        assertEquals("Delivery", name(note(pkg = "com.amazon", text = "Your parcel is out for delivery")))
        assertNull(name(note(text = "Order 12345678 shipped")))
    }

    @Test fun designGlyphFallsBackToPulseWithoutADesign() {
        val p = DeviceProfile.Phone3
        val anim = RuleEngine.animation(Rule(glyph = GlyphStyle.DESIGN), note(), p) { null }
        assertEquals(Effects.pulse(p).frames.size, anim.frames.size)
    }

    @Test fun letterGlyphUsesTheAppsFirstLetter() {
        val p = DeviceProfile.Phone3
        val anim = RuleEngine.animation(Rule(glyph = GlyphStyle.LETTER), note(title = "Zed"), p) { null }
        assertEquals(Effects.letter(p, 'E').pixels.toList(), anim.frames.first().frame.pixels.toList())
    }
}
