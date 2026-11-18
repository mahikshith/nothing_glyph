package com.dotforge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
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
import com.dotforge.ringtones.ImportMode

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

/** The four tabs. Settings is a page (Profile), opened from the avatar on Home. */
enum class Tab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    ALERTS("Alerts", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    STUDIO("Studio", Icons.Filled.Create, Icons.Outlined.Create),
    FEATURES("Features", GlyphSelected, GlyphUnselected),
}

/** Full-screen detail pages, pushed over a tab (no bottom bar). */
sealed interface Page {
    // ---- the redesign's pages
    /** Profile: the placeholder account card and every Settings row (the only way to Settings). */
    data object Profile : Page
    /** The editor; a new design starts from [draft] (a key into [com.dotforge.ui.editor.Drafts]) when given. */
    data class Editor(val designId: String?, val animated: Boolean, val draft: String? = null) : Page
    /** The animation library (Studio > New > From the library). */
    data object Library : Page
    /** Profile > Backup and restore. */
    data object Backup : Page
    /** A Features page by key (ui/features/ owns the keys). */
    data class Feature(val key: String) : Page
    /** Alerts > Add an app ("app"), Reminder apps ("reminders"), or Change app ("change:<rule id>"). */
    data class AlertApps(val mode: String) : Page
    /** Alerts > ⋮ > Alert settings, and its Quiet and Who wins pages. */
    data object AlertSettings : Page
    data object Quiet : Page
    data object WhoWins : Page
    /** Alerts > Apps > Phone moments: unlock, shake, camera, alarm or timer, headphones. */
    data object PhoneMoments : Page
    /** Third-party notices and font licences (Profile > About). */
    data object Licences : Page
    /** Temporary: the screens from before the redesign, until each is replaced. */
    data object OldScreens : Page
    /** Temporary: one of the old tabs (GLYPH, CREATE, SETTINGS), opened from [OldScreens]. */
    data class OldTab(val key: String) : Page
    /** Temporary: every design-system component on one page, for review. */
    data object Components : Page

    // ---- pages from before the redesign
    data object Notifications : Page
    /** A rule; a new one starts from [template] (a rule as JSON) when given. */
    data class RuleEditor(val ruleId: String?, val template: String? = null) : Page
    data object Calls : Page
    data object Spam : Page
    data object Toys : Page
    data object Integrations : Page
    data object SystemGlyphs : Page
    data class ToySettings(val toy: Toy) : Page
    data class DesignEditor(val designId: String?, val animated: Boolean) : Page
    data object Diagnostics : Page
    data object Ringtones : Page
    data class RingtoneImport(val uri: String, val mode: ImportMode) : Page
    /** The timeline editor for the open import page's lights. */
    data object RingtoneEdit : Page
}

private fun Page.encode(): String = when (this) {
    Page.Profile -> "profile"
    Page.Library -> "library"
    Page.Backup -> "backup"
    Page.OldScreens -> "old"
    Page.Licences -> "licences"
    Page.Components -> "components"
    is Page.AlertApps -> "alertapps:$mode"
    Page.AlertSettings -> "alertsettings"
    Page.Quiet -> "quiet"
    Page.WhoWins -> "whowins"
    Page.PhoneMoments -> "moments"
    is Page.OldTab -> "oldtab:$key"
    is Page.Feature -> "feature:$key"
    is Page.Editor -> "editor:$animated:${designId.orEmpty()}"
    Page.Notifications -> "notifications"
    Page.Calls -> "calls"
    Page.Spam -> "spam"
    Page.Toys -> "toys"
    Page.Integrations -> "integrations"
    Page.SystemGlyphs -> "system"
    Page.Diagnostics -> "diagnostics"
    Page.Ringtones -> "ringtones"
    Page.RingtoneEdit -> "ringedit"
    is Page.RingtoneImport -> "ringimport:${mode.name}:$uri"
    is Page.RuleEditor -> "rule:${ruleId.orEmpty()}:${template.orEmpty()}"
    is Page.ToySettings -> "toy:${toy.name}"
    is Page.DesignEditor -> "design:$animated:${designId.orEmpty()}"
}

private fun decode(s: String): Page? {
    val parts = s.split(':', limit = 3)
    return when (parts[0]) {
        "profile" -> Page.Profile
        "library" -> Page.Library
        "backup" -> Page.Backup
        "old" -> Page.OldScreens
        "licences" -> Page.Licences
        "components" -> Page.Components
        "alertapps" -> Page.AlertApps(s.removePrefix("alertapps:"))
        "alertsettings" -> Page.AlertSettings
        "quiet" -> Page.Quiet
        "whowins" -> Page.WhoWins
        "moments" -> Page.PhoneMoments
        "oldtab" -> parts.getOrNull(1)?.let(Page::OldTab)
        "feature" -> parts.getOrNull(1)?.let(Page::Feature)
        // a draft lives in memory only: after a restart the editor opens the saved design, or a blank one
        "editor" -> Page.Editor(parts.getOrNull(2)?.ifEmpty { null }, parts.getOrNull(1) == "true")
        "notifications" -> Page.Notifications
        "calls" -> Page.Calls
        "spam" -> Page.Spam
        "toys" -> Page.Toys
        "integrations" -> Page.Integrations
        "system" -> Page.SystemGlyphs
        "diagnostics" -> Page.Diagnostics
        "ringtones" -> Page.Ringtones
        "ringedit" -> Page.RingtoneEdit
        "ringimport" -> ImportMode.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let { mode ->
            parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Page.RingtoneImport(it, mode) }
        }
        "rule" -> Page.RuleEditor(parts.getOrNull(1)?.ifEmpty { null }, parts.getOrNull(2)?.ifEmpty { null })
        "toy" -> Toy.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let(Page::ToySettings)
        "design" -> Page.DesignEditor(parts.getOrNull(2)?.ifEmpty { null }, parts.getOrNull(1) == "true")
        else -> null
    }
}

/** Four tabs, each with its own back stack of pages. */
class Nav(tab: Tab = Tab.HOME, stacks: Map<Tab, List<Page>> = emptyMap()) {
    var tab by mutableStateOf(tab)
    private val stacks = Tab.entries.associateWith { t -> mutableStateListOf<Page>().also { it += stacks[t].orEmpty() } }

    /**
     * A request for the Alerts tab to show a segment ("history", "history:blocked", "spam"…), set by links from
     * other tabs (Home's spam line, Profile > Help) and consumed by the Alerts screen.
     */
    var alertsRequest by mutableStateOf<String?>(null)

    /** Alerts > History, optionally filtered ("blocked", "skipped"). */
    fun showHistory(filter: String? = null) {
        popTo(Tab.ALERTS)
        alertsRequest = if (filter == null) "history" else "history:$filter"
    }

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
                Nav(Tab.entries.firstOrNull { it.name == saved.firstOrNull() } ?: Tab.HOME, stacks)
            },
        )
    }
}
