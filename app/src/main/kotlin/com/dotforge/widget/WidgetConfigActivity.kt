package com.dotforge.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dotforge.AppGraph
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.Page
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.library.LibraryPrefs

/**
 * The widget's settings (parity row 92), opened when the widget is added and again from the launcher's
 * "Reconfigure": what it shows (the live Glyph, a Library favourite or one of your designs) and what a tap does.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // backing out leaves no half-made widget behind
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        enableEdgeToEdge()
        val store = WidgetStore.of(this)
        setContent {
            DotForgeTheme {
                WidgetConfigScreen(store.get(id), onCancel = ::finish) { choice ->
                    store.set(id, choice)
                    GlyphWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }
}

/** The widget settings page: a preview, Show (live, favourites, your designs), Tap, and Add widget. */
@Composable
fun WidgetConfigScreen(start: WidgetChoice, onCancel: () -> Unit, onSave: (WidgetChoice) -> Unit) {
    var source by rememberSaveable { mutableStateOf(start.source) }
    var tap by rememberSaveable { mutableStateOf(start.tap) }
    val choice = WidgetChoice(source, tap)
    val p = AppGraph.profile
    val shown = WidgetContent.of(choice, p)
    val favourites = LibraryPrefs.favourites.mapNotNull(AnimationLibrary::item)
    val designs = AppGraph.designs.designs.value.take(12)
    Page("Glyph widget", onBack = onCancel, bottomBar = { PrimaryButton("Add widget", Modifier.fillMaxWidth()) { onSave(choice) } }) {
        item(key = "preview") {
            Box(Modifier.fillMaxWidth().padding(vertical = Tokens.Gutter), contentAlignment = Alignment.Center) {
                DotMatrix(shown.still?.pixels, p, Modifier.size(200.dp).semantics { contentDescription = "Widget preview: ${shown.name}" })
            }
            Text(shown.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        item(key = "show") {
            SectionLabel("Show")
            RowGroup {
                OptionRow(
                    "Live Glyph", "What the Glyph shows now. Redraws at most once a minute.",
                    selected = source == WidgetChoice.LIVE, modifier = Modifier.padding(horizontal = Tokens.Gutter),
                ) { source = WidgetChoice.LIVE }
                favourites.forEach { item ->
                    val key = WidgetChoice.library(item.id)
                    RowDivider()
                    OptionRow(
                        item.name, "Library favourite",
                        preview = { DotMatrix(AnimationLibrary.thumbnail(item, p).pixels, p, Modifier.size(40.dp)) },
                        selected = source == key, modifier = Modifier.padding(horizontal = Tokens.Gutter),
                    ) { source = key }
                }
                designs.forEach { d ->
                    val key = WidgetChoice.design(d.id)
                    RowDivider()
                    OptionRow(
                        d.name, "Your design",
                        preview = { DotMatrix(WidgetContent.of(WidgetChoice(key), p).still?.pixels, p, Modifier.size(40.dp)) },
                        selected = source == key, modifier = Modifier.padding(horizontal = Tokens.Gutter),
                    ) { source = key }
                }
            }
            if (favourites.isEmpty()) Hint("Favourite designs in the Library to show them here.")
        }
        item(key = "tap") {
            SectionLabel("Tap")
            RowGroup {
                WidgetTap.entries.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    OptionRow(
                        t.label, if (t == WidgetTap.SHOW && source == WidgetChoice.LIVE) "Opens DotForge, as the live Glyph is already showing" else t.hint,
                        selected = tap == t, modifier = Modifier.padding(horizontal = Tokens.Gutter),
                    ) { tap = t }
                }
            }
        }
    }
}
