package com.dotforge.ui

import com.dotforge.ui.settings.reflow
import org.junit.Assert.assertEquals
import org.junit.Test

class LicencesTest {
    @Test fun reflowJoinsParagraphLinesAndKeepsListItems() {
        val text = "This license is copied\nbelow.\n\n-----\nSIL OPEN FONT LICENSE\n-----\n\n1) One item\n2) Two"
        assertEquals("This license is copied below.\n\nSIL OPEN FONT LICENSE\n\n1) One item\n2) Two", reflow(text))
    }
}
