package com.dotforge.ui.alerts

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dotforge.ui.design.IconCircle
import com.dotforge.device.Posters
import com.dotforge.rules.AlertSentence
import com.dotforge.rules.CallShow
import com.dotforge.rules.GlyphStyle
import com.dotforge.rules.SimpleRules
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
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

/** The poster of [a]: what 40 dp row previews and picker tiles show (only the 96 dp preview moves). */
fun stillOf(a: Animation): com.dotforge.device.Frame = Posters.of(a)

/** The sample sender previews use for "their name" and the caller's name. */
const val SAMPLE_NAME = "Mom"

/** The animation a rule shows, for previews (a sample sender stands in for "their name"). */
fun previewAnimation(r: Rule, appLabel: String, sender: String = SAMPLE_NAME): Animation {
    val pkg = r.packages.firstOrNull().orEmpty()
    val info = NotificationInfo("preview", pkg, appLabel, sender, "", 0, sender = sender)
    return RuleEngine.animation(r, info, AppGraph.profile, AppGraph::designAnimation, AlertRuntime::appIcon)
}

/**
 * A rule's poster: its glyph centred and whole; "their name" and the caller's name as a name that has
 * scrolled in ("MOM"), not the first column of the scroll.
 */
fun posterOf(r: Rule, appLabel: String): com.dotforge.device.Frame {
    val name = if (r.id == SimpleRules.PHONE_CALLS) r.callShow == CallShow.NAME else r.glyph == GlyphStyle.NAME
    return if (name) Posters.text(AppGraph.profile, SAMPLE_NAME) else Posters.of(previewAnimation(r, appLabel))
}

/** The big preview's animation: for Phone calls, the glyph then the name, as it rings. */
fun sheetAnimation(r: Rule, title: String): Animation =
    if (r.id == SimpleRules.PHONE_CALLS) AlertRuntime.previewCall(SAMPLE_NAME, r.callShow, previewAnimation(r, title)) else previewAnimation(r, title)

/** True when [pkg]'s own icon can be drawn in dots (else the Chat bubble glyph stands in). */
fun hasAppIcon(pkg: String): Boolean = AlertRuntime.appIcon(pkg) != null

/** A rule's glyph as a 40 dp still, for the end of its row. */
@Composable
fun RulePoster(r: Rule, title: String) {
    val frame = remember(r) { posterOf(r, title) }
    com.dotforge.ui.design.GlyphPreview(frame, "${AlertSentence.glyphWord(r, hasIcon = ::hasAppIcon)} for $title")
}

/**
 * The leading slot of a rule row: the app's icon, or what stands for where it listens (a phone for calls, a
 * bell for any app, an envelope for several chat apps, a calendar for reminders, a letter for a person, the
 * people glyph for a Contacts group).
 */
@Composable
fun SourceIcon(r: Rule, title: String) {
    val pkg = r.packages.singleOrNull()
    when {
        r.id == SimpleRules.PHONE_CALLS -> IconCircle(Icons.Filled.Call)
        r.id == SimpleRules.REMINDERS -> IconCircle(Icons.Filled.DateRange)
        SimpleRules.isGroupRule(r) -> com.dotforge.ui.design.LibraryGlyph("people", still = true)
        SimpleRules.isPersonRule(r) -> Avatar(r.name)
        pkg != null -> AppIcon(pkg, title)
        r.packages.isEmpty() -> IconCircle(Icons.Filled.Notifications)
        else -> IconCircle(Icons.Filled.Email)
    }
}

/**
 * The sentence whose words are the controls (row 47, row 90): plain text, and words with a dotted underline and
 * a chevron, each a button at least 48 dp tall, read as "Chat glyph, button, change glyph", in sentence order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sentence(parts: List<SentencePart>, modifier: Modifier = Modifier, onWord: (SentencePart.Word) -> Unit) {
    val style = MaterialTheme.typography.titleLarge
    // Each piece ends with the space after it, so a line never starts with a space; punctuation right after a
    // word is drawn against the word's chevron ("everyone⌄,"), so it never starts a line either.
    val pieces = ArrayList<Pair<SentencePart, String>>()
    parts.forEach { p ->
        val last = pieces.lastOrNull()
        if (p is SentencePart.Text && last?.first is SentencePart.Word) {
            val punct = p.text.takeWhile { it in ".,:;" }
            val rest = p.text.drop(punct.length)
            val space = rest.takeWhile { it == ' ' }
            pieces[pieces.size - 1] = last.first to last.second + punct + space
            rest.drop(space.length).split(Regex("(?<= )")).filter { it.isNotEmpty() }.forEach { pieces += SentencePart.Text(it) to "" }
        } else if (p is SentencePart.Text) {
            p.text.split(Regex("(?<= )")).filter { it.isNotEmpty() }.forEach { piece ->
                val prev = pieces.lastOrNull()
                // a bare space joins the end of what came before (the space between "Mom" and "messages")
                if (piece.isBlank() && prev != null) {
                    val (part, suffix) = prev
                    pieces[pieces.size - 1] = if (part is SentencePart.Text) SentencePart.Text(part.text + piece) to suffix else part to suffix + piece
                } else if (piece.isNotBlank()) pieces += SentencePart.Text(piece) to ""
            }
        } else pieces += p to ""
    }
    FlowRow(modifier, verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        pieces.forEach { (p, suffix) ->
            when (p) {
                is SentencePart.Text -> Text(p.text, style = style, modifier = Modifier.heightIn(min = Tokens.Target).padding(top = 10.dp))
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
    // the chevron's box has 3 dp of empty space on its right: punctuation sits against the arrow itself
    if (suffix.isNotEmpty()) Text(suffix, style = style, modifier = Modifier.offset(x = (-3).dp))
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

/**
 * What a switch or choice needs before it can work, in red, with the button that fixes it: "Needs call
 * screening" · Allow, "Needs Contacts" · Allow.
 */
@Composable
fun NeedsLine(text: String, button: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text, Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = com.dotforge.ui.design.Tokens.AccentText,
        )
        androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.heightIn(min = com.dotforge.ui.design.Tokens.Target)) {
            androidx.compose.material3.Text(button, color = androidx.compose.ui.graphics.Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
