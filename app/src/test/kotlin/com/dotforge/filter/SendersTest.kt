package com.dotforge.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bundles as the Android side copies them: platform extra names, messages as maps. */
class SendersTest {
    private fun msg(sender: String?, text: String, uri: String? = null) = mapOf("sender" to sender, "text" to text, "uri" to uri)

    private fun sender(extras: Map<String, Any?>, region: String? = "IN") = Senders.sender(Senders.fields(extras), region)

    @Test fun smsFromAnUnknownNumber() {
        // Google Messages: MessagingStyle, the number as the sender's name, a tel: person
        val s = sender(
            mapOf(
                Senders.TITLE to "+91 98765 43210",
                Senders.TEXT to "Your loan is approved",
                Senders.MESSAGES to listOf(msg("+91 98765 43210", "Your loan is approved", "tel:+919876543210")),
                Senders.PEOPLE to listOf("tel:+919876543210"),
            ),
        )
        assertEquals("+91 98765 43210", s.name)
        assertEquals("+919876543210", s.number)
        assertFalse(s.group)
    }

    @Test fun smsNumberWithoutAPersonIsNormalised() {
        val s = sender(mapOf(Senders.TITLE to "098765 43210", Senders.TEXT to "hi"))
        assertEquals("+919876543210", s.number)
        assertEquals("", s.email)
    }

    @Test fun smsFromABusinessIdHasNoNumber() {
        val s = sender(mapOf(Senders.TITLE to "VM-HDFCBK", Senders.MESSAGES to listOf(msg("VM-HDFCBK", "OTP 123456"))))
        assertEquals("VM-HDFCBK", s.name)
        assertNull(s.number)
    }

    @Test fun whatsAppDirect() {
        val s = sender(
            mapOf(
                Senders.TITLE to "Sam",
                Senders.TEXT to "On my way",
                Senders.MESSAGES to listOf(msg("Sam", "Hi"), msg("Sam", "On my way")),
                Senders.PEOPLE to listOf("content://com.android.contacts/contacts/lookup/abc/12"),
            ),
        )
        assertEquals("Sam", s.name)
        assertNull(s.number)
        assertFalse(s.group)
    }

    @Test fun whatsAppDirectSummaryTitle() {
        assertEquals("Sam", sender(mapOf(Senders.TITLE to "Sam (3 messages)", Senders.TEXT to "x")).name)
        assertEquals("Sam", sender(mapOf(Senders.TITLE to "Sam (2 new messages)")).name)
    }

    @Test fun whatsAppGroupTakesTheLastPersonWhoWrote() {
        val s = sender(
            mapOf(
                Senders.TITLE to "Family: Sam",
                Senders.CONVERSATION_TITLE to "Family",
                Senders.IS_GROUP to true,
                Senders.MESSAGES to listOf(msg("Mum", "Dinner at 8"), msg("Sam", "Ok"), msg(null, "Me too")),
            ),
        )
        assertEquals("Sam", s.name)
        assertTrue(s.group)
    }

    @Test fun groupWithoutTheFlagIsSeenFromItsSenders() {
        val s = sender(
            mapOf(
                Senders.CONVERSATION_TITLE to "Book club",
                Senders.MESSAGES to listOf(msg("Ana", "Chapter 3?"), msg("Ben", "Yes")),
            ),
        )
        assertEquals("Ben", s.name)
        assertTrue(s.group)
    }

    @Test fun olderGroupTitlesAreTakenApart() {
        assertEquals("Sam", sender(mapOf(Senders.TITLE to "Family: Sam", Senders.CONVERSATION_TITLE to "Family")).name)
        assertEquals("Sam", sender(mapOf(Senders.TITLE to "Sam @ Family", Senders.CONVERSATION_TITLE to "Family")).name)
        // without a conversation title a colon is just part of the title
        assertEquals("Re: invoice", sender(mapOf(Senders.TITLE to "Re: invoice")).name)
    }

    @Test fun gmailSenderNameAndEmail() {
        // Gmail: the title is the sender's name, the text the subject, the subtext the user's own account
        val s = sender(
            mapOf(
                Senders.TITLE to "Alex Doe",
                Senders.TEXT to "Invoice 42",
                Senders.BIG_TEXT to "Invoice 42\nPlease find attached",
                Senders.SUB_TEXT to "me@gmail.com",
                Senders.PEOPLE to listOf("mailto:alex@example.com"),
            ),
        )
        assertEquals("Alex Doe", s.name)
        assertEquals("alex@example.com", s.email)
        assertNull(s.number)
    }

    @Test fun gmailWithoutAPersonUsesAnAddressInTheTitleButNeverTheAccount() {
        val s = sender(mapOf(Senders.TITLE to "billing@shop.example", Senders.SUB_TEXT to "me@gmail.com"))
        assertEquals("billing@shop.example", s.email)
        assertEquals("", sender(mapOf(Senders.TITLE to "Alex Doe", Senders.SUB_TEXT to "me@gmail.com")).email)
    }

    @Test fun fieldsAreReadAndTrimmed() {
        val f = Senders.fields(
            mapOf(
                Senders.TITLE to " T ", Senders.TEXT to "x", Senders.SUMMARY to "2 new", Senders.TEXT_LINES to listOf("a", " ", "b"),
                Senders.MESSAGES to listOf("not a map", msg(" ", "y")), Senders.IS_GROUP to "true",
            ),
        )
        assertEquals("T", f.title)
        assertEquals("2 new", f.summary)
        assertEquals(listOf("a", "b"), f.textLines)
        assertEquals(listOf(NotificationMessage(null, "y")), f.messages)
        assertFalse(f.isGroupConversation) // only a real boolean counts
    }
}
