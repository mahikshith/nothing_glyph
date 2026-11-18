package com.dotforge.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test fun internationalFormsBecomeE164() {
        assertEquals("+447700900123", PhoneNumbers.e164("+44 7700 900123", "IN"))
        assertEquals("+447700900123", PhoneNumbers.e164("0044 (7700) 900-123", "IN"))
        assertEquals("+447700900123", PhoneNumbers.e164("011 44 7700 900123", "US"))
        assertEquals("+919876543210", PhoneNumbers.e164("+91-98765-43210", null))
    }

    @Test fun nationalNumbersUseTheRegionAndDropTheTrunkPrefix() {
        assertEquals("+447700900123", PhoneNumbers.e164("07700 900123", "GB"))
        assertEquals("+919876543210", PhoneNumbers.e164("09876543210", "IN"))
        assertEquals("+919876543210", PhoneNumbers.e164("98765 43210", "in"))
        assertEquals("+12025550123", PhoneNumbers.e164("(202) 555-0123", "US"))
        assertEquals("+12025550123", PhoneNumbers.e164("1 202 555 0123", "US"))
        // Italy keeps its leading zero; Russia's trunk prefix is 8
        assertEquals("+390612345678", PhoneNumbers.e164("06 1234 5678", "IT"))
        assertEquals("+74951234567", PhoneNumbers.e164("8 495 123-45-67", "RU"))
    }

    @Test fun notNumbersAndShortCodesStayOut() {
        assertNull(PhoneNumbers.e164("VM-HDFCBK", "IN"))
        assertNull(PhoneNumbers.e164("12345", "GB"))
        assertNull(PhoneNumbers.e164("07700 900123", null))
        assertNull(PhoneNumbers.e164("", "GB"))
        assertEquals("12345", PhoneNumbers.key("12345", "GB"))
        assertEquals("+447700900123", PhoneNumbers.key("07700900123", "GB"))
        assertEquals("44", PhoneNumbers.callingCode("gb"))
        assertNull(PhoneNumbers.callingCode("ZZ"))
    }

    @Test fun prefixesReadAsTyped() {
        val n = "+447700900123"
        assertTrue(PhoneNumbers.startsWith(n, "+44", "IN"))
        assertTrue(PhoneNumbers.startsWith(n, "0044", "IN"))
        assertTrue(PhoneNumbers.startsWith(n, "+4477", "IN"))
        assertFalse(PhoneNumbers.startsWith(n, "+1", "GB"))
        // national prefixes in the phone's own region
        assertTrue(PhoneNumbers.startsWith(n, "077", "GB"))
        assertTrue(PhoneNumbers.startsWith(n, "77", "GB"))
        assertFalse(PhoneNumbers.startsWith(n, "0800", "GB"))
        assertFalse(PhoneNumbers.startsWith(n, "077", "IN"))
        assertTrue(PhoneNumbers.startsWith("+18005550100", "800", "US"))
        assertFalse(PhoneNumbers.startsWith(n, "", "GB"))
    }
}
