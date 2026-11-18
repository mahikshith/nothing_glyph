package com.dotforge.ui.studio

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Screen

/** Studio: + New, My designs, My ringtones. */
@Composable
fun StudioScreen(nav: Nav, state: LazyListState) {
    Screen("Studio", state = state) {
        item { Hint("Coming in the next update.") }
    }
}
