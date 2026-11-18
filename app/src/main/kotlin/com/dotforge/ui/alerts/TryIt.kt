package com.dotforge.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.dotforge.AppGraph
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import com.dotforge.engine.Until
import com.dotforge.notify.AlertRuntime
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SimpleRules
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.Tokens
import kotlinx.coroutines.delay

/**
 * Try it on the Glyph (row 48): the exact alert (design, timing, remind) after a 3 s countdown that says "Flip
 * your phone over"; Play now skips it; any tap stops. Face down it says the firmware hides app lights; on a
 * phone that isn't a Phone (3) it plays on screen only. [startAt] is for screenshots (0 = playing).
 */
@Composable
fun TryItSheet(r: Rule, title: String, device: DeviceState, onDismiss: () -> Unit, startAt: Int = COUNTDOWN) {
    var left by remember { mutableIntStateOf(startAt) }
    val alert = remember(r) {
        val info = NotificationInfo("tryit", r.packages.firstOrNull().orEmpty(), title, "Mom", "", 0, sender = "Mom")
        RuleEngine.alert(r, info, AppGraph.profile, AppGraph::designAnimation, AppGraph::designTiming, appIcon = AlertRuntime::appIcon)
    }
    val anim = remember(r) {
        if (r.id == SimpleRules.PHONE_CALLS) AlertRuntime.previewCall("Mom", r.callShow, alert.item.animation) else alert.item.animation
    }
    val faceDown = AppGraph.faceDown.faceDown == true
    val onPhone = device == DeviceState.PHONE3
    LaunchedEffect(left) {
        if (left > 0) {
            delay(1_000)
            left -= 1
        } else if (onPhone) {
            AppGraph.arbiter.post(
                DisplayRequest(
                    TRY_ID, Priority.STUDIO_PREVIEW, anim, loop = alert.until != Until.TIMEOUT || alert.showMs != null,
                    timeoutMs = alert.showMs ?: if (alert.until != Until.TIMEOUT) UNTIL_MS else null, label = "Try it: $title",
                ),
            )
        }
    }
    DisposableEffect(Unit) { onDispose { AppGraph.arbiter.remove(TRY_ID) } }
    FullSheet(onDismiss, "Try it") {
        Column(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Stop") { onDismiss() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphPreview(anim, title, size = PreviewSize.LARGE, playing = left == 0)
            val line = when {
                left > 0 -> "Flip your phone over · $left"
                !onPhone -> "Playing on screen. The Glyph needs Phone (3)."
                faceDown -> "Nothing hides app lights face down. Try it face up."
                else -> "Playing on the Glyph"
            }
            Text(
                line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Tokens.Gutter).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text("Tap anywhere to stop.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Tiny))
        }
        if (left > 0) PrimaryButton("Play now", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { left = 0 }
    }
}

const val COUNTDOWN = 3
private const val TRY_ID = "tryit"
private const val UNTIL_MS = 10_000L
