package com.dotforge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.dotforge.diagnostics.DiagnosticsScreen
import com.dotforge.diagnostics.EventLog

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    RULES("Rules", Icons.AutoMirrored.Outlined.List),
    STUDIO("Studio", Icons.Outlined.Create),
    TOYS("Toys", Icons.Outlined.Star),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EventLog.init(this)
        setContent {
            DotForgeTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var diagnostics by rememberSaveable { mutableStateOf(false) }
                if (diagnostics) {
                    BackHandler { diagnostics = false }
                    DiagnosticsScreen(onClose = { diagnostics = false })
                } else Scaffold(
                    containerColor = Color.Black,
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF0A0A0A)) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            Tab.HOME -> HomeScreen()
                            Tab.RULES -> RulesScreen()
                            Tab.STUDIO -> StudioScreen()
                            Tab.TOYS -> ToysScreen()
                            Tab.SETTINGS -> SettingsScreen(onOpenDiagnostics = { diagnostics = true })
                        }
                    }
                }
            }
        }
    }
}
