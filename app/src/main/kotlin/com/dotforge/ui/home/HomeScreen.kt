package com.dotforge.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dotforge.ui.Nav
import com.dotforge.ui.Page
import com.dotforge.ui.Setup
import com.dotforge.ui.design.PersonAvatar
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Screen
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.library.LibraryItemSheet
import com.dotforge.ui.library.LibraryPickerSheet
import com.dotforge.ui.library.LibraryPrefs
import com.dotforge.ui.library.LibraryTile
import com.dotforge.device.library.AnimationLibrary
import com.dotforge.device.library.Categories
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Home: what the Glyph is doing, quick switches, the setup checklist. The avatar opens Profile (Settings). */
@Composable
fun HomeScreen(nav: Nav, setup: Setup, state: LazyListState) {
    var picking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var useFor by remember { mutableStateOf<com.dotforge.studio.Design?>(null) }
    var mine by remember { mutableStateOf<com.dotforge.studio.Design?>(null) }
    Screen("DotForge", state = state, actions = { ProfileButton { nav.push(Page.Profile) } }) {
        item { SectionLabel("Show something on the Glyph") }
        item {
            // your recent picks first, then a few Featured ones
            val recent = LibraryPrefs.recent.toList()
            val picks = remember(recent) {
                (recent.mapNotNull(AnimationLibrary::item) + AnimationLibrary.items(Categories.Featured)).distinctBy { it.id }.take(4)
            }
            // fewer, wider tiles at large text, so names wrap between words
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val n = (maxWidth / (80.dp * scale)).toInt().coerceIn(2, 4)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    picks.take(n).forEach { item ->
                        Box(Modifier.weight(1f)) {
                            LibraryTile(item, item.id in LibraryPrefs.favourites, false, "Open", onClick = { open = item.id }, onLongClick = { LibraryPrefs.toggleFavourite(item.id) })
                        }
                    }
                }
            }
        }
        // one way in: the same picker as everywhere else, with Library and Yours
        item { PrimaryButton("Choose a design", Modifier.fillMaxWidth().padding(top = Tokens.Small)) { picking = true } }
    }
    if (picking) LibraryPickerSheet("Choose a design", onDismiss = { picking = false }, onPickYours = { d -> picking = false; mine = d }) { item -> picking = false; open = item.id }
    mine?.let { d -> com.dotforge.ui.studio.UseForSheet(d, nav, fresh = false) { mine = null } }
    open?.let(AnimationLibrary::item)?.let { item ->
        LibraryItemSheet(item, nav, onDismiss = { open = null }) { d -> open = null; useFor = d }
    }
    useFor?.let { d -> com.dotforge.ui.studio.UseForSheet(d, nav, fresh = true, unsaved = true) { useFor = null } }
}

/** The round profile avatar at the top right of Home. */
@Composable
fun ProfileButton(onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Profile and settings" }) { PersonAvatar() }
