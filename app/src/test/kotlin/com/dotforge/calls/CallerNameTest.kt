package com.dotforge.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerNameTest {
    @Test fun genericAndNumberOnlyTitlesAreNotNames() {
        for (t in listOf("Incoming call", "incoming video call", "Unknown", "Private number", "No caller ID", "Calling…", "", "  ", "Incoming WhatsApp voice call")) {
            assertNull(t, CallerNames.clean(t))
        }
        for (t in listOf("+44 7700 900123", "(555) 010-9999", "07700900123")) assertNull(t, CallerNames.clean(t))
        assertEquals("Mom", CallerNames.clean(" Mom "))
        assertEquals("Pizza Palace", CallerNames.clean("Pizza Palace"))
        assertEquals("Room 101", CallerNames.clean("Room 101")) // digits inside a name are fine
    }

    @Test fun aGenericTitleFallsBackToTheText() {
        assertEquals("Priya", CallerNames.fromNotification("Incoming voice call", "Priya"))
        assertEquals("Priya", CallerNames.fromNotification("Priya", "Incoming voice call"))
        assertNull(CallerNames.fromNotification("Incoming call", "+91 98765 43210"))
    }

    @Test fun spamLabels() {
        assertTrue(CallerNames.isSpamLabel("Suspected spam"))
        assertTrue(CallerNames.isSpamLabel("Spam likely"))
        assertTrue(CallerNames.isSpamLabel("Likely spam · +1 555"))
        assertFalse(CallerNames.isSpamLabel("Spamalot Theatre box office".replace("Spamalot", "Camelot")))
        assertNull(CallerNames.clean("Suspected spam"))
        val n = CallerName()
        assertTrue(n.markSpam("Suspected spam", null))
        assertFalse(n.markSpam("Suspected spam")) // already known
        assertTrue(n.spam)
    }

    @Test fun theBestSourceWinsInOrder() {
        val n = CallerName()
        assertTrue(n.offer("+44 7700 900123", NameSource.NUMBER))
        assertEquals(NameSource.NUMBER, n.best?.source)
        assertTrue(n.offer("Acme Plumbing", NameSource.OTHER_APP))
        assertTrue(n.offer("Acme Plumbing Ltd", NameSource.DIALER))
        assertTrue(n.offer("Dad", NameSource.CONTACT))
        assertEquals(NameCandidate("Dad", NameSource.CONTACT), n.best)
        // a worse source arriving later never replaces a better one
        assertFalse(n.offer("Truecaller guess", NameSource.OTHER_APP))
        assertEquals("Dad", n.best?.name)
    }

    @Test fun liveUpgradeReportsAChangeOnlyWhenTheShownNameChanges() {
        val n = CallerName()
        assertTrue(n.offer("Bob", NameSource.OTHER_APP))
        assertFalse(n.offer("Incoming call", NameSource.DIALER)) // generic: ignored
        assertFalse(n.offer("+1 555 0100", NameSource.DIALER)) // number only: ignored as a name
        assertEquals("Bob", n.best?.name)
        assertTrue(n.offer("Robert Smith", NameSource.DIALER))
        assertEquals("Robert Smith", n.best?.name)
        n.clear()
        assertNull(n.best)
        assertFalse(n.spam)
    }
}
