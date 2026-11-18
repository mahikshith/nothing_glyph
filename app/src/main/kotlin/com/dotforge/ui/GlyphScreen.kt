package com.dotforge.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.composition.VisualStyle
import com.dotforge.rules.Action
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.Source
import com.dotforge.rules.SpamFilter
import com.dotforge.tile.TorchTileService
import com.dotforge.visualiser.LiveVisualiser
import java.time.LocalDate
import kotlin.math.roundToInt

/** Home: the live matrix, brightness, and every feature as a tile. */
@Composable
fun GlyphScreen(nav: Nav, setup: Setup, scroll: ScrollState) {
    val profile = AppGraph.profile
    val frame by AppGraph.arbiter.frame.collectAsStateWithLifecycle()
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val catches by AppGraph.filters.catches.collectAsStateWithLifecycle()
    val progress by AppGraph.progressRings.collectAsStateWithLifecycle()
    val brightness by AppGraph.brightness.collectAsStateWithLifecycle()
    var howTo by rememberSaveable { mutableStateOf<Toy?>(null) }
    var pickStyle by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val access = setup.notificationAccess
    val torchOn = active.any { it.id == TorchTileService.ID }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {
        DotTitle("DOTFORGE", Modifier.padding(top = 20.dp))
        MatrixView(
            frame?.pixels, profile,
            Modifier.fillMaxWidth(0.62f).align(Alignment.CenterHorizontally).padding(top = 20.dp, bottom = 8.dp),
        )
        Text(
            active.firstOrNull()?.let { "Showing: ${it.label}" } ?: "Nothing showing",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", Modifier.padding(end = 12.dp))
            Slider(
                value = brightness.toFloat(),
                onValueChange = { AppGraph.setBrightness(it.roundToInt()) },
                valueRange = 10f..255f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Surface2),
            )
            Text("${brightness * 100 / 255}%", Modifier.padding(start = 12.dp), color = Muted)
        }

        GroupTitle("NOTIFICATIONS AND CALLS")
        val on = rules.count { it.enabled && it.action == Action.SHOW }
        val callsOn = rules.count { it.enabled && it.source != Source.APPS }
        val spamOn = rules.count { it.enabled && RuleEngine.isSpamRule(it) }
        val today = LocalDate.now()
        TileRow {
            FeatureTile(
                "Notifications",
                summary = when {
                    !access && on > 0 -> "Needs access"
                    else -> if (on == 1) "1 rule on" else "$on rules on"
                },
                modifier = Modifier.weight(1f),
                onClick = { nav.push(Page.Notifications) },
            )
            FeatureTile(
                "Calls",
                summary = when {
                    callsOn > 0 && !setup.screening -> "Needs call screening"
                    else -> "${if (callsOn == 1) "1 rule" else "$callsOn rules"} · ${SpamFilter.blockedOn(today, catches.filter { it.call })} stopped today"
                },
                modifier = Modifier.weight(1f),
                onClick = { nav.push(Page.Calls) },
            )
        }
        TileRow {
            FeatureTile(
                "Spam filter",
                summary = "${if (spamOn == 1) "1 rule" else "$spamOn rules"} · ${SpamFilter.blockedOn(today, catches)} blocked today",
                modifier = Modifier.weight(1f),
                onClick = { nav.push(Page.Spam) },
            )
        }

        GroupTitle("LIVE")
        TileRow {
            val setProgress = { on: Boolean ->
                AppGraph.setProgressRings(on)
                if (on) setup.needNotificationAccess()
            }
            FeatureTile(
                "Progress rings",
                summary = when {
                    !progress -> "Off"
                    !access -> "Needs access"
                    else -> "Downloads, timers, deliveries"
                },
                modifier = Modifier.weight(1f),
                checked = progress,
                onCheckedChange = { setProgress(it) },
                onClick = { setProgress(!progress) },
            )
            FeatureTile(
                "Torch",
                summary = if (torchOn) "Torch on · Turn off" else "Whole matrix, 5 min",
                modifier = Modifier.weight(1f),
                checked = torchOn,
                onCheckedChange = { TorchTileService.toggle() },
                onClick = { TorchTileService.toggle() },
            )
        }
        val liveOn by LiveVisualiser.running.collectAsStateWithLifecycle()
        val liveMic by LiveVisualiser.usingMic.collectAsStateWithLifecycle()
        val liveMusic by LiveVisualiser.music.collectAsStateWithLifecycle()
        val setLive = { on: Boolean -> if (on) setup.startLive() else LiveVisualiser.stop(ctx) }
        FeatureTile(
            "Music visualiser",
            summary = when {
                !liveOn -> "Lights follow what's playing. Hold for the look."
                liveMic -> "Using the microphone: Android gave no music data"
                !liveMusic -> "On. Waiting for music"
                else -> "On · ${AppGraph.toys.visualStyle.label}"
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            checked = liveOn,
            onCheckedChange = { setLive(it) },
            onClick = { setLive(!liveOn) },
            onLongClick = { pickStyle = true },
        )
        FeatureTile(
            "Battery, volume and torch",
            summary = "Charging meter, full and low flashes, volume ring, shake for torch",
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            onClick = { nav.push(Page.SystemGlyphs) },
        )

        GroupTitle("TOYS")
        FeatureTile(
            "All toys",
            summary = "Every installed toy, toys to get from GitHub, Nothing Playground",
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            onClick = { nav.push(Page.Toys) },
        )
        Toy.entries.chunked(2).forEach { pair ->
            TileRow {
                pair.forEach { toy ->
                    val preview = remember(toy) { toy.preview(profile) }
                    FeatureTile(
                        stringResource(toy.nameRes),
                        summary = toy.short,
                        modifier = Modifier.weight(1f),
                        onClick = { howTo = toy },
                        onLongClick = { nav.push(Page.ToySettings(toy)) },
                        top = { MatrixView(preview.pixels, profile, Modifier.size(56.dp).padding(bottom = 10.dp)) },
                    )
                }
            }
        }
        Text("Tap to add a toy. Hold for its settings.", color = Muted, fontSize = 13.sp)

        GroupTitle("CUSTOMISE")
        val designImport = rememberDesignImport(nav)
        TileRow {
            FeatureTile("Essential Key", "No supported way yet", Modifier.weight(1f), comingSoon = true)
            FeatureTile("Ringtones", "Import, make and edit glyph ringtones", Modifier.weight(1f), onClick = { nav.push(Page.Ringtones) })
        }
        TileRow {
            FeatureTile("App integrations", "Tasker, media, more", Modifier.weight(1f), onClick = { nav.push(Page.Integrations) })
            FeatureTile("Import", "Pictures, Dot Hub and Glyph Museum files", Modifier.weight(1f), onClick = { designImport.file() })
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickStyle) VisualStyleDialog(onDismiss = { pickStyle = false })
    howTo?.let { toy ->
        ToyHowToDialog(toy, onDismiss = { howTo = null }, onSettings = {
            howTo = null
            nav.push(Page.ToySettings(toy))
        })
    }
}

/** The look of the Live visualiser and the DF Music toy. */
@Composable
fun VisualStyleDialog(onDismiss: () -> Unit) {
    var style by remember { mutableStateOf(AppGraph.toys.visualStyle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Visualiser look") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VisualStyle.entries.forEach { s ->
                    ChoiceCard(s.label, s.hint, s == style, Modifier.fillMaxWidth()) {
                        style = s
                        AppGraph.toys.visualStyle = s
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Color.White) } },
    )
}
