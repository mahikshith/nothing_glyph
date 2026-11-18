package com.dotforge.rules

import com.dotforge.engine.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RulesRepositoryTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    @Test fun freshInstallGetsPresets() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        assertEquals(listOf("Calls", "Messages", "OTP codes", "Delivery"), repo.rules.value.map { it.name })
    }

    @Test fun newRulesGoOnTopAndEditsPersist() {
        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file)
        val added = Rule(name = "Boss", people = listOf("Boss"), starred = true, regex = true, until = Until.UNLOCK, priority = 1)
        repo.upsert(added)
        repo.upsert(added.copy(enabled = false)) // replaced in place
        repo.delete("preset-messages")

        val reloaded = RulesRepository(file).rules.value
        assertEquals(listOf("Boss", "Calls", "OTP codes", "Delivery"), reloaded.map { it.name })
        assertEquals(added.copy(enabled = false), reloaded.first())
        assertTrue(file.readText().startsWith("""{"version":2,"""))
    }

    @Test fun addPresetsRestoresOnlyMissingOnes() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        repo.delete("preset-otp")
        repo.delete("preset-delivery")
        repo.upsert(repo.rules.value.first().copy(name = "My calls"))
        assertEquals(listOf("OTP codes", "Delivery"), repo.missingPresets().map { it.name })
        repo.addPresets(repo.missingPresets().takeLast(1))
        assertEquals(listOf("My calls", "Messages", "Delivery"), repo.rules.value.map { it.name })
        repo.addPresets()
        assertEquals(listOf("My calls", "Messages", "Delivery", "OTP codes"), repo.rules.value.map { it.name })
    }

    @Test fun unreadableFileFallsBackToPresets() {
        val file = tmp.root.resolve("rules.json").apply { writeText("not json") }
        assertEquals(4, RulesRepository(file).rules.value.size)
    }

    @Test fun versionOneRulesAreMigratedOnce() {
        val file = tmp.root.resolve("rules.json")
        file.writeText(
            """[
              {"id":"s","name":"Spam filter","suppress":true},
              {"id":"m","name":"Mum","senders":["Mum"],"effect":"MARQUEE","hold":true,"clearOnUnlock":true,"breakDnd":true},
              {"id":"o","name":"OTP","keywords":["\\d{6}"],"keywordMode":"REGEX","effect":"FLASH"},
              {"id":"x","name":"Old","effect":"LASER","keywordMode":"NONE","keywords":["promo"]}
            ]""",
        )
        val migrated = RulesRepository(file).rules.value
        assertEquals(listOf("Mum", "OTP", "Old"), migrated.map { it.name })
        val (mum, otp, old) = migrated
        assertEquals(listOf("Mum"), mum.people)
        assertEquals(GlyphStyle.NAME, mum.glyph)
        assertEquals(Until.READ, mum.until)
        assertTrue(otp.regex)
        assertEquals(KeywordMode.ANY, otp.keywordMode)
        assertEquals(Until.TIMEOUT, otp.until)
        assertEquals(GlyphStyle.PULSE, old.glyph)
        assertEquals(KeywordMode.NONE, old.keywordMode)

        assertTrue(file.readText().startsWith("""{"version":2,"""))
        assertEquals(migrated, RulesRepository(file).rules.value)
    }

    @Test fun unknownEnumValuesFallBackToDefaults() {
        val file = tmp.root.resolve("rules.json")
        file.writeText("""{"version":2,"rules":[{"id":"x","name":"Future","glyph":"LASER","until":"FOREVER","keywordMode":"FUZZY"}]}""")
        val r = RulesRepository(file).rules.value.single()
        assertEquals(GlyphStyle.PULSE, r.glyph)
        assertEquals(Until.READ, r.until)
        assertEquals(KeywordMode.ANY, r.keywordMode)
    }
}
