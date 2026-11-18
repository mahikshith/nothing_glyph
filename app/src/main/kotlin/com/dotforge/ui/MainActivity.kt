package com.dotforge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.dotforge.diagnostics.DiagnosticsScreen
import com.dotforge.diagnostics.EventLog
import com.dotforge.ui.alerts.AlertsScreen
import com.dotforge.ui.design.ComponentGallery
import com.dotforge.ui.design.DotForgeTheme
import com.dotforge.ui.design.LocalReduceMotion
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.TopBar
import com.dotforge.ui.design.UndoSnackbarHost
import com.dotforge.ui.editor.EditorScreen
import com.dotforge.ui.features.FeaturePage
import com.dotforge.ui.features.FeaturesScreen
import com.dotforge.ui.home.HomeScreen
import com.dotforge.ui.library.LibraryScreen
import com.dotforge.ui.onboarding.FirstRun
import com.dotforge.ui.settings.BackupPage
import com.dotforge.ui.settings.LicencesPage
import com.dotforge.ui.settings.OldScreensPage
import com.dotforge.ui.settings.ProfilePage
import com.dotforge.ui.settings.rememberPermissions
import com.dotforge.ui.studio.StudioScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Files opened with or shared to DotForge, waiting to be routed by type (row 8). */
    private var incoming by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming = importUris(intent)
        enableEdgeToEdge()
        EventLog.init(this)
        val setup = Setup(this)
        this.setup = setup
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_LIVE, false) == true) pendingLive = true
        setContent {
            DotForgeTheme {
                val nav = rememberSaveable(saver = Nav.saver) { Nav() }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(incoming) {
                    val uris = incoming.ifEmpty { return@LaunchedEffect }
                    incoming = emptyList()
                    nav.popTo(Tab.STUDIO)
                    com.dotforge.ui.studio.Picked.route(ctx, nav, uris)?.let { msg -> nav.studioRequest = "message:$msg" }
                }
                var firstRunDone by rememberSaveable { mutableStateOf(false) }
                AppRoot(nav, setup)
                if (!firstRunDone) FirstRun(setup) { firstRunDone = true }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importUris(intent).takeIf { it.isNotEmpty() }?.let { incoming = it }
        if (intent.getBooleanExtra(EXTRA_LIVE, false)) pendingLive = true
    }

    override fun onResume() {
        super.onResume()
        // started here, once visible, so Android lets the foreground service start
        if (pendingLive) {
            pendingLive = false
            setup?.startLive()
        }
    }

    private var setup: Setup? = null
    private var pendingLive = false

    companion object {
        /** Sent by the Live Quick Settings tile when it needs the app (to ask for the microphone). */
        const val EXTRA_LIVE = "com.dotforge.extra.LIVE"
    }

    /** The files of an "Open with" (VIEW) or Share (SEND, SEND_MULTIPLE) intent. */
    private fun importUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

/** The whole app under the theme: the tabs or the open page, the snackbar host, and the setup sheets. */
@Composable
fun AppRoot(nav: Nav, setup: Setup) {
    val lists = Tab.entries.associateWith { rememberLazyListState() }
    // keeps each tab's remembered UI state while a page is open or another tab is shown
    val saved = rememberSaveableStateHolder()
    UndoSnackbarHost(Modifier, bottomBar = nav.page == null) {
        Box(Modifier.fillMaxSize().background(Tokens.Background)) {
            val reduce = LocalReduceMotion.current
            AnimatedContent(
                targetState = nav.page,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val dir = if (nav.forward) 1 else -1
                        (slideInHorizontally(tween(Tokens.StandardMs, easing = Tokens.StandardEasing)) { it / 4 * dir } + fadeIn(tween(Tokens.StandardMs))) togetherWith
                            (slideOutHorizontally(tween(Tokens.StandardMs, easing = Tokens.StandardEasing)) { -it / 4 * dir } + fadeOut(tween(Tokens.StandardMs)))
                    }
                },
                label = "page",
            ) { page ->
                if (page == null) saved.SaveableStateProvider("tabs") { Tabs(nav, setup, lists) } else PageContent(page, nav, setup)
            }
        }
    }
    SetupSheets(setup)
}

@Composable
private fun Tabs(nav: Nav, setup: Setup, lists: Map<Tab, LazyListState>) {
    val scope = rememberCoroutineScope()
    val saved = rememberSaveableStateHolder()
    // call screening is never asked at launch: the setup checklist and turning on a call rule ask for it
    BackHandler(enabled = nav.tab != Tab.HOME) { nav.tab = Tab.HOME }
    Scaffold(
        containerColor = Tokens.Background,
        bottomBar = { BottomBar(nav.tab) { t -> if (t == nav.tab) scope.launch { lists.getValue(t).animateScrollToItem(0) } else nav.tab = t } },
    ) { pad ->
        Crossfade(nav.tab, Modifier.padding(pad), animationSpec = Tokens.standard(LocalReduceMotion.current), label = "tab") { tab ->
            saved.SaveableStateProvider(tab.name) {
                val state = lists.getValue(tab)
                when (tab) {
                    Tab.HOME -> HomeScreen(nav, setup, state)
                    Tab.ALERTS -> AlertsScreen(nav, setup, state)
                    Tab.STUDIO -> StudioScreen(nav, state)
                    Tab.FEATURES -> FeaturesScreen(nav, setup, state)
                }
            }
        }
    }
}

/** The bottom bar: 80 dp, 4 equal slots, a 64x32 pill behind the selected icon, a label under each. */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    // labels grow with the font up to 1.5x, then stay on one line ("Features" must not break mid-word)
    val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val labelStyle = MaterialTheme.typography.labelMedium.let { it.copy(fontSize = it.fontSize * (minOf(fs, 1.5f) / fs)) }
    Column {
    androidx.compose.material3.HorizontalDivider(color = Tokens.Hairline)
    NavigationBar(containerColor = Tokens.Background, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            val on = selected == t
            NavigationBarItem(
                selected = on,
                onClick = { onSelect(t) },
                icon = { Icon(if (on) t.selectedIcon else t.icon, contentDescription = null) },
                label = { Text(t.label, style = labelStyle, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    indicatorColor = Tokens.Hairline,
                    unselectedIconColor = Tokens.Secondary,
                    unselectedTextColor = Tokens.Secondary,
                ),
            )
        }
    }
    }
}

@Composable
fun PageContent(page: Page, nav: Nav, setup: Setup) {
    when (page) {
        Page.Profile -> ProfilePage(nav, rememberPermissions(setup))
        Page.Backup -> BackupPage(nav)
        Page.Licences -> LicencesPage(nav)
        Page.OldScreens -> OldScreensPage(nav)
        Page.Components -> ComponentGallery(onBack = nav::pop)
        is Page.AlertApps -> com.dotforge.ui.alerts.AlertAppsPage(page.mode, nav)
        Page.AlertSettings -> com.dotforge.ui.alerts.AlertSettingsPage(nav)
        Page.Quiet -> com.dotforge.ui.alerts.QuietPage(nav)
        Page.WhoWins -> com.dotforge.ui.alerts.WhoWinsPage(nav)
        Page.PhoneMoments -> com.dotforge.ui.alerts.PhoneMomentsPage(nav, setup)
        Page.Library -> LibraryScreen(nav)
        is Page.Editor -> EditorScreen(page.designId, page.animated, page.draft, nav)
        is Page.TextDesign -> com.dotforge.ui.studio.TextScreen(page.designId, page.text, nav)
        is Page.Photo -> com.dotforge.ui.studio.PhotoScreen(page.key, page.designId, nav)
        is Page.GlyphRingtone -> com.dotforge.ui.studio.RingtoneScreen(page.uri, nav)
        is Page.Feature -> FeaturePage(page.key, nav, setup)
        is Page.OldTab -> OldTab(page.key, nav, setup)
        Page.Notifications -> NotificationsPage(nav, setup)
        is Page.RuleEditor -> RuleEditorPage(page.ruleId, page.template, setup, onClose = nav::pop)
        Page.Calls -> CallsPage(nav, setup)
        Page.Spam -> SpamPage(nav, setup)
        Page.Toys -> ToysPage(nav, setup)
        Page.Integrations -> IntegrationsPage(nav, setup)
        Page.SystemGlyphs -> SystemGlyphsPage(nav)
        is Page.ToySettings -> ToySettingsPage(page.toy, nav)
        is Page.DesignEditor -> DesignEditorPage(page.designId, page.animated, onClose = nav::pop)
        Page.Ringtones -> RingtonesPage(nav)
        is Page.RingtoneImport -> RingtoneImportPage(page.uri, page.mode, nav)
        Page.RingtoneEdit -> TimelineEditorPage(nav)
        Page.Diagnostics -> {
            BackHandler(onBack = nav::pop)
            DiagnosticsScreen(onClose = nav::pop)
        }
    }
}

/** Temporary: an old tab from before the redesign, as a page under Profile > Old screens. */
@Composable
private fun OldTab(key: String, nav: Nav, setup: Setup) {
    BackHandler(onBack = nav::pop)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(Tokens.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar("Old ${key.lowercase()} tab", onBack = nav::pop)
        Box(Modifier.weight(1f)) {
            when (key) {
                "GLYPH" -> GlyphScreen(nav, setup, scroll)
                "CREATE" -> CreateScreen(nav, scroll)
                else -> SettingsScreen(nav, setup, scroll)
            }
        }
    }
}
