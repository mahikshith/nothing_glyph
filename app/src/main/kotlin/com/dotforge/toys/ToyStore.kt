package com.dotforge.toys

import com.dotforge.ringtones.RingtoneCatalog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A GitHub repository that publishes a Glyph Toy APK in its Releases. */
@Serializable
data class ToyRepo(
    val owner: String,
    val repo: String,
    val name: String,
    val summary: String = "",
    /** SPDX id, checked when the repo was added; checked again with GitHub before anything is offered. */
    val licence: String,
    /** Marks the right APK when a release has one per phone (e.g. "phone3"). */
    val device: String? = null,
) {
    val fullName get() = "$owner/$repo"
    val webUrl get() = "https://github.com/$owner/$repo"
}

@Serializable
private data class ToyCatalogFile(val version: Int = 1, val toys: List<ToyRepo> = emptyList())

@Serializable
data class ReleaseAsset(val name: String, val size: Long, val url: String)

/** The newest release of a toy repo: its tag and the APK to install (null when it has none for this phone). */
@Serializable
data class ToyRelease(val repo: String, val tag: String, val licence: String, val asset: ReleaseAsset?, val fetchedAt: Long)

sealed interface ToyReleaseResult {
    val toy: ToyRepo
    data class Ready(override val toy: ToyRepo, val release: ToyRelease) : ToyReleaseResult
    data class NotShared(override val toy: ToyRepo, val licence: String) : ToyReleaseResult
    data class RateLimited(override val toy: ToyRepo, val resetAt: Long) : ToyReleaseResult
    data class Failed(override val toy: ToyRepo, val message: String) : ToyReleaseResult
}

/** The curated toy list and the rules for reading GitHub's release answers. Pure; tested. */
object ToyCatalog {
    /** Our own copy of the list, refreshed from the repo; the bundled assets/toys.json is the fallback. */
    const val REMOTE_URL = "https://raw.githubusercontent.com/mahikshith/nothing_glyph/main/catalog/toys.json"
    const val PLAYGROUND_URL = "https://playground.nothing.tech"
    const val DEVICE = "phone3"

    private val json = Json { ignoreUnknownKeys = true }

    /** The toys in a catalog file; repos whose licence doesn't allow sharing are dropped. Null when unreadable. */
    fun parse(text: String): List<ToyRepo>? = runCatching {
        json.decodeFromString(ToyCatalogFile.serializer(), text).toys
            .filter { RingtoneCatalog.allowsSharing(it.licence) && it.owner.isNotBlank() && it.repo.isNotBlank() }
            .distinctBy { it.fullName.lowercase() }
    }.getOrNull()

    /** Tag and assets from `GET /repos/{owner}/{repo}/releases/latest`. */
    fun parseRelease(body: String): Pair<String, List<ReleaseAsset>>? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: o["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val assets = o["assets"]?.jsonArray.orEmpty().mapNotNull { a ->
            val m = a.jsonObject
            val name = m["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = m["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ReleaseAsset(name, m["size"]?.jsonPrimitive?.longOrNull ?: 0, url)
        }
        tag to assets
    }.getOrNull()

    private val OTHER_PHONES = Regex("""(phone|np|nothing)[-_ ]?\(?(1|2a|2|3a|4a|4)\)?|4a[-_ ]?pro|cmf""", RegexOption.IGNORE_CASE)

    private val PHONE3 = Regex("""(?<![a-z])(phone|np|p)[-_ ]?\(?3\)?(?![0-9a-z])""", RegexOption.IGNORE_CASE)

    private fun mentions(name: String, device: String): Boolean =
        if (device.equals(DEVICE, ignoreCase = true)) PHONE3.containsMatchIn(name) else name.contains(device, ignoreCase = true)

    /**
     * The APK to install from a release: only .apk files; one made for [device] first, then one made for no
     * particular phone (never another phone's), release over debug, universal / arm64 over other ABIs, then
     * the order GitHub lists them in.
     */
    fun pickAsset(assets: List<ReleaseAsset>, device: String = DEVICE): ReleaseAsset? {
        // another phone's build is never offered
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && (mentions(it.name, device) || !OTHER_PHONES.containsMatchIn(it.name)) }
        fun score(a: ReleaseAsset): Int {
            val n = a.name.lowercase()
            var s = 0
            if (mentions(a.name, device)) s += 100
            if ("debug" in n) s -= 20
            if ("release" in n || "signed" in n) s += 5
            if ("universal" in n || "arm64" in n) s += 3
            if ("x86" in n || "armeabi" in n) s -= 10
            if ("unsigned" in n) s -= 30
            return s
        }
        return apks.withIndex().maxWithOrNull(compareBy<IndexedValue<ReleaseAsset>> { score(it.value) }.thenByDescending { it.index })?.value
    }

    /** "2.2 MB", "850 KB". */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        bytes > 0 -> "$bytes B"
        else -> ""
    }

    const val CACHE_MS = 24 * 60 * 60 * 1000L
    fun encode(r: ToyRelease): String = json.encodeToString(ToyRelease.serializer(), r)
    fun decode(text: String): ToyRelease? = runCatching { json.decodeFromString(ToyRelease.serializer(), text) }.getOrNull()
}

private class ToyRateLimited(val resetAt: Long) : IOException("rate limited")

/**
 * Reads the toy list (bundled, then our repo's copy) and each toy's latest release through GitHub's API
 * without signing in, caching answers for a day. Downloads an APK for [ToyInstaller]. Blocking; call off the
 * main thread.
 */
class ToyStoreClient(private val dir: File, private val bundled: () -> String, private val now: () -> Long = System::currentTimeMillis) {
    private val listFile get() = File(dir, "toys.json")
    private fun releaseFile(t: ToyRepo) = File(dir, "${t.owner}_${t.repo}.json")

    /** The curated list: our repo's copy when [refresh] reaches it (kept for next time), else the last one, else the bundled one. */
    fun toys(refresh: Boolean = false): List<ToyRepo> {
        if (refresh) {
            runCatching { fetch(ToyCatalog.REMOTE_URL, api = false) }.getOrNull()?.takeIf { ToyCatalog.parse(it) != null }?.let { text ->
                runCatching {
                    dir.mkdirs()
                    listFile.writeText(text)
                }
            }
        }
        val saved = runCatching { listFile.readText() }.getOrNull()?.let(ToyCatalog::parse)
        return saved ?: ToyCatalog.parse(bundled()).orEmpty()
    }

    fun release(t: ToyRepo, refresh: Boolean = false): ToyReleaseResult {
        val old = runCatching { releaseFile(t).readText() }.getOrNull()?.let(ToyCatalog::decode)
        fun result(r: ToyRelease) =
            if (RingtoneCatalog.allowsSharing(r.licence)) ToyReleaseResult.Ready(t, r) else ToyReleaseResult.NotShared(t, r.licence)
        if (!refresh && old != null && now() - old.fetchedAt < ToyCatalog.CACHE_MS) return result(old)
        return try {
            val licence = RingtoneCatalog.licenceOf(fetch("https://api.github.com/repos/${t.owner}/${t.repo}/license")) ?: "NOASSERTION"
            val r = if (!RingtoneCatalog.allowsSharing(licence)) {
                ToyRelease(t.fullName, "", licence, null, now())
            } else {
                val (tag, assets) = ToyCatalog.parseRelease(fetch("https://api.github.com/repos/${t.owner}/${t.repo}/releases/latest"))
                    ?: throw IOException("No release")
                ToyRelease(t.fullName, tag, licence, ToyCatalog.pickAsset(assets, t.device ?: ToyCatalog.DEVICE), now())
            }
            runCatching {
                dir.mkdirs()
                releaseFile(t).writeText(ToyCatalog.encode(r))
            }
            result(r)
        } catch (e: ToyRateLimited) {
            old?.let(::result) ?: ToyReleaseResult.RateLimited(t, e.resetAt)
        } catch (e: Exception) {
            old?.let(::result) ?: ToyReleaseResult.Failed(t, "Couldn't reach GitHub. Check the connection.")
        }
    }

    private fun fetch(url: String, api: Boolean = true): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (api) {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            conn.setRequestProperty("User-Agent", "DotForge-Android")
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }
            if (RingtoneCatalog.isRateLimited(code, conn.getHeaderField("X-RateLimit-Remaining"))) {
                throw ToyRateLimited(RingtoneCatalog.resetAt(conn.getHeaderField("X-RateLimit-Reset")))
            }
            throw IOException("GitHub answered $code")
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [asset] into the cache, at most [MAX_APK] bytes; [progress] gets 0..1. */
    fun download(asset: ReleaseAsset, progress: (Float) -> Unit = {}): File {
        val out = File(dir, "download/" + asset.name.replace(Regex("[/\\\\:*?\"<>|]"), "_"))
        out.parentFile?.deleteRecursively()
        out.parentFile?.mkdirs()
        val conn = URL(asset.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "DotForge-Android")
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var got = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        if (got > MAX_APK) throw IOException("This file is too big for a toy.")
                        o.write(buf, 0, n)
                        if (total > 0) progress((got.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    companion object {
        const val MAX_APK = 150L * 1024 * 1024
    }
}
