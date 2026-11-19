package com.dotforge.ui.home

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.ToyFaces
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.device.library.Categories
import com.dotforge.engine.AlertPlayer
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.NothingLights
import com.dotforge.rules.Conditions
import com.dotforge.rules.FilterSettings
import com.dotforge.rules.Outcome
import com.dotforge.rules.QuietText
import com.dotforge.rules.SimpleRules
import com.dotforge.tile.TorchTileService
import com.dotforge.toys.AlwaysOn
import com.dotforge.toys.HubFace
import com.dotforge.toys.LIBRARY_PREFIX
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.alerts.DoubleLights
import com.dotforge.ui.alerts.DoubleLightsSheet
import com.dotforge.ui.alerts.deviceState
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.PersonAvatar
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Screen
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.capped
import com.dotforge.ui.design.rememberHaptics
import com.dotforge.ui.design.rememberSnacks
import com.dotforge.ui.design.switchColors
import com.dotforge.ui.library.LibraryItemSheet
import com.dotforge.ui.library.LibraryPickerSheet
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.visualiser.LiveVisualiser
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * What Home knows about the phone: the device (row 67), the core permissions (the banner), the setup checklist's
 * two lines (row 51, parity row 66) and whether DF Always-on is the always-on toy. Screenshots and tests pass their own.
 */
data class HomeStatus(
    val device: DeviceState = DeviceState.PHONE3,
    val access: Boolean = true,
    val screening: Boolean = true,
    val appsPicked: Boolean = true,
    val doubleLights: Boolean = true,
    val keepRunning: Boolean = true,
    val microphone: Boolean = true,
    val contacts: Boolean = true,
    /** DF Always-on is the always-on toy (true), another is (false), or the phone can't say (null). */
    val alwaysOn: Boolean? = null,
) {
    /** The checklist's lines that are still to do (permissions are the banner's, not the checklist's). */
    val todo: Int get() = listOf(appsPicked, doubleLights).count { !it }

    /** Core permissions missing: Notification access, Call screening, Contacts, Keep running. */
    val missing: List<String> get() = com.dotforge.setup.Perms(access, screening, contacts, keepRunning).missingNames
}

/** What the Glyph is doing: the frame, what shows it, the alerts waiting until checked, and Nothing's own light. */
data class HomeNow(
    val frame: Frame? = null,
    val showing: DisplayRequest? = null,
    val waiting: List<String> = emptyList(),
    val busy: String? = null,
)

/** Home's own remembered choices: the checklist hidden, the not-a-Phone (3) note read. */
internal class HomePrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    var checklistHidden by mutableStateOf(prefs.getBoolean(HIDDEN, false))
        private set
    var otherPhoneRead by mutableStateOf(prefs.getBoolean(OTHER_READ, false))
        private set

    fun hideChecklist() { checklistHidden = true; prefs.edit().putBoolean(HIDDEN, true).apply() }
    fun readOtherPhone() { otherPhoneRead = true; prefs.edit().putBoolean(OTHER_READ, true).apply() }

    private companion object {
        const val HIDDEN = "checklist_hidden"
        const val OTHER_READ = "other_phone_read"
    }
}

/**
 * Home (production design > Home; rows 49–52, 60, 67, 68, 72–74; parity rows 65, 66): the title and the
 * avatar, the phone's note, the Glyph now (a live matrix, the status line in priority order and the one red
 * button while something of ours shows), the setup checklist until it's done or hidden, the quick switches, the
 * spam and always-on lines, and one compact row of recent designs. [status] and [now] are for screenshots.
 */
@Composable
fun HomeScreen(nav: Nav, setup: Setup, state: LazyListState, status: HomeStatus? = null, now: HomeNow? = null) {
    val ctx = LocalContext.current
    val s = status ?: run {
        val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
        val resumes = setup.resumes
        val device = remember { deviceState(ctx) }
        HomeStatus(
            device = device, access = setup.notificationAccess, screening = setup.screening,
            appsPicked = rules.any { it.enabled && SimpleRules.isAppRule(it) && it.packages.isNotEmpty() },
            doubleLights = device != DeviceState.PHONE3 || remember(resumes) { DoubleLights.done(ctx) },
            keepRunning = setup.keepRunning, microphone = setup.microphone, contacts = setup.contacts,
            alwaysOn = remember(resumes) { AlwaysOn.ours(ctx) },
        )
    }
    val n = now ?: run {
        val frame by AppGraph.arbiter.frame.collectAsStateWithLifecycle()
        val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
        val waiting by AppGraph.alerts.waiting.collectAsStateWithLifecycle()
        val busy by NothingLights.busy.collectAsStateWithLifecycle()
        LaunchedEffect(busy) { while (busy != null) { delay(1_000); NothingLights.expire() } }
        HomeNow(frame, active.firstOrNull(), waiting, busy)
    }
    val prefs = remember { HomePrefs(ctx) }
    var picking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var useFor by remember { mutableStateOf<com.dotforge.studio.Design?>(null) }
    var mine by remember { mutableStateOf<com.dotforge.studio.Design?>(null) }
    var alwaysOnSheet by remember { mutableStateOf(false) }
    var doubleSheet by remember { mutableStateOf(false) }
    // read again when the sheet closes
    val aod = remember(alwaysOnSheet) { alwaysOnName() }
    Screen("DotForge", state = state, actions = { ProfileButton { nav.push(Page.Profile) } }) {
        deviceBanner(s.device)?.let { (text, button) ->
            if (s.device != DeviceState.OTHER || !prefs.otherPhoneRead) item(key = "device") {
                StatusBanner(text, Modifier.padding(bottom = Tokens.Gap), button = button, info = s.device == DeviceState.OTHER) {
                    when (s.device) {
                        DeviceState.OTHER -> prefs.readOtherPhone()
                        DeviceState.GLYPH_OFF -> openGlyphSettings(ctx)
                        else -> openUpdates(ctx)
                    }
                }
            }
        }
        permissionBanner(s.missing)?.let { text ->
            item(key = "permissions") { PermissionBanner(text) { nav.push(Page.Permissions) } }
        }
        item(key = "now") { NowCard(n, s, setup, onChoose = { picking = true }) }
        if (s.todo > 0 && !prefs.checklistHidden) item(key = "setup") {
            SetupChecklist(s, setup, nav, onDoubleLights = { doubleSheet = true }, onHide = prefs::hideChecklist)
        }
        item(key = "switches-label") { SectionTitle("Quick switches") }
        item(key = "switches") { QuickSwitches(s, n, setup, nav) }
        item(key = "lines") {
            Lines(s, setup, nav, aod, onAlwaysOn = {
                val chooser = if (s.alwaysOn == false) AlwaysOn.setUpIntent(ctx) else null
                if (chooser != null) runCatching { ctx.startActivity(chooser) } else alwaysOnSheet = true
            })
        }
        // one compact row, with a 12 sp label (the spec's label size), not a section of its own
        item(key = "recent-label") {
            Text(
                "Recent designs", Modifier.fillMaxWidth().padding(top = Tokens.Gap + Tokens.Tiny, bottom = Tokens.Tiny).semantics { heading() },
                style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary,
            )
        }
        item(key = "recent") { RecentRow { open = it } }
    }
    if (picking) LibraryPickerSheet("Choose a design", onDismiss = { picking = false }, onPickYours = { d -> picking = false; mine = d }) { item -> picking = false; open = item.id }
    mine?.let { d -> com.dotforge.ui.studio.UseForSheet(d, nav, fresh = false) { mine = null } }
    open?.let(AnimationLibrary::item)?.let { item ->
        LibraryItemSheet(item, nav, onDismiss = { open = null }) { d -> open = null; useFor = d }
    }
    useFor?.let { d -> com.dotforge.ui.studio.UseForSheet(d, nav, fresh = true, unsaved = true) { useFor = null } }
    if (alwaysOnSheet) AlwaysOnSheet(onDismiss = { alwaysOnSheet = false })
    if (doubleSheet) DoubleLightsSheet(onDismiss = { doubleSheet = false })
}

private fun openGlyphSettings(ctx: Context) {
    val link = com.dotforge.system.NothingSettings.glyphNotificationSettings(ctx.packageManager)
    runCatching { ctx.startActivity(link.intent) }
}

private fun openUpdates(ctx: Context) {
    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)) }
}

/** A section title on Home: 22 sp (the spec's section title), with an optional action at its end. */
@Composable
private fun SectionTitle(text: String, end: (@Composable () -> Unit)? = null) =
    // the card above already leaves 12 dp: 24 dp between sections in all
    Row(Modifier.fillMaxWidth().padding(top = Tokens.Section - Tokens.Gap, bottom = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        end?.invoke()
    }

/** The note at the top for a phone that can't light the Glyph (row 67), with its one action. */
private fun deviceBanner(d: DeviceState): Pair<String, String?>? = when (d) {
    DeviceState.PHONE3 -> null
    DeviceState.OTHER -> "DotForge lights the Glyph Matrix on Nothing Phone (3). On this phone you can still design and block spam." to "Continue"
    DeviceState.SERVICE_MISSING -> "Update Nothing OS to use the Glyph" to "Check for updates"
    DeviceState.GLYPH_OFF -> "The Glyph is off in Settings" to "Open Glyph settings"
}

/** What stops alerts from lighting, most serious first (row 68, then Home's own switches). */
internal enum class Notice { NONE, ACCESS, PAUSED, QUIET }

internal fun notice(access: Boolean, f: FilterSettings, quietOn: Boolean): Notice = when {
    !access -> Notice.ACCESS
    f.alertsPaused -> Notice.PAUSED
    quietOn -> Notice.QUIET
    else -> Notice.NONE
}

/** The words for a [Notice]; [quietStatus] is Quiet's own status ("On until 16:37"). */
internal fun noticeLine(n: Notice, quietStatus: String = ""): String? = when (n) {
    Notice.ACCESS -> "Alerts are off until Notification access is allowed."
    Notice.PAUSED -> "Alerts paused · calls still light"
    Notice.QUIET -> "Quiet " + quietStatus.removePrefix("On ")
    Notice.NONE -> null
}

/** The alerts waiting besides the one showing: the shown alert is never counted twice (row 49). */
internal fun othersWaiting(showing: DisplayRequest?, waiting: List<String>): List<String> {
    if (showing?.id != AlertPlayer.ID) return waiting
    val i = waiting.indexOf(showing.label)
    return if (i < 0) waiting else waiting.filterIndexed { j, _ -> j != i }
}

/** "2 more waiting: WhatsApp, Gmail", three names at most, then "+N more". */
internal fun waitingText(others: List<String>, showing: Boolean): String? {
    if (others.isEmpty()) return null
    val names = others.take(3).joinToString(", ") + if (others.size > 3) " +${others.size - 3} more" else ""
    return (if (showing) "${others.size} more waiting: " else "${others.size} waiting: ") + names
}

/** The line under the matrix when the Glyph can't light, or nothing of ours shows (then [notice] decides). */
internal fun nowLine(n: HomeNow, device: DeviceState, notice: String? = null, nowMs: Long = System.currentTimeMillis()): String = when {
    device == DeviceState.OTHER -> "Designs play here on screen."
    device == DeviceState.SERVICE_MISSING -> "Update Nothing OS to light the Glyph."
    device == DeviceState.GLYPH_OFF -> "Nothing can light the Glyph until it's on in Settings."
    n.busy != null -> "The Glyph is busy (Nothing: ${n.busy}). " +
        (n.showing?.let { showingName(it, nowMs) + " shows when it ends." } ?: "Yours shows when it ends.")
    n.showing != null -> "Showing: " + showingName(n.showing, nowMs) + (waitingText(othersWaiting(n.showing, n.waiting), true)?.let { " · $it" } ?: "")
    notice != null -> notice
    n.waiting.isNotEmpty() -> waitingText(n.waiting, false)!!
    else -> "Nothing on the Glyph. Alerts appear here as they arrive."
}

/** What a request is called on Home: the alert's own words, "Torch · off in 4:12", the library design's name. */
internal fun showingName(r: DisplayRequest, nowMs: Long): String = when (r.id) {
    TorchTileService.ID -> torchLine(nowMs, prefix = "Torch")
    LiveVisualiser.ID -> "Visualiser"
    else -> r.label.removePrefix("Library: ").removePrefix("Try it: ")
}

/** "4:12": what is left of the torch's auto-off ([offMs], 5 minutes unless the Torch page says otherwise). */
internal fun torchLeft(nowMs: Long, since: Long = TorchTileService.onSince, offMs: Long = TorchTileService.AUTO_OFF_MS): String {
    val left = ((since + offMs - nowMs) / 1000).coerceIn(0, offMs / 1000)
    return "%d:%02d".format(left / 60, left % 60)
}

/** "Torch · off in 4:12", or "Torch · stays on" with no auto-off; [prefix] "On" for the tile. */
internal fun torchLine(nowMs: Long, prefix: String = "On", offMs: Long? = TorchTileService.autoOffMs()): String =
    if (offMs == null) "$prefix · stays on" else "$prefix · off in " + torchLeft(nowMs, offMs = offMs)

/** The one red button while something of ours shows: "Turn off torch", "Stop all" (alerts waiting) or "Stop". */
internal fun stopLabel(n: HomeNow): String? {
    if (n.busy != null || (n.showing == null && n.waiting.isEmpty())) return null
    val alerts = othersWaiting(n.showing, n.waiting).size + if (n.showing?.id == AlertPlayer.ID) 1 else 0
    return when {
        alerts >= 2 -> "Stop all"
        n.showing?.id == TorchTileService.ID -> "Turn off torch"
        else -> "Stop"
    }
}

/** Stop (one thing) or Stop all (alerts waiting): the light goes, the phone's notifications stay (row 60). */
internal fun stop(n: HomeNow, ctx: Context) {
    val top = n.showing
    when {
        stopLabel(n) == "Stop all" -> {
            AppGraph.stopAll()
            if (top != null && top.id != AlertPlayer.ID) stopOne(top, ctx)
        }
        top == null || top.id == AlertPlayer.ID -> AppGraph.stopAll()
        else -> stopOne(top, ctx)
    }
}

private fun stopOne(r: DisplayRequest, ctx: Context) = when (r.id) {
    LiveVisualiser.ID -> LiveVisualiser.stop(ctx)
    // a timer ends (ours) or stops showing (the Clock app's); a timer that has ended is dismissed
    com.dotforge.timer.GlyphTimers.ID, com.dotforge.timer.GlyphTimers.DONE_ID -> com.dotforge.timer.GlyphTimers.stopShown()
    else -> AppGraph.arbiter.remove(r.id)
}

/** Quiet's status now. */
@Composable
private fun quietNow(f: FilterSettings): String =
    QuietText.status(f, Conditions(LocalDateTime.now(), dnd = false, locked = false, faceDown = null))

/**
 * The Glyph now: a 200 dp matrix (160 dp on short screens) mirroring the real one, the status line in priority
 * order, and the one red button (Stop, Stop all, Turn off torch) while something of ours shows; when nothing
 * does, "Choose a design". Nothing's own light only gets the line: there is nothing of ours to stop.
 */
@Composable
private fun NowCard(n: HomeNow, s: HomeStatus, setup: Setup, onChoose: () -> Unit) {
    val ctx = LocalContext.current
    val filters by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val quiet = quietNow(filters)
    val note = notice(s.access, filters, quiet.startsWith("On"))
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // the torch counts down on Home
    LaunchedEffect(n.showing?.id) { while (n.showing?.id == TorchTileService.ID) { delay(1_000); clock = System.currentTimeMillis() } }
    val lit = s.device == DeviceState.PHONE3
    val noticeText = noticeLine(note, quiet)
    val line = nowLine(n, s.device, noticeText, clock)
    // the notice is the line itself when nothing shows; under "Showing" it gets its own row. Missing access is
    // only said here: the permission banner above is the one place that asks for it
    val asks = note != Notice.NONE && note != Notice.ACCESS
    val noticeRow = lit && n.busy == null && n.showing != null && asks
    val lineIsNotice = lit && n.busy == null && n.showing == null && asks
    val stop = if (lit) stopLabel(n) else null
    val side = if (LocalConfiguration.current.screenHeightDp < 700) 160.dp else Tokens.PreviewLarge
    RowGroup(Modifier.padding(bottom = Tokens.Gap)) {
        Column(Modifier.fillMaxWidth().padding(Tokens.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            DotMatrix(
                n.frame?.pixels, AppGraph.profile,
                Modifier.size(side).alpha(if (n.busy != null) 0.35f else 1f).semantics { contentDescription = "The Glyph now. $line" },
            )
            if (lineIsNotice) NoticeRow(note, noticeText!!, setup, Modifier.padding(top = Tokens.Gap))
            else Text(
                line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Tokens.Gap).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (noticeRow) NoticeRow(note, noticeText!!, setup, Modifier.padding(top = Tokens.Small))
            if (stop != null) {
                PrimaryButton(stop, Modifier.fillMaxWidth().padding(top = Tokens.Gap)) { stop(n, ctx) }
                val alerts = n.showing?.id == AlertPlayer.ID || n.waiting.isNotEmpty()
                if (alerts) Text(
                    if (stop == "Stop all") "Stops the lights. Your notifications stay." else "Stops the light. Your notification stays.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Tokens.Tiny),
                )
            } else if (n.showing == null && n.busy == null) {
                SecondaryButton("Choose a design", Modifier.fillMaxWidth().padding(top = Tokens.Gap)) { onChoose() }
            }
        }
    }
}

/** A status that stops alerts: an icon, the words (never colour alone), and its fix. */
@Composable
private fun NoticeRow(n: Notice, text: String, setup: Setup, modifier: Modifier = Modifier) {
    val (action, go) = when (n) {
        Notice.PAUSED -> "Turn on" to { AppGraph.filters.updateFilters { it.copy(alertsPaused = false) } }
        else -> null to {}
    }
    Surface(color = Tokens.Raised, shape = Tokens.CardShape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Tiny, bottom = Tokens.Tiny), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = Color.White, modifier = Modifier.size(Tokens.Icon))
            Text(text, Modifier.weight(1f).padding(horizontal = Tokens.Gap, vertical = Tokens.Small), style = MaterialTheme.typography.bodyMedium, color = Tokens.Text)
            if (action != null) TextButton(onClick = go, modifier = Modifier.heightIn(min = Tokens.Target)) {
                Text(action, style = MaterialTheme.typography.labelLarge.capped(), color = Color.White)
            }
        }
    }
}

/**
 * "Call screening and Keep running need permission" (names when two or fewer), "3 things need permission", or
 * null when every core permission is allowed (row 51: one slim line, not a card).
 */
internal fun permissionBanner(missing: List<String>): String? = com.dotforge.setup.Perms.needLine(missing)

/** Home's one slim line while a core permission is missing: a warning, the count and Fix, opening Permissions. */
@Composable
private fun PermissionBanner(text: String, onFix: () -> Unit) =
    Surface(color = Tokens.Raised, shape = Tokens.CardShape, modifier = Modifier.fillMaxWidth().padding(bottom = Tokens.Gap)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.Target)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Fix", onClick = onFix)
                .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = Tokens.Small, bottom = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Gap),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = Color.White, modifier = Modifier.size(20.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("Fix", style = MaterialTheme.typography.labelLarge.capped(), color = Color.White)
        }
    }

/**
 * The setup checklist (first run's skipped steps; row 51, parity row 66): only the lines still to do, one
 * button each, "N of 2 done", and Hide. It goes once both are done. Permissions are the banner's.
 */
@Composable
private fun SetupChecklist(s: HomeStatus, setup: Setup, nav: Nav, onDoubleLights: () -> Unit, onHide: () -> Unit) {
    data class Line(val title: String, val why: String, val done: Boolean, val button: String, val go: () -> Unit)
    val lines = listOf(
        Line("Pick your apps", "Which apps light up, each with its own glyph", s.appsPicked, "Pick") { nav.push(Page.AlertApps("app")) },
        Line("Stop double lights", "So Nothing's own light doesn't show on top of yours", s.doubleLights, "Fix", onDoubleLights),
    )
    RowGroup(Modifier.padding(bottom = Tokens.Gap)) {
        Row(Modifier.padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Small), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Finish setting up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text("${lines.count { it.done }} of ${lines.size} done", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
            }
            TextButton(onClick = onHide, modifier = Modifier.heightIn(min = Tokens.Target)) {
                Text("Hide", style = MaterialTheme.typography.labelLarge.capped(), color = Tokens.Secondary)
            }
        }
        lines.filterNot { it.done }.forEach { l ->
            RowDivider()
            ListRow(
                l.title, subtitle = l.why, titleLines = 3, subtitleLines = 4,
                trailing = {
                    TextButton(onClick = l.go, modifier = Modifier.heightIn(min = Tokens.Target)) {
                        Text(l.button, style = MaterialTheme.typography.labelLarge.capped(), color = Color.White)
                    }
                },
            )
        }
    }
}

/** What a quick switch shows at its end: a switch, or one action (Allow, Turn on) when it can't work yet. */
private sealed interface TileControl {
    data class Toggle(val checked: Boolean, val enabled: Boolean = true, val onToggle: (Boolean) -> Unit) : TileControl
    data class Action(val label: String, val description: String, val go: () -> Unit) : TileControl
}

/**
 * A quick switch (rows 72, 73): the name (2 lines at most) with its switch or action in a fixed end column, the
 * status pinned to the bottom with a › (the tile opens its page). A long name shrinks rather than break inside
 * a word, so every tile keeps one layout. The status (3 lines at most, for 2x text) sits on the tile's bottom edge, so the
 * statuses of a row share a baseline; the row takes its tallest tile's height.
 */
@Composable
private fun HomeTile(title: String, status: String, control: TileControl, modifier: Modifier = Modifier, enabled: Boolean = true, openLabel: String = "Open settings", onOpen: () -> Unit) {
    val haptic = rememberHaptics()
    val labelStyle = MaterialTheme.typography.labelLarge.capped()
    Surface(color = Tokens.Card, shape = Tokens.CardShape, modifier = modifier.heightIn(min = Tokens.Tile)) {
        Column(
            Modifier.fillMaxSize()
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                .padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Tiny, bottom = Tokens.Gap),
        ) {
            // the name shrinks (to 60% at most) rather than break inside a word, so every tile keeps one layout: its
            // room is the tile's width (two to a row) less the padding and the switch or action beside it
            val base = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = LocalDensity.current
            val screen = LocalConfiguration.current.screenWidthDp.dp
            // sizes are worked out in pixels: Android scales large text non-linearly, so a smaller sp isn't
            // proportionally narrower
            val nameStyle = remember(title, control::class, screen, density, base) {
                with(density) {
                    val tile = (screen - Tokens.Gutter * 2 - Tokens.Gap) / 2
                    val beside = when (control) {
                        is TileControl.Toggle -> 64.dp
                        is TileControl.Action -> measurer.measure(control.label, labelStyle).size.width.toDp() + 24.dp
                    }
                    val room = (tile - Tokens.Gutter - Tokens.Tiny - beside).toPx() * 0.95f
                    val widest = title.split(' ').maxOf { measurer.measure(it, base).size.width }.toFloat()
                    val k = (room / widest).coerceIn(0.6f, 1f)
                    if (k >= 1f) base
                    else base.copy(fontSize = (base.fontSize.toPx() * k).toSp(), lineHeight = (base.lineHeight.toPx() * k).toSp())
                }
            }
            val name: @Composable (Modifier) -> Unit = { m ->
                Text(
                    title, m, style = nameStyle,
                    color = if (enabled) Tokens.Text else Tokens.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val end: @Composable () -> Unit = {
                when (control) {
                    is TileControl.Toggle -> Box(
                        Modifier.size(width = 64.dp, height = Tokens.Target)
                            .toggleable(control.checked, enabled = control.enabled, role = Role.Switch) { haptic.toggle(it); control.onToggle(it) }
                            .semantics {
                                contentDescription = title
                                customActions = listOf(CustomAccessibilityAction(openLabel) { onOpen(); true })
                            },
                        contentAlignment = Alignment.Center,
                    ) { Switch(checked = control.checked, onCheckedChange = null, enabled = control.enabled, colors = switchColors()) }
                    is TileControl.Action -> TextButton(
                        onClick = control.go,
                        modifier = Modifier.heightIn(min = Tokens.Target).semantics { contentDescription = control.description },
                    ) { Text(control.label, style = labelStyle, color = Color.White, maxLines = 1) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                name(Modifier.weight(1f).padding(top = Tokens.Gap))
                end()
            }
            Spacer(Modifier.weight(1f).heightIn(min = Tokens.Tiny))
            // the › runs on after the status (it opens the tile's page), so it never takes a column of its own
            Text(
                "$status ›", Modifier.padding(end = Tokens.Small).semantics { contentDescription = status },
                style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Two tiles side by side, as tall as the taller one. */
@Composable
private fun HomeTileRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) =
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(bottom = Tokens.Gap),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Gap),
    ) {
        first(Modifier.weight(1f).fillMaxHeight())
        second(Modifier.weight(1f).fillMaxHeight())
    }

/** What the Glyph tiles say on a phone where the Glyph can't light (row 67); null on a working Phone (3). */
internal fun glyphNote(d: DeviceState): String? = when (d) {
    DeviceState.PHONE3 -> null
    DeviceState.OTHER -> "Needs Phone (3)"
    DeviceState.SERVICE_MISSING -> "Update Nothing OS"
    DeviceState.GLYPH_OFF -> "Glyph is off"
}

/**
 * The quick switches, equal tiles two to a row (rows 72, 73): each has its name, one status line and a switch,
 * or an Allow when a permission is missing; the tile opens its page. Where the Glyph can't light, every tile
 * reads why (and, when the Glyph is off or Nothing OS is old, offers the fix).
 */
@Composable
private fun QuickSwitches(s: HomeStatus, n: HomeNow, setup: Setup, nav: Nav) {
    val ctx = LocalContext.current
    val snacks = rememberSnacks()
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val filters by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val live by LiveVisualiser.running.collectAsStateWithLifecycle()
    val music by LiveVisualiser.music.collectAsStateWithLifecycle()
    val battery by AppGraph.system.chargingMeter.collectAsStateWithLifecycle()
    val progress by AppGraph.progressRings.collectAsStateWithLifecycle()
    val note = glyphNote(s.device)
    val torchOn = active.any { it.id == TorchTileService.ID } || n.showing?.id == TorchTileService.ID
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(torchOn) { while (torchOn) { delay(1_000); clock = System.currentTimeMillis() } }
    val cond = Conditions(LocalDateTime.now(), dnd = false, locked = false, faceDown = null)
    val quietStatus = QuietText.status(filters, cond)
    val quietOn = quietStatus.startsWith("On")
    val apps = rules.count { it.enabled && SimpleRules.isAppRule(it) }

    /**
     * A tile for a phone whose Glyph can't light: the reason, a switch that can't be used, and the tile opens the
     * fix (Glyph settings, the system update) when there is one, else its page.
     */
    @Composable
    fun blocked(title: String, page: () -> Unit, m: Modifier) = when (s.device) {
        DeviceState.GLYPH_OFF -> HomeTile(title, note!!, TileControl.Toggle(false, enabled = false) {}, m, enabled = false, openLabel = "Open Glyph settings") { openGlyphSettings(ctx) }
        DeviceState.SERVICE_MISSING -> HomeTile(title, note!!, TileControl.Toggle(false, enabled = false) {}, m, enabled = false, openLabel = "Check for updates") { openUpdates(ctx) }
        else -> HomeTile(title, note!!, TileControl.Toggle(false, enabled = false) {}, m, enabled = false, onOpen = page)
    }

    val torchPage = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("torch")) }
    val alertsPage = { nav.popTo(Tab.ALERTS) }
    val quietPage = { nav.popTo(Tab.ALERTS); nav.push(Page.Quiet) }
    val musicPage = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("music")) }
    val batteryPage = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("battery")) }
    val progressPage = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("progress")) }

    HomeTileRow({ m ->
        if (note != null) blocked("Torch", torchPage, m)
        else HomeTile("Torch", if (torchOn) torchLine(clock) else "Off", TileControl.Toggle(torchOn) { TorchTileService.toggle() }, m, onOpen = torchPage)
    }, { m ->
        if (note != null) blocked("Alerts", alertsPage, m)
        // the banner is the one place that asks for a core permission: the tile only says why it's off
        else if (!s.access) HomeTile("Alerts", "Off · needs permission", TileControl.Toggle(false, enabled = false) {}, m, onOpen = alertsPage)
        else HomeTile(
            "Alerts", if (filters.alertsPaused) "Paused · calls still light" else "On · ${if (apps == 1) "1 app" else "$apps apps"}",
            TileControl.Toggle(!filters.alertsPaused) { on ->
                AppGraph.filters.updateFilters { it.copy(alertsPaused = !on) }
                if (!on) snacks?.show("Alerts paused. Calls still light.") { AppGraph.filters.updateFilters { it.copy(alertsPaused = false) } }
            },
            m, onOpen = alertsPage,
        )
    })
    HomeTileRow({ m ->
        if (note != null) blocked("Quiet", quietPage, m)
        else HomeTile(
            "Quiet", quietStatus,
            TileControl.Toggle(quietOn) { on -> AppGraph.filters.updateFilters { f -> f.copy(quietUntil = if (on) QuietText.untilMs(f, cond) else 0L) } },
            m, onOpen = quietPage,
        )
    }, { m ->
        if (note != null) blocked("Visualiser", musicPage, m)
        else if (!s.microphone && !live) HomeTile("Visualiser", "Needs microphone", TileControl.Action("Allow", "Allow the microphone") { setup.startLive() }, m, onOpen = musicPage)
        else HomeTile(
            "Visualiser", when {
                !live -> "Off"
                !music -> "On · waiting for music"
                else -> "On · ${AppGraph.toys.visualStyle.label}"
            },
            TileControl.Toggle(live) { on -> if (on) setup.startLive() else LiveVisualiser.stop(ctx) },
            m, onOpen = musicPage,
        )
    })
    HomeTileRow({ m ->
        if (note != null) blocked("Battery", batteryPage, m)
        else HomeTile("Battery", if (battery) "Shows when charging" else "Off", TileControl.Toggle(battery) { AppGraph.system.setChargingMeter(it) }, m, onOpen = batteryPage)
    }, { m ->
        if (note != null) blocked("Live progress", progressPage, m)
        else if (progress && !s.access) HomeTile("Live progress", "Off · needs permission", TileControl.Toggle(false, enabled = false) {}, m, onOpen = progressPage)
        else HomeTile(
            "Live progress", if (progress) "On" else "Off",
            TileControl.Toggle(progress) { on -> AppGraph.setProgressRings(on); if (on) setup.needNotificationAccess() },
            m, onOpen = progressPage,
        )
    })
}

/** Spam blocked in the last 7 days, from History (rows 52, 74): calls and messages counted apart. */
internal fun blockedThisWeek(entries: List<com.dotforge.rules.HistoryEntry>, nowMs: Long = System.currentTimeMillis()): Pair<Int, Int> {
    val week = entries.filter { it.outcome == Outcome.BLOCKED && it.at > nowMs - WEEK_MS && it.at <= nowMs }
    return week.count { it.call } to week.count { !it.call }
}

private const val WEEK_MS = 7 * 24 * 3_600_000L

/**
 * "3 spam calls blocked this week", messages or both; at 0, that the filter is on (row 74, so 0 doesn't read
 * as off); without call screening, that spam calls aren't being blocked (row 68).
 */
internal fun spamLine(calls: Int, messages: Int, screening: Boolean = true): String {
    val n = calls + messages
    return when {
        !screening && n == 0 -> "Spam calls aren't being blocked"
        n == 0 -> "Spam filter on · nothing blocked this week"
        messages == 0 -> if (n == 1) "1 spam call blocked this week" else "$n spam calls blocked this week"
        calls == 0 -> if (n == 1) "1 spam message blocked this week" else "$n spam messages blocked this week"
        else -> "$n spam calls and messages blocked this week"
    }
}

/** The two quiet lines: spam blocked this week (opens History filtered to Blocked) and what Always-on shows. */
@Composable
private fun Lines(s: HomeStatus, setup: Setup, nav: Nav, alwaysOn: Pair<String, Frame?>, onAlwaysOn: () -> Unit) {
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val (calls, messages) = blockedThisWeek(history)
    RowGroup(Modifier.padding(top = Tokens.Tiny)) {
        val line = spamLine(calls, messages, s.screening)
        if (!s.screening && calls + messages == 0) {
            ListRow(line, titleLines = 3, trailing = { ActionText("Allow") }) { setup.needCallScreening() }
        } else ListRow(line, titleLines = 3, trailing = { Chevron() }) { nav.showHistory("blocked") }
        RowDivider()
        val other = s.alwaysOn == false
        ListRow(
            if (other) "Always-on: set in Nothing's Glyph Toys" else "Always-on: ${alwaysOn.first}",
            titleLines = 3,
            leading = if (other) null else ({ DotMatrix(alwaysOn.second?.pixels, AppGraph.profile, Modifier.size(Tokens.Avatar)) }),
            trailing = { ActionText("Change") },
        ) { onAlwaysOn() }
    }
}

/** A row's trailing action word (the whole row is the target, at least 64 dp tall). */
@Composable
private fun ActionText(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge.capped(), color = Color.White, modifier = Modifier.padding(end = Tokens.Gap))

/** What Always-on shows: the face's name and picture, or the one design picked for it. */
internal fun alwaysOnName(p: DeviceProfile = AppGraph.profile): Pair<String, Frame?> {
    val face = AppGraph.toys.hubFace
    val one = AppGraph.toys.galleryIds.singleOrNull()
    if (face == HubFace.GALLERY && one != null) {
        if (one.startsWith(LIBRARY_PREFIX)) AnimationLibrary.item(one.removePrefix(LIBRARY_PREFIX))?.let { return it.name to AnimationLibrary.thumbnail(it, p) }
        AppGraph.designs.get(one)?.let { d -> return d.name to AppGraph.designs.animation(d)?.let(com.dotforge.device.Posters::of) }
    }
    return face.label to facePreview(face, p)
}

/** A still of each Always-on face, for the line and the sheet. */
internal fun facePreview(face: HubFace, p: DeviceProfile = AppGraph.profile): Frame? = when (face) {
    HubFace.CLOCK -> LocalDateTime.now().let { ToyFaces.clock(p, AppGraph.toys.clockStyle, it.hour, it.minute, AppGraph.toys.clock24h) }
    HubFace.MOON -> ToyFaces.moon(p, ToyFaces.moonAge(java.time.LocalDate.now().toEpochDay() + 0.5))
    HubFace.NOW_PLAYING -> ToyFaces.nowPlaying(p, true, 0.4f)
    HubFace.GALLERY -> com.dotforge.toys.galleryAnimations().firstOrNull()?.let(com.dotforge.device.Posters::of)
}

/** Your recent picks, then Featured: one compact row of 4 small tiles (row 9 of the review: no carousel). */
@Composable
private fun RecentRow(onOpen: (String) -> Unit) {
    val recent = LibraryPrefs.recent.toList()
    val picks = remember(recent) {
        (recent.mapNotNull(AnimationLibrary::item) + AnimationLibrary.items(Categories.Featured)).distinctBy { it.id }.take(4)
    }
    // at large text a name keeps one line rather than break inside a word
    val large = LocalDensity.current.fontScale >= 1.3f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
        picks.forEach { item ->
            val still = remember(item.id) { AnimationLibrary.thumbnail(item, AppGraph.profile) }
            Column(
                Modifier.weight(1f).clickable(onClickLabel = "Open", role = Role.Button) { onOpen(item.id) }.padding(vertical = Tokens.Tiny)
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DotMatrix(still.pixels, AppGraph.profile, Modifier.sizeIn(maxWidth = 64.dp).fillMaxWidth().aspectRatio(1f))
                Text(
                    item.name, Modifier.padding(top = Tokens.Tiny), style = MaterialTheme.typography.labelMedium, color = Tokens.Secondary,
                    textAlign = TextAlign.Center, maxLines = if (large) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Always-on's faces, your designs and Library favourites (parity row 65): every option with its picture. */
@Composable
internal fun AlwaysOnSheet(onDismiss: () -> Unit) {
    var face by remember { mutableStateOf(AppGraph.toys.hubFace) }
    var gallery by remember { mutableStateOf(AppGraph.toys.galleryIds) }
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    val p = AppGraph.profile
    fun pick(key: String) {
        face = HubFace.GALLERY
        gallery = setOf(key)
        AppGraph.toys.galleryIds = gallery
        AppGraph.toys.hubFace = HubFace.GALLERY
    }
    FullSheet(onDismiss, "Always-on", subtitle = "What the Glyph shows when the screen is off. It changes once a minute.") {
        HubFace.entries.forEach { f ->
            val still = remember(f) { facePreview(f, p) }
            OptionRow(
                f.label, f.hint,
                preview = { DotMatrix(still?.pixels, p, Modifier.size(Tokens.Avatar)) },
                selected = face == f && !(f == HubFace.GALLERY && gallery.size == 1), chevron = false,
            ) {
                face = f
                AppGraph.toys.hubFace = f
                if (f == HubFace.GALLERY) { gallery = emptySet(); AppGraph.toys.galleryIds = emptySet() }
            }
        }
        if (designs.isNotEmpty()) {
            SectionLabel("Your designs")
            designs.take(6).forEach { d ->
                val poster = remember(d.id) { AppGraph.designs.animation(d)?.let(com.dotforge.device.Posters::of) }
                OptionRow(
                    d.name, if (d.animated) "Shows still" else null,
                    preview = { DotMatrix(poster?.pixels, p, Modifier.size(Tokens.Avatar)) },
                    selected = face == HubFace.GALLERY && gallery == setOf(d.id), chevron = false,
                ) { pick(d.id) }
            }
        }
        val favourites = LibraryPrefs.favourites.mapNotNull(AnimationLibrary::item).take(6)
        if (favourites.isNotEmpty()) {
            SectionLabel("Library favourites")
            favourites.forEach { item ->
                val key = LIBRARY_PREFIX + item.id
                OptionRow(
                    item.name, if (item.animated) "Shows still" else null,
                    preview = { DotMatrix(AnimationLibrary.thumbnail(item, p).pixels, p, Modifier.size(Tokens.Avatar)) },
                    selected = face == HubFace.GALLERY && gallery == setOf(key), chevron = false,
                ) { pick(key) }
            }
        }
        if (designs.isEmpty() && favourites.isEmpty()) Hint("Designs you save or favourite in the Library can show here too.", Modifier.padding(top = Tokens.Gap))
    }
}

/** The round profile avatar at the top right of Home: a 48 dp target. */
@Composable
fun ProfileButton(onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(Tokens.Target).semantics { contentDescription = "Profile and settings" }) { PersonAvatar() }
