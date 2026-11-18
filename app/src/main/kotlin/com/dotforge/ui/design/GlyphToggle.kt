package com.dotforge.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * Show on Glyph, in a top bar: the Glyph icon, filled white while on, with a small red dot while it is live on
 * the Glyph. Long-press shows its name; TalkBack reads "Show on Glyph" and the state ([state], e.g. "Live on
 * Glyph", "Off", "Phone face down: the Glyph is paused"). [labelled] adds the word "Glyph" beside the icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphToggle(on: Boolean, live: Boolean, state: String, enabled: Boolean = true, labelled: Boolean = false, onToggle: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (on) "Show on Glyph: $state" else "Show on Glyph: off") } },
        state = rememberTooltipState(),
    ) {
        val content: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    Modifier.heightIn(min = 36.dp)
                        .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
                        .then(if (on) Modifier else Modifier.border(1.dp, Tokens.Hairline, RoundedCornerShape(18.dp)))
                        .padding(horizontal = if (labelled) 10.dp else 7.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(DfIcons.OnPhone, contentDescription = null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(22.dp))
                    if (labelled) Text(
                        "Glyph", color = if (on) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge.capped(),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                // live on the Glyph now: a small red dot
                if (on && live) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(10.dp)
                        .border(2.dp, Tokens.Background, CircleShape).background(Tokens.Accent, CircleShape),
                )
            }
        }
        val desc = Modifier.semantics {
            contentDescription = "Show on Glyph"
            stateDescription = if (on) state else "Off"
            role = Role.Switch
        }
        if (labelled) Box(
            Modifier.heightIn(min = Tokens.Target).clickable(enabled = enabled, onClick = onToggle).then(desc).alpha(if (enabled) 1f else DISABLED_ALPHA).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
        else IconButton(onClick = onToggle, enabled = enabled, modifier = desc.alpha(if (enabled) 1f else DISABLED_ALPHA)) { content() }
    }
}
