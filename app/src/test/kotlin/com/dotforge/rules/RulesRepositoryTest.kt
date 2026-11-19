package com.dotforge.rules

import com.dotforge.engine.Until
import com.dotforge.filter.Condition
import com.dotforge.filter.Field
import com.dotforge.filter.Group
import com.dotforge.filter.Match
import com.dotforge.filter.NamedList
import com.dotforge.filter.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RulesRepositoryTest {
    @get:JUnitRule val tmp = TemporaryFolder()

    private val showPresets = listOf("Phone calls", "Messages", "OTP codes", "Delivery")

    private fun shows(rules: List<Rule>) = rules.filter { it.action == Action.SHOW && it.moment == null && it.id != SimpleRules.REMINDERS }.map { it.name }

    @Test fun freshInstallGetsPresetsAndLists() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        assertEquals(showPresets, shows(repo.rules.value))
        assertTrue(repo.rules.value.any { it.id == "spam-contacts" && it.enabled })
        assertTrue(repo.rules.value.any { it.id == "spam-repeated" && it.enabled })
        val words = repo.rules.value.first { it.id == "spam-words" }
        assertFalse(words.enabled)
        // the starter spam words are an ordinary block rule now, with the words in it
        assertEquals(BlockPattern(PatternType.WORDS, SpamSettings.DEFAULT_KEYWORDS.joinToString(", "), setOf(Where.ANY_APP)), SimpleRules.pattern(words))
        assertEquals(
            listOf(RuleEngine.ALLOWED, RuleEngine.BLOCKED, RuleEngine.SPAM_PREFIXES),
            repo.lists.value.map { it.name },
        )
    }

    @Test fun newRulesGoOnTopAndEditsPersist() {
        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file)
        val added = Rule(
            name = "Boss", match = Match(conditions = listOf(Condition(Field.SENDER, Op.IS, listOf("Boss")))),
            until = Until.UNLOCK, priority = 1, vibrate = true,
        )
        repo.upsert(added)
        repo.upsert(added.copy(enabled = false)) // replaced in place
        repo.delete("preset-messages")

        val reloaded = RulesRepository(file).rules.value
        assertEquals(listOf("Boss", "Phone calls", "OTP codes", "Delivery"), shows(reloaded))
        assertEquals(added.copy(enabled = false), reloaded.first())
        assertTrue(file.readText().startsWith("""{"version":7,"""))
    }

    @Test fun addPresetsRestoresOnlyMissingOnes() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        repo.delete("preset-otp")
        repo.delete("spam-list")
        repo.deleteList(RuleEngine.SPAM_PREFIXES)
        assertEquals(listOf("OTP codes", "Spam list"), repo.missingPresets().map { it.name })
        repo.addPresets(repo.missingPresets().take(1))
        assertEquals(listOf("Spam list"), repo.missingPresets().map { it.name })
        assertTrue(repo.list(RuleEngine.SPAM_PREFIXES) != null) // missing preset lists come back too
    }

    @Test fun ensurePresetAddsOrSwitchesOn() {
        val repo = RulesRepository(tmp.root.resolve("rules.json"))
        repo.delete("spam-blocked")
        repo.ensurePreset("spam-blocked")
        assertTrue(repo.rules.value.first { it.id == "spam-blocked" }.enabled)
        repo.upsert(repo.rules.value.first { it.id == "spam-words" }.copy(enabled = false))
        repo.ensurePreset("spam-words")
        assertTrue(repo.rules.value.first { it.id == "spam-words" }.enabled)
    }

    @Test fun listsAddRenameAndDelete() {
        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file)
        repo.addToList(RuleEngine.ALLOWED, " +447700900123 ")
        repo.addToList(RuleEngine.BLOCKED, "+447700900123", removeFrom = RuleEngine.ALLOWED)
        assertEquals(emptyList<String>(), repo.list(RuleEngine.ALLOWED)!!.entries)
        assertEquals(listOf("+447700900123"), repo.list("blocked senders")!!.entries)
        repo.addToList(RuleEngine.BLOCKED, "+447700900123") // no duplicates
        assertEquals(1, repo.list(RuleEngine.BLOCKED)!!.entries.size)
        repo.addToList("VIP", "Mum") // made when missing
        assertEquals(listOf("Mum"), repo.list("vip")!!.entries)

        // renaming a list keeps the rules that use it
        val r = Rule(name = "VIP", match = Match(groups = listOf(Group(conditions = listOf(Condition(Field.SENDER, Op.IN_LIST, list = "VIP"))))))
        repo.upsert(r)
        repo.putList(NamedList("Family", listOf("Mum", "Dad")), oldName = "VIP")
        assertEquals("Family", repo.rules.value.first { it.id == r.id }.match.allConditions.single().list)
        assertEquals(null, repo.list("VIP"))
        repo.deleteList("Family")
        assertEquals(null, RulesRepository(file).list("Family"))
    }

    @Test fun versionThreeGetsTheProgressRuleOnce() {
        val file = tmp.root.resolve("rules.json")
        file.writeText(
            """{"version":3,"rules":[
              {"id":"preset-calls","name":"Calls","packages":["com.google.android.dialer"],"glyph":"RING"},
              {"id":"mine","name":"Boss","enabled":false}
            ],"lists":[{"name":"Allowed senders","entries":["Bank"]}]}""",
        )
        val rules = RulesRepository(file).rules.value
        val added = RuleEngine.DEFAULT_PROTECTIONS + SimpleRules.REMINDERS + SimpleRules.moments().map { it.id }
        assertEquals(listOf("preset-calls", "mine", RuleEngine.PROGRESS_PRESET) + added.sorted(), rules.map { it.id }.take(3) + rules.drop(3).map { it.id }.sorted())
        assertEquals("Phone calls", rules[0].name)
        assertFalse(rules[1].enabled) // the rest is kept as it was
        val progress = rules.first { it.id == RuleEngine.PROGRESS_PRESET }
        assertTrue(progress.enabled && progress.action == Action.PROGRESS && progress.packages.isEmpty())
        assertTrue(file.readText().startsWith("""{"version":7,"""))
        // a deleted progress rule stays deleted
        val repo = RulesRepository(file)
        repo.delete(RuleEngine.PROGRESS_PRESET)
        assertTrue(RulesRepository(file).rules.value.none { it.id == RuleEngine.PROGRESS_PRESET })
        assertEquals(listOf("Bank"), RulesRepository(file).list(RuleEngine.ALLOWED)!!.entries)
    }

    @Test fun versionFourTurnsTheSpamProtectionsOn() {
        val file = tmp.root.resolve("rules.json")
        file.writeText(
            """{"version":4,"rules":[
              {"id":"spam-list","name":"Spam list","source":"EVERYTHING","action":"SPAM","enabled":false},
              {"id":"spam-prefixes","name":"Spam prefixes","source":"EVERYTHING","action":"SPAM","enabled":false}
            ],"lists":[]}""",
        )
        val rules = RulesRepository(file).rules.value.associateBy { it.id }
        assertTrue(RuleEngine.DEFAULT_PROTECTIONS.all { rules.getValue(it).enabled })
        assertFalse(rules.getValue("spam-prefixes").enabled) // the user's own rules stay as they were
        assertTrue(RuleEngine.needsCallScreening(rules.values.toList()))
        assertFalse(RuleEngine.needsCallScreening(listOf(RuleEngine.progressPreset(), Rule(name = "x"))))
        assertTrue(file.readText().startsWith("""{"version":7,"""))
    }

    @Test fun unreadableFileFallsBackToPresets() {
        val file = tmp.root.resolve("rules.json").apply { writeText("not json") }
        assertEquals(RuleEngine.presets().size, RulesRepository(file).rules.value.size)
    }

    @Test fun versionOneRulesAreMigrated() {
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
        val (mum, otp, old) = migrated.take(3)
        assertEquals(listOf("Mum", "OTP", "Old"), listOf(mum.name, otp.name, old.name))
        assertEquals(listOf(Condition(Field.SENDER, Op.CONTAINS, listOf("Mum"))), mum.match.conditions)
        assertEquals(GlyphStyle.NAME, mum.glyph)
        assertEquals(Until.READ, mum.until)
        assertEquals(listOf(Condition(Field.MESSAGE, Op.REGEX, listOf("\\d{6}"))), otp.match.conditions)
        assertEquals(Until.TIMEOUT, otp.until)
        assertEquals(GlyphStyle.PULSE, old.glyph)
        assertEquals(listOf(Condition(Field.MESSAGE, Op.NOT_CONTAINS, listOf("promo"))), old.match.conditions)
        assertTrue(migrated.none { it.id == "s" })
        assertTrue(migrated.any { it.id == "spam-list" }) // spam rules join

        assertTrue(file.readText().startsWith("""{"version":7,"""))
        assertEquals(migrated, RulesRepository(file).rules.value)
    }

    @Test fun versionTwoRulesKeepEverySetting() {
        val file = tmp.root.resolve("rules.json")
        file.writeText(
            """{"version":2,"rules":[
              {"id":"a","name":"Family","enabled":false,"packages":["com.whatsapp"],"people":["Mum","Dad"],"starred":true,
               "keywords":["urgent","asap"],"keywordMode":"ANY","glyph":"RING","until":"UNLOCK","priority":1,
               "soundUri":"content://x/1","soundName":"Bells"},
              {"id":"b","name":"Both words","keywords":["server","down"],"keywordMode":"ALL"},
              {"id":"c","name":"No promo pattern","keywords":["promo\\d"],"keywordMode":"NONE","regex":true},
              {"id":"d","name":"Strangers","unknown":true},
              {"id":"e","name":"Future","glyph":"LASER","until":"FOREVER","keywordMode":"FUZZY"}
            ]}""",
        )
        val rules = RulesRepository(file).rules.value.associateBy { it.id }
        val a = rules.getValue("a")
        assertFalse(a.enabled)
        assertEquals(listOf("com.whatsapp"), a.packages)
        assertEquals(listOf(Condition(Field.MESSAGE, Op.CONTAINS, listOf("urgent", "asap"))), a.match.conditions)
        assertEquals(
            listOf(Group(all = false, conditions = listOf(Condition(Field.SENDER, Op.CONTAINS, listOf("Mum", "Dad")), Condition.flag(Field.STARRED)))),
            a.match.groups,
        )
        assertEquals(GlyphStyle.RING, a.glyph)
        assertEquals(Until.UNLOCK, a.until)
        assertEquals(1, a.priority)
        assertEquals("content://x/1" to "Bells", a.soundUri to a.soundName)
        assertEquals(Action.SHOW, a.action)

        assertEquals(
            listOf(Condition(Field.MESSAGE, Op.CONTAINS, listOf("server")), Condition(Field.MESSAGE, Op.CONTAINS, listOf("down"))),
            rules.getValue("b").match.conditions,
        )
        assertEquals(listOf(Condition(Field.MESSAGE, Op.REGEX, listOf("promo\\d"), not = true)), rules.getValue("c").match.conditions)
        assertEquals(listOf(Condition.flag(Field.CONTACT, yes = false)), rules.getValue("d").match.conditions)
        val e = rules.getValue("e")
        assertEquals(GlyphStyle.PULSE, e.glyph)
        assertEquals(Until.READ, e.until)
    }

    @Test fun oldSpamSettingsBecomeSpamRulesAndLists() {
        val file = tmp.root.resolve("rules.json")
        file.writeText("""{"version":2,"rules":[]}""")
        val legacy = SpamSettings(
            enabled = true, keywords = listOf("casino"), patterns = listOf("win\\s+big"), blockUnknown = true, allowedSenders = listOf("Bank"),
        )
        val repo = RulesRepository(file, legacySpam = legacy)
        val rules = repo.rules.value.associateBy { it.id }
        assertTrue(rules.getValue("spam-words").enabled)
        assertEquals("casino", SimpleRules.pattern(rules.getValue("spam-words"))!!.text)
        // the old filter's patterns keep working as their own rule
        assertTrue(rules.getValue(RuleEngine.SPAM_PATTERNS_RULE).enabled)
        assertEquals(listOf(Condition(Field.MESSAGE, Op.REGEX, listOf("win\\s+big"))), rules.getValue(RuleEngine.SPAM_PATTERNS_RULE).match.conditions)
        assertTrue(rules.getValue("spam-unknown").enabled)
        assertEquals(listOf("Bank"), repo.list(RuleEngine.ALLOWED)!!.entries)
        // read once: a later start keeps what the user changed
        repo.upsert(rules.getValue("spam-unknown").copy(enabled = false))
        assertFalse(RulesRepository(file, legacySpam = legacy).rules.value.first { it.id == "spam-unknown" }.enabled)
    }

    @Test fun oldSpamSettingsAreKeptWithoutARulesFile() {
        val file = tmp.root.resolve("rules.json")
        val repo = RulesRepository(file, legacySpam = SpamSettings(enabled = true, keywords = listOf("casino")))
        assertTrue(repo.rules.value.first { it.id == "spam-words" }.enabled)
        assertEquals(showPresets, shows(repo.rules.value))
        assertEquals(RuleEngine.presets().size, repo.rules.value.size)
        assertTrue(file.exists())
        // an untouched old spam filter changes nothing
        val other = tmp.root.resolve("other.json")
        assertFalse(RulesRepository(other, legacySpam = SpamSettings()).rules.value.first { it.id == "spam-words" }.enabled)
    }

    @Test fun versionSixSpamWordsBecomeAnEditableBlockRule() {
        val file = tmp.root.resolve("rules.json")
        file.writeText(
            """{"version":6,"rules":[
              {"id":"spam-words","name":"Spam words","enabled":true,"source":"APPS","action":"SPAM",
               "match":{"all":false,"conditions":[{"field":"MESSAGE","op":"CONTAINS","list":"Spam words"}]}}
            ],"lists":[{"name":"Spam words","entries":["loan","lottery"]}]}""",
        )
        val repo = RulesRepository(file)
        val r = repo.rules.value.single { it.id == RuleEngine.SPAM_WORDS_RULE }
        assertTrue(r.enabled)
        assertEquals(BlockPattern(PatternType.WORDS, "loan, lottery", setOf(Where.ANY_APP)), SimpleRules.pattern(r))
        // the list stays, in case another rule uses it, and nothing else is added
        assertEquals(listOf("loan", "lottery"), repo.list(RuleEngine.SPAM_WORDS)!!.entries)
        assertFalse(repo.rules.value.any { it.id == RuleEngine.SPAM_PATTERNS_RULE })
        assertTrue(file.readText().startsWith("""{"version":7,"""))
    }
}
