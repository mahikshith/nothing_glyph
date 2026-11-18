package com.dotforge.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dotforge.backup.Backup
import com.dotforge.backup.BackupStore
import com.dotforge.ui.Nav
import com.dotforge.ui.design.FullSheet
import com.dotforge.ui.design.ConfirmDialog
import com.dotforge.ui.design.SecondaryButton
import com.dotforge.ui.design.StatusBanner
import com.dotforge.ui.design.Hint
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
 * Profile > Backup and restore: Back up now, the main button with the backup's size (one .dotforge file through
 * the system picker, so Drive or Downloads), weekly automatic backup to a folder, and Restore with a preview and "Add to what's here" or
 * "Replace everything". Android's own backup carries the same data.
 */
@Composable
fun BackupPage(nav: Nav, preview: RestoreChoice? = null, error: String? = null, backingUp: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snacks = rememberSnacks()
    var restore by remember { mutableStateOf(preview) }
    var problem by remember { mutableStateOf(error) }
    var busy by remember { mutableStateOf(backingUp) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { BackupStore.backUp(ctx, ctx.contentResolver.openOutputStream(uri)!!) } }
            busy = false
            r.onSuccess { snacks?.show("Backed up · ${Formatter.formatShortFileSize(ctx, it)}") }
                .onFailure { problem = "Couldn't save the backup. Try another place." }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BackupStore.read(ctx.contentResolver.openInputStream(uri)!!) } }
            r.onSuccess { restore = RestoreChoice(it); problem = null }
                .onFailure { problem = if (it is Backup.NewerBackupException) "Update DotForge to restore this backup." else "DotForge can't read this file. It isn't a .dotforge backup." }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { BackupStore.setAutoFolder(ctx, uri) }.onFailure { problem = "Couldn't use that folder. Pick another one." }
    }

    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(BackupStore.lastMs) { size = withContext(Dispatchers.IO) { runCatching { BackupStore.estimateBytes(ctx) }.getOrNull() } }

    PageLayout("Backup and restore", onBack = nav::pop) {
        item(key = "why") {
            Hint("Designs (with their photos and trace photos) and rules live inside DotForge. Uninstalling deletes them unless you back up. Glyph ringtones stay in your Ringtones folder.")
        }
        item(key = "backup") {
            SectionLabel("Back up")
            PrimaryButton(
                if (busy) "Backing up…" else "Back up now" + size?.let { " · " + Formatter.formatShortFileSize(ctx, it) }.orEmpty(),
                Modifier.fillMaxWidth(), enabled = !busy,
            ) { save.launch(BackupStore.fileName()) }
            Hint(lastBackupText(BackupStore.lastMs) + ". Saves one .dotforge file where you choose, like Drive or Downloads.")
            RowGroup(Modifier.padding(top = Tokens.Small)) {
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
            SecondaryButton("Restore from a file", Modifier.fillMaxWidth()) { open.launch(arrayOf("*/*")) }
            problem?.let { StatusBanner(it, Modifier.padding(top = Tokens.Gap), button = "Choose another file") { open.launch(arrayOf("*/*")) } }
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
            ConfirmDialog(
                "Replace everything?",
                "Your designs, rules and settings on this phone are replaced by the backup's. This can't be undone.",
                "Replace everything",
                onDismiss = { confirm = false },
            ) { scope.launch(Dispatchers.IO) { BackupStore.restoreAndRestart(ctx, choice.contents, replace = true) } }
        }
    }
}
