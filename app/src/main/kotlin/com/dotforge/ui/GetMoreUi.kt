package com.dotforge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.ringtones.CatalogFile
import com.dotforge.ringtones.CatalogResult
import com.dotforge.ringtones.ImportMode
import com.dotforge.ringtones.RingtoneCatalog
import com.dotforge.ringtones.RingtoneCatalogClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ringtones shared on GitHub (only collections whose licence allows sharing), and a link out to Glyphtones.
 * A tapped file is downloaded and opened on the import page, which converts other phones' files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetMoreSheet(
    nav: Nav, onDismiss: () -> Unit,
    /** Reads the lists; screenshots pass their own (still loading, failed or a list). */
    load: (suspend (refresh: Boolean) -> List<CatalogResult>)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RingtoneCatalogClient(File(ctx.cacheDir, "ringtone-catalog")) }
    var results by remember { mutableStateOf<List<CatalogResult>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf<CatalogFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        results = null
        failed = false
        val got = runCatching {
            if (load != null) load(reload > 0)
            else withContext(Dispatchers.IO) { RingtoneCatalog.COLLECTIONS.map { client.load(it, refresh = reload > 0) } }
        }
        if (got.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw got.exceptionOrNull()!!
        results = got.getOrNull()
        failed = got.isFailure
    }

    fun open(file: CatalogFile) {
        if (downloading != null) return
        downloading = file
        error = null
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { client.download(file, File(ctx.cacheDir, "ringtone-download")) }
            }
            downloading = null
            got.onSuccess {
                onDismiss()
                nav.push(Page.RingtoneImport(Uri.fromFile(it).toString(), ImportMode.FILE))
            }.onFailure { error = it.message ?: "Download failed." }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            item {
                Text("Get more", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Free glyph ringtones shared on GitHub. Ones made for other phones are converted.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                error?.let { Text(it, color = NothingRed, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            }
            val list = results
            if (failed) {
                item { Notice("Couldn't load the ringtones.", "Check your connection and try again.", "Retry") { reload++ } }
            } else if (list == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        com.dotforge.ui.design.DotSpinner("Loading ringtones…")
                    }
                }
            } else {
                list.forEach { r ->
                    item(key = "h-" + r.collection.fullName) {
                        com.dotforge.ui.design.SectionLabel(r.collection.title, Modifier.padding(top = 8.dp))
                        val licence = (r as? CatalogResult.Files)?.licence
                        Text(
                            r.collection.fullName + (licence?.let { " · $it licence" } ?: ""),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    when (r) {
                        is CatalogResult.Files -> if (r.files.isEmpty()) {
                            item { Notice("Nothing to get here right now.", "The collection has no ringtones at the moment.", null) {} }
                        } else {
                            items(r.files, key = { r.collection.fullName + "/" + it.folder + "/" + it.name }) { f ->
                                FileRow(f, busy = downloading == f, enabled = downloading == null) { open(f) }
                            }
                        }
                        is CatalogResult.RateLimited -> item {
                            Notice("GitHub needs a short break.", waitText(r.resetAt), "Try again") { reload++ }
                        }
                        is CatalogResult.Failed -> item { Notice("Couldn't load this list.", r.message, "Try again") { reload++ } }
                        is CatalogResult.NotShared -> item {
                            Notice("Not offered any more.", "Its licence (${r.licence}) no longer allows sharing.", null) {}
                        }
                    }
                }
            }
            item {
                com.dotforge.ui.design.SectionLabel("More on the web", Modifier.padding(top = 12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clickable {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RingtoneCatalog.GLYPHTONES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        .padding(vertical = 12.dp),
                ) {
                    Text("Glyphtones", fontSize = 16.sp)
                    Text("Ringtones shared by Nothing users. Opens in your browser; import what you download.", color = Muted, fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(vertical = 8.dp)) { Text("Close", color = Color.White) }
            }
        }
    }
}

@Composable
private fun FileRow(file: CatalogFile, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name.substringBeforeLast('.'), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(file.folder, sizeText(file.size)).filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
}

/** Two lines and at most one button, like the app's empty states. */
@Composable
private fun Notice(title: String, body: String, button: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, fontSize = 15.sp)
        Text(body, color = Muted, fontSize = 13.sp)
        if (button != null) com.dotforge.ui.design.SecondaryButton(button, Modifier.padding(top = 8.dp), onClick = onClick)
    }
}

private fun waitText(resetAt: Long): String {
    val min = ((resetAt - System.currentTimeMillis()) / 60_000).toInt() + 1
    return if (resetAt <= 0 || min <= 0) "Its limit for lists is used up. Try again later." else "Its limit for lists is used up. Try again in $min min."
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}
