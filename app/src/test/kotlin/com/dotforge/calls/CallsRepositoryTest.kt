package com.dotforge.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun settingsAndCallerGlyphsPersist() {
        val f = tmp.root.resolve("calls.json")
        val a = CallsRepository(f)
        assertEquals(CallSettings(), a.settings.value)
        a.updateSettings { it.copy(repeatMinutes = 10, missedGlyph = true) }
        val g = CallerGlyph(id = "g", target = GlyphTarget.GROUP, key = "Family", name = "someone in Family", ringtoneUri = "content://x/1", ringtoneName = "Bells")
        a.upsertGlyph(g)
        a.upsertGlyph(g.copy(applied = 3)) // replaced in place
        val b = CallsRepository(f)
        assertEquals(10, b.settings.value.repeatMinutes)
        assertTrue(b.settings.value.missedGlyph)
        assertEquals(listOf(g.copy(applied = 3)), b.glyphs.value)
        b.deleteGlyph("g")
        assertEquals(emptyList<CallerGlyph>(), CallsRepository(f).glyphs.value)
    }

    @Test fun recentCallsKeepTheLastHour() {
        var now = 0L
        val r = CallsRepository(tmp.root.resolve("calls.json")) { now }
        r.noteCall("+1", 0)
        r.noteCall(null, 1) // hidden numbers aren't kept
        r.noteCall("", 2)
        r.noteCall("+2", CallsRepository.RECENT_MS - 1)
        r.noteCall("+3", CallsRepository.RECENT_MS)
        assertEquals(listOf("+2", "+3"), r.recent.map { it.number })
        now = 5
        r.noteCall("+4")
        assertEquals(5L, r.recent.last().atMs)
    }

    @Test fun recentCallsAreCapped() {
        val r = CallsRepository(tmp.root.resolve("calls.json"))
        repeat(CallsRepository.MAX_RECENT + 20) { r.noteCall("+$it", it.toLong()) }
        assertEquals(CallsRepository.MAX_RECENT, r.recent.size)
        assertEquals("+${CallsRepository.MAX_RECENT + 19}", r.recent.last().number)
    }

    @Test fun unreadableFileStartsEmpty() {
        val f = tmp.root.resolve("calls.json").apply { writeText("{broken") }
        assertEquals(CallSettings(), CallsRepository(f).settings.value)
    }
}
