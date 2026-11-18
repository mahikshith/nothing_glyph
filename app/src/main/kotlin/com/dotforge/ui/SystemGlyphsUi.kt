package com.dotforge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.system.BatteryEvents

/** Battery and volume glyphs: four switches, all off until switched on. */
@Composable
fun SystemGlyphsPage(nav: Nav) {
    val s = AppGraph.system
    val charging by s.chargingMeter.collectAsStateWithLifecycle()
    val wiggle by s.wiggle.collectAsStateWithLifecycle()
    val alarms by s.alarms.collectAsStateWithLifecycle()
    val volume by s.volume.collectAsStateWithLifecycle()
    PageScaffold("BATTERY", onBack = nav::pop) {
        GroupTitle("BATTERY")
        SwitchRow("Charging meter", "Shows the battery level when you plug in", charging, s::setChargingMeter)
        SwitchRow("Shake to see the level", "While charging, give the phone a shake", wiggle, s::setWiggle)
        SwitchRow("Full and low flashes", "At 100% while charging and at ${BatteryEvents.LOW}% on battery", alarms, s::setAlarms)
        GroupTitle("VOLUME")
        SwitchRow("Volume ring", "The media volume on the outer ring when it changes", volume, s::setVolume)
        Text(
            "These run while DotForge is running, which notification access keeps it doing.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}
