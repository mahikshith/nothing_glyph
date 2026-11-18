package com.dotforge.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private fun tempDir(): File = Files.createTempDirectory("backup").toFile().also { it.deleteOnExit() }

    private val designs = """[{"id":"a","name":"Heart","animated":false,"frames":"x"},{"id":"b","name":"Star","animated":true,"frames":"y"}]"""
    private val rules = """{"version":5,"rules":[
        {"id":"preset-calls","name":"Calls","action":"SHOW"},
        {"id":"person-1","name":"Mom","action":"SHOW"},
        {"id":"block-1","name":"Start with 140","action":"SPAM"},
        {"id":"r1","name":"WhatsApp","action":"SHOW"}],
        "lists":[{"name":"Spam prefixes","entries":["140"]}]}"""

    private fun backupOf(dir: File, settings: JsonObject = JsonObject(emptyMap())): ByteArray {
        val out = ByteArrayOutputStream()
        val size = Backup.write(dir, settings, out, "0.1.0")
        assertEquals(out.size().toLong(), size)
        return out.toByteArray()
    }

    @Test fun aBackupPreviewsAndReplacesEverything() {
        val src = tempDir()
        File(src, "designs.json").writeText(designs)
        File(src, "rules.json").writeText(rules)
        File(src, "toy_events.tsv").writeText("not backed up")
        val settings = buildJsonObject { put("toys", buildJsonObject { put("clock", true) }) }
        val c = Backup.read(ByteArrayInputStream(backupOf(src, settings)))
        assertEquals(Backup.Summary(designs = 2, alertRules = 2, people = 1, spamRules = 1), c.summary)
        assertEquals("2 designs, 2 alert rules, 1 person, 1 spam rule", c.summary.text())
        assertEquals(setOf("designs.json", "rules.json"), c.files.keys)
        assertEquals(JsonPrimitive(true), (c.settings!!["toys"] as JsonObject)["clock"])

        val dst = tempDir()
        File(dst, "designs.json").writeText("[]")
        File(dst, "filters.json").writeText("{}")
        Backup.restore(c, dst, replace = true)
        assertEquals(designs, File(dst, "designs.json").readText())
        assertEquals("{}", File(dst, "filters.json").readText()) // not in the backup: untouched
        assertFalse(File(dst, "designs.json.restore").exists())
    }

    @Test fun addToWhatsHereKeepsThePhonesItemsAndRenamesClashes() {
        val src = tempDir()
        File(src, "designs.json").writeText(designs)
        File(src, "rules.json").writeText(rules)
        val c = Backup.read(ByteArrayInputStream(backupOf(src)))

        val dst = tempDir()
        File(dst, "designs.json").writeText("""[{"id":"a","name":"Heart edited","animated":false,"frames":"z"},{"id":"c","name":"Star","animated":false,"frames":"w"}]""")
        File(dst, "rules.json").writeText("""{"version":5,"rules":[{"id":"preset-calls","name":"Calls","action":"SHOW","enabled":false}],"lists":[{"name":"Spam prefixes","entries":["999"]}]}""")
        Backup.restore(c, dst, replace = false)

        val d = File(dst, "designs.json").readText()
        assertTrue(d.contains("Heart edited")) // same id: the phone's wins
        assertFalse(d.contains("\"frames\":\"x\""))
        assertTrue(d.contains("\"Star (2)\"")) // name clash
        val r = File(dst, "rules.json").readText()
        assertTrue(r.contains("\"enabled\":false")) // the phone's preset stays
        assertEquals(1, Regex("preset-calls").findAll(r).count())
        assertTrue(r.contains("person-1") && r.contains("block-1") && r.contains("r1"))
        assertTrue(r.contains("\"999\"") && r.contains("\"140\""))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test(expected = IOException::class) fun aZipThatIsNotABackupIsRefused() {
        Backup.read(ByteArrayInputStream(zip("rules.json" to "{}")))
    }

    @Test(expected = Backup.NewerBackupException::class) fun aBackupFromANewerVersionIsRefused() {
        Backup.read(ByteArrayInputStream(zip(Backup.MANIFEST to """{"format":99}""")))
    }

    @Test fun unknownAndUnsafeEntriesAreNeverWritten() {
        val c = Backup.read(ByteArrayInputStream(zip(Backup.MANIFEST to """{"format":1}""", "../evil.json" to "{}", "designs.json" to "[]")))
        val dst = tempDir()
        Backup.restore(c, dst, replace = true)
        assertFalse(File(dst.parentFile, "evil.json").exists())
        assertEquals("[]", File(dst, "designs.json").readText())
    }

    @Test(expected = IOException::class) fun aDamagedFileIsRefused() {
        Backup.read(ByteArrayInputStream(zip(Backup.MANIFEST to """{"format":1}""", "rules.json" to "{not json")))
    }

    @Test fun uniqueNames() {
        assertEquals("Heart (2)", Backup.uniqueName("Heart", setOf("Heart")))
        assertEquals("Heart (3)", Backup.uniqueName("Heart (2)", setOf("Heart", "Heart (2)")))
    }

    @Test fun photosAndTracePhotosRoundTrip() {
        val src = tempDir()
        File(src, "designs.json").writeText(
            """[{"id":"p","name":"Sky","animated":false,"frames":"x","kind":"PHOTO","photoFile":"p.png"},
                {"id":"t","name":"Cat","animated":false,"frames":"y","trace":{"file":"t-trace.png","opacity":45}}]""",
        )
        val media = File(src, Backup.MEDIA).apply { mkdirs() }
        val photo = ByteArray(3000) { (it * 7).toByte() }
        val trace = ByteArray(1200) { (it * 3).toByte() }
        File(media, "p.png").writeBytes(photo)
        File(media, "t-trace.png").writeBytes(trace)
        File(media, "old-unused.png").writeBytes(ByteArray(10)) // no design uses it: left out
        val c = Backup.read(ByteArrayInputStream(backupOf(src)))
        assertEquals(setOf("p.png", "t-trace.png"), c.media.keys)

        // restore on a fresh phone: the designs and their pictures come back byte for byte
        val dst = tempDir()
        Backup.restore(c, dst, replace = false)
        assertTrue(File(dst, "designs.json").readText().contains("t-trace.png"))
        assertTrue(photo.contentEquals(File(dst, "${Backup.MEDIA}/p.png").readBytes()))
        assertTrue(trace.contentEquals(File(dst, "${Backup.MEDIA}/t-trace.png").readBytes()))
        assertFalse(File(dst, "${Backup.MEDIA}/old-unused.png").exists())

        // "Add to what's here" keeps a picture that is already on the phone; "Replace" overwrites it
        File(dst, "${Backup.MEDIA}/p.png").writeBytes(ByteArray(5))
        Backup.restore(c, dst, replace = false)
        assertEquals(5, File(dst, "${Backup.MEDIA}/p.png").length())
        Backup.restore(c, dst, replace = true)
        assertTrue(photo.contentEquals(File(dst, "${Backup.MEDIA}/p.png").readBytes()))
    }

    @Test fun mediaEntriesThatLeaveTheFolderAreNeverWritten() {
        val c = Backup.read(ByteArrayInputStream(zip(Backup.MANIFEST to """{"format":1}""", "design_media/../../evil.png" to "x", "design_media/ok.png" to "y")))
        assertEquals(setOf("ok.png"), c.media.keys)
        val dst = tempDir()
        Backup.restore(c, dst, replace = true)
        assertEquals("y", File(dst, "design_media/ok.png").readText())
        assertFalse(File(dst.parentFile, "evil.png").exists())
    }
}
