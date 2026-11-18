package com.dotforge.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Show on Glyph, the same in every top bar (editor, Text, Photo): a pill that says "Glyph · On" (filled white)
 * or "Glyph · Off" (outlined), with a small red dot before the word only while the design is live on the Glyph.
 * Long-press shows what it does; TalkBack reads "Show on Glyph", a switch, and the state ([state], e.g. "Live on
 * Glyph", "Off", "Face down: the Glyph is off").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphToggle(on: Boolean, live: Boolean, state: String, enabled: Boolean = true, onToggle: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (on) "Show on Glyph: $state" else "Show on Glyph: off") } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.heightIn(min = Tokens.Target).clickable(enabled = enabled, onClick = onToggle)
                .semantics {
                    contentDescription = "Show on Glyph"
                    stateDescription = if (on) state else "Off"
                    role = Role.Switch
                }
                .alpha(if (enabled) 1f else DISABLED_ALPHA).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.heightIn(min = 36.dp)
                    .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
                    .then(if (on) Modifier else Modifier.border(1.dp, Tokens.Hairline, RoundedCornerShape(18.dp)))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // live on the Glyph now: a small red dot
                if (on && live) Box(Modifier.padding(end = 6.dp).size(8.dp).background(Tokens.Accent, CircleShape))
                Text(
                    if (on) "Glyph · On" else "Glyph · Off", color = if (on) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge.capped(1.15f), maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
