package com.dotforge.engine

import com.dotforge.engine.AlertQueue.Offer
import com.dotforge.engine.AlertQueue.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertQueueTest {
    private var t = 0L
    private val q = AlertQueue<String>(now = { t }, maxQueued = 3, cooldownMs = 10_000, showMs = 6_000)

    private fun alert(key: String, until: Until = Until.TIMEOUT, call: Boolean = false, conversation: String = key, item: String = key) =
        AlertQueue.Alert(key, conversation, call, until, item)

    private fun shown(step: Step<String>): String? = (step as? Step.Show)?.alert?.key

    @Test fun firstAlertShowsAndTimesOutAtACycleEnd() {
        q.offer(alert("a"))
        assertEquals("a", shown(q.advance()))
        t = 2_000
        assertEquals(Step.Keep, q.advance())
        t = 6_500
        assertEquals(Step.Clear, q.advance())
        assertNull(q.current)
    }

    @Test fun waitingAlertTakesOverOnlyAtTheEndOfACycle() {
        q.offer(alert("a"))
        q.advance()
        t = 100
        assertEquals(Offer.QUEUED, q.offer(alert("b")))
        assertEquals("a", q.current?.key) // nothing cut: the player only advances at a cycle end
        t = 1_600
        assertEquals(Step.Keep, q.advance()) // never cut in its first 2 s
        t = 2_000
        assertEquals("b", shown(q.advance()))
    }

    @Test fun callsJumpAheadOfMessagesAndTheNewestShowsFirstWithinALevel() {
        q.offer(alert("m1"))
        t = 10
        q.offer(alert("call1", call = true))
        t = 20
        q.offer(alert("call2", call = true))
        assertEquals(listOf("call2", "call1", "m1"), q.waiting.map { it.key })
    }

    @Test fun peopleBeatAppsAndWhoWinsOrderDecidesWithinALevel() {
        q.offer(AlertQueue.Alert("whatsapp", "w", false, Until.TIMEOUT, "w", rank = AlertQueue.RANK_APP + 1))
        t = 10
        q.offer(AlertQueue.Alert("gmail", "g", false, Until.TIMEOUT, "g", rank = AlertQueue.RANK_APP + 0))
        t = 20
        q.offer(AlertQueue.Alert("mom", "m", false, Until.TIMEOUT, "m", rank = AlertQueue.RANK_PERSON))
        assertEquals(listOf("mom", "gmail", "whatsapp"), q.waiting.map { it.key })
    }

    @Test fun aCallCutsInAtOnce() {
        q.offer(alert("a"))
        q.advance()
        t = 500
        q.offer(alert("call", call = true))
        assertEquals("call", shown(q.advance()))
    }

    @Test fun boundedQueueDropsTheOldestNonCallAndKeepsUnreadOnesHeld() {
        q.offer(alert("m1", until = Until.READ))
        q.offer(alert("m2"))
        q.offer(alert("call", call = true))
        q.offer(alert("m3"))
        assertEquals(listOf("call", "m2", "m3"), q.waiting.map { it.key })
        assertEquals(listOf("m1"), q.holding.map { it.key })
    }

    @Test fun cooldownPerConversationButCallsBypassIt() {
        assertEquals(Offer.QUEUED, q.offer(alert("k1", conversation = "chat")))
        q.advance()
        t = 3_000
        assertEquals(Offer.COOLDOWN, q.offer(alert("k2", conversation = "chat")))
        assertEquals(Offer.QUEUED, q.offer(alert("other")))
        t = 10_500
        assertEquals(Offer.QUEUED, q.offer(alert("k3", conversation = "chat")))
        assertEquals(Offer.QUEUED, q.offer(alert("c1", call = true, conversation = "dialer")))
        q.advance()
        assertEquals(Offer.QUEUED, q.offer(alert("c2", call = true, conversation = "dialer")))
    }

    @Test fun repostUpdatesInPlace() {
        q.offer(alert("a"))
        q.offer(alert("b"))
        assertEquals(Offer.UPDATED, q.offer(alert("a", item = "a2")))
        assertEquals(listOf("a2", "b"), q.waiting.map { it.item })
        q.advance()
        assertEquals(Offer.UPDATED, q.offer(alert("a", item = "a3")))
        assertEquals("a3", q.current?.item)
    }

    @Test fun holdUntilReadLoopsAndResumesAfterOthers() {
        q.offer(alert("msg", until = Until.READ))
        assertEquals("msg", shown(q.advance()))
        t = 60_000
        assertEquals(Step.Keep, q.advance())
        q.offer(alert("ping"))
        assertEquals("ping", shown(q.advance()))
        t = 70_000
        assertEquals("msg", shown(q.advance()))
    }

    @Test fun holdUntilReadClearsWhenRemoved() {
        q.offer(alert("msg", until = Until.READ))
        q.advance()
        assertTrue(q.removed("msg"))
        assertEquals(Step.Clear, q.advance())
    }

    @Test fun holdUntilReadAndUnlockBothClearOnUnlock() {
        q.offer(alert("a", until = Until.READ))
        q.offer(alert("b", until = Until.UNLOCK))
        q.advance()
        t = 2_000
        q.advance()
        assertEquals(setOf("a", "b"), q.holding.map { it.key }.toSet())
        assertTrue(q.unlocked(counts = true))
        assertTrue(q.holding.isEmpty())
        assertEquals(Step.Clear, q.advance())
    }

    @Test fun byDefaultUnlockingDoesNotCountAsChecking() {
        q.offer(alert("a", until = Until.READ))
        q.offer(alert("missed", until = Until.UNLOCK))
        q.advance()
        t = 2_000
        q.advance()
        q.unlocked()
        assertEquals(listOf("a"), q.holding.map { it.key })
    }

    @Test fun severalWaitingAlertsTakeTurns() {
        q.offer(alert("a", until = Until.READ))
        q.advance()
        t = 2_000
        q.offer(alert("b", until = Until.READ))
        assertEquals("b", shown(q.advance()))
        t = 4_000
        assertEquals(Step.Keep, q.advance()) // not yet 3 s
        t = 5_000
        assertEquals("a", shown(q.advance()))
        t = 8_000
        assertEquals("b", shown(q.advance()))
    }

    @Test fun waitingStopsAfterTheCap() {
        q.capMs = 60 * 60_000L
        q.offer(alert("a", until = Until.READ))
        q.advance()
        t = 59 * 60_000L
        assertEquals(Step.Keep, q.advance())
        t = 60 * 60_000L
        assertEquals(Step.Clear, q.advance())
        q.capMs = null
        q.offer(alert("b", until = Until.READ, conversation = "b"))
        q.advance()
        t += 24 * 60 * 60_000L
        assertEquals(Step.Keep, q.advance())
    }

    @Test fun stopAllClearsEverythingAsChecked() {
        q.offer(alert("a", until = Until.READ))
        q.offer(alert("b"))
        q.advance()
        assertTrue(q.stopAll())
        assertTrue(q.holding.isEmpty() && q.waiting.isEmpty())
        assertEquals(Step.Clear, q.advance())
    }

    @Test fun anAlertCanHaveItsOwnCooldown() {
        q.offer(AlertQueue.Alert("k1", "chat", false, Until.TIMEOUT, "k1", cooldownMs = 60_000))
        q.advance()
        t = 30_000
        assertEquals(Offer.COOLDOWN, q.offer(AlertQueue.Alert("k2", "chat", false, Until.TIMEOUT, "k2", cooldownMs = 60_000)))
        t = 61_000
        assertEquals(Offer.QUEUED, q.offer(AlertQueue.Alert("k3", "chat", false, Until.TIMEOUT, "k3", cooldownMs = 60_000)))
    }

    @Test fun holdUntilUnlockSurvivesRemoval() {
        q.offer(alert("missed", until = Until.UNLOCK))
        q.advance()
        assertFalse(q.removed("missed"))
        assertEquals(Step.Keep, q.advance())
    }

    @Test fun timeoutAlertIsNotStoppedByUnlock() {
        q.offer(alert("otp"))
        q.advance()
        assertFalse(q.unlocked())
        assertEquals("otp", q.current?.key)
    }

    @Test fun newMessageInAHeldConversationShowsAgainAfterTheCooldown() {
        q.offer(alert("chat", until = Until.READ))
        q.advance()
        q.offer(alert("x"))
        t = 2_000
        q.advance() // x showing, chat held
        assertEquals(Offer.COOLDOWN, q.offer(alert("chat", until = Until.READ)))
        t = 20_000
        assertEquals(Offer.QUEUED, q.offer(alert("chat", until = Until.READ)))
    }

    @Test fun aTimeoutAlertCanShowForItsOwnTime() {
        q.offer(AlertQueue.Alert("a", "a", false, Until.TIMEOUT, "a", showMs = 30_000))
        q.advance()
        t = 20_000
        assertEquals(Step.Keep, q.advance())
        t = 30_000
        assertEquals(Step.Clear, q.advance())
    }

    @Test fun aHeldAlertWithShowAgainRestsAndComesBackUntilRead() {
        q.offer(AlertQueue.Alert("a", "a", false, Until.READ, "a", showMs = 5_000, againMs = 60_000))
        assertEquals("a", shown(q.advance()))
        t = 2_000
        assertEquals(Step.Keep, q.advance())
        t = 5_000
        assertEquals(Step.Wait(60_000), q.advance()) // dark for a minute
        assertNull(q.current)
        t = 65_000
        assertEquals("a", shown(q.advance())) // back
        t = 70_000
        assertEquals(Step.Wait(60_000), q.advance())
        q.removed("a") // read: gone for good
        t = 200_000
        assertEquals(Step.Clear, q.advance())
    }

    @Test fun aHeldAlertWithoutShowAgainStaysOn() {
        q.offer(alert("a", Until.READ))
        q.advance()
        t = 600_000
        assertEquals(Step.Keep, q.advance())
    }
}
