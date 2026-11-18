package com.dotforge.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.design.PersonAvatar
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Screen

/** Home: what the Glyph is doing, quick switches, the setup checklist. The avatar opens Profile (Settings). */
@Composable
fun HomeScreen(nav: Nav, setup: Setup, state: LazyListState) {
    Screen("DotForge", state = state, actions = { ProfileButton { nav.push(Page.Profile) } }) {
        item { Hint("Coming in the next update.") }
    }
}

/** The round profile avatar at the top right of Home. */
@Composable
fun ProfileButton(onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Profile and settings" }) { PersonAvatar() }
