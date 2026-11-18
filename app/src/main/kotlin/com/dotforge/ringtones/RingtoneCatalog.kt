package com.dotforge.ringtones

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A GitHub repository that shares glyph ringtones. Only repositories whose licence allows redistribution are
 * listed, and the licence is checked again with the GitHub API before any file is shown.
 */
data class RingtoneCollection(
    val owner: String,
    val repo: String,
    val branch: String,
    /** Folders to look in; each may hold ringtones or one level of subfolders with ringtones. */
    val paths: List<String>,
    val title: String,
    val hint: String,
) {
    val fullName get() = "$owner/$repo"
    val webUrl get() = "https://github.com/$owner/$repo"
}

@Serializable
data class CatalogFile(val name: String, val folder: String, val size: Long, val downloadUrl: String)

@Serializable
data class CatalogEntry(val repo: String, val licence: String, val files: List<CatalogFile>, val fetchedAt: Long)

/** What one collection shows in the Get more sheet. */
sealed interface CatalogResult {
    val collection: RingtoneCollection
    data class Files(override val collection: RingtoneCollection, val licence: String, val files: List<CatalogFile>) : CatalogResult
    /** GitHub's hourly limit for unsigned requests is used up; [resetAt] is in epoch milliseconds (0 = unknown). */
    data class RateLimited(override val collection: RingtoneCollection, val resetAt: Long) : CatalogResult
    data class Failed(override val collection: RingtoneCollection, val message: String) : CatalogResult
    /** The licence no longer allows sharing: nothing is offered. */
    data class NotShared(override val collection: RingtoneCollection, val licence: String) : CatalogResult
}

/** The collections offered, and the rules for reading GitHub's answers. */
object RingtoneCatalog {
    /** Glyphtones: a site where Nothing users share ringtones. Opened in the browser, never scraped. */
    const val GLYPHTONES_URL = "https://glyphtones.firu.dev"

    /** Checked with GitHub's licence API before adding (MIT on 2026-11-18). */
    val COLLECTIONS = listOf(
        RingtoneCollection(
            "taiixu", "osu-to-glyph", "main", listOf("demo"),
            "osu! to Glyph demos", "Compositions for Phone (1) and (2), converted when you open them",
        ),
    )

    /** SPDX ids of licences that allow passing the files on. */
    val SHARING_LICENCES = setOf(
        "MIT", "MIT-0", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD", "Unlicense", "Zlib",
        "CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0", "MPL-2.0",
    )

    fun allowsSharing(spdx: String?): Boolean = spdx != null && spdx in SHARING_LICENCES

    const val CACHE_MS = 24 * 60 * 60 * 1000L
    private val AUDIO = setOf("ogg", "opus")

    private val json = Json { ignoreUnknownKeys = true }

    /** The SPDX id from `GET /repos/{owner}/{repo}/license`, or null. */
    fun licenceOf(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["license"]?.jsonObject?.get("spdx_id")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** A folder listing from `GET /repos/{owner}/{repo}/contents/{path}`: its ringtones and its subfolders. */
    fun parseContents(body: String, folder: String): Pair<List<CatalogFile>, List<String>> {
        val items = json.parseToJsonElement(body) as? JsonArray ?: throw IOException("Unexpected answer from GitHub.")
        val files = mutableListOf<CatalogFile>()
        val dirs = mutableListOf<String>()
        for (item in items) {
            val o = item as? JsonObject ?: continue
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val path = o["path"]?.jsonPrimitive?.contentOrNull ?: continue
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "dir" -> dirs += path
                "file" -> {
                    val url = o["download_url"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (name.substringAfterLast('.', "").lowercase() !in AUDIO) continue
                    val size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    files += CatalogFile(name, folder, size, url)
                }
            }
        }
        return files to dirs
    }

    /** True when an error answer means the hourly limit is used up. */
    fun isRateLimited(code: Int, remaining: String?): Boolean = (code == 403 || code == 429) && remaining?.trim() == "0"

    /** Epoch milliseconds from the X-RateLimit-Reset header (epoch seconds), 0 when missing. */
    fun resetAt(header: String?): Long = (header?.trim()?.toLongOrNull() ?: 0L) * 1000

    /** Files sorted for showing: by folder, then name. */
    fun sorted(files: List<CatalogFile>) = files.sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))

    fun encodeCache(entry: CatalogEntry): String = json.encodeToString(CatalogEntry.serializer(), entry)
    fun decodeCache(text: String): CatalogEntry? = runCatching { json.decodeFromString(CatalogEntry.serializer(), text) }.getOrNull()
}

private class RateLimitedException(val resetAt: Long) : IOException("rate limited")

/**
 * Reads the collections through GitHub's contents API without signing in. Answers are cached for a day in
 * [cacheDir]; when GitHub refuses (offline or rate-limited) a cached answer of any age is used instead.
 */
class RingtoneCatalogClient(private val cacheDir: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun cacheFile(c: RingtoneCollection) = File(cacheDir, "${c.owner}_${c.repo}.json")

    private fun cached(c: RingtoneCollection): CatalogEntry? =
        runCatching { cacheFile(c).readText() }.getOrNull()?.let(RingtoneCatalog::decodeCache)

    private fun result(c: RingtoneCollection, e: CatalogEntry): CatalogResult =
        if (RingtoneCatalog.allowsSharing(e.licence)) CatalogResult.Files(c, e.licence, e.files) else CatalogResult.NotShared(c, e.licence)

    /** Blocking; call off the main thread. [refresh] skips a fresh cache. */
    fun load(c: RingtoneCollection, refresh: Boolean = false): CatalogResult {
        val old = cached(c)
        if (!refresh && old != null && now() - old.fetchedAt < RingtoneCatalog.CACHE_MS) return result(c, old)
        return try {
            val entry = fetch(c)
            runCatching {
                cacheDir.mkdirs()
                cacheFile(c).writeText(RingtoneCatalog.encodeCache(entry))
            }
            result(c, entry)
        } catch (e: RateLimitedException) {
            old?.let { result(c, it) } ?: CatalogResult.RateLimited(c, e.resetAt)
        } catch (e: Exception) {
            old?.let { result(c, it) } ?: CatalogResult.Failed(c, "Couldn't reach GitHub. Check the connection.")
        }
    }

    private fun fetch(c: RingtoneCollection): CatalogEntry {
        val licence = RingtoneCatalog.licenceOf(get("repos/${c.owner}/${c.repo}/license")) ?: "NOASSERTION"
        // a licence that doesn't allow sharing: don't list anything
        if (!RingtoneCatalog.allowsSharing(licence)) return CatalogEntry(c.fullName, licence, emptyList(), now())
        val files = mutableListOf<CatalogFile>()
        val queue = ArrayDeque(c.paths.map { it to 0 })
        var folders = 0
        while (queue.isNotEmpty() && folders < MAX_FOLDERS_PER_REPO) {
            val (path, depth) = queue.removeFirst()
            folders++
            val (found, dirs) = RingtoneCatalog.parseContents(get(contentsPath(c, path)), path.substringAfterLast('/'))
            files += found
            if (depth == 0) dirs.forEach { queue.addLast(it to 1) }
        }
        return CatalogEntry(c.fullName, licence, RingtoneCatalog.sorted(files), now())
    }

    private fun contentsPath(c: RingtoneCollection, path: String): String {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "repos/${c.owner}/${c.repo}/contents/$encoded?ref=${c.branch}"
    }

    private fun get(path: String): String {
        val conn = URL("https://api.github.com/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "DotForge-Android")
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }
            if (RingtoneCatalog.isRateLimited(code, conn.getHeaderField("X-RateLimit-Remaining"))) {
                throw RateLimitedException(RingtoneCatalog.resetAt(conn.getHeaderField("X-RateLimit-Reset")))
            }
            throw IOException("GitHub answered $code")
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [file] into [dir], keeping its name so the import page shows it. Blocking. */
    fun download(file: CatalogFile, dir: File): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val safe = file.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val out = File(dir, safe)
        val conn = URL(file.downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "DotForge-Android")
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode}).")
            var total = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD) throw IOException("This file is too big for a ringtone.")
                        o.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    companion object {
        private const val MAX_FOLDERS_PER_REPO = 12
        /** Ringtones are small; anything larger is not one. */
        const val MAX_DOWNLOAD = 20L * 1024 * 1024
    }
}
