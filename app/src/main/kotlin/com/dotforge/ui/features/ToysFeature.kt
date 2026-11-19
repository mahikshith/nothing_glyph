package com.dotforge.ui.features

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Tokens
import com.dotforge.widget.GlyphWidgetProvider

/** Features > Toys. The toys list is not built yet; the page ends with "Add the widget" (parity row 92). */
@Composable
fun ToysFeature(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    var manual by rememberSaveable { mutableStateOf(false) }
    FeatureLayout(Feature.TOYS, nav, extra = {
        item(key = "widget") {
            RowGroup(Modifier.padding(top = Tokens.Section)) {
                ListRow("Add the widget", subtitle = "A design or the live Glyph on your home screen", subtitleLines = 3, trailing = { Chevron() }) {
                    manual = !GlyphWidgetProvider.requestPin(ctx)
                }
            }
            if (manual) Hint("Your launcher can't add it from here. Touch and hold your home screen, then Widgets > DotForge.")
        }
    })
}
