package com.dotforge.calls

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.rules.CallShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTest {
    private val shown = mutableListOf<CallDisplay?>()
    private val s = CallSession { shown += it }

    @Test fun aBetterNameReplacesTheNumberLive() {
        s.ringing("+44 7700 900123", null)
        assertEquals(NameCandidate("+44 7700 900123", NameSource.NUMBER), shown.last()?.name)
        s.notification("Incoming call", "+44 7700 900123", dialer = true) // nothing better: no change
        assertEquals(1, shown.size)
        s.notification("Acme Plumbing", null, dialer = false)
        assertEquals("Acme Plumbing", shown.last()?.name?.name)
        s.notification("Acme Plumbing Ltd", null, dialer = true)
        assertEquals(NameSource.DIALER, shown.last()?.name?.source)
        s.ended()
        assertNull(shown.last())
        // the next call starts clean
        s.notification("Priya", null, dialer = false)
        assertEquals("Priya", shown.last()?.name?.name)
    }

    @Test fun aSpamLabelShowsTheSpamGlyph() {
        val p = DeviceProfile.Phone3
        val glyph = Animation.single(Frame.blank(p).fill(10), 500)
        val spam = Animation.single(Frame.blank(p).fill(99), 500)
        s.ringing("+1 555 0100", null)
        s.notification("Suspected spam", "+1 555 0100", dialer = true)
        val d = shown.last()!!
        assertTrue(d.spam)
        assertSame(spam, CallSession.animation(p, d, CallShow.BOTH, glyph, spam))
        // no name yet: Name falls back to the glyph; Glyph shows the glyph
        val nameless = CallDisplay(null, null, false)
        assertSame(glyph, CallSession.animation(p, nameless, CallShow.NAME, glyph, spam))
        val named = CallDisplay(NameCandidate("Dad", NameSource.CONTACT), null, false)
        assertSame(glyph, CallSession.animation(p, named, CallShow.GLYPH, glyph, spam))
        // Both: the glyph first, then the name
        val both = CallSession.animation(p, named, CallShow.BOTH, glyph, spam)
        assertEquals(10, both.frames.first().frame.pixels[p.ledCells[0]])
        assertTrue(both.frames.size > CallSession.animation(p, named, CallShow.NAME, glyph, spam).frames.size)
    }
}
