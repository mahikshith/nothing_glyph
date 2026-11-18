package com.dotforge.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.DotArt
import com.dotforge.device.DotArtMode
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.studio.Design

/** A picture scaled down for dot art (the matrix needs no more than this). */
private fun pixels(b: Bitmap): Triple<Int, Int, IntArray> {
    val scaled = if (maxOf(b.width, b.height) > 400) {
        val k = 400f / maxOf(b.width, b.height)
        Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    } else b
    val soft = scaled.copy(Bitmap.Config.ARGB_8888, false)
    val out = IntArray(soft.width * soft.height)
    soft.getPixels(out, 0, soft.width, 0, 0, soft.width, soft.height)
    return Triple(soft.width, soft.height, out)
}

private fun saveDesign(name: String, a: Animation): Design {
    val d = Design(name = name, animated = a.frames.size > 1, frames = a.toMuseumJson(), updatedAt = System.currentTimeMillis())
    AppGraph.designs.save(d)
    return d
}

private fun readText(ctx: Context, uri: Uri): String? =
    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size < 4_000_000 }?.decodeToString() } }.getOrNull()

private fun readBitmap(ctx: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        d.setTargetSampleSize(4)
    }
}.getOrNull()

/** Shares a design as Dot Hub / Glyph Museum JSON, which those apps (and DotForge) open. */
fun shareDesign(ctx: Context, d: Design) {
    val send = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_SUBJECT, "${d.name}.json")
        .putExtra(Intent.EXTRA_TEXT, d.frames)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share ${d.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * New designs from outside: a Dot Hub / Glyph Museum JSON file, a picture (dot art: dither, three levels or
 * shades), a photo from the camera, or text. Returns the starters; the dialogs show themselves.
 */
class DesignImport(val file: () -> Unit, val camera: () -> Unit, val text: () -> Unit)

@Composable
fun rememberDesignImport(nav: Nav): DesignImport {
    val ctx = LocalContext.current
    var picture by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    var askText by remember { mutableStateOf(false) }

    fun open(d: Design) = nav.push(Page.DesignEditor(d.id, d.animated))

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.take(40) ?: "Imported"
        val type = ctx.contentResolver.getType(uri).orEmpty()
        if (type.startsWith("image/")) {
            readBitmap(ctx, uri)?.let { picture = name to it } ?: Toast.makeText(ctx, "Couldn't read that picture.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val anim = readText(ctx, uri)?.let { runCatching { Animation.fromMuseumJson(AppGraph.profile, it) }.getOrNull() }
        if (anim != null) open(saveDesign(name, anim))
        else readBitmap(ctx, uri)?.let { picture = name to it }
            ?: Toast.makeText(ctx, "Not a Dot Hub or Glyph Museum file, or a picture.", Toast.LENGTH_SHORT).show()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp?.let { picture = "Photo" to it }
    }

    picture?.let { (name, bmp) ->
        val (w, h, px) = remember(bmp) { pixels(bmp) }
        var invert by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { picture = null },
            containerColor = Surface2,
            title = { Text("Dot art") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DotArtMode.entries.forEach { mode ->
                            val f = remember(px, mode, invert) { DotArt.frame(AppGraph.profile, w, h, px, mode, invert) }
                            Column(
                                Modifier.weight(1f).clickable {
                                    picture = null
                                    open(saveDesign(name, Animation.single(f)))
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MatrixView(f.pixels, AppGraph.profile, Modifier.fillMaxWidth())
                                Text(mode.label, fontSize = 13.sp)
                            }
                        }
                    }
                    Text("Tap the one you like.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    SwitchRow("Invert", "Dark parts light up", invert) { invert = it }
                }
            },
            confirmButton = { TextButton(onClick = { picture = null }) { Text("Cancel", color = Color.White) } },
        )
    }
    if (askText) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askText = false },
            containerColor = Surface2,
            title = { Text("Text") },
            text = {
                OutlinedTextField(value, { value = it.take(40) }, singleLine = true, placeholder = { Text("Scrolls across the matrix", color = Muted) })
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    askText = false
                    val t = value.trim()
                    // short text that fits stands still; longer text scrolls
                    val still = Frame.of(AppGraph.profile, com.dotforge.device.DotText.centered(AppGraph.profile.size, t.uppercase()))
                    val fits = com.dotforge.device.DotText.width(t.uppercase()) <= AppGraph.profile.size - 4
                    open(saveDesign(t.take(20), if (fits) Animation.single(still) else Effects.marquee(AppGraph.profile, t)))
                }) { Text("Make", color = NothingRed) }
            },
            dismissButton = { TextButton(onClick = { askText = false }) { Text("Cancel", color = Color.White) } },
        )
    }
    return remember {
        DesignImport(
            file = { files.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "image/*")) },
            camera = { runCatching { camera.launch(null) } },
            text = { askText = true },
        )
    }
}
