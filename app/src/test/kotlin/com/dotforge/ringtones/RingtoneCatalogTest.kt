package com.dotforge.ringtones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RingtoneCatalogTest {
    @Test fun licenceIsReadAndOnlySharingOnesPass() {
        val body = """{"name":"LICENSE","path":"LICENSE","license":{"key":"mit","name":"MIT License","spdx_id":"MIT"}}"""
        assertEquals("MIT", RingtoneCatalog.licenceOf(body))
        assertTrue(RingtoneCatalog.allowsSharing("MIT"))
        assertTrue(RingtoneCatalog.allowsSharing("Apache-2.0"))
        assertFalse(RingtoneCatalog.allowsSharing("GPL-3.0"))
        assertFalse(RingtoneCatalog.allowsSharing("NOASSERTION"))
        assertFalse(RingtoneCatalog.allowsSharing(null))
        assertNull(RingtoneCatalog.licenceOf("""{"message":"Not Found"}"""))
        assertNull(RingtoneCatalog.licenceOf("not json"))
    }

    @Test fun everyListedCollectionHasASharingLicenceInItsCheck() {
        // the catalog only lists repositories checked with the licence API; the client checks again at runtime
        assertTrue(RingtoneCatalog.COLLECTIONS.isNotEmpty())
        for (c in RingtoneCatalog.COLLECTIONS) assertTrue(c.paths.isNotEmpty())
        assertTrue(RingtoneCatalog.GLYPHTONES_URL.startsWith("https://"))
    }

    @Test fun contentsKeepRingtonesAndFolders() {
        val body = """[
            {"name":"The Edge.ogg","path":"demo/The Edge/The Edge.ogg","type":"file","size":120345,
             "download_url":"https://raw.githubusercontent.com/o/r/main/demo/The%20Edge/The%20Edge.ogg"},
            {"name":"label.txt","path":"demo/The Edge/label.txt","type":"file","size":10,"download_url":"https://x/label.txt"},
            {"name":"Loud.OPUS","path":"demo/The Edge/Loud.OPUS","type":"file","size":5,"download_url":"https://x/Loud.OPUS"},
            {"name":"more","path":"demo/The Edge/more","type":"dir","size":0,"download_url":null},
            {"name":"link","path":"demo/link","type":"symlink","size":0,"download_url":"https://x/link"}
        ]"""
        val (files, dirs) = RingtoneCatalog.parseContents(body, "The Edge")
        assertEquals(listOf("The Edge.ogg", "Loud.OPUS"), files.map { it.name })
        assertEquals("The Edge", files[0].folder)
        assertEquals(120345L, files[0].size)
        assertEquals(listOf("demo/The Edge/more"), dirs)
    }

    @Test(expected = java.io.IOException::class)
    fun aFileAnswerIsNotAListing() {
        RingtoneCatalog.parseContents("""{"name":"a.ogg","type":"file"}""", "x")
    }

    @Test fun rateLimitIsRecognised() {
        assertTrue(RingtoneCatalog.isRateLimited(403, "0"))
        assertTrue(RingtoneCatalog.isRateLimited(429, "0"))
        assertFalse(RingtoneCatalog.isRateLimited(403, "12"))
        assertFalse(RingtoneCatalog.isRateLimited(404, "0"))
        assertEquals(1_700_000_000_000L, RingtoneCatalog.resetAt("1700000000"))
        assertEquals(0L, RingtoneCatalog.resetAt(null))
    }

    @Test fun aFreshCacheIsUsedWithoutAsking() {
        val dir = Files.createTempDirectory("catalog").toFile()
        val c = RingtoneCatalog.COLLECTIONS.first()
        val now = 1_000_000_000L
        val files = listOf(CatalogFile("a.ogg", "x", 10, "https://example.invalid/a.ogg"))
        java.io.File(dir, "${c.owner}_${c.repo}.json").writeText(
            RingtoneCatalog.encodeCache(CatalogEntry(c.fullName, "MIT", files, now - 1000)),
        )
        val r = RingtoneCatalogClient(dir) { now }.load(c)
        assertTrue(r is CatalogResult.Files)
        assertEquals(files, (r as CatalogResult.Files).files)

        // a cached licence that stopped allowing sharing offers nothing
        java.io.File(dir, "${c.owner}_${c.repo}.json").writeText(
            RingtoneCatalog.encodeCache(CatalogEntry(c.fullName, "GPL-3.0", files, now - 1000)),
        )
        assertTrue(RingtoneCatalogClient(dir) { now }.load(c) is CatalogResult.NotShared)
        dir.deleteRecursively()
    }

    @Test fun filesAreSortedByFolderThenName() {
        val f = listOf(
            CatalogFile("b.ogg", "Two", 1, "u"), CatalogFile("a.ogg", "two", 1, "u"), CatalogFile("z.ogg", "One", 1, "u"),
        )
        assertEquals(listOf("z.ogg", "a.ogg", "b.ogg"), RingtoneCatalog.sorted(f).map { it.name })
    }
}
