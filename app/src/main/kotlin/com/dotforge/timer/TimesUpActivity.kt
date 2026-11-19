package com.dotforge.timer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.live.TimerSource
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.GlyphPreview
import com.dotforge.ui.design.PreviewSize
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.Tokens

/**
 * "Time's up", full screen (the end notification's full-screen intent): over the lock screen and with the screen
 * turned on, so a timer never ends unseen, even face down or across the room. A big red Dismiss stops the
 * burst, the ringing and the buzzing; +1 min starts it again with a minute. It closes once nothing has ended.
 */
class TimesUpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent { DotForgeTheme { TimesUp(onClose = ::finish) } }
    }
}

@Composable
fun TimesUp(onClose: () -> Unit) {
    val state by GlyphTimers.state.collectAsStateWithLifecycle()
    val now = GlyphTimers.now()
    val done = state.timers.filter { it.over(now) && it.source == TimerSource.OURS }
    LaunchedEffect(done.isEmpty()) { if (done.isEmpty()) onClose() }
    Column(
        Modifier.fillMaxSize().background(Tokens.Background).systemBarsPadding().padding(horizontal = Tokens.Gutter, vertical = Tokens.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val finish = remember { GlyphTimers.finishPreview() }
        GlyphPreview(finish, "Time's up on the Glyph", Modifier.size(200.dp), size = PreviewSize.LARGE, userPlay = true)
        Text(
            "Time's up", Modifier.padding(top = Tokens.Section).semantics { heading(); liveRegion = LiveRegionMode.Assertive },
            style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center,
        )
        Text(
            done.joinToString(" · ") { it.name }, Modifier.padding(top = Tokens.Small),
            style = MaterialTheme.typography.bodyLarge, color = Tokens.Secondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        PrimaryButton(if (done.size > 1) "Dismiss all" else "Dismiss", Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            GlyphTimers.dismissAllDone()
            onClose()
        }
        if (done.size == 1) SecondaryButton("+1 min", Modifier.fillMaxWidth().padding(top = Tokens.Gap)) {
            GlyphTimers.plusMinute(done.first().id)
            onClose()
        }
    }
}
