package com.dotforge.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

class SpamTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private fun catch(key: String, title: String = "Promo", sender: String = "", number: String = "", call: Boolean = false) =
        SpamCatch(key, "Example", title, "You have won", 0, call = call, sender = sender, number = number)

    @Test fun theSpamAlertIsASilentGlyphByDefault() {
        val s = SpamSettings()
        assertTrue(s.glyphOn)
        assertEquals(GlyphStyle.SPAM, s.glyph)
        assertEquals(null, s.soundUri)
    }

    @Test fun senderKeyPrefersTheNumber() {
        assertEquals("+447700900123", catch("a", sender = "Sam", number = "+447700900123").senderKey)
        assertEquals("Sam", catch("a", sender = " Sam ").senderKey)
        assertEquals("Promo", catch("a").senderKey)
    }

    @Test fun blockedTodayCountsOnlyThatDay() {
        val day = LocalDate.of(2026, 11, 18)
        val start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val catches = listOf(start - 1, start, start + 86_399_999).map { SpamCatch("k$it", "A", "T", "x", it) }
        assertEquals(2, SpamFilter.blockedOn(day, catches, ZoneOffset.UTC))
    }

    @Test fun repositoryRecordsForgetsAndPersists() {
        val file = tmp.root.resolve("filters.json")
        val repo = FiltersRepository(file)
        repo.updateSpam { it.copy(soundUri = "content://x/1", soundName = "Bells", glyphOn = false) }
        repo.updateFilters { it.copy(onlyLocked = true) }
        repo.record(catch("a", number = "+447700900123", call = true))
        repo.record(catch("b", sender = "Other"))
        repo.record(catch("c", number = "+447700900123"))
        repo.record(catch("b", sender = "Other")) // the same key moves to the top
        assertEquals(listOf("b", "c", "a"), repo.catches.value.map { it.key })

        repo.forget("+447700900123")
        val reloaded = FiltersRepository(file)
        assertEquals(listOf("b"), reloaded.catches.value.map { it.key })
        assertEquals("Bells", reloaded.spam.value.soundName)
        assertFalse(reloaded.spam.value.glyphOn)
        assertTrue(reloaded.filters.value.onlyLocked)
        reloaded.clearCatches()
        assertEquals(emptyList<SpamCatch>(), FiltersRepository(file).catches.value)
    }

    @Test fun theLogKeepsTheNewestFifty() {
        val repo = FiltersRepository(tmp.root.resolve("filters.json"))
        repeat(60) { repo.record(catch("k$it")) }
        assertEquals(50, repo.catches.value.size)
        assertEquals("k59", repo.catches.value.first().key)
    }

    @Test fun versionOneSpamFileMovesIntoFilters() {
        val legacy = tmp.root.resolve("spam.json")
        legacy.writeText(
            """{"settings":{"enabled":true,"keywords":["casino"],"allowStarred":true},
               "catches":[{"key":"a","appLabel":"A","title":"T","text":"x","at":5}]}""",
        )
        val file = tmp.root.resolve("filters.json")
        val repo = FiltersRepository(file, legacySpam = legacy)
        assertEquals(listOf("casino"), repo.spam.value.keywords)
        assertTrue(repo.spam.value.enabled)
        assertEquals(listOf("a"), repo.catches.value.map { it.key })
        assertEquals(Action.SPAM, repo.catches.value.single().action)
        assertFalse(legacy.exists())
        assertEquals(listOf("casino"), FiltersRepository(file, legacySpam = legacy).spam.value.keywords)
    }
}
