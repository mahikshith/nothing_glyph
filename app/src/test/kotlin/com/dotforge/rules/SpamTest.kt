package com.dotforge.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

class SpamTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private fun note(title: String = "Promo", text: String = "You have WON the lottery", at: Long = 0, key: String = "k", person: Boolean = false) =
        NotificationInfo(key, "com.example", "Example", title, text, at, fromPerson = person)

    private val on = SpamSettings(enabled = true)
    private val nobody = { null as Sender? }

    @Test fun offFilterBlocksNothing() = assertNull(SpamFilter.check(note(), SpamSettings(), nobody))

    @Test fun keywordsAndPatternsBlockWithAReason() {
        assertEquals("spam word \"lottery\"", SpamFilter.check(note(), on, nobody))
        assertNull(SpamFilter.check(note(text = "dinner?"), on, nobody))
        val regex = on.copy(keywords = emptyList(), patterns = listOf("""\bfree\s+\w+\s+inside\b"""))
        assertEquals("spam pattern", SpamFilter.check(note(text = "Free gift inside!"), regex, nobody))
        assertNull(SpamFilter.check(note(text = "x"), on.copy(patterns = listOf("(broken")), nobody))
    }

    @Test fun unknownSendersAreBlockedOnlyWhenKnownToBeUnknown() {
        val s = on.copy(keywords = emptyList(), blockUnknown = true)
        val hi = note(title = "+44 7700 900123", text = "hi", person = true)
        assertEquals("unknown sender", SpamFilter.check(hi, s) { Sender(ContactStatus.UNKNOWN) })
        assertNull(SpamFilter.check(hi, s) { Sender(ContactStatus.CONTACT, "Sam") })
        assertNull(SpamFilter.check(hi, s, nobody)) // no Contacts permission
        assertNull(SpamFilter.check(hi.copy(fromPerson = false), s) { Sender(ContactStatus.UNKNOWN) })
    }

    @Test fun allowListWins() {
        assertNull(SpamFilter.check(note(title = " promo "), on.copy(allowedSenders = listOf("PROMO")), nobody))
        assertNull(SpamFilter.check(note(), on.copy(allowContacts = true)) { Sender(ContactStatus.CONTACT) })
        assertTrue(SpamFilter.check(note(), on.copy(allowStarred = true)) { Sender(ContactStatus.CONTACT) } != null)
        assertNull(SpamFilter.check(note(), on.copy(allowStarred = true)) { Sender(ContactStatus.STARRED) })
    }

    @Test fun contactsAreOnlyReadWhenNeeded() {
        var asked = 0
        SpamFilter.check(note(), on) { asked++; null }
        SpamFilter.check(note(text = "hi"), on.copy(allowContacts = true)) { asked++; null }
        assertEquals(0, asked)
    }

    @Test fun blockedTodayCountsOnlyThatDay() {
        val day = LocalDate.of(2026, 11, 18)
        val start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val catches = listOf(start - 1, start, start + 86_399_999).map { SpamCatch("k$it", "A", "T", "x", it) }
        assertEquals(2, SpamFilter.blockedOn(day, catches, ZoneOffset.UTC))
    }

    @Test fun invalidPatternsAreRejected() {
        assertTrue(RuleEngine.isValidPattern("""\d{6}"""))
        assertFalse(RuleEngine.isValidPattern("(unclosed"))
        assertFalse(RuleEngine.isValidPattern(" "))
    }

    @Test fun repositoryRecordsAllowsAndPersists() {
        val file = tmp.root.resolve("filters.json")
        val repo = FiltersRepository(file)
        repo.updateSpam { it.copy(enabled = true) }
        repo.updateFilters { it.copy(onlyLocked = true) }
        repo.record(note(title = "Promo", key = "a"))
        repo.record(note(title = "Other", key = "b"))
        repo.record(note(title = "promo", key = "c"))
        assertEquals(listOf("c", "b", "a"), repo.catches.value.map { it.key })

        repo.allow(repo.catches.value.first())
        val reloaded = FiltersRepository(file)
        assertEquals(listOf("b"), reloaded.catches.value.map { it.key })
        assertEquals(listOf("promo"), reloaded.spam.value.allowedSenders)
        assertTrue(reloaded.spam.value.enabled)
        assertTrue(reloaded.filters.value.onlyLocked)
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
        assertTrue(repo.spam.value.allowStarred)
        assertEquals(listOf("a"), repo.catches.value.map { it.key })
        assertFalse(legacy.exists())
        assertEquals(listOf("casino"), FiltersRepository(file, legacySpam = legacy).spam.value.keywords)
    }
}
