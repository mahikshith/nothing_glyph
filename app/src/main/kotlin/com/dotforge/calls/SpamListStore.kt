package com.dotforge.calls

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dotforge.AppGraph
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Calls page shows about the spam list. */
data class SpamListStatus(val count: Int = 0, val version: Int? = null, val updated: String? = null, val fetchedAt: Long = 0, val error: String? = null)

/**
 * The spam number list, downloaded from CallShield's GitHub data (MIT; numbers from US FCC and FTC complaint
 * data and CallShield community reports), cached in app storage and refreshed weekly by [SpamListWorker].
 * No keys and no paid services. Safe to call from any thread.
 */
class SpamListStore(private val dir: File) {
    private val file get() = File(dir, "spam_numbers.txt")
    private val meta get() = File(dir, "spam_numbers.meta")

    @Volatile private var list: SpamList? = null

    private val _status = MutableStateFlow(SpamListStatus())
    val status: StateFlow<SpamListStatus> = _status.asStateFlow()

    /** The cached list (read from storage the first time; empty before the first download). */
    fun list(): SpamList = list ?: synchronized(this) {
        list ?: (runCatching { file.bufferedReader().useLines { SpamList.parse(it) } }.getOrNull() ?: SpamList.EMPTY).also {
            list = it
            publish(it, null)
        }
    }

    fun contains(number: String): Boolean = number in list()

    private fun readMeta(): Map<String, String> = runCatching {
        meta.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }.getOrDefault(emptyMap())

    private fun publish(l: SpamList, error: String?) {
        _status.value = SpamListStatus(l.size, l.version, l.updated, readMeta()["fetched"]?.toLongOrNull() ?: 0, error)
    }

    /** Downloads the list when it changed (ETag). Blocking. Keeps the old list on any failure. */
    fun refresh(): Result<Int> = runCatching {
        dir.mkdirs()
        val m = readMeta()
        val conn = URL(URL_TXT).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "DotForge-Android")
            if (file.exists()) m["etag"]?.let { conn.setRequestProperty("If-None-Match", it) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                writeMeta(m["etag"])
                return@runCatching list().size.also { publish(list(), null) }
            }
            if (code != 200) throw IOException("The spam list server answered $code.")
            val tmp = File(dir, "spam_numbers.tmp")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("The spam list is unexpectedly large.")
                        out.write(buf, 0, n)
                    }
                }
            }
            val parsed = tmp.bufferedReader().useLines { SpamList.parse(it) }
            if (parsed.size < MIN_NUMBERS) {
                tmp.delete()
                throw IOException("The spam list looks broken; keeping the old one.")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            writeMeta(conn.getHeaderField("ETag"))
            list = parsed
            publish(parsed, null)
            parsed.size
        } finally {
            conn.disconnect()
        }
    }.onFailure { e -> _status.value = _status.value.copy(error = e.message ?: "Couldn't download the spam list.") }

    private fun writeMeta(etag: String?) {
        meta.writeText(listOfNotNull(etag?.let { "etag=$it" }, "fetched=${System.currentTimeMillis()}").joinToString("\n"))
    }

    companion object {
        /** One E.164 number per line, signed by CallShield (the signature is not checked here). */
        const val URL_TXT = "https://raw.githubusercontent.com/SysAdminDoc/CallShield/master/data/spam_numbers.txt"
        const val SOURCE = "CallShield community spam list (GitHub)"
        private const val MAX_BYTES = 16L * 1024 * 1024
        private const val MIN_NUMBERS = 100
    }
}

/** Refreshes the spam list: weekly on any network, and once now when the spam rule is switched on. */
class SpamListWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (AppGraph.spamList.refresh().isSuccess) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()

    companion object {
        private const val WEEKLY = "spam-list-weekly"
        private const val NOW = "spam-list-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Weekly refresh while a spam-list rule is on; cancelled when none is. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) {
                wm.cancelUniqueWork(WEEKLY)
                return
            }
            wm.enqueueUniquePeriodicWork(
                WEEKLY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpamListWorker>(7, TimeUnit.DAYS).setConstraints(network).build(),
            )
        }

        /** Download now (e.g. the list was never fetched, or Update was tapped). */
        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SpamListWorker>().setConstraints(network).build(),
            )
        }
    }
}
