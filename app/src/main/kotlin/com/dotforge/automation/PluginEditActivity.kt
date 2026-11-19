package com.dotforge.automation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.rules.GlyphStyle
import com.dotforge.ui.ChoiceCard
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.EmptyState
import com.dotforge.ui.GroupTitle
import com.dotforge.ui.Muted
import com.dotforge.ui.NothingRed
import com.dotforge.ui.PageScaffold
import com.dotforge.ui.PrimaryButton
import com.dotforge.ui.TileRow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Tasker / Locale plugin's edit screen (EDIT_SETTING): pick one of the automation actions and save it.
 * The result carries the signed settings ([PluginBundle]) and a one-line blurb for Tasker's list.
 */
class PluginEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val key = AppGraph.automation.pluginKey
        val previous = intent.getBundleExtra(PluginBundle.EXTRA_BUNDLE)?.toMap()
            ?.let { PluginBundle.read(it, key) as? Parsed.Ok }?.command
        setContent {
            DotForgeTheme {
                PluginEditor(previous, onCancel = { finish() }) { command ->
                    val values = Bundle()
                    PluginBundle.of(command, key).forEach { (k, v) ->
                        when (v) {
                            is Boolean -> values.putBoolean(k, v)
                            is Int -> values.putInt(k, v)
                            else -> values.putString(k, v?.toString())
                        }
                    }
                    // a rule is kept by id (names change); the blurb shows its name
                    val shown = (command as? Command.SetRule)?.let { c ->
                        AppGraph.rules.rules.value.firstOrNull { it.id == c.rule }?.let { c.copy(rule = it.name) }
                    } ?: command
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(PluginBundle.EXTRA_BUNDLE, values).putExtra(PluginBundle.EXTRA_BLURB, Automation.describe(shown)),
                    )
                    finish()
                }
            }
        }
    }
}

private enum class Kind(val title: String, val hint: String) {
    GLYPH("Show a glyph", "A style, a design or text"),
    PLAY("Play a composition", "Its sound and lights"),
    VISUALISER("Visualiser", "Start or stop it"),
    RULE("A rule", "Switch it on or off"),
}

@Composable
private fun PluginEditor(previous: Command?, onCancel: () -> Unit, onSave: (Command) -> Unit) {
    var kind by remember {
        mutableStateOf(
            when (previous) {
                is Command.PlayComposition -> Kind.PLAY
                is Command.Visualiser -> Kind.VISUALISER
                is Command.SetRule -> Kind.RULE
                else -> Kind.GLYPH
            },
        )
    }
    var glyph by remember { mutableStateOf((previous as? Command.ShowGlyph)?.glyph ?: GlyphStyle.PULSE.label) }
    var text by remember { mutableStateOf((previous as? Command.ShowGlyph)?.text.orEmpty()) }
    var seconds by remember { mutableFloatStateOf(((previous as? Command.ShowGlyph)?.seconds ?: 10).toFloat()) }
    var loops by remember { mutableFloatStateOf(((previous as? Command.ShowGlyph)?.loops ?: 3).toFloat()) }
    var byLoops by remember { mutableStateOf((previous as? Command.ShowGlyph)?.loops != null) }
    var composition by remember { mutableStateOf((previous as? Command.PlayComposition)?.composition) }
    var rule by remember { mutableStateOf((previous as? Command.SetRule)?.rule) }
    var on by remember {
        mutableStateOf((previous as? Command.SetRule)?.on ?: (previous as? Command.Visualiser)?.on ?: true)
    }

    val command: Command? = when (kind) {
        Kind.GLYPH -> Command.ShowGlyph(
            glyph.takeIf { text.isBlank() }, text.trim().take(64).ifBlank { null },
            seconds.roundToInt().takeIf { !byLoops }, loops.roundToInt().takeIf { byLoops },
        )
        Kind.PLAY -> composition?.let(Command::PlayComposition)
        Kind.VISUALISER -> Command.Visualiser(on)
        Kind.RULE -> rule?.let { Command.SetRule(it, on) }
    }

    PageScaffold("TASKER", onBack = onCancel, bottomBar = {
        PrimaryButton("Save", Modifier.fillMaxWidth(), enabled = command != null) { command?.let(onSave) }
    }) {
        Kind.entries.chunked(2).forEach { pair ->
            TileRow { pair.forEach { k -> ChoiceCard(k.title, k.hint, kind == k, Modifier.weight(1f)) { kind = k } } }
        }
        when (kind) {
            Kind.GLYPH -> {
                val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
                GroupTitle("GLYPH")
                val styles = GlyphStyle.entries.filter { it != GlyphStyle.DESIGN && it != GlyphStyle.NONE && it != GlyphStyle.ICON }.map { it.label } +
                    com.dotforge.device.GlyphLibrary.all.map { it.name }
                (styles + designs.map { it.name }).forEach { name ->
                    Choice(name, selected = text.isBlank() && glyph == name) { glyph = name; text = "" }
                }
                GroupTitle("OR TEXT")
                OutlinedTextField(
                    text, { text = it.take(64) }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Scrolls by, up to 64 letters", color = Muted) }, singleLine = true,
                )
                GroupTitle("HOW LONG")
                Choice("For ${seconds.roundToInt()} s", !byLoops) { byLoops = false }
                Choice("${loops.roundToInt()} times through", byLoops) { byLoops = true }
                if (byLoops) Slider(
                    loops, { loops = it }, valueRange = 1f..Automation.MAX_LOOPS.toFloat(), steps = Automation.MAX_LOOPS - 2,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = NothingRed),
                ) else Slider(
                    seconds, { seconds = it }, valueRange = 1f..Automation.MAX_SECONDS.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = NothingRed),
                )
            }
            Kind.PLAY -> {
                val items by AppGraph.ringtones.items.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AppGraph.ringtones.refresh() } }
                GroupTitle("COMPOSITION")
                if (items.isEmpty()) EmptyState("No compositions yet", "Make or import one on the Ringtones page.")
                items.forEach { r -> Choice(r.title, composition == r.title) { composition = r.title } }
            }
            Kind.VISUALISER -> OnOff(on) { on = it }
            Kind.RULE -> {
                val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
                GroupTitle("RULE")
                rules.forEach { r -> Choice(r.name, rule == r.id) { rule = r.id } }
                OnOff(on) { on = it }
            }
        }
        command?.let { Text(Automation.describe(it), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) {
    GroupTitle("TURN")
    Choice("On", on) { onChange(true) }
    Choice("Off", !on) { onChange(false) }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = NothingRed))
        Column(Modifier.padding(start = 4.dp)) { Text(label) }
    }
}
