package com.dotforge.rules

import com.dotforge.engine.Until
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleTextTest {
    private val label = mapOf("com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram")
    private fun l(pkg: String) = label[pkg] ?: pkg

    @Test fun emptyRuleReadsAsAny() =
        assertEquals("When Any app from Anyone mention Anything, show Pulse until read", RuleText.sentence(Rule(), ::l))

    @Test fun slotsShowFirstValueAndCount() {
        val r = Rule(
            packages = listOf("com.whatsapp", "org.telegram.messenger", "x"),
            people = listOf("Mum"),
            starred = true,
            keywords = listOf("urgent", " "),
            glyph = GlyphStyle.NAME,
            until = Until.UNLOCK,
        )
        assertEquals("When WhatsApp +2 from Mum +1 mention \"urgent\", show Sender name until unlock", RuleText.sentence(r, ::l))
    }

    @Test fun peopleSlotNamesStarredAndUnknown() {
        assertEquals("Starred", RuleText.people(Rule(starred = true)))
        assertEquals("Unknown senders", RuleText.people(Rule(unknown = true)))
    }

    @Test fun keywordModesReadPlainly() {
        assertEquals("a pattern", RuleText.keywords(Rule(keywords = listOf("\\d+"), regex = true)))
        assertEquals("not a pattern", RuleText.keywords(Rule(keywords = listOf("\\d+"), regex = true, keywordMode = KeywordMode.NONE)))
        assertEquals("not \"promo\"", RuleText.keywords(Rule(keywords = listOf("promo"), keywordMode = KeywordMode.NONE)))
        assertEquals("all of 2 words", RuleText.keywords(Rule(keywords = listOf("a", "b"), keywordMode = KeywordMode.ALL)))
    }

    @Test fun designGlyphUsesTheDesignName() {
        val r = Rule(glyph = GlyphStyle.DESIGN, designId = "d1")
        assertEquals("Heart", RuleText.glyph(r) { if (it == "d1") "Heart" else null })
        assertEquals("My design", RuleText.glyph(r) { null })
    }

    @Test fun soundIsNamedAtTheEnd() {
        val r = Rule(soundUri = "content://media/external/audio/media/7", soundName = "Bells")
        assertEquals("When Any app from Anyone mention Anything, show Pulse until read and play Bells", RuleText.sentence(r, ::l))
        assertEquals("a ringtone", RuleText.sound(r.copy(soundName = null)))
        assertEquals(null, RuleText.sound(Rule(soundName = "Bells")))
    }
}
