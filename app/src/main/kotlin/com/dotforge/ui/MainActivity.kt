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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dotforge.diagnostics.DiagnosticsScreen
import com.dotforge.diagnostics.EventLog
import com.dotforge.ringtones.ImportMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A file opened with or shared to DotForge, waiting to be shown in the import page. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming = importUri(intent)
        enableEdgeToEdge()
        EventLog.init(this)
        val setup = Setup(this)
        this.setup = setup
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_LIVE, false) == true) pendingLive = true
        setContent {
            DotForgeTheme {
                val nav = rememberSaveable(saver = Nav.saver) { Nav() }
                LaunchedEffect(incoming) {
                    val uri = incoming ?: return@LaunchedEffect
                    incoming = null
                    nav.popTo(Tab.GLYPH)
                    nav.push(Page.Ringtones)
                    nav.push(Page.RingtoneImport(uri.toString(), ImportMode.FILE))
                }
                val scrolls = Tab.entries.associateWith { rememberScrollState() }
                // keeps each tab's remembered UI state while a page is open or another tab is shown
                val saved = rememberSaveableStateHolder()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AnimatedContent(
                        targetState = nav.page,
                        transitionSpec = {
                            val dir = if (nav.forward) 1 else -1
                            (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                        },
                        label = "page",
                    ) { page ->
                        if (page == null) saved.SaveableStateProvider("tabs") { Tabs(nav, setup, scrolls) } else PageContent(page, nav, setup)
                    }
                }
                SetupSheets(setup)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importUri(intent)?.let { incoming = it }
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

    /** The file of an "Open with" (VIEW) or Share (SEND) intent. */
    private fun importUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}

@Composable
private fun Tabs(nav: Nav, setup: Setup, scrolls: Map<Tab, ScrollState>) {
    val scope = rememberCoroutineScope()
    val saved = rememberSaveableStateHolder()
    val needsContacts by rememberNeedsContacts()
    val needsSetup = !setup.notificationAccess || (needsContacts && !setup.contacts)
    BackHandler(enabled = nav.tab != Tab.GLYPH) { nav.tab = Tab.GLYPH }
    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A0A0A)) {
                Tab.entries.forEach { t ->
                    val selected = nav.tab == t
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (selected) scope.launch { scrolls.getValue(t).animateScrollTo(0) } else nav.tab = t
                        },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.SETTINGS && needsSetup) Badge(containerColor = NothingRed) }) {
                                Icon(if (selected) t.selectedIcon else t.icon, contentDescription = t.label)
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Color(0xFF262626),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Crossfade(nav.tab, Modifier.padding(pad), label = "tab") { tab ->
            saved.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.GLYPH -> GlyphScreen(nav, setup, scrolls.getValue(tab))
                    Tab.CREATE -> CreateScreen(nav, scrolls.getValue(tab))
                    Tab.SETTINGS -> SettingsScreen(nav, setup, scrolls.getValue(tab))
                }
            }
        }
    }
}

@Composable
private fun PageContent(page: Page, nav: Nav, setup: Setup) {
    when (page) {
        Page.Notifications -> NotificationsPage(nav, setup)
        is Page.RuleEditor -> RuleEditorPage(page.ruleId, setup, onClose = nav::pop)
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
