package com.dotforge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.AppGraph
import com.dotforge.device.Frame
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.device.library.Categories
import com.dotforge.engine.AlertPlayer
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.NothingLights
import com.dotforge.rules.Conditions
import com.dotforge.rules.Outcome
import com.dotforge.rules.QuietText
import com.dotforge.rules.SimpleRules
import com.dotforge.tile.TorchTileService
import com.dotforge.toys.AlwaysOn
import com.dotforge.toys.HubFace
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.Tab
import com.dotforge.ui.alerts.DeviceState
import com.dotforge.ui.alerts.deviceState
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.DotMatrix
import com.dotforge.ui.design.EqualTile
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.PersonAvatar
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowDivider
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Screen
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.TilePair
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.capped
import com.dotforge.ui.library.LibraryItemSheet
import com.dotforge.ui.library.LibraryPickerSheet
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.ui.library.LibraryTile
import com.dotforge.visualiser.LiveVisualiser
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What Home knows about the phone: the device (row 67), the setup checklist's four lines and whether DF
 * Always-on is the always-on toy. [HomeStatus.of] reads it live; screenshots pass their own.
 */
data class HomeStatus(
    val device: DeviceState = DeviceState.PHONE3,
    val access: Boolean = true,
    val screening: Boolean = true,
    val appsPicked: Boolean = true,
    val keepRunning: Boolean = true,
    val microphone: Boolean = true,
    /** DF Always-on is the always-on toy (true), another is (false), or the phone can't say (null). */
    val alwaysOn: Boolean? = null,
) {
    /** The checklist's lines that are still to do. */
    val todo: Int get() = listOf(access, screening, appsPicked, keepRunning).count { !it }
}

/** What the Glyph is doing: the frame, what shows it, the alerts waiting until checked, and Nothing's own light. */
data class HomeNow(
    val frame: Frame? = null,
    val showing: DisplayRequest? = null,
    val waiting: List<String> = emptyList(),
    val busy: String? = null,
)

/**
 * Home (production design > Home, rows 49, 50, 60, 67, 73; parity rows 65, 66): the title and the avatar, the
 * phone warnings, the Glyph now (a 200 dp live matrix, one line, Stop or Stop all), the setup checklist until
 * it's done, the quick switches as equal tiles, the spam and always-on lines, then your recent and Featured
 * designs with the one "Choose a design" button. [status] and [now] are for screenshots.
 */
@Composable
fun HomeScreen(nav: Nav, setup: Setup, state: LazyListState, status: HomeStatus? = null, now: HomeNow? = null) {
    val ctx = LocalContext.current
    val s = status ?: run {
        val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
        @Suppress("UNUSED_EXPRESSION") setup.resumes
        val device = remember { deviceState(ctx) }
        HomeStatus(
            device = device, access = setup.notificationAccess, screening = setup.screening,
            appsPicked = rules.any { it.enabled && SimpleRules.isAppRule(it) && it.packages.isNotEmpty() },
            keepRunning = setup.keepRunning, microphone = setup.microphone,
            alwaysOn = remember(setup.resumes) { AlwaysOn.ours(ctx) },
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
    var picking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var useFor by remember { mutableStateOf<com.dotforge.studio.Design?>(null) }
    var mine by remember { mutableStateOf<com.dotforge.studio.Design?>(null) }
    var alwaysOnSheet by remember { mutableStateOf(false) }
    // read again when the sheet closes
    val aodName = remember(alwaysOnSheet) { alwaysOnName() }
    Screen("DotForge", state = state, actions = { ProfileButton { nav.push(Page.Profile) } }) {
        deviceBanner(s.device)?.let { (text, button) ->
            item(key = "device") {
                StatusBanner(text, Modifier.padding(bottom = Tokens.Gap), button = button) {
                    if (s.device == DeviceState.GLYPH_OFF) {
                        val link = com.dotforge.system.NothingSettings.glyphNotificationSettings(ctx.packageManager)
                        runCatching { ctx.startActivity(link.intent) }
                    } else runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                }
            }
        }
        item(key = "now") { NowCard(n, s.device) }
        if (s.todo > 0) item(key = "setup") { SetupChecklist(s, setup, nav) }
        item(key = "switches-label") { SectionLabel("Quick switches") }
        item(key = "switches") { QuickSwitches(s, n, setup, nav) }
        item(key = "lines") { Lines(s, setup, nav, aodName, onAlwaysOn = {
            if (s.alwaysOn == false) {
                val i = AlwaysOn.setUpIntent(ctx)
                if (i != null) runCatching { ctx.startActivity(i) } else alwaysOnSheet = true
            } else alwaysOnSheet = true
        }) }
        item(key = "designs-label") { SectionLabel("Show a design") }
        item(key = "designs") { DesignStrip { open = it } }
        // one way in: the same picker as everywhere else, with Library and Yours
        item(key = "choose") { PrimaryButton("Choose a design", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { picking = true } }
    }
    if (picking) LibraryPickerSheet("Choose a design", onDismiss = { picking = false }, onPickYours = { d -> picking = false; mine = d }) { item -> picking = false; open = item.id }
    mine?.let { d -> com.dotforge.ui.studio.UseForSheet(d, nav, fresh = false) { mine = null } }
    open?.let(AnimationLibrary::item)?.let { item ->
        LibraryItemSheet(item, nav, onDismiss = { open = null }) { d -> open = null; useFor = d }
    }
    useFor?.let { d -> com.dotforge.ui.studio.UseForSheet(d, nav, fresh = true, unsaved = true) { useFor = null } }
    if (alwaysOnSheet) AlwaysOnSheet(onDismiss = { alwaysOnSheet = false })
}

/** The warning at the top for a phone that can't light the Glyph (row 67), with its one action. */
private fun deviceBanner(d: DeviceState): Pair<String, String?>? = when (d) {
    DeviceState.PHONE3 -> null
    DeviceState.OTHER -> "DotForge lights the Glyph Matrix on Nothing Phone (3). On this phone you can still design and block spam." to null
    DeviceState.SERVICE_MISSING -> "Update Nothing OS to use the Glyph" to "Check for updates"
    DeviceState.GLYPH_OFF -> "The Glyph is off in Settings" to "Open Glyph settings"
}

/** The one line under the matrix: what shows, or why nothing can. */
internal fun nowLine(n: HomeNow, device: DeviceState, nowMs: Long = System.currentTimeMillis()): String = when {
    device == DeviceState.OTHER || device == DeviceState.SERVICE_MISSING -> "The Glyph needs Phone (3). Designs play here on screen."
    device == DeviceState.GLYPH_OFF -> "The Glyph is off in Settings, so nothing can light it."
    n.busy != null -> "The Glyph is busy (Nothing: ${n.busy}). Yours shows again after."
    n.showing != null -> "Showing: " + showingName(n.showing, nowMs)
    else -> "Nothing on the Glyph. Alerts appear here as they arrive."
}

/** What a request is called on Home: the alert's own words, "Torch · off in 4:12", the library design's name. */
internal fun showingName(r: DisplayRequest, nowMs: Long): String = when (r.id) {
    TorchTileService.ID -> "Torch · off in " + torchLeft(nowMs)
    LiveVisualiser.ID -> "Visualiser"
    else -> r.label.removePrefix("Library: ").removePrefix("Try it: ")
}

/** "4:12": what is left of the torch's 5 minutes. */
internal fun torchLeft(nowMs: Long, since: Long = TorchTileService.onSince): String {
    val left = ((since + TorchTileService.AUTO_OFF_MS - nowMs) / 1000).coerceIn(0, TorchTileService.AUTO_OFF_MS / 1000)
    return "%d:%02d".format(left / 60, left % 60)
}

/** "2 waiting: Mom, WhatsApp" when more than one alert is waiting until checked. */
internal fun waitingLine(waiting: List<String>): String? =
    if (waiting.size < 2) null else "${waiting.size} waiting: " + waiting.take(3).joinToString(", ") + if (waiting.size > 3) "…" else ""

/** Stop (one thing) or Stop all (alerts waiting): the light goes, the phone's notifications stay (row 60). */
internal fun stop(n: HomeNow, ctx: android.content.Context) {
    val top = n.showing
    when {
        n.waiting.size >= 2 -> {
            AppGraph.stopAll()
            if (top != null && top.id != AlertPlayer.ID) stopOne(top, ctx)
        }
        top == null -> AppGraph.stopAll()
        top.id == AlertPlayer.ID -> AppGraph.stopAll()
        else -> stopOne(top, ctx)
    }
}

private fun stopOne(r: DisplayRequest, ctx: android.content.Context) = when (r.id) {
    LiveVisualiser.ID -> LiveVisualiser.stop(ctx)
    else -> AppGraph.arbiter.remove(r.id)
}

/** The Glyph now: a 200 dp matrix mirroring the real one, one line, the waiting line and Stop. */
@Composable
private fun NowCard(n: HomeNow, device: DeviceState) {
    val ctx = LocalContext.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // the torch counts down on Home
    LaunchedEffect(n.showing?.id) { while (n.showing?.id == TorchTileService.ID) { delay(1_000); clock = System.currentTimeMillis() } }
    val line = nowLine(n, device, clock)
    val waiting = waitingLine(n.waiting)
    RowGroup(Modifier.padding(bottom = Tokens.Gap)) {
        Column(Modifier.fillMaxWidth().padding(Tokens.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            DotMatrix(n.frame?.pixels, AppGraph.profile, Modifier.size(Tokens.PreviewLarge).semantics { contentDescription = "The Glyph now. $line" })
            Text(
                line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Tokens.Gutter).semantics { liveRegion = LiveRegionMode.Polite },
            )
            waiting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Tiny)) }
            if (n.showing != null || n.waiting.isNotEmpty()) {
                val all = n.waiting.size >= 2
                SecondaryButton(if (all) "Stop all" else "Stop", Modifier.fillMaxWidth().padding(top = Tokens.Gap)) { stop(n, ctx) }
                if (all || n.showing?.id == AlertPlayer.ID) {
                    Text("Stops the light. Your notification stays.", style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Tokens.Tiny))
                }
            }
        }
    }
}

/** The setup checklist (first run's skipped steps, parity row 66): one line each, hidden once all are done. */
@Composable
private fun SetupChecklist(s: HomeStatus, setup: Setup, nav: Nav) {
    data class Line(val title: String, val why: String, val done: Boolean, val button: String, val go: () -> Unit)
    val lines = listOf(
        Line("Notification access", "So your apps can light the Glyph", s.access, "Turn on") { setup.needNotificationAccess() },
        Line("Call screening", "So spam calls are blocked and callers' names show", s.screening, "Allow") { setup.needCallScreening() },
        Line("Pick your apps", "Which apps light up, each with its own glyph", s.appsPicked, "Pick") { nav.push(Page.AlertApps("app")) },
        Line("Keep DotForge running", "So alerts, the torch timer and the visualiser keep working", s.keepRunning, "Allow") { setup.needKeepRunning() },
    )
    RowGroup(Modifier.padding(bottom = Tokens.Gap)) {
        Column(Modifier.padding(start = Tokens.Gutter, end = Tokens.Gutter, top = Tokens.Gutter)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("${lines.count { it.done }} of ${lines.size} done", style = MaterialTheme.typography.bodyMedium, color = Tokens.Secondary)
        }
        lines.forEachIndexed { i, l ->
            if (i > 0) RowDivider()
            ListRow(
                l.title, subtitle = if (l.done) "Done" else l.why, subtitleLines = 3,
                trailing = {
                    if (l.done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = Tokens.Secondary, modifier = Modifier.padding(end = Tokens.Gap))
                    else TextButton(onClick = l.go, modifier = Modifier.heightIn(min = Tokens.Target)) {
                        Text(l.button, style = MaterialTheme.typography.labelLarge.capped(), color = Color.White)
                    }
                },
            )
        }
    }
}

/**
 * The quick switches, equal tiles two to a row (row 73): each has its name, one status line and a switch; the
 * tile opens its page. On a phone that isn't a Phone (3) the ones that light the Glyph read "Needs Phone (3)".
 */
@Composable
private fun QuickSwitches(s: HomeStatus, n: HomeNow, setup: Setup, nav: Nav) {
    val ctx = LocalContext.current
    val active by AppGraph.arbiter.active.collectAsStateWithLifecycle()
    val filters by AppGraph.filters.filters.collectAsStateWithLifecycle()
    val rules by AppGraph.rules.rules.collectAsStateWithLifecycle()
    val live by LiveVisualiser.running.collectAsStateWithLifecycle()
    val music by LiveVisualiser.music.collectAsStateWithLifecycle()
    val battery by AppGraph.system.chargingMeter.collectAsStateWithLifecycle()
    val progress by AppGraph.progressRings.collectAsStateWithLifecycle()
    val phone3 = s.device == DeviceState.PHONE3
    val torchOn = active.any { it.id == TorchTileService.ID } || n.showing?.id == TorchTileService.ID
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(torchOn) { while (torchOn) { delay(1_000); clock = System.currentTimeMillis() } }
    val cond = Conditions(LocalDateTime.now(), dnd = false, locked = false, faceDown = null)
    val quietStatus = QuietText.status(filters, cond)
    val quietOn = quietStatus.startsWith("On")
    val apps = rules.count { it.enabled && SimpleRules.isAppRule(it) }
    TilePair({ m ->
        EqualTile(
            "Torch", if (!phone3) "Needs Phone (3)" else if (torchOn) "Off in ${torchLeft(clock)}" else "Off · 5 min max",
            m, statusLines = 2, checked = torchOn && phone3, enabled = phone3, onToggle = { TorchTileService.toggle() },
            onOpen = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("torch")) },
        )
    }, { m ->
        EqualTile(
            "Alerts", when {
                !s.access -> "Needs access"
                filters.alertsPaused -> "Paused · calls on"
                else -> "On · ${if (apps == 1) "1 app" else "$apps apps"}"
            },
            m, statusLines = 2, checked = !filters.alertsPaused && s.access,
            onToggle = { on ->
                if (on && !s.access) setup.needNotificationAccess()
                AppGraph.filters.updateFilters { it.copy(alertsPaused = !on) }
            },
            onOpen = { nav.popTo(Tab.ALERTS) },
        )
    })
    TilePair({ m ->
        EqualTile(
            "Quiet", quietStatus, m, statusLines = 2, checked = quietOn,
            onToggle = { on ->
                AppGraph.filters.updateFilters { f -> f.copy(quietUntil = if (on) QuietText.untilMs(f, cond) else 0L) }
            },
            onOpen = { nav.popTo(Tab.ALERTS); nav.push(Page.Quiet) },
        )
    }, { m ->
        EqualTile(
            "Visualiser", when {
                !phone3 -> "Needs Phone (3)"
                !s.microphone && !live -> "Needs the microphone"
                !live -> "Off"
                !music -> "On · waiting for music"
                else -> "On · ${AppGraph.toys.visualStyle.label}"
            },
            m, statusLines = 2, checked = live && phone3, enabled = phone3,
            onToggle = { on -> if (on) setup.startLive() else LiveVisualiser.stop(ctx) },
            onOpen = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("music")) },
        )
    })
    TilePair({ m ->
        EqualTile(
            "Battery", if (battery) "Shows when charging" else "Off", m, statusLines = 2, checked = battery,
            onToggle = { AppGraph.system.setChargingMeter(it) },
            onOpen = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("battery")) },
        )
    }, { m ->
        EqualTile(
            "Live progress", if (progress) (if (s.access) "Deliveries, timers" else "Needs access") else "Off", m, statusLines = 2, checked = progress,
            onToggle = { on -> AppGraph.setProgressRings(on); if (on) setup.needNotificationAccess() },
            onOpen = { nav.popTo(Tab.FEATURES); nav.push(Page.Feature("progress")) },
        )
    })
}

/** Spam blocked today, from History (row 52): calls and messages counted apart. */
internal fun blockedToday(entries: List<com.dotforge.rules.HistoryEntry>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
    val today2 = entries.filter { it.outcome == Outcome.BLOCKED && java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
    return today2.count { it.call } to today2.count { !it.call }
}

/**
 * "3 spam calls blocked today ›", messages or both; at 0, that the filter is on (row 74, so 0 doesn't read as
 * off); without call screening, that the filter needs it.
 */
internal fun spamLine(calls: Int, messages: Int, screening: Boolean = true): String {
    val n = calls + messages
    return when {
        !screening && n == 0 -> "Spam filter needs Call screening"
        n == 0 -> "Spam filter on · nothing blocked today"
        messages == 0 -> if (n == 1) "1 spam call blocked today" else "$n spam calls blocked today"
        calls == 0 -> if (n == 1) "1 spam message blocked today" else "$n spam messages blocked today"
        else -> "$n spam calls and messages blocked today"
    }
}

/** The two quiet lines: spam blocked today (opens History filtered to Blocked) and what Always-on shows. */
@Composable
private fun Lines(s: HomeStatus, setup: Setup, nav: Nav, alwaysOn: String, onAlwaysOn: () -> Unit) {
    val history by AppGraph.history.entries.collectAsStateWithLifecycle()
    val (calls, messages) = blockedToday(history)
    RowGroup(Modifier.padding(top = Tokens.Tiny)) {
        val line = spamLine(calls, messages, s.screening)
        if (!s.screening && calls + messages == 0) {
            ListRow(line, subtitle = "So spam calls are blocked", titleLines = 3, subtitleLines = 3, trailing = {
                Text("Turn on", style = MaterialTheme.typography.labelLarge.capped(), color = Color.White, modifier = Modifier.padding(end = Tokens.Gap))
            }) { setup.needCallScreening() }
        } else ListRow(line, subtitle = "History", titleLines = 3, trailing = { Chevron() }) { nav.showHistory("blocked") }
        RowDivider()
        ListRow(
            if (s.alwaysOn == false) "Always-on: another toy" else "Always-on: $alwaysOn",
            subtitle = if (s.alwaysOn == false) "Set up DF Always-on in Nothing's toys" else "What the Glyph shows when the screen is off",
            titleLines = 3, subtitleLines = 3,
            trailing = { Text(if (s.alwaysOn == false) "Set up" else "Change", style = MaterialTheme.typography.labelLarge.capped(), color = Color.White, modifier = Modifier.padding(end = Tokens.Gap)) },
        ) { onAlwaysOn() }
    }
}

/** What Always-on shows: the face, or the one design picked for it. */
internal fun alwaysOnName(): String {
    val face = AppGraph.toys.hubFace
    val one = AppGraph.toys.galleryIds.singleOrNull()
    if (face != HubFace.GALLERY || one == null) return face.label
    return if (one.startsWith(com.dotforge.toys.LIBRARY_PREFIX)) AnimationLibrary.item(one.removePrefix(com.dotforge.toys.LIBRARY_PREFIX))?.name ?: face.label
    else AppGraph.designs.get(one)?.name ?: face.label
}

/** Your recent picks first, then Featured; fewer, wider tiles at large text, so names wrap between words. */
@Composable
private fun DesignStrip(onOpen: (String) -> Unit) {
    val recent = LibraryPrefs.recent.toList()
    val picks = remember(recent) {
        (recent.mapNotNull(AnimationLibrary::item) + AnimationLibrary.items(Categories.Featured)).distinctBy { it.id }.take(4)
    }
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = (maxWidth / (80.dp * scale)).toInt().coerceIn(2, 4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            picks.take(n).forEach { item ->
                Box(Modifier.weight(1f)) {
                    LibraryTile(item, item.id in LibraryPrefs.favourites, false, "Open", onClick = { onOpen(item.id) }, onLongClick = { LibraryPrefs.toggleFavourite(item.id) })
                }
            }
        }
    }
}

/** Always-on's faces and your designs (parity row 65): one tick, and DF Always-on shows it from the next minute. */
@Composable
internal fun AlwaysOnSheet(onDismiss: () -> Unit) {
    var face by remember { mutableStateOf(AppGraph.toys.hubFace) }
    var gallery by remember { mutableStateOf(AppGraph.toys.galleryIds) }
    val designs by AppGraph.designs.designs.collectAsStateWithLifecycle()
    FullSheet(onDismiss, "Always-on", subtitle = "Shows when the screen is off, once a minute") {
        HubFace.entries.forEach { f ->
            OptionRow(f.label, f.hint, selected = face == f && !(f == HubFace.GALLERY && gallery.size == 1), chevron = false) {
                face = f
                AppGraph.toys.hubFace = f
                if (f == HubFace.GALLERY) { gallery = emptySet(); AppGraph.toys.galleryIds = emptySet() }
            }
        }
        if (designs.isNotEmpty()) {
            SectionLabel("One of your designs")
            designs.take(6).forEach { d ->
                val poster = remember(d.id) { AppGraph.designs.animation(d)?.let(com.dotforge.device.Posters::of) }
                OptionRow(
                    d.name, if (d.animated) "Shows still on Always-on" else null,
                    preview = { DotMatrix(poster?.pixels, AppGraph.profile, Modifier.size(Tokens.Avatar)) },
                    selected = face == HubFace.GALLERY && gallery == setOf(d.id), chevron = false,
                ) {
                    face = HubFace.GALLERY
                    gallery = setOf(d.id)
                    AppGraph.toys.galleryIds = gallery
                    AppGraph.toys.hubFace = HubFace.GALLERY
                }
            }
        }
        val favourites = LibraryPrefs.favourites.mapNotNull(AnimationLibrary::item).take(6)
        if (favourites.isNotEmpty()) {
            SectionLabel("Library favourites")
            favourites.forEach { item ->
                val key = com.dotforge.toys.LIBRARY_PREFIX + item.id
                OptionRow(
                    item.name, if (item.animated) "Shows still on Always-on" else null,
                    preview = { DotMatrix(AnimationLibrary.thumbnail(item, AppGraph.profile).pixels, AppGraph.profile, Modifier.size(Tokens.Avatar)) },
                    selected = face == HubFace.GALLERY && gallery == setOf(key), chevron = false,
                ) {
                    face = HubFace.GALLERY
                    gallery = setOf(key)
                    AppGraph.toys.galleryIds = gallery
                    AppGraph.toys.hubFace = HubFace.GALLERY
                }
            }
        }
        if (designs.isEmpty() && favourites.isEmpty()) Hint("Designs you save or favourite in the Library can show here too.", Modifier.padding(top = Tokens.Gap))
    }
}

/** The round profile avatar at the top right of Home. */
@Composable
fun ProfileButton(onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Profile and settings" }) { PersonAvatar() }
