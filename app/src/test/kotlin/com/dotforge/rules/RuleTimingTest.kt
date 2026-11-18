package com.dotforge.rules

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.GlyphLibrary
import com.dotforge.device.ShowFor
import com.dotforge.device.Timing
import com.dotforge.device.TimingOverride
import com.dotforge.engine.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleTimingTest {
    private val p = DeviceProfile.Phone3
    private val heart = Animation(p, List(2) { AnimFrame(GlyphLibrary.get("heart")!!.still(p), 100) })
    private val info = NotificationInfo("k", "com.whatsapp", "WhatsApp", "Mom", "hi", 0)
    private val rule = SimpleRules.newApp("com.whatsapp", "WhatsApp").copy(glyph = GlyphStyle.DESIGN, designId = "d1")

    @Test fun aPlaceOverridesOnlyWhatItChangedAndFollowsTheDesign() {
        val design = Timing(speed = 2f, showFor = ShowFor.SECONDS, seconds = 3)
        val own = RuleEngine.alert(rule, info, p, { heart }, { design })
        assertEquals(3_000L, own.showMs)
        assertEquals(100, own.item.animation.totalMs) // 2 frames of 100 ms at 2x

        val placed = rule.copy(timing = TimingOverride(seconds = 10))
        val a = RuleEngine.alert(placed, info, p, { heart }, { design })
        assertEquals(10_000L, a.showMs)
        assertEquals(100, a.item.animation.totalMs) // speed still the design's
    }

    @Test fun untilYouCheckWithRemindMe() {
        val placed = rule.copy(until = Until.TIMEOUT, timing = TimingOverride(showFor = ShowFor.UNTIL_CHECKED, remindMinutes = 5))
        val a = RuleEngine.alert(placed, info, p, { heart }, { null })
        assertEquals(Until.READ, a.until)
        assertEquals(300_000L, a.againMs)
        assertNull(RuleEngine.alert(rule, info, p, { heart }, { null }).againMs)
    }
}
