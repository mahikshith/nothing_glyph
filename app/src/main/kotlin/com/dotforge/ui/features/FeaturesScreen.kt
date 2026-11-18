package com.dotforge.ui.features

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.Page
import com.dotforge.ui.design.Screen

/** The six Features cards, in grid order (an even 2x3 grid). [key] is the [com.dotforge.ui.Page.Feature] key; [what] is the page's first line. */
enum class Feature(val key: String, val title: String, val what: String) {
    TOYS("toys", "Toys", "Little things for the Glyph. Press the Glyph Button on the back to switch toys; toys rest after 30 s."),
    MUSIC("music", "Music visualiser", "The Glyph moves with whatever is playing."),
    TORCH("torch", "Torch", "The whole Glyph as a light. Turns itself off after 5 minutes unless you change it."),
    BATTERY("battery", "Battery and volume", "The Glyph shows the battery while charging and the volume as you change it."),
    PROGRESS("progress", "Progress rings", "A ring that fills for downloads, deliveries and timers."),
    INTEGRATIONS("integrations", "Integrations", "Tasker and other apps can light the Glyph."),
    ;

    companion object {
        fun of(key: String): Feature? = entries.firstOrNull { it.key == key }
    }
}

/** Features: an equal 2-column grid of cards, each opening a page with the same layout. */
@Composable
fun FeaturesScreen(nav: Nav, setup: Setup, state: LazyListState) {
    Screen("Features", state = state) {
        item { Hint("Features are being rebuilt.") }
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
 * The one layout every Features page shares: a short line on what it does, then (from the caller) one main
 * switch, at most 5 options, and a "How it works" row.
 */
@Composable
fun FeatureLayout(feature: Feature, nav: Nav, content: LazyListScope.() -> Unit = { item { Hint("This page is being rebuilt.") } }) {
    Page(feature.title, onBack = nav::pop) {
        item(key = "what") { Hint(feature.what) }
        content()
    }
}

/** The last row of every Features page. */
fun LazyListScope.howItWorks(text: String) = item(key = "how") {
    ListRow("How it works", subtitle = text)
}
