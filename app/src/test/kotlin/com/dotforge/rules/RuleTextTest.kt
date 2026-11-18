package com.dotforge.rules

import com.dotforge.engine.Until
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Match
import com.dotforge.filter.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleTextTest {
    private val label = mapOf("com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram")
    private fun l(pkg: String) = label[pkg] ?: pkg

    @Test fun emptyRuleReadsAsAnyApp() =
        assertEquals("When Any app, show Pulse until read", RuleText.sentence(Rule(), ::l))

    @Test fun sourcesConditionsAndAction() {
        val r = Rule(
            packages = listOf("com.whatsapp", "org.telegram.messenger", "x"),
            match = Match(all = false, conditions = listOf(Condition(Field.SENDER, Op.STARTS, listOf("+44")), Condition.flag(Field.CONTACT, yes = false))),
            action = Action.SPAM,
        )
        assertEquals(
            "When WhatsApp +2 and sender starts with \"+44\" or the sender is not in Contacts, mark as spam",
            RuleText.sentence(r, ::l),
        )
        assertEquals("When Phone calls and the number is hidden, silence it", RuleText.sentence(
            Rule(source = Source.CALLS, match = Match(conditions = listOf(Condition.flag(Field.HIDDEN))), action = Action.SILENCE), ::l,
        ))
        assertEquals("When Calls and any app, always allow", RuleText.sentence(Rule(source = Source.EVERYTHING, action = Action.ALLOW), ::l))
        assertEquals("When Calls and WhatsApp, reject it, no call log", RuleText.sentence(
            Rule(source = Source.EVERYTHING, packages = listOf("com.whatsapp"), action = Action.REJECT_QUIET), ::l,
        ))
    }

    @Test fun showReadsGlyphUntilSoundAndVibration() {
        val r = Rule(glyph = GlyphStyle.NAME, until = Until.UNLOCK, soundUri = "content://media/external/audio/media/7", soundName = "Bells", vibrate = true)
        assertEquals("When Any app, show Sender name until unlock and play Bells, vibrate", RuleText.sentence(r, ::l))
        assertEquals("show no glyph and play Bells", RuleText.action(r.copy(glyph = GlyphStyle.NONE, vibrate = false)))
        assertEquals("a ringtone", RuleText.sound(r.copy(soundName = null)))
        assertNull(RuleText.sound(Rule(soundName = "Bells")))
    }

    @Test fun designGlyphUsesTheDesignName() {
        val r = Rule(glyph = GlyphStyle.DESIGN, designId = "d1")
        assertEquals("Heart", RuleText.glyph(r) { if (it == "d1") "Heart" else null })
        assertEquals("My design", RuleText.glyph(r) { null })
    }

    @Test fun conditionsReadAnythingWhenEmpty() {
        assertEquals(RuleText.ANYTHING, RuleText.conditions(Rule()))
    }
}
