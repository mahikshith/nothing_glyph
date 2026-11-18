package com.dotforge.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dotforge.backup.Backup
import com.dotforge.backup.BackupStore
import com.dotforge.ui.Nav
import com.dotforge.ui.design.Chevron
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.Hint
import com.dotforge.ui.design.ListRow
import com.dotforge.ui.design.OptionRow
import com.dotforge.ui.design.PrimaryButton
import com.dotforge.ui.design.RowGroup
import com.dotforge.ui.design.SectionLabel
import com.dotforge.ui.design.SwitchRow
import com.dotforge.ui.design.Tokens
import com.dotforge.ui.design.rememberSnacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dotforge.ui.design.Page as PageLayout

/** A backup picked for restoring, waiting for "Add" or "Replace". */
class RestoreChoice(val contents: Backup.Contents, val replace: Boolean = false)

/**
 * Profile > Backup and restore: Back up now (one .dotforge file through the system picker, so Drive or
 * Downloads), weekly automatic backup to a folder, and Restore with a preview and "Add to what's here" or
 * "Replace everything". Android's own backup carries the same data.
 */
@Composable
fun BackupPage(nav: Nav, preview: RestoreChoice? = null, error: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snacks = rememberSnacks()
    var restore by remember { mutableStateOf(preview) }
    var problem by remember { mutableStateOf(error) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BackupStore.backUp(ctx, ctx.contentResolver.openOutputStream(uri)!!) } }
            r.onSuccess { snacks?.show("Backed up · ${Formatter.formatShortFileSize(ctx, it)}") }
                .onFailure { problem = "Couldn't save the backup. Try another place." }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BackupStore.read(ctx.contentResolver.openInputStream(uri)!!) } }
            r.onSuccess { restore = RestoreChoice(it); problem = null }
                .onFailure { problem = if (it is Backup.NewerBackupException) "Update DotForge to restore this backup." else "DotForge can't read this file. Pick a .dotforge backup." }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { BackupStore.setAutoFolder(ctx, uri) }.onFailure { problem = "Couldn't use that folder. Pick another one." }
    }

    PageLayout("Backup and restore", onBack = nav::pop) {
        item(key = "why") {
            Hint("Designs and rules live inside DotForge. Uninstalling deletes them unless you back up. Glyph ringtones stay in your Ringtones folder.")
        }
        item(key = "backup") {
            SectionLabel("Back up")
            RowGroup {
                ListRow("Back up now", subtitle = lastBackupText(BackupStore.lastMs), trailing = { Chevron() }) { save.launch(BackupStore.fileName()) }
                HorizontalDivider(Modifier.padding(start = Tokens.Gutter), color = Tokens.Hairline)
                val auto = BackupStore.autoFolder
                SwitchRow(
                    "Automatic backup",
                    if (auto == null) "Off" else "Weekly to ${BackupStore.folderName(auto)}",
                    auto != null,
                ) { on -> if (on) folder.launch(null) else BackupStore.setAutoFolder(ctx, null) }
            }
            Hint("A backup holds your designs, alert rules, people, spam rules and settings. Not permissions: Android asks for those again.", Modifier.padding(top = Tokens.Small))
            Hint("Android's own backup (Google) also keeps them when you move to a new phone.")
        }
        item(key = "restore") {
            SectionLabel("Restore")
            RowGroup {
                ListRow("Restore…", subtitle = "From a .dotforge file", trailing = { Chevron() }) { open.launch(arrayOf("*/*")) }
            }
            problem?.let { Text(it, color = Tokens.AccentText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Small)) }
        }
    }

    restore?.let { choice ->
        var confirm by remember { mutableStateOf(false) }
        FullSheet({ restore = null }, "Restore this backup?", choice.contents.summary.text(), confirm = "Cancel") {
            OptionRow("Add to what's here", "Keeps everything on this phone and adds what's new. A name that's taken gets a number.", selected = !choice.replace) {
                restore = RestoreChoice(choice.contents, replace = false)
            }
            OptionRow("Replace everything", "Your designs, rules and settings here are replaced by the backup's.", selected = choice.replace) {
                restore = RestoreChoice(choice.contents, replace = true)
            }
            Hint("DotForge restarts when it's done.")
            PrimaryButton("Restore", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {
                if (choice.replace) confirm = true
                else scope.launch(Dispatchers.IO) { BackupStore.restoreAndRestart(ctx, choice.contents, replace = false) }
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Replace everything?") },
                text = { Text("Your designs, rules and settings on this phone are replaced by the backup's. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { BackupStore.restoreAndRestart(ctx, choice.contents, replace = true) } }) {
                        Text("Replace everything", color = Tokens.AccentText)
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = Color.White) } },
                containerColor = Tokens.Sheet,
            )
        }
    }
}
