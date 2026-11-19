package com.dotforge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dotforge.toys.InstalledToy
import com.dotforge.toys.InstalledToys
import com.dotforge.toys.ToyCatalog
import com.dotforge.toys.ToyInstaller
import com.dotforge.toys.ToyReleaseResult
import com.dotforge.toys.ToyRepo
import com.dotforge.toys.ToyStoreClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every installed Glyph Toy (from any app), toys to get from GitHub releases (licence-checked), and a link to
 * Nothing Playground. Installing goes through Android's installer; afterwards Nothing's toy manager opens so
 * the new toy can be switched on.
 */
@Composable
fun ToysPage(nav: Nav, setup: Setup) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ToyStoreClient(File(ctx.cacheDir, "toy-store"), { ctx.assets.open("toys.json").bufferedReader().use { it.readText() } }) }
    var installed by remember { mutableStateOf<List<InstalledToy>?>(null) }
    var store by remember { mutableStateOf<List<ToyReleaseResult>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val install by ToyInstaller.state.collectAsStateWithLifecycle()

    LaunchedEffect(setup.resumes, install) {
        installed = withContext(Dispatchers.IO) { InstalledToys.query(ctx) }
    }
    LaunchedEffect(reload) {
        store = null
        store = withContext(Dispatchers.IO) { client.toys(refresh = reload > 0).map { client.release(it, refresh = reload > 0) } }
    }
    LaunchedEffect(install) {
        // once per install: open Nothing's toy manager so the new toy can be switched on
        if (ToyInstaller.takeJustInstalled()) InstalledToys.openManager(ctx)
    }

    fun get(r: ToyReleaseResult.Ready) {
        val asset = r.release.asset ?: return
        if (install is ToyInstaller.State.Downloading || install is ToyInstaller.State.Installing) return
        if (!setup.needInstallPermission()) return
        scope.launch {
            ToyInstaller.set(ToyInstaller.State.Downloading(r.toy.name, 0f))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = client.download(asset) { ToyInstaller.set(ToyInstaller.State.Downloading(r.toy.name, it)) }
                    ToyInstaller.install(ctx, apk, r.toy.name)
                }
            }
            result.onFailure { ToyInstaller.set(ToyInstaller.State.Failed(r.toy.name, it.message ?: "The download didn't finish.")) }
        }
    }

    PageScaffold("TOYS", onBack = nav::pop) {
        GroupTitle("INSTALLED")
        when (val list = installed) {
            null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(8.dp).size(24.dp))
            else -> {
                if (list.isEmpty()) EmptyState("No toys found", "Toys you install show here.")
                list.forEach { InstalledRow(it) }
            }
        }
        Text("Add toys and order them in Glyph Toys. DotForge's own toys are under Features > Toys.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        SecondaryButton("Open Nothing's toy settings", Modifier.fillMaxWidth().padding(top = 8.dp)) { InstalledToys.openManager(ctx) }

        GroupTitle("GET TOYS")
        Text(
            "Toys their makers publish on GitHub under an open licence. Installing asks Android first; DotForge doesn't check the app itself.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp),
        )
        InstallStatus(install)
        when (val list = store) {
            null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(8.dp).size(24.dp))
            else -> {
                if (list.isEmpty()) EmptyState("No toys listed", "Check the connection and refresh.", "Refresh") { reload++ }
                list.forEach { r -> StoreRow(r, installed.orEmpty(), onGet = { get(it) }) }
                TextButton(onClick = { reload++ }) { Text("Refresh", color = Color.White) }
            }
        }

        GroupTitle("PLAYGROUND")
        Text("Nothing Playground lists toys and more from Nothing's community.", color = Muted, fontSize = 13.sp)
        SecondaryButton("Open Nothing Playground", Modifier.fillMaxWidth().padding(top = 8.dp)) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ToyCatalog.PLAYGROUND_URL))) }
        }
    }
}

@Composable
private fun ToyImage(toy: InstalledToy) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val bmp = toy.image
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
        else Text(toy.name.take(1).uppercase(), fontSize = 20.sp)
    }
}

@Composable
private fun InstalledRow(toy: InstalledToy) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ToyImage(toy)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(toy.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (toy.summary.isNotBlank()) Text(toy.summary, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(toy.appLabel, "long press".takeIf { toy.longPress }, "always-on display".takeIf { toy.aod }).joinToString(" · "),
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InstallStatus(s: ToyInstaller.State) {
    val (text, progress) = when (s) {
        ToyInstaller.State.Idle -> return
        is ToyInstaller.State.Downloading -> "Downloading ${s.name}…" to s.progress
        is ToyInstaller.State.Installing -> "Installing ${s.name}…" to null
        is ToyInstaller.State.Installed -> "${s.name} is installed. Turn it on in Nothing's toy settings." to null
        is ToyInstaller.State.Failed -> "${s.name}: ${s.message}" to null
    }
    Surface(color = Surface1, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = if (s is ToyInstaller.State.Failed) NothingRed else Color.White, fontSize = 14.sp)
            progress?.let { LinearProgressIndicator(progress = { it }, color = Color.White, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun StoreRow(r: ToyReleaseResult, installed: List<InstalledToy>, onGet: (ToyReleaseResult.Ready) -> Unit) {
    val toy: ToyRepo = r.toy
    Surface(color = Surface1, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(toy.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (toy.summary.isNotBlank()) Text(toy.summary, color = Muted, fontSize = 13.sp)
                val detail = when (r) {
                    is ToyReleaseResult.Ready -> listOfNotNull(
                        r.release.tag.takeIf { it.isNotBlank() },
                        r.release.asset?.let { ToyCatalog.size(it.size) }?.takeIf { it.isNotBlank() },
                        r.release.licence,
                        toy.fullName,
                    ).joinToString(" · ") + if (r.release.asset == null) "\nNo APK for this phone in the latest release." else ""
                    is ToyReleaseResult.NotShared -> "Licence ${r.licence} doesn't allow sharing; not offered."
                    is ToyReleaseResult.RateLimited -> "GitHub's hourly limit is used up. Try again later."
                    is ToyReleaseResult.Failed -> r.message
                }
                Text(detail, color = Muted, fontSize = 12.sp)
            }
            if (r is ToyReleaseResult.Ready && r.release.asset != null) {
                val have = installed.any { it.appLabel.equals(toy.name, ignoreCase = true) }
                TextButton(onClick = { onGet(r) }) { Text(if (have) "Update" else "Install", color = NothingRed) }
            }
        }
    }
}
