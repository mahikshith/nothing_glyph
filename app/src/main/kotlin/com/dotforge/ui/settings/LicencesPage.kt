package com.dotforge.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dotforge.ui.Nav
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.Page as PageLayout

/** One licence text bundled in assets/notices (copied from NOTICE and licenses/ at build time). */
class Licence(val title: String, val subtitle: String, val asset: String)

val LICENCES = listOf(
    Licence("Third-party notices", "Code and data DotForge builds on", "notices/NOTICE"),
    Licence("Doto", "Display font · SIL Open Font License 1.1", "notices/licenses/Doto-OFL.txt"),
    Licence("Inter", "Body font · SIL Open Font License 1.1", "notices/licenses/Inter-OFL.txt"),
    Licence("Glyphifier", "Apache License 2.0", "notices/licenses/Glyphifier-LICENSE.txt"),
)

/**
 * Licence files are hard-wrapped at 80 columns; on a phone that leaves ragged lines. Joins the lines of each
 * paragraph, keeping blank lines and list items on their own lines.
 */
fun reflow(text: String): String = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).joinToString("\n\n") { para ->
    // rules of dashes only wrap badly on a phone: drop them
    para.lines().filterNot { it.isNotBlank() && it.trim().all { c -> c == '-' } }.fold(StringBuilder()) { acc, raw ->
        val line = raw.trim()
        val own = line.startsWith("-") || line.matches(Regex("^(\\d+[.)]|[a-z][.)]|\\(\\w+\\)|\\*).*")) || line.isEmpty()
        if (acc.isNotEmpty()) acc.append(if (own) "\n" else " ")
        acc.append(line)
    }.toString()
}

fun readAsset(ctx: Context, path: String): String =
    runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrElse { "Not found: $path" }

/** Licences: the notices file and each bundled licence, opened in a sheet. */
@Composable
fun LicencesPage(nav: Nav) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    PageLayout("Licences", onBack = nav::pop) {
        item {
            Hint("DotForge is built on these. Tap one to read it.")
            RowGroup {
                LICENCES.forEachIndexed { i, l -> ListRow(l.title, subtitle = l.subtitle, trailing = { Chevron() }) { open = i } }
            }
        }
    }
    open?.let { i ->
        val l = LICENCES[i]
        val text = remember(i) { reflow(readAsset(ctx, l.asset)) }
        FullSheet({ open = null }, l.title, l.subtitle) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.Secondary)
        }
    }
}
