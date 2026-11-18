package com.dotforge.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.rules.SpamFilter
import com.dotforge.tile.TorchTileService
import java.time.LocalDate
import kotlin.math.roundToInt

/** Home: the live matrix, brightness, and every feature as a tile. */
@Composable
fun GlyphScreen(nav: Nav, setup: Setup, scroll: ScrollState) {
    val profile = AppGraph.profile
    val frame by AppGraph.arbiter.frame.collectAsStateWithLifecycle()
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val spam by AppGraph.spam.settings.collectAsStateWithLifecycle()
    val catches by AppGraph.spam.catches.collectAsStateWithLifecycle()
    val progress by AppGraph.progressRings.collectAsStateWithLifecycle()
    val brightness by AppGraph.brightness.collectAsStateWithLifecycle()
    var howTo by rememberSaveable { mutableStateOf<Toy?>(null) }
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

        GroupTitle("NOTIFICATIONS")
        TileRow {
            val on = rules.count { it.enabled }
            FeatureTile(
                "Rules",
                summary = if (!access && on > 0) "Needs access" else "$on active",
                modifier = Modifier.weight(1f),
                onClick = { nav.push(Page.Rules) },
            )
            FeatureTile(
                "Spam filter",
                summary = when {
                    !spam.enabled -> "Off"
                    !access -> "Needs access"
                    else -> "${SpamFilter.blockedOn(LocalDate.now(), catches)} blocked today"
                },
                modifier = Modifier.weight(1f),
                checked = spam.enabled,
                onCheckedChange = { on ->
                    AppGraph.spam.update { it.copy(enabled = on) }
                    if (on) setup.needNotificationAccess()
                },
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
                summary = if (torchOn) "On" else "Whole matrix",
                modifier = Modifier.weight(1f),
                checked = torchOn,
                onCheckedChange = { TorchTileService.toggle() },
                onClick = { TorchTileService.toggle() },
            )
        }

        GroupTitle("TOYS")
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
        TileRow {
            FeatureTile("Essential Key", "Remap the side key", Modifier.weight(1f), comingSoon = true)
            FeatureTile("Ringtones", "Glyph ringtones", Modifier.weight(1f), comingSoon = true)
        }
        TileRow {
            FeatureTile("App integrations", "Tasker, media, more", Modifier.weight(1f), comingSoon = true)
            FeatureTile("Import", "Glyph Museum files", Modifier.weight(1f), comingSoon = true)
        }
        Spacer(Modifier.height(24.dp))
    }

    howTo?.let { toy ->
        ToyHowToDialog(toy, onDismiss = { howTo = null }, onSettings = {
            howTo = null
            nav.push(Page.ToySettings(toy))
        })
    }
}
