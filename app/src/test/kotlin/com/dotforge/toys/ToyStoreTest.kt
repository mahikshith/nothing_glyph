package com.dotforge.toys

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToyStoreTest {
    private fun a(name: String, size: Long = 1000) = ReleaseAsset(name, size, "https://example.invalid/$name")
    private fun pick(vararg names: String) = ToyCatalog.pickAsset(names.map { a(it) })?.name

    @Test fun onlyApksArePicked() {
        assertEquals("GlyphTuner-1.0.0.apk", pick("v1.0.0.zip", "GlyphTuner-1.0.0.apk", "notes.txt"))
        assertNull(pick("v1.0.0.zip", "v1.0.0.tar.gz"))
        assertNull(pick())
        assertEquals("app.APK", pick("app.APK"))
    }

    @Test fun thisPhonesApkWinsAndOtherPhonesAreNeverPicked() {
        assertEquals(
            "glyph-orbit-dial-v0.3-phone3-release.apk",
            pick("glyph-orbit-dial-v0.3-phone4aPro-release.apk", "glyph-orbit-dial-v0.3-phone3-release.apk"),
        )
        assertNull(pick("toy-phone4aPro-release.apk"))
        assertNull(pick("toy-phone3a.apk"))
        assertEquals("toy-np3.apk", pick("toy-4a-pro.apk", "toy-np3.apk"))
        assertEquals("toy-Phone(3).apk", pick("toy-Phone(2).apk", "toy-Phone(3).apk"))
        // "mp3" and versions like "v0.3" are not a phone
        assertEquals("mp3-toy.apk", pick("mp3-toy.apk"))
    }

    @Test fun releaseBeatsDebugAndUniversalBeatsOtherAbis() {
        assertEquals("app-release.apk", pick("app-debug.apk", "app-release.apk"))
        assertEquals("app-universal.apk", pick("app-x86_64.apk", "app-universal.apk", "app-armeabi-v7a.apk"))
        assertEquals("app-arm64-v8a.apk", pick("app-armeabi-v7a.apk", "app-arm64-v8a.apk"))
        assertEquals("app-release-signed.apk", pick("app-release-unsigned.apk", "app-release-signed.apk"))
        // equal scores: GitHub's order
        assertEquals("first.apk", pick("first.apk", "second.apk"))
        // a custom device marker from the catalog
        assertEquals("toy-np3-matrix.apk", ToyCatalog.pickAsset(listOf(a("toy-other.apk"), a("toy-np3-matrix.apk")), "matrix")?.name)
    }

    @Test fun latestReleaseIsRead() {
        val body = """{"tag_name":"v1.2","name":"Toy 1.2","assets":[
            {"name":"DigimonGlyph-1.2-release.apk","size":6510000,"browser_download_url":"https://github.com/u/r/releases/download/1.2/DigimonGlyph-1.2-release.apk"},
            {"name":"broken"}]}"""
        val (tag, assets) = ToyCatalog.parseRelease(body)!!
        assertEquals("v1.2", tag)
        assertEquals(listOf("DigimonGlyph-1.2-release.apk"), assets.map { it.name })
        assertEquals(6_510_000L, assets.single().size)
        assertNull(ToyCatalog.parseRelease("""{"message":"Not Found"}"""))
        assertNull(ToyCatalog.parseRelease("not json"))
    }

    @Test fun catalogKeepsOnlyOpenLicences() {
        val text = """{"version":1,"toys":[
            {"owner":"a","repo":"one","name":"One","licence":"MIT"},
            {"owner":"b","repo":"two","name":"Two","licence":"GPL-3.0"},
            {"owner":"c","repo":"three","name":"Three","licence":"NOASSERTION"},
            {"owner":"A","repo":"ONE","name":"Dup","licence":"MIT"},
            {"owner":"d","repo":"four","name":"Four","licence":"Apache-2.0","device":"phone3","extra":1}]}"""
        assertEquals(listOf("One", "Four"), ToyCatalog.parse(text)!!.map { it.name })
        assertNull(ToyCatalog.parse("{"))
    }

    @Test fun bundledCatalogIsValidAndMatchesTheRepoCopy() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "catalog/toys.json").exists() }
        val repo = File(root, "catalog/toys.json").readText()
        val bundled = File(root, "app/src/main/assets/toys.json").readText()
        assertEquals(repo, bundled)
        val toys = ToyCatalog.parse(repo)!!
        assertTrue(toys.size >= 5)
        assertTrue(toys.all { it.licence in setOf("MIT", "Apache-2.0") })
    }

    @Test fun sizesReadPlainly() {
        assertEquals("2.2 MB", ToyCatalog.size(2_210_000))
        assertEquals("850 KB", ToyCatalog.size(850_000))
        assertEquals("", ToyCatalog.size(0))
    }

    @Test fun releasesAreCached() {
        val dir = java.nio.file.Files.createTempDirectory("toys").toFile()
        var now = 1_000L
        val client = ToyStoreClient(dir, { "{}" }) { now }
        val toy = ToyRepo("o", "r", "Toy", licence = "MIT")
        File(dir, "o_r.json").writeText(ToyCatalog.encode(ToyRelease("o/r", "v1", "MIT", a("t.apk"), 900)))
        val r = client.release(toy) // fresh cache: no network
        assertTrue(r is ToyReleaseResult.Ready)
        assertEquals("v1", (r as ToyReleaseResult.Ready).release.tag)
        File(dir, "o_r.json").writeText(ToyCatalog.encode(ToyRelease("o/r", "", "GPL-3.0", null, 900)))
        assertTrue(client.release(toy) is ToyReleaseResult.NotShared)
        assertFalse(dir.resolve("download").exists())
        dir.deleteRecursively()
    }
}
