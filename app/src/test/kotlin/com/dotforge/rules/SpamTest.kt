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

    private fun note(title: String = "Promo", text: String = "You have WON the lottery", at: Long = 0, key: String = "k") =
        NotificationInfo(key, "com.example", "Example", title, text, at)

    private val on = SpamSettings(enabled = true)

    @Test fun offFilterBlocksNothing() = assertFalse(SpamFilter.isSpam(note(), SpamSettings()) { ContactStatus.UNKNOWN })

    @Test fun keywordsAndPatternsBlock() {
        assertTrue(SpamFilter.isSpam(note(), on) { ContactStatus.UNKNOWN })
        assertFalse(SpamFilter.isSpam(note(text = "dinner?"), on) { ContactStatus.UNKNOWN })
        val regex = on.copy(keywords = emptyList(), patterns = listOf("""\bfree\s+\w+\s+inside\b"""))
        assertTrue(SpamFilter.isSpam(note(text = "Free gift inside!"), regex) { ContactStatus.UNKNOWN })
    }

    @Test fun allowListWins() {
        assertFalse(SpamFilter.isSpam(note(title = " promo "), on.copy(allowedSenders = listOf("PROMO"))) { ContactStatus.UNKNOWN })
        assertFalse(SpamFilter.isSpam(note(), on.copy(allowContacts = true)) { ContactStatus.CONTACT })
        assertTrue(SpamFilter.isSpam(note(), on.copy(allowStarred = true)) { ContactStatus.CONTACT })
        assertFalse(SpamFilter.isSpam(note(), on.copy(allowStarred = true)) { ContactStatus.STARRED })
    }

    @Test fun contactsAreOnlyReadWhenNeeded() {
        var asked = 0
        SpamFilter.isSpam(note(), on) { asked++; ContactStatus.UNKNOWN }
        SpamFilter.isSpam(note(text = "hi"), on.copy(allowContacts = true)) { asked++; ContactStatus.UNKNOWN }
        assertEquals(0, asked)
    }

    @Test fun blockedTodayCountsOnlyThatDay() {
        val day = LocalDate.of(2026, 11, 18)
        val start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val catches = listOf(start - 1, start, start + 86_399_999).map { SpamCatch("k$it", "A", "T", "x", it) }
        assertEquals(2, SpamFilter.blockedOn(day, catches, ZoneOffset.UTC))
    }

    @Test fun invalidPatternsAreRejected() {
        assertTrue(SpamFilter.isValidPattern("""\d{6}"""))
        assertFalse(SpamFilter.isValidPattern("(unclosed"))
        assertFalse(SpamFilter.isValidPattern(" "))
    }

    @Test fun repositoryRecordsAllowsAndPersists() {
        val file = tmp.root.resolve("spam.json")
        val repo = SpamRepository(file)
        repo.update { it.copy(enabled = true) }
        repo.record(note(title = "Promo", key = "a"))
        repo.record(note(title = "Other", key = "b"))
        repo.record(note(title = "promo", key = "c"))
        assertEquals(listOf("c", "b", "a"), repo.catches.value.map { it.key })

        repo.allow(repo.catches.value.first())
        val reloaded = SpamRepository(file)
        assertEquals(listOf("b"), reloaded.catches.value.map { it.key })
        assertEquals(listOf("promo"), reloaded.settings.value.allowedSenders)
        assertTrue(reloaded.settings.value.enabled)
    }
}
