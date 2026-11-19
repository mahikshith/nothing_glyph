package com.dotforge.ui.features

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.Page
import com.dotforge.ui.design.Screen

/**
 * The six Features cards, in grid order (an even 2x3 grid). [key] is the [com.dotforge.ui.Page.Feature] key;
 * [what] is the page's first line, [options] its option rows (name to value) and [how] its How it works text.
 */
enum class Feature(val key: String, val title: String, val what: String, val options: List<Pair<String, String>>, val how: String) {
    TOYS(
        "toys", "Toys", "Little things for the Glyph. Press the Glyph Button on the back to switch toys.",
        listOf("Clock" to "Added in Glyph Toys", "Battery" to "Not added", "Get more toys" to "15 to choose from"),
        "Nothing's Glyph Toys list decides which toys you can switch to. Add DotForge's toys there. Toys rest after 30 s.",
    ),
    MUSIC(
        "music", "Visualiser", "The Glyph moves with whatever is playing.",
        listOf("Style" to "Bars", "Brightness" to "100%"),
        "It reads the sound that is playing on this phone. Android counts that as microphone access; nothing is recorded.",
    ),
    TORCH(
        "torch", "Torch", "The whole Glyph as a light.",
        listOf("Turns off after" to "5 min", "Brightness" to "100%"),
        "The torch uses the Glyph at full brightness and turns itself off so it never drains the battery by accident.",
    ),
    BATTERY(
        "battery", "Battery & volume", "The Glyph shows the battery while charging and the volume as you change it.",
        listOf("While charging" to "Battery ring", "Volume" to "Bar"),
        "When you plug in, a ring fills to the battery level. When you press the volume keys, a bar shows the level.",
    ),
    PROGRESS(
        "progress", "Live progress", "A ring that fills for deliveries, rides, downloads and timers.",
        listOf("Apps" to "Any app", "Show minutes left" to "On"),
        "It works when the app shows progress or an arrival time in its notification.",
    ),
    INTEGRATIONS(
        "integrations", "Integrations", "Tasker and other apps can light the Glyph.",
        listOf("Tasker" to "Not set up", "Glyph widget" to "Off"),
        "Other apps send DotForge a design name or a short text; DotForge shows it like an alert.",
    ),
    ;

    companion object {
        fun of(key: String): Feature? = entries.firstOrNull { it.key == key }
    }
}

/** Features: an equal 2-column grid of cards, each opening a page with the same layout. */
@Composable
fun FeaturesScreen(nav: Nav, setup: Setup, state: LazyListState) {
    Screen("Features", state = state) {
        item { Hint("Coming in the next update.") }
    }
}

/** A Features page by key. */
@Composable
fun FeaturePage(key: String, nav: Nav, setup: Setup) {
    when (Feature.of(key)) {
        Feature.TOYS -> ToysFeature(nav, setup)
        Feature.MUSIC -> MusicFeature(nav, setup)
        Feature.TORCH -> TorchFeature(nav, setup)
        Feature.BATTERY -> BatteryFeature(nav, setup)
        Feature.PROGRESS -> ProgressFeature(nav, setup)
        Feature.INTEGRATIONS -> IntegrationsFeature(nav, setup)
        null -> Page("Features", onBack = nav::pop) { item { Hint("This feature doesn't exist any more.") } }
    }
}

/**
 * The one layout every Features page shares: a short line on what it does, one main switch, at most 5 options
 * and a "How it works" row. Until a page is built, [content] is null and the placeholder shows the same layout
 * with the switch and options greyed out.
 */
@Composable
fun FeatureLayout(feature: Feature, nav: Nav, extra: (LazyListScope.() -> Unit)? = null, content: (LazyListScope.() -> Unit)? = null) {
    var how by rememberSaveable { mutableStateOf(false) }
    Page(feature.title, onBack = nav::pop) {
        item(key = "what") { Hint(feature.what) }
        if (content != null) content() else {
            item(key = "main") {
                RowGroup { SwitchRow(feature.title, "Coming in the next update", false, enabled = false) {} }
            }
            item(key = "options") {
                SectionLabel("Options")
                RowGroup {
                    feature.options.take(5).forEachIndexed { i, (name, value) ->
                        if (i > 0) RowDivider()
                        ListRow(name, subtitle = value, enabled = false, trailing = { Chevron() }) {}
                    }
                }
            }
        }
        extra?.invoke(this)
        item(key = "how") {
            RowGroup(Modifier.padding(top = Tokens.Section)) { ListRow("How it works", trailing = { Chevron() }) { how = true } }
        }
    }
    if (how) FullSheet({ how = false }, "How it works") { Text(feature.how, style = MaterialTheme.typography.bodyLarge) }
}
