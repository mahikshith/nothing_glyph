package com.dotforge.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The Android side of [Backup]: which settings go in, the last backup time, the weekly automatic backup to a
 * folder the user picked, and the restart after a restore so every list reloads.
 */
object BackupStore {
    /** The settings files a backup carries. Permissions ("setup") can't be restored, so they stay out. */
    val SETTINGS_FILES = listOf("dotforge", "toys", "media", "automation", "system_glyphs", "ui")
    private const val PREFS = "backup"
    private const val KEY_LAST = "last_ms"
    private const val KEY_FOLDER = "auto_folder"
    private const val WORK = "backup-weekly"

    private var prefs: SharedPreferences? = null
    var lastMs by mutableLongStateOf(0L)
        private set
    /** The folder automatic backups go to; null = automatic backup off. */
    var autoFolder by mutableStateOf<Uri?>(null)
        private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        lastMs = p.getLong(KEY_LAST, 0L)
        autoFolder = p.getString(KEY_FOLDER, null)?.let(Uri::parse)
    }

    fun fileName(): String = "DotForge backup ${LocalDate.now()}.${Backup.EXTENSION}"

    fun settings(ctx: Context): JsonObject = buildJsonObject {
        SETTINGS_FILES.forEach { name ->
            val all = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
            put(name, buildJsonObject {
                all.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> put(k, v)
                        is Int -> put(k, buildJsonObject { put("i", v) })
                        is Long -> put(k, buildJsonObject { put("l", v) })
                        is Float -> put(k, buildJsonObject { put("f", v) })
                        is String -> put(k, v)
                        is Set<*> -> put(k, buildJsonObject { put("s", kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it.toString()) })) })
                    }
                }
            })
        }
    }

    private fun restoreSettings(ctx: Context, settings: JsonObject, replace: Boolean) {
        settings.forEach { (name, values) ->
            if (name !in SETTINGS_FILES || values !is JsonObject) return@forEach
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            if (replace) e.clear()
            values.forEach { (k, v) ->
                when {
                    v is JsonPrimitive && v.isString -> e.putString(k, v.content)
                    v is JsonPrimitive -> v.booleanOrNull?.let { e.putBoolean(k, it) }
                    v is JsonObject -> {
                        v["i"]?.jsonPrimitive?.longOrNull?.let { e.putInt(k, it.toInt()) }
                        v["l"]?.jsonPrimitive?.longOrNull?.let { e.putLong(k, it) }
                        v["f"]?.jsonPrimitive?.doubleOrNull?.let { e.putFloat(k, it.toFloat()) }
                        (v["s"] as? kotlinx.serialization.json.JsonArray)?.let { a -> e.putStringSet(k, a.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()) }
                    }
                }
            }
            e.commit()
        }
    }

    /** Writes a backup to [out]; returns its size. */
    fun backUp(ctx: Context, out: OutputStream): Long {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val size = out.use { Backup.write(ctx.filesDir, settings(ctx), it, version) }
        val now = System.currentTimeMillis()
        prefs?.edit()?.putLong(KEY_LAST, now)?.apply()
        lastMs = now
        return size
    }

    fun read(input: InputStream): Backup.Contents = input.use { Backup.read(it) }

    /** Restores [c], then restarts the app so every list loads the restored files. */
    fun restoreAndRestart(ctx: Context, c: Backup.Contents, replace: Boolean) {
        Backup.restore(c, ctx.filesDir, replace)
        c.settings?.let { restoreSettings(ctx, it, replace) }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    /** Turns weekly automatic backups to [folder] (a tree the user picked) on, or off with null. */
    fun setAutoFolder(ctx: Context, folder: Uri?) {
        val wm = WorkManager.getInstance(ctx)
        val old = autoFolder
        if (folder == null) {
            wm.cancelUniqueWork(WORK)
            old?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        } else {
            ctx.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build())
        }
        prefs?.edit()?.putString(KEY_FOLDER, folder?.toString())?.apply()
        autoFolder = folder
    }

    /** A readable name for the automatic backup folder ("Download", "Drive › Backups"). */
    fun folderName(folder: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(folder).substringAfter(':').ifEmpty { "Phone storage" } }.getOrDefault("the folder you picked")

    /** Writes one backup into the automatic backup folder. */
    fun backUpToFolder(ctx: Context): Boolean {
        val tree = autoFolder ?: return false
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", fileName()) ?: return false
        val out = ctx.contentResolver.openOutputStream(doc) ?: return false
        backUp(ctx, out)
        return true
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BackupStore.init(applicationContext)
        return if (runCatching { BackupStore.backUpToFolder(applicationContext) }.getOrDefault(false)) Result.success()
        else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
