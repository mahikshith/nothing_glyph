package com.dotforge.setup

import com.dotforge.setup.AccessFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessFlowTest {
    @Test fun grantedNeedsNoSheet() {
        val f = AccessFlow()
        assertTrue(f.request(granted = true))
        assertEquals(Step.NONE, f.step)
    }

    @Test fun firstAskExplainsThenBlockedReturnShowsUnlockSteps() {
        val f = AccessFlow()
        f.request(granted = false)
        assertEquals(Step.EXPLAIN, f.step)
        f.openedAccessSettings()
        assertEquals(Step.NONE, f.step)
        f.resumed(granted = false) // Android blocked it: restricted settings
        assertEquals(Step.UNLOCK, f.step)
        f.openedAppInfo()
        f.resumed(granted = false) // back from App info: offer Try again
        assertEquals(Step.UNLOCK, f.step)
        f.openedAccessSettings()
        f.resumed(granted = true)
        assertEquals(Step.NONE, f.step)
    }

    @Test fun notNowStaysQuietOnLaterResumes() {
        val f = AccessFlow()
        f.request(granted = false)
        f.notNow()
        f.resumed(granted = false)
        assertEquals(Step.NONE, f.step)
    }

    @Test fun askingAgainAfterATryGoesStraightToUnlockSteps() {
        val f = AccessFlow()
        f.request(granted = false)
        f.openedAccessSettings()
        f.resumed(granted = false)
        f.notNow()
        f.request(granted = false)
        assertEquals(Step.UNLOCK, f.step)
    }
}
