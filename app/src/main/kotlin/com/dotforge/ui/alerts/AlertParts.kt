package com.dotforge.ui.alerts

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.notify.AlertRuntime
import com.dotforge.rules.NotificationInfo
import com.dotforge.rules.Rule
import com.dotforge.rules.RuleEngine
import com.dotforge.rules.SentencePart
import com.dotforge.system.NothingSettings
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Avatar
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberHaptics
import com.dotforge.ui.design.switchColors
import androidx.compose.foundation.selection.toggleable

/** What the phone can do with the Glyph (row 67). */
enum class DeviceState { PHONE3, OTHER, SERVICE_MISSING, GLYPH_OFF }

/** The warnings at the top of Alerts (row 67, row 68). */
data class AlertBanners(
    val device: DeviceState = DeviceState.PHONE3,
    /** Notification access is on. */
    val access: Boolean = true,
    /** Call screening is needed by a rule but not held. */
    val screeningLost: Boolean = false,
    /** Nothing's own notification lights are not set to Sound only (null = can't tell). */
    val doubleLights: Boolean = false,
    /** Nothing's ringtone lights are not set to Sound only, so caller names are covered. */
    val ringtoneLights: Boolean = false,
)

fun deviceState(ctx: Context): DeviceState = when {
    AppGraph.device.isHardware -> if (NothingSettings.glyphLightsEnabled(ctx.contentResolver) == false) DeviceState.GLYPH_OFF else DeviceState.PHONE3
    Build.MANUFACTURER.equals("Nothing", ignoreCase = true) -> DeviceState.SERVICE_MISSING
    else -> DeviceState.OTHER
}

/** "Stop double lights" done by hand (when the firmware can't be read). */
object DoubleLights {
    private const val KEY = "double_lights_done"
    fun done(ctx: Context): Boolean {
        val read = NothingSettings.soundOnly(ctx.contentResolver, NothingSettings.NOTIFICATION_MODE)
        return read ?: ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }
    fun markDone(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    fun ringtoneDone(ctx: Context): Boolean =
        NothingSettings.soundOnly(ctx.contentResolver, NothingSettings.RINGTONE_MODE)
            ?: ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("$KEY.ring", false)
    fun markRingtoneDone(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("$KEY.ring", true).apply()
}

@Composable
fun rememberAlertBanners(setup: Setup, needsScreening: Boolean): AlertBanners {
    val ctx = LocalContext.current
    return AlertBanners(
        device = remember { deviceState(ctx) },
        access = setup.notificationAccess,
        screeningLost = needsScreening && !setup.screening,
        doubleLights = !DoubleLights.done(ctx),
        ringtoneLights = !DoubleLights.ringtoneDone(ctx),
    )
}

/** An app's launcher icon (40 dp), or its first letter in a circle. */
@Composable
fun AppIcon(pkg: String, label: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = modifier.size(Tokens.Avatar).clip(CircleShape))
    else Avatar(label.ifBlank { "?" }, modifier)
}

/** The fullest frame of [a]: a still for 40 dp row previews (the first frame of a fade-in is dark). */
fun stillOf(a: Animation): com.dotforge.device.Frame = a.frames.maxBy { f -> f.frame.pixels.sum() }.frame

/** The animation a rule shows, for previews (a sample sender stands in for "their name"). */
fun previewAnimation(r: Rule, appLabel: String, sender: String = "Mom"): Animation {
    val pkg = r.packages.firstOrNull().orEmpty()
    val info = NotificationInfo("preview", pkg, appLabel, sender, "", 0, sender = sender)
    return RuleEngine.animation(r, info, AppGraph.profile, AppGraph::designAnimation, AlertRuntime::appIcon)
}

/**
 * The sentence whose words are the controls (row 47, row 90): plain text, and words with a dotted underline and
 * a chevron, each a button at least 48 dp tall, read as "Chat glyph, button, change glyph", in sentence order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sentence(parts: List<SentencePart>, modifier: Modifier = Modifier, onWord: (SentencePart.Word) -> Unit) {
    val style = MaterialTheme.typography.titleLarge
    // punctuation right after a word stays on its line: it is drawn after the word's chevron
    val pieces = ArrayList<Pair<SentencePart, String>>()
    parts.forEach { p ->
        val last = pieces.lastOrNull()
        if (p is SentencePart.Text && last?.first is SentencePart.Word) {
            val punct = p.text.takeWhile { it in ".,:;" }
            if (punct.isNotEmpty()) pieces[pieces.size - 1] = last.first to last.second + punct
            val rest = p.text.drop(punct.length)
            if (rest.isNotEmpty()) pieces += SentencePart.Text(rest) to ""
        } else pieces += p to ""
    }
    FlowRow(modifier, verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        pieces.forEach { (p, suffix) ->
            when (p) {
                is SentencePart.Text -> p.text.split(Regex("(?<= )")).forEach { piece ->
                    if (piece.isNotEmpty()) Text(piece, style = style, modifier = Modifier.heightIn(min = Tokens.Target).padding(top = 10.dp))
                }
                is SentencePart.Word -> WordButton(p.text, suffix, p.spoken, style) { onWord(p) }
            }
        }
    }
}

@Composable
private fun WordButton(text: String, suffix: String, spoken: String, style: TextStyle, onClick: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Row(
        Modifier.heightIn(min = Tokens.Target)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = style,
            modifier = Modifier.drawBehind {
                val y = size.height - 2.dp.toPx()
                drawLine(
                    Tokens.Secondary, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
            },
        )
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Tokens.Secondary, modifier = Modifier.size(20.dp))
    }
    if (suffix.isNotEmpty()) Text(suffix, style = style)
}

/** A switch on its own 48 dp target, for the end of a row whose body opens something else. */
@Composable
fun RowSwitch(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptic = rememberHaptics()
    androidx.compose.foundation.layout.Box(
        Modifier.size(width = 56.dp, height = Tokens.Target)
            .toggleable(checked, enabled = enabled, role = Role.Switch) { haptic.toggle(it); onChange(it) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors()) }
}

/** Remembers a value across recompositions that a sheet edits before saving. */
@Composable
fun <T> rememberDraft(initial: T) = remember { mutableStateOf(initial) }
