package com.dotforge.rules

import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RulesRepositoryTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    @Test fun freshInstallGetsPresets() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        assertEquals(listOf("Calls", "OTP", "Messages"), repo.rules.value.map { it.name })
    }

    @Test fun newRulesGoOnTopAndEditsPersist() {
        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file)
        val added = Rule(name = "Boss", senders = listOf("Boss"), keywordMode = KeywordMode.REGEX, holdUntilDismissed = true)
        repo.upsert(added)
        repo.upsert(added.copy(enabled = false)) // replaced in place
        repo.delete("preset-messages")

        val reloaded = RulesRepository(file).rules.value
        assertEquals(listOf("Boss", "Calls", "OTP"), reloaded.map { it.name })
        assertEquals(added.copy(enabled = false), reloaded.first())
    }

    @Test fun addPresetsRestoresOnlyMissingOnes() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        repo.delete("preset-otp")
        repo.upsert(repo.rules.value.first().copy(name = "My calls"))
        repo.addPresets()
        assertEquals(listOf("My calls", "Messages", "OTP"), repo.rules.value.map { it.name })
    }

    @Test fun unreadableFileFallsBackToPresets() {
        val file = tmp.root.resolve("rules.json").apply { writeText("not json") }
        assertEquals(3, RulesRepository(file).rules.value.size)
    }

    @Test fun oldSpamRulesAreDroppedOnLoad() {
        val file = tmp.root.resolve("rules.json")
        file.writeText("""[{"id":"s","name":"Spam filter","suppress":true},{"id":"m","name":"Messages"}]""")
        assertEquals(listOf("Messages"), RulesRepository(file).rules.value.map { it.name })
    }

    @Test fun unknownEnumValuesFallBackToDefaults() {
        val file = tmp.root.resolve("rules.json")
        file.writeText("""[{"id":"x","name":"Old","effect":"LASER","keywordMode":"FUZZY","hold":true}]""")
        val r = RulesRepository(file).rules.value.single()
        assertEquals(Effect.PULSE, r.effect)
        assertEquals(KeywordMode.ANY, r.keywordMode)
        assertEquals(true, r.holdUntilDismissed)
    }
}
