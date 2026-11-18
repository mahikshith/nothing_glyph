package com.dotforge.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleTextTest {
    private val label = mapOf("com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram")
    private fun l(pkg: String) = label[pkg] ?: pkg

    @Test fun emptyRuleReadsAsAny() =
        assertEquals("When Any app from Anyone mention Anything, light up Pulse", RuleText.sentence(Rule(), ::l))

    @Test fun slotsShowFirstValueAndCount() {
        val r = Rule(
            packages = listOf("com.whatsapp", "org.telegram.messenger", "x"),
            senders = listOf("Mum"),
            keywords = listOf("urgent", " "),
            effect = Effect.LETTER,
        )
        assertEquals("When WhatsApp +2 from Mum mention \"urgent\", light up Sender letter", RuleText.sentence(r, ::l))
    }

    @Test fun keywordModesReadPlainly() {
        assertEquals("a pattern", RuleText.keywords(Rule(keywords = listOf("\\d+"), keywordMode = KeywordMode.REGEX)))
        assertEquals("not \"promo\"", RuleText.keywords(Rule(keywords = listOf("promo"), keywordMode = KeywordMode.NONE)))
        assertEquals("all of 2 words", RuleText.keywords(Rule(keywords = listOf("a", "b"), keywordMode = KeywordMode.ALL)))
    }
}
