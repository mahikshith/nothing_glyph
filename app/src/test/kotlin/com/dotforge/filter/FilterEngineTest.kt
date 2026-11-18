package com.dotforge.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class FilterEngineTest {
    // 2026-11-16 is a Monday
    private val monday = LocalDateTime.of(2026, 11, 16, 12, 0)

    private fun note(
        title: String = "Sam",
        text: String = "Lunch tomorrow?",
        sender: String = "Sam",
        number: String? = null,
        app: String = "com.whatsapp",
        at: LocalDateTime = monday,
        contact: Caller? = null,
        group: Boolean = false,
        region: String? = "IN",
    ) = Subject(
        isCall = false, app = app, appLabel = "WhatsApp", channel = "individual_chats", category = "msg", sender = sender,
        number = number, title = title, text = text, bigText = "$text (long)", subText = "work", summary = "2 new messages",
        fromPerson = true, groupChat = group, at = at, region = region, contact = { contact },
    )

    private fun c(field: Field, op: Op, vararg values: String, not: Boolean = false, list: String? = null) =
        Condition(field, op, values.toList(), list, not)

    private fun test(cond: Condition, s: Subject = note(), lists: List<NamedList> = emptyList()) = FilterEngine.test(cond, s, lists)

    @Test fun textOperatorsIgnoreCase() {
        assertTrue(test(c(Field.SENDER, Op.IS, "sam")))
        assertFalse(test(c(Field.SENDER, Op.IS, "sa")))
        assertTrue(test(c(Field.SENDER, Op.IS_NOT, "Alex")))
        assertFalse(test(c(Field.SENDER, Op.IS_NOT, "Alex", "SAM"))) // none of them
        assertTrue(test(c(Field.TEXT, Op.STARTS, "LUNCH")))
        assertTrue(test(c(Field.TEXT, Op.ENDS, "tomorrow?")))
        assertTrue(test(c(Field.TEXT, Op.CONTAINS, "dinner", "TOMORROW"))) // any of them
        assertFalse(test(c(Field.TEXT, Op.CONTAINS, "dinner")))
        assertTrue(test(c(Field.TEXT, Op.NOT_CONTAINS, "dinner", "loan")))
        assertFalse(test(c(Field.TEXT, Op.NOT_CONTAINS, "dinner", "lunch")))
        assertTrue(test(c(Field.TEXT, Op.REGEX, """^lunch\s+\w+""")))
        assertFalse(test(c(Field.TEXT, Op.REGEX, "(broken"))) // an invalid pattern never matches
    }

    @Test fun everyTextFieldReadsItsOwnPart() {
        assertTrue(test(c(Field.TITLE, Op.IS, "sam")))
        assertTrue(test(c(Field.BIG_TEXT, Op.ENDS, "(long)")))
        assertTrue(test(c(Field.SUB_TEXT, Op.IS, "Work")))
        assertTrue(test(c(Field.SUMMARY, Op.STARTS, "2 new")))
        assertTrue(test(c(Field.MESSAGE, Op.CONTAINS, "sam"))) // title or text
        assertTrue(test(c(Field.MESSAGE, Op.CONTAINS, "lunch")))
        assertTrue(test(c(Field.APP, Op.IS, "whatsapp"))) // the app's name
        assertTrue(test(c(Field.APP, Op.IS, "com.whatsapp"))) // or its package
        assertTrue(test(c(Field.CHANNEL, Op.CONTAINS, "individual")))
        assertTrue(test(c(Field.CATEGORY, Op.IS, "MSG")))
        assertFalse(test(c(Field.EMAIL, Op.CONTAINS, "@"))) // no email: nothing to match
    }

    @Test fun namedListsAddTheirEntries() {
        val lists = listOf(NamedList("VIP senders", listOf("Alex", " sam ")), NamedList("Spam words", listOf("loan")))
        assertTrue(test(c(Field.SENDER, Op.IN_LIST, list = "vip senders"), lists = lists))
        assertFalse(test(c(Field.SENDER, Op.IN_LIST, list = "Spam words"), lists = lists))
        assertFalse(test(c(Field.SENDER, Op.IN_LIST, list = "Missing list"), lists = lists))
        // a list adds to typed values for any operator
        assertTrue(test(c(Field.TEXT, Op.CONTAINS, "nothing", list = "Spam words"), note(text = "Loan approved"), lists))
        assertTrue(test(c(Field.TEXT, Op.NOT_CONTAINS, list = "Spam words"), lists = lists))
    }

    @Test fun numbersMatchInAnyFormat() {
        val india = note(sender = "+91 98765 43210", number = "+919876543210")
        listOf("+91 98", "+9198", "0091 98", "098", "98765").forEach { p ->
            assertTrue(p, test(c(Field.NUMBER, Op.STARTS, p), india))
        }
        assertFalse(test(c(Field.NUMBER, Op.STARTS, "+44"), india))
        assertFalse(test(c(Field.NUMBER, Op.STARTS, "097"), india))
        assertTrue(test(c(Field.NUMBER, Op.ENDS, "43 210"), india))
        assertTrue(test(c(Field.NUMBER, Op.CONTAINS, "765-43"), india))
        assertTrue(test(c(Field.NUMBER, Op.IS, "09876543210"), india))
        assertTrue(test(c(Field.NUMBER, Op.IS, "+91 98765 43210"), india))
        assertFalse(test(c(Field.NUMBER, Op.IS, "9876543211"), india))
        assertTrue(test(c(Field.NUMBER, Op.IS_NOT, "+441234567890"), india))
        assertTrue(test(c(Field.NUMBER, Op.REGEX, """^\+91(98|99)"""), india))
        // a national prefix means the phone's region: "098" in the UK is not an Indian number
        assertFalse(test(c(Field.NUMBER, Op.STARTS, "098"), note(number = "+919876543210", region = "GB")))
        // no number: number conditions never match
        assertFalse(test(c(Field.NUMBER, Op.STARTS, "+91"), note(number = null)))
        assertFalse(test(c(Field.NUMBER, Op.ENDS, ""), india))
        assertTrue(test(c(Field.NUMBER, Op.IN_LIST, list = "Blocked"), india, listOf(NamedList("Blocked", listOf("098765 43210")))))
    }

    @Test fun allAnyAndNot() {
        val sam = c(Field.SENDER, Op.IS, "Sam")
        val loan = c(Field.TEXT, Op.CONTAINS, "loan")
        assertFalse(FilterEngine.matches(Match(all = true, conditions = listOf(sam, loan)), note()))
        assertTrue(FilterEngine.matches(Match(all = false, conditions = listOf(sam, loan)), note()))
        assertTrue(FilterEngine.matches(Match(all = true, conditions = listOf(sam, loan.copy(not = true))), note()))
        assertTrue(FilterEngine.matches(Match(), note())) // empty matches everything
    }

    @Test fun nestedGroups() {
        // sender is Sam and (text contains "loan" or text contains "lunch")
        val words = Group(all = false, conditions = listOf(c(Field.TEXT, Op.CONTAINS, "loan"), c(Field.TEXT, Op.CONTAINS, "lunch")))
        val m = Match(all = true, conditions = listOf(c(Field.SENDER, Op.IS, "Sam")), groups = listOf(words))
        assertTrue(FilterEngine.matches(m, note()))
        assertFalse(FilterEngine.matches(m, note(text = "hello")))
        assertFalse(FilterEngine.matches(m, note(sender = "Alex")))
        // not (…)
        assertTrue(FilterEngine.matches(m.copy(groups = listOf(words.copy(not = true))), note(text = "hello")))
        // an all-group inside an any-match
        val both = Group(all = true, conditions = listOf(c(Field.SENDER, Op.IS, "Alex"), c(Field.TEXT, Op.CONTAINS, "hello")))
        val any = Match(all = false, conditions = listOf(c(Field.TITLE, Op.IS, "nobody")), groups = listOf(both))
        assertTrue(FilterEngine.matches(any, note(sender = "Alex", text = "hello there")))
        assertFalse(FilterEngine.matches(any, note(sender = "Alex")))
    }

    @Test fun flagsAndContacts() {
        val inContacts = Condition.flag(Field.CONTACT)
        val notInContacts = Condition.flag(Field.CONTACT, yes = false)
        assertTrue(test(inContacts, note(contact = Caller(contact = true))))
        assertTrue(test(notInContacts, note(contact = Caller(contact = false))))
        // without Contacts access we don't know, so neither matches
        assertFalse(test(inContacts, note(contact = null)))
        assertFalse(test(notInContacts, note(contact = null)))
        assertTrue(test(Condition.flag(Field.STARRED), note(contact = Caller(contact = true, starred = true))))
        assertTrue(test(Condition.flag(Field.GROUP_CHAT), note(group = true)))
        assertTrue(test(Condition.flag(Field.GROUP_CHAT, yes = false), note()))
    }

    @Test fun callFlagsAreReadLazily() {
        var lookups = 0
        val call = Subject(
            isCall = true, number = "+12025550123", fromPerson = true, unverified = true, at = monday,
            contact = { lookups++; Caller(contact = false) }, repeated = { true }, spamList = { it == "+12025550123" },
            state = { PhoneState(locked = true, dnd = false, charging = null, quiet = true) },
        )
        assertTrue(test(Condition.flag(Field.REPEATED), call))
        assertTrue(test(Condition.flag(Field.UNVERIFIED), call))
        assertTrue(test(Condition.flag(Field.SPAM_LIST), call))
        assertFalse(test(Condition.flag(Field.HIDDEN), call))
        assertTrue(test(Condition.flag(Field.LOCKED), call))
        assertTrue(test(Condition.flag(Field.DND, yes = false), call))
        assertTrue(test(Condition.flag(Field.QUIET), call))
        assertFalse(test(Condition.flag(Field.CHARGING), call)) // unknown
        assertFalse(test(Condition.flag(Field.CHARGING, yes = false), call))
        assertEquals(0, lookups)
        test(Condition.flag(Field.CONTACT), call)
        test(Condition.flag(Field.STARRED), call)
        assertEquals(1, lookups)

        val hidden = Subject(isCall = true, hidden = true, contact = { error("no lookup for a hidden number") })
        assertTrue(test(Condition.flag(Field.CONTACT, yes = false), hidden))
        assertTrue(test(Condition.flag(Field.HIDDEN), hidden))
    }

    @Test fun timeRangesAndTheirEdges() {
        fun at(h: Int, m: Int) = LocalTime.of(h, m)
        assertTrue(FilterEngine.inRange("22:00-07:00", at(22, 0)))
        assertTrue(FilterEngine.inRange("22:00-07:00", at(0, 0)))
        assertTrue(FilterEngine.inRange("22:00-07:00", at(6, 59)))
        assertFalse(FilterEngine.inRange("22:00-07:00", at(7, 0))) // the end is not included
        assertFalse(FilterEngine.inRange("22:00-07:00", at(21, 59)))
        assertTrue(FilterEngine.inRange("09:00 – 17:30", at(17, 29)))
        assertFalse(FilterEngine.inRange("09:00-17:30", at(17, 30)))
        assertFalse(FilterEngine.inRange("25:00-07:00", at(1, 0)))
        assertNull(FilterEngine.parseRange("9-5"))
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(17, 0), FilterEngine.parseRange("9:00-17:00"))

        val night = Condition(Field.TIME, Op.IS, listOf("22:00-07:00"))
        assertTrue(test(night, note(at = monday.withHour(23))))
        assertFalse(test(night, note(at = monday.withHour(12))))
    }

    @Test fun days() {
        val weekdays = Condition(Field.DAY, Op.IS, (1..5).map { it.toString() })
        assertTrue(test(weekdays, note(at = monday)))
        assertTrue(test(weekdays, note(at = monday.plusDays(4)))) // Friday
        assertFalse(test(weekdays, note(at = monday.plusDays(5)))) // Saturday
        assertTrue(test(weekdays.copy(not = true), note(at = monday.plusDays(6)))) // Sunday
    }

    @Test fun conditionsReadAsWords() {
        assertEquals("sender starts with \"+44\" or \"+1\"", FilterEngine.describe(c(Field.SENDER, Op.STARTS, "+44", "+1")))
        assertEquals("title or text contains \"a\" or 2 more", FilterEngine.describe(c(Field.MESSAGE, Op.CONTAINS, "a", "b", "c")))
        assertEquals("sender is in list \"VIP\"", FilterEngine.describe(c(Field.SENDER, Op.IN_LIST, list = "VIP")))
        assertEquals("the sender is not in Contacts", FilterEngine.describe(Condition.flag(Field.CONTACT, yes = false)))
        assertEquals("not (text contains \"x\")", FilterEngine.describe(c(Field.TEXT, Op.CONTAINS, "x", not = true)))
        assertEquals("the time is 22:00–07:00", FilterEngine.describe(Condition(Field.TIME, Op.IS, listOf("22:00-07:00"))))
        assertEquals("the day is Sat, Sun", FilterEngine.describe(Condition(Field.DAY, Op.IS, listOf("7", "6"))))
        val g = Group(all = false, conditions = listOf(c(Field.SENDER, Op.IS, "A"), c(Field.SENDER, Op.IS, "B")), not = true)
        assertEquals("not (sender is \"A\" or sender is \"B\")", FilterEngine.describe(g))
        assertEquals(
            "app is \"WhatsApp\" and not (sender is \"A\" or sender is \"B\")",
            FilterEngine.describe(Match(conditions = listOf(c(Field.APP, Op.IS, "com.whatsapp")), groups = listOf(g))) { if (it == "com.whatsapp") "WhatsApp" else it },
        )
    }

    @Test fun completeness() {
        assertFalse(c(Field.SENDER, Op.IS, " ").isComplete)
        assertTrue(c(Field.SENDER, Op.IN_LIST, list = "VIP").isComplete)
        assertTrue(Condition.flag(Field.HIDDEN).isComplete)
    }
}
