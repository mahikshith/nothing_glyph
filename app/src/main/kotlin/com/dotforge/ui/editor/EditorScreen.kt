package com.dotforge.ui.editor

import androidx.compose.runtime.Composable
import com.dotforge.device.Animation
import com.dotforge.ui.Nav
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.Page

/** New designs that aren't saved yet (an import, a library pick), handed to the editor by key. In memory only. */
object Drafts {
    class Draft(val name: String, val animation: Animation)

    private val drafts = HashMap<String, Draft>()
    private var next = 0

    fun put(name: String, animation: Animation): String = synchronized(drafts) {
        "d${next++}".also { drafts[it] = Draft(name, animation) }
    }

    fun take(key: String?): Draft? = key?.let { synchronized(drafts) { drafts.remove(it) } }
}

/** The editor: the screen never scrolls; the matrix takes the space left by the pinned bars. */
@Composable
fun EditorScreen(designId: String?, animated: Boolean, draft: String?, nav: Nav) {
    Page("Editor", onBack = nav::pop) {
        item { Hint("The editor is being rebuilt.") }
    }
}
