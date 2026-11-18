package com.dotforge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/** A 3x3 dot grid, the Glyph tab icon. Bigger dots when selected. */
private fun dotGrid(name: String, r: Float): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    for (y in listOf(5f, 12f, 19f)) for (x in listOf(5f, 12f, 19f)) {
        addPath(
            PathData {
                moveTo(x - r, y)
                arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
                arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
                close()
            },
            fill = SolidColor(Color.Black),
        )
    }
}.build()

private val GlyphSelected = dotGrid("GlyphSelected", 2.6f)
private val GlyphUnselected = dotGrid("Glyph", 1.7f)

enum class Tab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    GLYPH("Glyph", GlyphSelected, GlyphUnselected),
    CREATE("Create", Icons.Filled.Create, Icons.Outlined.Create),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** Full-screen detail pages, pushed over a tab (no bottom bar). */
sealed interface Page {
    data object Notifications : Page
    data class RuleEditor(val ruleId: String?) : Page
    data class ToySettings(val toy: Toy) : Page
    data class DesignEditor(val designId: String?, val animated: Boolean) : Page
    data object Diagnostics : Page
}

private fun Page.encode(): String = when (this) {
    Page.Notifications -> "notifications"
    Page.Diagnostics -> "diagnostics"
    is Page.RuleEditor -> "rule:${ruleId.orEmpty()}"
    is Page.ToySettings -> "toy:${toy.name}"
    is Page.DesignEditor -> "design:$animated:${designId.orEmpty()}"
}

private fun decode(s: String): Page? {
    val parts = s.split(':', limit = 3)
    return when (parts[0]) {
        "notifications" -> Page.Notifications
        "diagnostics" -> Page.Diagnostics
        "rule" -> Page.RuleEditor(parts.getOrNull(1)?.ifEmpty { null })
        "toy" -> Toy.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let(Page::ToySettings)
        "design" -> Page.DesignEditor(parts.getOrNull(2)?.ifEmpty { null }, parts.getOrNull(1) == "true")
        else -> null
    }
}

/** Three tabs, each with its own back stack of pages. */
class Nav(tab: Tab = Tab.GLYPH, stacks: Map<Tab, List<Page>> = emptyMap()) {
    var tab by mutableStateOf(tab)
    private val stacks = Tab.entries.associateWith { t -> mutableStateListOf<Page>().also { it += stacks[t].orEmpty() } }

    /** True when the last change opened a page (for the slide direction). */
    var forward by mutableStateOf(true)
        private set

    val page: Page? get() = stacks.getValue(tab).lastOrNull()

    fun push(p: Page) {
        forward = true
        stacks.getValue(tab) += p
    }

    fun pop() {
        forward = false
        stacks.getValue(tab).removeLastOrNull()
    }

    /** Leave the current page and show another tab's root. */
    fun popTo(target: Tab) {
        stacks.getValue(tab).clear()
        tab = target
        stacks.getValue(target).clear()
    }

    companion object {
        val saver = Saver<Nav, List<String>>(
            save = { nav -> listOf(nav.tab.name) + Tab.entries.flatMap { t -> nav.stacks.getValue(t).map { "${t.name}|${it.encode()}" } } },
            restore = { saved ->
                val stacks = saved.drop(1).mapNotNull { line ->
                    val (t, p) = line.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    val tab = Tab.entries.firstOrNull { it.name == t } ?: return@mapNotNull null
                    decode(p)?.let { tab to it }
                }.groupBy({ it.first }, { it.second })
                Nav(Tab.entries.firstOrNull { it.name == saved.firstOrNull() } ?: Tab.GLYPH, stacks)
            },
        )
    }
}
