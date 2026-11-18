package com.dotforge.rules

import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RulesRepositoryTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    @Test fun freshInstallGetsStarterRules() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        assertEquals(listOf("Spam filter", "Messages", "Everything else"), repo.rules.value.map { it.name })
    }

    @Test fun editsAndOrderPersist() {
        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file)
        val added = Rule(name = "Boss", senders = listOf("Boss"), keywordMode = KeywordMode.REGEX, holdUntilDismissed = true)
        repo.upsert(added)
        repo.move(added.id, -3)
        repo.move(added.id, -1) // already first: ignored
        repo.delete(repo.rules.value.last().id)

        val reloaded = RulesRepository(file).rules.value
        assertEquals(listOf("Boss", "Spam filter", "Messages"), reloaded.map { it.name })
        assertEquals(added, reloaded.first())
    }

    @Test fun unreadableFileFallsBackToStarterRules() {
        val file = tmp.root.resolve("rules.json").apply { writeText("not json") }
        assertEquals(3, RulesRepository(file).rules.value.size)
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
