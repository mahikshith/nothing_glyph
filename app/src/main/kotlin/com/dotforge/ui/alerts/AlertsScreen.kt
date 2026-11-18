package com.dotforge.ui.alerts

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Screen

/** Alerts: Apps · People · Spam · History. */
@Composable
fun AlertsScreen(nav: Nav, setup: Setup, state: LazyListState) {
    Screen("Alerts", state = state) {
        item { Hint("Coming in the next update.") }
    }
}
