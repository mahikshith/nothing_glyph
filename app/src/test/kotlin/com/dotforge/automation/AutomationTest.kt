package com.dotforge.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {
    private fun ok(action: String, vararg extras: Pair<String, Any?>): Command =
        (Automation.parse(action, mapOf(*extras)) as Parsed.Ok).command

    private fun rejected(action: String?, vararg extras: Pair<String, Any?>) =
        assertTrue(Automation.parse(action, mapOf(*extras)) is Parsed.Rejected)

    @Test fun eachActionParses() {
        assertEquals(Command.PlayComposition("Glimmer"), ok(Automation.ACTION_PLAY, "composition" to " Glimmer "))
        assertEquals(Command.ShowGlyph("Comet", null, 10), ok(Automation.ACTION_GLYPH, "glyph" to "Comet", "seconds" to 10))
        assertEquals(Command.Visualiser(true), ok(Automation.ACTION_VISUALISER, "on" to true))
        assertEquals(Command.SetRule("Work", false), ok(Automation.ACTION_RULE, "rule" to "Work", "on" to false))
    }

    @Test fun taskerStringsAreRead() {
        assertEquals(Command.Visualiser(true), ok(Automation.ACTION_VISUALISER, "on" to "on"))
        assertEquals(Command.Visualiser(false), ok(Automation.ACTION_VISUALISER, "on" to "0"))
        assertEquals(Command.Visualiser(true), ok(Automation.ACTION_VISUALISER, "on" to " TRUE "))
        assertEquals(Command.SetRule("Work", true), ok(Automation.ACTION_RULE, "rule" to "Work", "on" to "yes"))
        assertEquals(Command.ShowGlyph(null, "hi", 12), ok(Automation.ACTION_GLYPH, "text" to "hi", "seconds" to "12"))
        assertEquals(Command.ShowGlyph(null, null, 30), ok(Automation.ACTION_GLYPH, "seconds" to 30L))
    }

    @Test fun secondsAreClampedAndTextIsCut() {
        // no time given: the Integrations page's timing decides
        assertEquals(null, (ok(Automation.ACTION_GLYPH) as Command.ShowGlyph).seconds)
        assertEquals(null, (ok(Automation.ACTION_GLYPH, "seconds" to "soon") as Command.ShowGlyph).seconds)
        assertEquals(60, (ok(Automation.ACTION_GLYPH, "seconds" to 9999) as Command.ShowGlyph).seconds)
        assertEquals(1, (ok(Automation.ACTION_GLYPH, "seconds" to -4) as Command.ShowGlyph).seconds)
        assertEquals(64, (ok(Automation.ACTION_GLYPH, "text" to "x".repeat(200)) as Command.ShowGlyph).text!!.length)
        // blank text counts as none
        assertEquals(null, (ok(Automation.ACTION_GLYPH, "text" to "  ") as Command.ShowGlyph).text)
    }

    @Test fun incompleteCommandsAreRejected() {
        rejected(Automation.ACTION_PLAY)
        rejected(Automation.ACTION_PLAY, "composition" to " ")
        rejected(Automation.ACTION_VISUALISER)
        rejected(Automation.ACTION_VISUALISER, "on" to "maybe")
        rejected(Automation.ACTION_RULE, "rule" to "Work")
        rejected(Automation.ACTION_RULE, "on" to true)
        rejected("com.dotforge.action.FORMAT_PHONE")
        rejected(null)
    }

    @Test fun onlyAllowedSendersPass() {
        val self = "com.dotforge"
        val allowed = setOf("com.example.home")
        assertEquals(Gate.UnknownSender, Automation.check(null, allowed, self))
        assertEquals(Gate.UnknownSender, Automation.check(" ", allowed, self))
        assertEquals(Gate.NotAllowed("com.example.other"), Automation.check("com.example.other", allowed, self))
        assertEquals(Gate.Allowed, Automation.check("com.example.home", allowed, self))
        assertEquals(Gate.Allowed, Automation.check(self, emptySet(), self))
        assertEquals(Gate.NotAllowed("com.example.home"), Automation.check("com.example.home", emptySet(), self))
    }

    private val key = ByteArray(32) { it.toByte() }

    @Test fun pluginSettingsRoundTrip() {
        val commands = listOf(
            Command.PlayComposition("Glimmer"),
            Command.ShowGlyph("Comet", null, 8),
            Command.ShowGlyph(null, "Dinner", 20),
            Command.Visualiser(false),
            Command.SetRule("rule-1", true),
        )
        for (c in commands) {
            assertEquals(Parsed.Ok(c), PluginBundle.read(PluginBundle.of(c, key), key))
        }
    }

    @Test fun changedUnsignedOrForeignPluginSettingsAreRefused() {
        val signed = PluginBundle.of(Command.ShowGlyph("Comet", null, 8), key)
        assertTrue(PluginBundle.read(signed + ("seconds" to 60), key) is Parsed.Rejected)
        assertTrue(PluginBundle.read(signed + ("text" to "pwned"), key) is Parsed.Rejected)
        // the same value with another type is a change too
        assertTrue(PluginBundle.read(signed + ("seconds" to "8"), key) is Parsed.Rejected)
        assertTrue(PluginBundle.read(signed - PluginBundle.KEY_SIG, key) is Parsed.Rejected)
        assertTrue(PluginBundle.read(signed, ByteArray(32) { 7 }) is Parsed.Rejected)
        val forged = mapOf(PluginBundle.KEY_ACTION to Automation.ACTION_RULE, "rule" to "Work", "on" to false)
        assertTrue(PluginBundle.read(forged + (PluginBundle.KEY_SIG to "00"), key) is Parsed.Rejected)
    }

    @Test fun describeReadsPlainly() {
        assertEquals("Show \"Hi\" for 5 s", Automation.describe(Command.ShowGlyph(null, "Hi", 5)))
        assertEquals("Turn Work off", Automation.describe(Command.SetRule("Work", false)))
        assertEquals("Start the visualiser", Automation.describe(Command.Visualiser(true)))
    }

    @Test fun loopsOrSecondsAndTheKey() {
        assertEquals(Command.ShowGlyph("Heart", null, 10), ok(Automation.ACTION_GLYPH, "glyph" to "Heart", "seconds" to "10"))
        assertEquals(Command.ShowGlyph("Heart", null, null, 3), ok(Automation.ACTION_GLYPH, "glyph" to "Heart", "loops" to "3"))
        // loops win over seconds; both are kept in range
        assertEquals(Command.ShowGlyph("Heart", null, null, 3), ok(Automation.ACTION_GLYPH, "glyph" to "Heart", "loops" to 3, "seconds" to 10))
        assertEquals(Automation.MAX_LOOPS, (ok(Automation.ACTION_GLYPH, "loops" to 99) as Command.ShowGlyph).loops)
        assertEquals("Show Heart 3 times", Automation.describe(Command.ShowGlyph("Heart", null, null, 3)))
        assertEquals("Show Heart once", Automation.describe(Command.ShowGlyph("Heart", null, null, 1)))
        assertEquals("Show Heart", Automation.describe(Command.ShowGlyph("Heart", null, null)))

        val secret = "0123456789abcdef0123456789abcdef"
        assertEquals(Gate.Allowed, Automation.check(null, emptySet(), "com.dotforge", key = secret, secret = secret))
        assertEquals(Gate.Allowed, Automation.check(null, emptySet(), "com.dotforge", key = " $secret ", secret = secret))
        assertEquals(Gate.WrongKey, Automation.check(null, emptySet(), "com.dotforge", key = "nope", secret = secret))
        // a wrong key shuts out even an allowed app, so a new key ends old senders
        assertEquals(Gate.WrongKey, Automation.check("net.dinglisch.android.taskerm", setOf("net.dinglisch.android.taskerm"), "com.dotforge", key = "old", secret = secret))
        assertEquals(Gate.UnknownSender, Automation.check(null, emptySet(), "com.dotforge"))
    }

    @Test fun theIntegrationsTimingFillsInWhatTheAppLeftOut() {
        val page = com.dotforge.system.FeatureTiming.APPS.default.copy(brightness = 60, speed = 2f)
        val none = Automation.timingFor(Command.ShowGlyph("Heart", null, null), page)
        assertEquals(page, none)
        val secs = Automation.timingFor(Command.ShowGlyph("Heart", null, 25), page)
        assertEquals(com.dotforge.device.ShowFor.SECONDS, secs.showFor)
        assertEquals(25, secs.seconds)
        assertEquals(60, secs.brightness)
        val three = Automation.timingFor(Command.ShowGlyph("Heart", null, null, 3), page)
        assertEquals(com.dotforge.device.ShowFor.WHOLE, three.showFor)
        assertEquals(com.dotforge.device.Repeat.TIMES, three.repeat)
        assertEquals(3, three.times)
        assertEquals(2f, three.speed)
        assertEquals(com.dotforge.device.Repeat.ONCE, Automation.timingFor(Command.ShowGlyph("Heart", null, null, 1), page).repeat)
        // 3 loops of a 1 s design: 3 s, then off
        val heart = com.dotforge.device.Animation(com.dotforge.device.DeviceProfile.Phone3, List(4) { com.dotforge.device.AnimFrame(com.dotforge.device.Frame.blank(com.dotforge.device.DeviceProfile.Phone3).fill(it * 60), 250) })
        val plan = com.dotforge.device.Timings.plan(heart, Automation.timingFor(Command.ShowGlyph("Heart", null, null, 3), com.dotforge.system.FeatureTiming.APPS.default))
        assertEquals(3_000, plan.animation.totalMs)
        assertEquals(false, plan.loop)
    }

    @Test fun theOutgoingEventSaysOnlyWhatKind() {
        fun r(id: String, prio: Int) = com.dotforge.engine.DisplayRequest(id, prio, com.dotforge.device.Animation.single(com.dotforge.device.Frame.blank(com.dotforge.device.DeviceProfile.Phone3)))
        val P = com.dotforge.engine.Priority
        assertEquals("call", GlyphEvents.name(r("call", P.CALL)))
        assertEquals("torch", GlyphEvents.name(r(com.dotforge.tile.TorchTileService.ID, P.TORCH)))
        assertEquals("timer", GlyphEvents.name(r("timer", P.TIMER)))
        assertEquals("timer", GlyphEvents.name(r("timer", P.TIMER_DONE)))
        assertEquals("music", GlyphEvents.name(r(com.dotforge.visualiser.LiveVisualiser.ID, P.LIVE)))
        assertEquals("battery", GlyphEvents.name(r(com.dotforge.system.SystemGlyphs.BATTERY_ID, P.NOTIFICATION)))
        assertEquals("volume", GlyphEvents.name(r(com.dotforge.system.SystemGlyphs.VOLUME_ID, P.NOTIFICATION)))
        assertEquals("automation", GlyphEvents.name(r(AutomationRunner.GLYPH_ID, P.NOTIFICATION)))
        assertEquals("progress", GlyphEvents.name(r("progress:zomato", P.PROGRESS)))
        assertEquals("alert", GlyphEvents.name(r("alert", P.NOTIFICATION)))
        // DotForge's own previews never leave the app
        assertEquals(null, GlyphEvents.name(r("try", P.STUDIO_PREVIEW)))
        assertTrue(GlyphEvents.NAMES.containsAll(listOf("alert", "call", "timer", "progress", "torch", "battery", "volume", "music", "automation")))
    }
}
