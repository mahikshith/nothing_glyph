package com.dotforge.ui.library

import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Page

/** The animation library: category chips, search, a grid of animated thumbnails, a big preview with Use. */
@Composable
fun LibraryScreen(nav: Nav) {
    Page("Library", onBack = nav::pop) {
        item { Hint("Coming in the next update.") }
    }
}
