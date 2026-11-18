package com.dotforge.ui.settings

import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.Page as PageLayout

/** Temporary: every screen from before the redesign, so nothing is lost until each is rebuilt. */
@Composable
fun OldScreensPage(nav: Nav) {
    val rows = listOf(
        "Old Glyph tab" to Page.OldTab("GLYPH"),
        "Old Create tab" to Page.OldTab("CREATE"),
        "Old Settings tab" to Page.OldTab("SETTINGS"),
        "Notifications and rules" to Page.Notifications,
        "Calls" to Page.Calls,
        "Spam" to Page.Spam,
        "Toys" to Page.Toys,
        "Integrations" to Page.Integrations,
        "System glyphs" to Page.SystemGlyphs,
        "Ringtones" to Page.Ringtones,
        "Old design editor" to Page.DesignEditor(null, false),
        "Old animation editor" to Page.DesignEditor(null, true),
        "Diagnostics" to Page.Diagnostics,
        "Component gallery" to Page.Components,
    )
    PageLayout("Old screens", onBack = nav::pop) {
        item {
            Hint("These go away as the new screens replace them.")
            RowGroup {
                rows.forEachIndexed { i, (title, page) ->
                    if (i > 0) RowDivider()
                    ListRow(title, trailing = { Chevron() }) { nav.push(page) }
                }
            }
        }
    }
}
