package com.dotforge.studio

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Effects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesignsRepositoryTest {
    @get:JUnitRule val tmp = TemporaryFolder()
    private val p = DeviceProfile.Phone3

    @Test fun savedDesignsAreNewestFirstAndPersist() {
        val file = tmp.root.resolve("designs.json")
        val repo = DesignsRepository(p, file)
        val a = Design(name = repo.nextName(), animated = false, frames = Effects.flash(p, 1).toMuseumJson())
        repo.save(a)
        val b = Design(name = repo.nextName(), animated = true, frames = Effects.pulse(p).toMuseumJson())
        repo.save(b)
        repo.save(a.copy(name = "Renamed"))

        val reloaded = DesignsRepository(p, file)
        assertEquals(listOf("Renamed", "Design 2"), reloaded.designs.value.map { it.name })
        assertEquals(Effects.pulse(p).frames.size, reloaded.animation(reloaded.get(b.id)!!)!!.frames.size)
        reloaded.delete(a.id)
        assertEquals(listOf(b.id), DesignsRepository(p, file).designs.value.map { it.id })
    }

    @Test fun oldStudioAnimationBecomesTheFirstDesign() {
        val legacy = tmp.root.resolve("studio_current.json").apply { writeText(Effects.pulse(p).toMuseumJson()) }
        val repo = DesignsRepository(p, tmp.root.resolve("designs.json"), legacy)
        val d = repo.designs.value.single()
        assertEquals("My design", d.name)
        assertEquals(true, d.animated)
        assertFalse(legacy.exists())
    }
}
