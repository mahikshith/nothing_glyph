package com.dotforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TileShape = RoundedCornerShape(20.dp)

@Composable
fun nothingSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = NothingRed,
    checkedBorderColor = NothingRed,
    uncheckedThumbColor = Muted,
    uncheckedTrackColor = Surface2,
    uncheckedBorderColor = Color(0xFF444444),
)

/** Small dot-matrix group heading. */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) =
    DotTitle(text, modifier.padding(top = 28.dp, bottom = 12.dp), dot = 2.5.dp, color = Muted)

/**
 * A feature tile: title and a short summary, an optional switch, tap to open (or toggle when there is nothing
 * to configure), optional long-press. A [comingSoon] tile is shown but disabled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeatureTile(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    comingSoon: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    top: (@Composable () -> Unit)? = null,
) {
    val clickable = if (comingSoon || (onClick == null && onLongClick == null)) Modifier
    else Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    Surface(
        color = Surface1,
        shape = TileShape,
        modifier = modifier.heightIn(min = 96.dp).then(if (comingSoon) Modifier.alpha(0.4f) else Modifier),
    ) {
        Column(clickable.padding(16.dp)) {
            top?.invoke()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (comingSoon) "Coming soon" else summary,
                        color = if (comingSoon) NothingRed else Muted, fontSize = 13.sp, maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (comingSoon) Text(summary, color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                if (checked != null) {
                    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = !comingSoon, colors = nothingSwitchColors())
                }
            }
        }
    }
}

/** Two tiles side by side. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)

/** One of two (or a few) options, with a one-line hint. */
@Composable
fun ChoiceCard(title: String, hint: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) Surface2 else Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) NothingRed else Color(0xFF2E2E2E)),
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Column(Modifier.clickable(onClick = onClick).padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A settings row that shows its current value. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    attention: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = Muted, fontSize = 13.sp) }
        }
        Text(value, color = if (attention) NothingRed else Muted, modifier = Modifier.padding(start = 12.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = Muted, fontSize = 13.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = nothingSwitchColors())
    }
}

/** Two lines and at most one button. */
@Composable
fun EmptyState(title: String, body: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(body, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (button != null) PrimaryButton(button, Modifier.padding(top = 16.dp), onClick = onClick)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) { Text(text, fontWeight = FontWeight.Medium) }

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 52.dp),
        border = BorderStroke(1.dp, Color(0xFF555555)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text) }

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(title) },
        text = { Text(text, color = Muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = NothingRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )

/** A section that starts collapsed, such as "Advanced". */
@Composable
fun Collapsible(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = Muted)
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Muted)
        }
        AnimatedVisibility(expanded) { Column(content = content) }
    }
}

@Composable
fun rememberToggle(initial: Boolean = false) = rememberSaveable { mutableStateOf(initial) }

/**
 * A full-screen detail page: back arrow, dot-matrix title, scrolling content and an optional bottom bar.
 * System back calls [onBack] too.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Box(Modifier.weight(1f).padding(start = 4.dp)) { DotTitle(title, dot = 3.dp) }
            actions()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
        bottomBar?.let { Box(Modifier.fillMaxWidth().padding(16.dp)) { it() } }
    }
}

/** Editable list of words as chips, with a field to add more. [validate] returns an error message or null. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipsEditor(
    items: List<String>,
    onChange: (List<String>) -> Unit,
    placeholder: String,
    validate: (String) -> String? = { null },
) {
    var text by rememberSaveable { mutableStateOf("") }
    val error = text.trim().takeIf { it.isNotEmpty() }?.let(validate)
    fun add() {
        val t = text.trim()
        if (t.isEmpty() || error != null) return
        if (items.none { it.equals(t, ignoreCase = true) }) onChange(items + t)
        text = ""
    }
    if (items.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                InputChip(
                    selected = false,
                    onClick = { onChange(items - item) },
                    label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", Modifier.padding(start = 2.dp)) },
                    colors = InputChipDefaults.inputChipColors(containerColor = Surface2),
                )
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(placeholder, color = Muted) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = { IconButton(onClick = ::add, enabled = text.isNotBlank() && error == null) { Icon(Icons.Filled.Add, "Add") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
